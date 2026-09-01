package com.secondbrain.agent

import com.secondbrain.model.Backlink
import com.secondbrain.model.Cart
import com.secondbrain.model.CartLine
import com.secondbrain.model.CartMutation
import com.secondbrain.model.CommerceAvailability
import com.secondbrain.model.CommerceConfig
import com.secondbrain.model.FolderVerdict
import com.secondbrain.model.Money
import com.secondbrain.model.Note
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.NoteSource
import com.secondbrain.model.OrderOutcome
import com.secondbrain.model.OrderProposal
import com.secondbrain.model.Product
import com.secondbrain.model.SearchHit
import com.secondbrain.model.SearchOutcome
import com.secondbrain.model.TreeNode
import com.secondbrain.ports.CommercePort
import com.secondbrain.ports.LlmBlock
import com.secondbrain.ports.VaultStore
import com.secondbrain.ports.WriteResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * WF-4's tools through the real [ToolDispatcher] and [ConfirmationGate].
 *
 * `:agent` cannot depend on `:integrations` (ARCHITECTURE §1), so this defines
 * its own [CommercePort] double rather than reusing `FakeCommerceAdapter` —
 * which is the dependency rule doing its job, not an inconvenience.
 */
class CommerceToolsTest {

    // ── doubles ─────────────────────────────────────────────────────────────

    private class RecordingVault : VaultStore {
        val written = mutableListOf<NoteDraft>()
        override suspend fun tree(depth: Int?) = TreeNode("", "", 0, 0, 0, 0)
        override suspend fun read(path: String): Note? = null
        override suspend fun search(query: String, limit: Int): List<SearchHit> = emptyList()
        override suspend fun createFolder(path: String): FolderVerdict = FolderVerdict.Accepted(path)
        override suspend fun writeNote(draft: NoteDraft, confirmNew: Boolean): WriteResult {
            written += draft
            return WriteResult.Written(path = "Lists/${draft.title}.md")
        }
        override suspend fun appendNote(path: String, heading: String, markdown: String) = WriteResult.Written(path)
        override suspend fun moveNote(path: String, toFolder: String) = WriteResult.Written(path)
        override suspend fun backlinks(path: String): List<Backlink> = emptyList()
    }

    private class FakeCommerce(
        var cart: Cart = Cart(),
        private val availability: CommerceAvailability = CommerceAvailability.Ready,
        private val orderOutcome: OrderOutcome = OrderOutcome.Placed("ORD-1"),
        private val searchResults: List<Product> = emptyList(),
        /** Overrides [searchResults] entirely when set - the D-091 ProviderError path has no equivalent list to wrap. */
        private val searchOutcome: SearchOutcome? = null,
        /** Stage 4 (D-098): per-query outcomes, for `commerce_prepare_list`'s tests - a single [searchOutcome] cannot express "this item found things, that one didn't". Falls back to [searchOutcome]/[searchResults] for any query not listed here. */
        private val searchOutcomesByQuery: Map<String, SearchOutcome> = emptyMap(),
        override val isFake: Boolean = false,
    ) : CommercePort {
        var placedWith: String? = null
        var readCartCalls = 0
        val searchedQueries = mutableListOf<String>()

        override val displayName = "TestMart"
        override suspend fun availability() = availability
        override suspend fun search(query: String, limit: Int): SearchOutcome {
            searchedQueries += query
            return searchOutcomesByQuery[query] ?: searchOutcome ?: SearchOutcome.Found(searchResults)
        }
        override suspend fun addToCart(productId: String, quantity: Int) = CartMutation.Applied(cart)
        override suspend fun updateQuantity(lineId: String, quantity: Int): CartMutation {
            cart = cart.copy(lines = cart.lines.mapNotNull {
                when {
                    it.lineId != lineId -> it
                    quantity == 0 -> null
                    else -> it.copy(quantity = quantity)
                }
            })
            return CartMutation.Applied(cart)
        }
        override suspend fun removeFromCart(lineId: String): CartMutation {
            cart = cart.copy(lines = cart.lines.filterNot { it.lineId == lineId })
            return CartMutation.Applied(cart)
        }
        override suspend fun readCart(): Cart {
            readCartCalls++
            return cart
        }
        override suspend fun placeOrder(cart: Cart, idempotencyKey: String): OrderOutcome {
            placedWith = idempotencyKey
            return orderOutcome
        }
    }

    private fun line(id: String, name: String, rupees: Long, qty: Int = 1, thisSession: Boolean = true) =
        CartLine(id, "p-$id", name, "1 kg", Money.ofRupees(rupees), qty, thisSession)

    /**
     * Every [AgentDb] this class opens, so [closeDatabases] can shut them.
     *
     * On Windows an open SQLite connection keeps `app.db`, `app.db-shm` and
     * `app.db-wal` locked, and JUnit's `@TempDir` cleanup then fails the test
     * *after* its assertions have already passed — a green test reported red.
     * POSIX unlink semantics hide this on Linux, which is why it is easy to
     * write and not notice.
     */
    private val openDatabases = mutableListOf<AgentDb>()

    private fun newGate(dir: Path): ConfirmationGate {
        val db = AgentDb(dir.resolve("app.db"))
        openDatabases += db
        return ConfirmationGate(ActionLedger(db))
    }

    @AfterEach
    fun closeDatabases() {
        openDatabases.forEach { runCatching { it.close() } }
        openDatabases.clear()
    }

    private fun setup(
        dir: Path,
        commerce: FakeCommerce,
        config: CommerceConfig = CommerceConfig(enabled = true, orderCeilingInr = 2_000),
        vault: VaultStore = RecordingVault(),
        turnClock: TurnClock = TurnClock(),
    ): Triple<ToolDispatcher, ConfirmationGate, CommerceTools> {
        val gate = newGate(dir)
        val tools = CommerceTools(commerce, gate, config, vault, turnClock)
        val registry = tools.register(ToolRegistry.builder()).build()
        return Triple(ToolDispatcher(registry), gate, tools)
    }

    private suspend fun call(dispatcher: ToolDispatcher, tool: String, args: String = "{}") =
        dispatcher.dispatch(LlmBlock.ToolUse("tu_1", tool, args)).result.content

    // ── R2: exactly one gated tool ──────────────────────────────────────────

    @Test
    @DisplayName("R2/EC-Z16: only the order tool is gated; every cart operation is autonomous")
    fun `only ordering is gated`(@TempDir dir: Path) {
        val gate = newGate(dir)
        val registry = CommerceTools(FakeCommerce(), gate, CommerceConfig(), RecordingVault())
            .register(ToolRegistry.builder()).build()

        assertEquals(listOf("commerce_propose_order"), registry.gatedNames())
    }

    // ── EC-Z1 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z1: the list is written to the vault before anything touches the network")
    fun `save list writes a note`(@TempDir dir: Path) = runTest {
        val vault = RecordingVault()
        val (dispatcher, _, _) = setup(dir, FakeCommerce(), vault = vault)

        val result = call(dispatcher, "commerce_save_list", """{"items":["2 kg onions","bread"]}""")

        assertTrue(result.contains("\"saved\":true"))
        assertEquals(1, vault.written.size)
        assertEquals("Lists", vault.written.single().folder)
        assertTrue(vault.written.single().bodyMarkdown.contains("2 kg onions"))
    }

    @Test
    @DisplayName("D-092: a list saved from a photographed turn is recorded with IMAGE provenance, not VOICE")
    fun `save list from an image turn records IMAGE source`(@TempDir dir: Path) = runTest {
        val vault = RecordingVault()
        val turnClock = TurnClock().apply { set(java.time.Instant.now(), java.time.ZoneId.systemDefault(), hasImage = true) }
        val (dispatcher, _, _) = setup(dir, FakeCommerce(), vault = vault, turnClock = turnClock)

        call(dispatcher, "commerce_save_list", """{"items":["bread"]}""")

        assertEquals(NoteSource.IMAGE, vault.written.single().source)
    }

    @Test
    @DisplayName("a voice-only turn (the default) still records VOICE provenance, unchanged from before D-092")
    fun `save list from a voice turn still records VOICE source`(@TempDir dir: Path) = runTest {
        val vault = RecordingVault()
        val (dispatcher, _, _) = setup(dir, FakeCommerce(), vault = vault)

        call(dispatcher, "commerce_save_list", """{"items":["bread"]}""")

        assertEquals(NoteSource.VOICE, vault.written.single().source)
    }

    @Test
    fun `save list with no items is rejected`(@TempDir dir: Path) = runTest {
        val (dispatcher, _, _) = setup(dir, FakeCommerce())
        assertTrue(call(dispatcher, "commerce_save_list", """{"items":[]}""").contains("empty_list"))
    }

    // ── EC-Z2 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z2: zero results tells the model to ask, and explicitly not to substitute")
    fun `zero search results are a normal answer`(@TempDir dir: Path) = runTest {
        val (dispatcher, _, _) = setup(dir, FakeCommerce(searchResults = emptyList()))

        val result = call(dispatcher, "commerce_search", """{"query":"saffron"}""")

        assertTrue(result.contains("\"count\":0"))
        assertTrue(result.contains("Do not substitute"))
    }

    @Test
    @DisplayName("D-091: a provider failure is never reported as 'nothing matched'")
    fun `a search provider error is distinguished from zero results`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(searchOutcome = SearchOutcome.ProviderError("Couldn't set a delivery location: session expired"))
        val (dispatcher, _, _) = setup(dir, commerce)

        val result = call(dispatcher, "commerce_search", """{"query":"steel bottle"}""")

        assertTrue(result.contains("search_failed"))
        assertTrue(result.contains("session expired"))
        // The exact bug D-090/D-091 exist to prevent: a real failure must
        // never come back looking like "nothing matched", because the
        // model's own next move for that (try a different name) cannot fix it.
        assertFalse(result.contains("\"count\":0"))
        assertTrue(result.contains("Do not say nothing was found"))
    }

    @Test
    @DisplayName("WF-4: every candidate carries a name/size/price read-back")
    fun `search results include a read back line`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(
            searchResults = listOf(Product("p1", "Britannia Brown Bread", "400 g", Money.ofRupees(45)))
        )
        val (dispatcher, _, _) = setup(dir, commerce)

        val result = call(dispatcher, "commerce_search", """{"query":"bread"}""")

        assertTrue(result.contains("read_back"))
        assertTrue(result.contains("400 g"))
        assertTrue(result.contains("45 rupees"))
    }

    // ── Stage 4 (D-098): commerce_prepare_list, the comparison-table entry point ──

    @Test
    @DisplayName("Stage 4: one commerce_prepare_list call searches every item and returns candidates per item")
    fun `prepare list returns candidates grouped by query`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(
            searchOutcomesByQuery = mapOf(
                "bread" to SearchOutcome.Found(listOf(Product("p1", "Brown Bread", "400 g", Money.ofRupees(45)))),
                "milk" to SearchOutcome.Found(listOf(Product("p2", "Toned Milk", "500 ml", Money.ofRupees(28)))),
            ),
        )
        val (dispatcher, _, _) = setup(dir, commerce)

        val result = call(dispatcher, "commerce_prepare_list", """{"items":[{"query":"bread"},{"query":"milk"}]}""")

        assertTrue(result.contains("\"query\":\"bread\""))
        assertTrue(result.contains("Brown Bread"))
        assertTrue(result.contains("\"query\":\"milk\""))
        assertTrue(result.contains("Toned Milk"))
        assertEquals(setOf("bread", "milk"), commerce.searchedQueries.toSet())
    }

    @Test
    @DisplayName("D-091's ProviderError distinction carries into commerce_prepare_list too, per item")
    fun `prepare list flags a provider error without hiding the other items' results`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(
            searchOutcomesByQuery = mapOf(
                "bread" to SearchOutcome.Found(listOf(Product("p1", "Brown Bread", "400 g", Money.ofRupees(45)))),
                "steel bottle" to SearchOutcome.ProviderError("session expired"),
            ),
        )
        val (dispatcher, _, _) = setup(dir, commerce)

        val result = call(dispatcher, "commerce_prepare_list", """{"items":[{"query":"bread"},{"query":"steel bottle"}]}""")

        assertTrue(result.contains("Brown Bread"))
        assertTrue(result.contains("session expired"))
        assertTrue(result.contains("At least one search failed outright"))
    }

    @Test
    @DisplayName("a zero-match item comes back with an empty candidate list, not an error")
    fun `prepare list reports a genuine zero match as empty candidates`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(searchOutcomesByQuery = mapOf("saffron" to SearchOutcome.NoMatch))
        val (dispatcher, _, _) = setup(dir, commerce)

        val result = call(dispatcher, "commerce_prepare_list", """{"items":[{"query":"saffron"}]}""")

        assertTrue(result.contains("\"query\":\"saffron\""))
        assertTrue(result.contains("\"candidates\":[]"))
        assertFalse(result.contains("\"error\""))
    }

    @Test
    fun `prepare list with no items is rejected`(@TempDir dir: Path) = runTest {
        val (dispatcher, _, _) = setup(dir, FakeCommerce())
        assertTrue(call(dispatcher, "commerce_prepare_list", """{"items":[]}""").contains("empty_list"))
    }

    @Test
    @DisplayName("R7: concurrency is bounded by config, not unlimited")
    fun `prepare list never runs more searches at once than the configured concurrency`(@TempDir dir: Path) = runTest {
        var inFlight = 0
        var maxInFlight = 0
        val lock = Mutex()
        val commerce = object : CommercePort {
            override val isFake = true
            override val displayName = "TestMart"
            override suspend fun availability() = CommerceAvailability.Ready
            override suspend fun search(query: String, limit: Int): SearchOutcome {
                lock.withLock { inFlight++; if (inFlight > maxInFlight) maxInFlight = inFlight }
                delay(20)
                lock.withLock { inFlight-- }
                return SearchOutcome.Found(listOf(Product("p", query, null, Money.ofRupees(10))))
            }
            override suspend fun addToCart(productId: String, quantity: Int) = CartMutation.Applied(Cart())
            override suspend fun updateQuantity(lineId: String, quantity: Int) = CartMutation.Applied(Cart())
            override suspend fun removeFromCart(lineId: String) = CartMutation.Applied(Cart())
            override suspend fun readCart() = Cart()
            override suspend fun placeOrder(cart: Cart, idempotencyKey: String) = OrderOutcome.Placed("x")
        }
        val gate = newGate(dir)
        val tools = CommerceTools(commerce, gate, CommerceConfig(enabled = true, maxComparisonConcurrency = 2), RecordingVault())
        val dispatcher = ToolDispatcher(tools.register(ToolRegistry.builder()).build())

        val items = (1..8).joinToString(",") { """{"query":"item$it"}""" }
        call(dispatcher, "commerce_prepare_list", """{"items":[$items]}""")

        assertTrue(maxInFlight <= 2, "expected at most 2 concurrent searches, saw $maxInFlight")
    }

    // ── the cart-edit flow ──────────────────────────────────────────────────

    @Test
    @DisplayName("the user's 'make it two, not four' path")
    fun `quantity can be reduced through the tool`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45, qty = 4))))
        val (dispatcher, _, _) = setup(dir, commerce)

        call(dispatcher, "commerce_cart_update", """{"line_id":"l1","quantity":2}""")

        assertEquals(2, commerce.cart.lines.single().quantity)
    }

    @Test
    @DisplayName("the user's 'drop the milk' path")
    fun `an item can be removed through the tool`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(
            cart = Cart(lines = listOf(line("l1", "Milk", 74), line("l2", "Bread", 45)))
        )
        val (dispatcher, _, _) = setup(dir, commerce)

        call(dispatcher, "commerce_cart_remove", """{"line_id":"l1"}""")

        assertEquals(listOf("Bread"), commerce.cart.lines.map { it.name })
    }

    @Test
    fun `quantity zero removes rather than leaving an empty line`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45, qty = 3))))
        val (dispatcher, _, _) = setup(dir, commerce)

        call(dispatcher, "commerce_cart_update", """{"line_id":"l1","quantity":0}""")

        assertTrue(commerce.cart.isEmpty)
    }

    @Test
    fun `a cart mutation without a line id is a field error, not a crash`(@TempDir dir: Path) = runTest {
        val (dispatcher, _, _) = setup(dir, FakeCommerce())
        assertTrue(call(dispatcher, "commerce_cart_remove", "{}").contains("invalid_input"))
        assertTrue(call(dispatcher, "commerce_cart_update", """{"line_id":"l1"}""").contains("invalid_input"))
    }

    // ── EC-Z18 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z18: a lost cart write says 'look', never 'try again'")
    fun `an unknown mutation outcome forbids a blind retry`(@TempDir dir: Path) = runTest {
        val commerce = object : CommercePort by FakeCommerce() {
            override suspend fun addToCart(productId: String, quantity: Int) =
                CartMutation.Unknown("the connection dropped")
        }
        val gate = newGate(dir)
        val registry = CommerceTools(commerce, gate, CommerceConfig(), RecordingVault())
            .register(ToolRegistry.builder()).build()

        val result = ToolDispatcher(registry)
            .dispatch(LlmBlock.ToolUse("tu_1", "commerce_cart_add", """{"product_id":"p1"}""")).result.content

        assertTrue(result.contains("commerce_cart_view"), "must tell the model to look")
        assertTrue(result.contains("Never repeat the call blind"))
    }

    // ── EC-Z6 / EC-Z19 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z6/EC-Z19: the cart is always re-read before proposing, never remembered")
    fun `propose re-reads the cart from the server`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45))))
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }

        assertTrue(commerce.readCartCalls > 0, "the proposal must come from a fresh read")
        gate.cancel(gate.state.value!!.proposalId)
        dispatched.await()
    }

    @Test
    fun `proposing an empty cart is refused before any window opens`(@TempDir dir: Path) = runTest {
        val (dispatcher, gate, _) = setup(dir, FakeCommerce(cart = Cart()))

        val result = call(dispatcher, "commerce_propose_order")

        assertTrue(result.contains("empty_cart"))
        assertNull(gate.state.value, "no gate should have opened")
    }

    // ── EC-Z9 / EC-Z21 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z9: no COD means stop, never fall back to another payment method")
    fun `cod unavailable blocks the proposal`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(
            cart = Cart(
                lines = listOf(line("l1", "Milk", 28)),
                codAvailable = false,
                codUnavailableReason = "Minimum order is 99 rupees.",
            )
        )
        val (dispatcher, gate, _) = setup(dir, commerce)

        val result = call(dispatcher, "commerce_propose_order")

        assertTrue(result.contains("cod_unavailable"))
        assertTrue(result.contains("Do not offer or use any other payment method"))
        assertNull(gate.state.value)
    }

    // ── EC-Z10 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z10: failures are spoken before the total, not after it")
    fun `failed items lead the speech summary`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45))))
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(
                dispatcher, "commerce_propose_order",
                """{"failed_items":[{"requested":"paneer","reason":"out of stock"}]}""",
            )
        }

        val proposal = gate.state.value!!.proposal as OrderProposal
        val summary = proposal.speechSummary
        assertTrue(summary.startsWith("I couldn't get paneer"), "summary was: $summary")
        assertTrue(summary.indexOf("paneer") < summary.indexOf("rupees"), "failure must precede the total")
        assertEquals(1, proposal.failedItems.size)

        gate.cancel(gate.state.value!!.proposalId)
        dispatched.await()
    }

    // ── EC-Z17 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z17: a total over the ceiling is flagged on the proposal")
    fun `over-ceiling orders are marked`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Rice", 3_000))))
        val (dispatcher, gate, _) = setup(dir, commerce, CommerceConfig(enabled = true, orderCeilingInr = 2_000))

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }

        val proposal = gate.state.value!!.proposal as OrderProposal
        assertTrue(proposal.overCeiling)
        assertEquals(Money.ofRupees(2_000), proposal.ceiling)
        assertTrue(proposal.speechSummary.contains("over your usual limit"))

        gate.cancel(gate.state.value!!.proposalId)
        dispatched.await()
    }

    @Test
    fun `a total under the ceiling is not flagged`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45))))
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }

        assertFalse((gate.state.value!!.proposal as OrderProposal).overCeiling)
        gate.cancel(gate.state.value!!.proposalId)
        dispatched.await()
    }

    // ── EC-Z14: the revision loop ───────────────────────────────────────────

    @Test
    @DisplayName("EC-Z14: speaking during the order window revises instead of approving")
    fun `a spoken revision returns the words to the model and places nothing`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Milk", 74), line("l2", "Bread", 45))))
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }

        gate.requestRevision(gate.state.value!!.proposalId, "drop the milk and make it two breads")

        val result = dispatched.await()
        assertTrue(result.contains("\"revision_requested\":true"))
        // Verbatim, because "two, not four" only survives if the numbers do.
        assertTrue(result.contains("drop the milk and make it two breads"))
        assertFalse(result.contains("cancelled_by_user"), "a revision is not a cancellation")
        assertNull(commerce.placedWith, "nothing may be ordered on a revision")
    }

    @Test
    @DisplayName("EC-Z14: the 'Change something' button revises with no instruction, and the model is told to ask")
    fun `a revision with no words tells the model to ask`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45))))
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }

        gate.requestRevision(gate.state.value!!.proposalId, null)

        val result = dispatched.await()
        assertTrue(result.contains("\"revision_requested\":true"))
        assertTrue(result.contains("ask_user"))
        assertNull(commerce.placedWith)
    }

    @Test
    @DisplayName("the full loop: propose, revise by voice, edit the cart, propose again, order")
    fun `revise then re-propose then place`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Milk", 74), line("l2", "Bread", 45, qty = 4))))
        val (dispatcher, gate, _) = setup(dir, commerce)

        // 1. First proposal.
        val first = async(start = CoroutineStart.UNDISPATCHED) { call(dispatcher, "commerce_propose_order") }
        gate.requestRevision(gate.state.value!!.proposalId, "drop the milk, only two breads")
        assertTrue(first.await().contains("revision_requested"))

        // 2. The model acts on it with the autonomous cart tools.
        call(dispatcher, "commerce_cart_remove", """{"line_id":"l1"}""")
        call(dispatcher, "commerce_cart_update", """{"line_id":"l2","quantity":2}""")

        // 3. Propose again - and the second proposal reflects the real cart.
        val second = async(start = CoroutineStart.UNDISPATCHED) { call(dispatcher, "commerce_propose_order") }
        val revised = gate.state.value!!.proposal as OrderProposal
        assertEquals(listOf("Bread"), revised.cart.lines.map { it.name })
        assertEquals(2, revised.cart.lines.single().quantity)
        assertEquals(Money.ofRupees(90), revised.cart.total)

        // 4. Only now, on a click, does anything get ordered.
        val secondId = gate.state.value!!.proposalId
        gate.confirmContent(secondId)
        gate.confirmExecute(secondId)

        assertTrue(second.await().contains("ORD-1"))
        // R5: the idempotency key is the *second* proposal's id - a revision
        // gets a new row rather than mutating the abandoned one.
        assertEquals(secondId, commerce.placedWith)
    }

    // ── the irreversible step ───────────────────────────────────────────────

    @Test
    @DisplayName("R5: the order is placed with the ledger's proposal_id as the idempotency key")
    fun `placing uses the proposal id`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45))))
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }
        val proposalId = gate.state.value!!.proposalId
        gate.confirmContent(proposalId)
        gate.confirmExecute(proposalId)

        assertTrue(dispatched.await().contains("ORD-1"))
        assertEquals(proposalId, commerce.placedWith)
    }

    @Test
    @DisplayName("EC-Z8: a lost order response is never reported as a failure, and never retried")
    fun `an unknown order outcome tells the user to check manually`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(
            cart = Cart(lines = listOf(line("l1", "Bread", 45))),
            orderOutcome = OrderOutcome.Unknown("the connection dropped after sending"),
        )
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }
        val proposalId = gate.state.value!!.proposalId
        gate.confirmContent(proposalId)
        gate.confirmExecute(proposalId)

        val result = dispatched.await()
        assertTrue(result.contains("\"status\":\"unknown\""))
        assertTrue(result.contains("Do not try again"))
    }

    @Test
    @DisplayName("EC-E6: cancelling tells the model not to re-propose, and orders nothing")
    fun `cancelling places nothing`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45))))
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }
        gate.cancel(gate.state.value!!.proposalId)

        assertTrue(dispatched.await().contains("cancelled_by_user"))
        assertNull(commerce.placedWith)
    }

    // ── EC-Z7 and the fake label ────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z7: a pre-existing line is called out in the spoken summary")
    fun `pre-existing lines are announced`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(
            cart = Cart(lines = listOf(line("old", "Butter", 62, thisSession = false), line("l1", "Bread", 45)))
        )
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }

        val proposal = gate.state.value!!.proposal as OrderProposal
        assertTrue(proposal.speechSummary.contains("already in the cart"), proposal.speechSummary)

        gate.cancel(gate.state.value!!.proposalId)
        dispatched.await()
    }

    @Test
    @DisplayName("Step 7: a fake adapter marks the proposal so the UI can never demo it silently")
    fun `fake adapters are flagged on the proposal`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(cart = Cart(lines = listOf(line("l1", "Bread", 45))), isFake = true)
        val (dispatcher, gate, _) = setup(dir, commerce)

        val dispatched = async(start = CoroutineStart.UNDISPATCHED) {
            call(dispatcher, "commerce_propose_order")
        }

        assertTrue((gate.state.value!!.proposal as OrderProposal).isFake)
        gate.cancel(gate.state.value!!.proposalId)
        dispatched.await()
    }

    // ── EC-Z1 / EC-Z22 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-Z1: an unreachable provider is a speakable reason, and the list is protected")
    fun `unavailable commerce reports cleanly`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(availability = CommerceAvailability.Unavailable("Zepto is not responding."))
        val (dispatcher, _, _) = setup(dir, commerce)

        val result = call(dispatcher, "commerce_search", """{"query":"bread"}""")

        assertTrue(result.contains("commerce_unavailable"))
        assertTrue(result.contains("commerce_save_list"), "should protect the list")
    }

    @Test
    fun `a signed-out provider asks the user to sign in`(@TempDir dir: Path) = runTest {
        val commerce = FakeCommerce(availability = CommerceAvailability.NeedsLogin("Not signed in."))
        val (dispatcher, _, _) = setup(dir, commerce)

        assertTrue(call(dispatcher, "commerce_cart_view").contains("needs_login"))
    }
}
