package com.secondbrain.integrations

import com.secondbrain.model.Cart
import com.secondbrain.model.CartLine
import com.secondbrain.model.CartMutation
import com.secondbrain.model.CommerceAvailability
import com.secondbrain.model.Money
import com.secondbrain.model.OrderOutcome
import com.secondbrain.model.Product
import com.secondbrain.model.SearchOutcome
import com.secondbrain.ports.CommercePort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * [CommercePort] over a live MCP server.
 *
 * ### D-089 supersedes D-079's guesswork with the real API
 *
 * D-079/D-080 shipped this class from Zepto's own *documentation*, because no
 * authenticated session had ever been reached — the adapter discovered role
 * bindings by scoring `tools/list` against name/description patterns rather
 * than assuming fixed tool names. That discipline (see [Role], [bestMatch])
 * is kept, because the shape it protects against — a tool renamed on Zepto's
 * side silently breaking every call — is still real. What changes with D-089
 * is everything downstream of a bound name: real Zepto exposes 23 tools, and
 * reading their actual `inputSchema`s plus live response bodies (`:app`'s
 * `ZeptoDiscovery`) answered every "Uncertain" D-079 recorded:
 *
 * - **Prices are integers, already in paise** (`29900` = ₹299.00), not a
 *   string to guess-parse — [Money.parse] was built for the wrong shape and
 *   is not used here; see [moneyOf].
 * - **There is one cart-write tool, `update_cart`, not three.** Add, change
 *   quantity, and remove (quantity `0`) are the same call with different
 *   arguments — see [Role.CART_WRITE] and D-089's note there.
 * - **A cart line has no id of its own.** Zepto keys everything on the pair
 *   `(productVariantId, storeProductId)`. [Product.id] and [CartLine.lineId]
 *   both carry that pair joined as `"pvid|spid"` — see [compositeId] — so
 *   `:model`/`:ports` stay untouched (an opaque string id is still all they
 *   see) and only this file knows the pair exists.
 * - **Nothing works before a store is selected**, and this was the entire
 *   cause of the "no results" the user hit live: `search_products` (and every
 *   cart/order tool) returns a tool-level error — *"Store not selected"* —
 *   until a store is set for the session, via `select_store` (needs
 *   coordinates this desktop app has no way to get) or `select_saved_address`
 *   (needs only an id from `list_saved_addresses`, and *also* sets the store).
 *   [ensureStoreSelected] runs that once, lazily, before the first real call.
 * - **`create_order` is its own two-stage gate** (`confirmOrder: false`
 *   previews, `true` commits) layered *underneath* ours. Since [placeOrder]
 *   is only ever reached after a human has already clicked through
 *   `ConfirmationGate`, that click **is** the confirmation Zepto's own flag
 *   asks for — `confirmOrder` is sent `true` directly, once, per R9.
 *
 * ### What is still a guess
 *
 * A *populated* cart's `items[]` shape was never observed live — the account
 * probed had an empty cart at the time (D-089) — so [parseCart] infers it
 * from `update_cart`'s own input schema (which a cart item's fields should
 * mirror) and stays tolerant of alternate spellings the way D-079's version
 * was for everything. Tighten this the first time a real populated cart is
 * read and record what it actually said.
 */
class McpCommerceAdapter(
    private val client: McpClient,
    private val oauth: McpOAuth,
    /** D-089: `update_cart`'s required fallback cart key. See [DeviceId]. */
    private val deviceId: String,
) : CommercePort {

    private val log = LoggerFactory.getLogger(McpCommerceAdapter::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override val isFake: Boolean = false
    override val displayName: String = "Zepto"

    /**
     * D-089: four roles, not six. `CART_ADD`/`CART_UPDATE`/`CART_REMOVE` from
     * D-079 assumed three distinct tools; the real API has one (`update_cart`)
     * that every cart write goes through, distinguished only by `quantity`.
     */
    enum class Role { SEARCH, CART_VIEW, CART_WRITE, ORDER_PLACE, ADDRESS_LIST, ADDRESS_SELECT }

    @Volatile private var bindings: Map<Role, String> = emptyMap()
    @Volatile private var discovered = false

    /** D-089: which saved address (and therefore which store) this session is using. Selected once, lazily. */
    @Volatile private var selectedAddressId: String? = null
    private val storeSelectionMutex = Mutex()

    /** Lines the server already had the first time we looked (EC-Z7). */
    @Volatile private var preExistingLineIds: Set<String> = emptySet()
    @Volatile private var baselineTaken = false

    // ── availability & discovery ────────────────────────────────────────────

    override suspend fun availability(): CommerceAvailability {
        if (!oauth.isSignedIn()) {
            return CommerceAvailability.NeedsLogin("You're not signed in to Zepto yet.")
        }
        return when (val result = discover()) {
            is McpClient.McpResult.Err -> when (val e = result.error) {
                is McpClient.McpError.Unauthorized -> CommerceAvailability.NeedsLogin(e.message)
                is McpClient.McpError.SessionLost -> CommerceAvailability.Unavailable(e.message)
                is McpClient.McpError.Rpc -> CommerceAvailability.Unavailable(e.message)
                is McpClient.McpError.Transport -> CommerceAvailability.Unavailable(e.message)
            }
            is McpClient.McpResult.Ok -> when (val store = ensureStoreSelected()) {
                is McpClient.McpResult.Err -> when (val e = store.error) {
                    is McpClient.McpError.Unauthorized -> CommerceAvailability.NeedsLogin(e.message)
                    is McpClient.McpError.SessionLost -> CommerceAvailability.Unavailable(e.message)
                    is McpClient.McpError.Rpc -> CommerceAvailability.Unavailable(e.message)
                    is McpClient.McpError.Transport -> CommerceAvailability.Unavailable(e.message)
                }
                is McpClient.McpResult.Ok -> CommerceAvailability.Ready
            }
        }
    }

    /**
     * Binds roles to real tool names. Idempotent; the table is logged once.
     *
     * Also runs the classification table §5 WF-4 asks for, so what the bridge
     * would have decided is visible even for tools this adapter does not bind.
     */
    suspend fun discover(): McpClient.McpResult<Map<Role, String>> {
        if (discovered) return McpClient.McpResult.Ok(bindings)

        return when (val listed = client.listTools()) {
            is McpClient.McpResult.Err -> listed
            is McpClient.McpResult.Ok -> {
                val tools = listed.value
                val bound = mutableMapOf<Role, String>()
                Role.entries.forEach { role ->
                    bestMatch(role, tools)?.let { bound[role] = it }
                }
                bindings = bound
                discovered = true

                log.info("MCP exposed {} tool(s). Role bindings:", tools.size)
                Role.entries.forEach { role ->
                    log.info("  {} -> {}", role.name.padEnd(13), bound[role] ?: "(unbound)")
                }
                // R2/R3 visibility, for every tool, bound or not. Known false
                // positives here (D-089): search_products/view_cart/
                // get_product_details classify GATED because their real
                // descriptions are marketing prose mentioning "order" /
                // "checkout" / "purchase" in passing, not because they mutate
                // anything - this table is diagnostic only, since nothing in
                // this codebase gates by it (no dynamic bridge exists, D-082
                // gap 3). CommerceTools' own six-autonomous-one-gated split
                // is what actually governs R2.
                log.info("Mutation classification:")
                MutationClassifier.classifyAll(tools.map { it.name to it.description }).forEach {
                    log.info("  {}  {}  [{}]", it.toolClass.name.padEnd(10), it.toolName, it.reason)
                }

                val missing = Role.entries.filter { it !in bound }
                if (missing.isNotEmpty()) {
                    log.warn(
                        "No tool matched: {}. Those capabilities will report unavailable rather than guess.",
                        missing.joinToString(", "),
                    )
                }
                McpClient.McpResult.Ok(bound)
            }
        }
    }

    /**
     * D-089: the actual root cause of "no results" against the real server.
     * `search_products` (and every cart/order tool) return a tool-level error
     * — *"Store not selected. Please use get_location_serviceability or
     * select_store first."* — until a store is set for the session. This app
     * has no GPS, so the only viable path is `list_saved_addresses` ->
     * `select_saved_address`, which the tool's own description confirms
     * *"automatically set[s] the corresponding store context for future
     * actions."*
     *
     * Runs once per adapter lifetime (a fresh sign-in or app restart re-runs
     * it), guarded by [storeSelectionMutex] so two concurrent first-calls
     * cannot both select. The address chosen is simply the first one Zepto
     * returns — there is no UI yet for picking among several, which is a real,
     * named limitation (D-089's own "Uncertain").
     */
    private suspend fun ensureStoreSelected(): McpClient.McpResult<Unit> {
        selectedAddressId?.let { return McpClient.McpResult.Ok(Unit) }

        return storeSelectionMutex.withLock {
            selectedAddressId?.let { return@withLock McpClient.McpResult.Ok(Unit) }

            val listTool = toolFor(Role.ADDRESS_LIST)
                ?: return@withLock McpClient.McpResult.Err(
                    McpClient.McpError.Transport("This provider has no way to list delivery addresses."),
                )
            val selectTool = toolFor(Role.ADDRESS_SELECT)
                ?: return@withLock McpClient.McpResult.Err(
                    McpClient.McpError.Transport("This provider has no way to select a delivery address."),
                )

            val listed = when (val r = client.callTool(listTool, JsonObject(emptyMap()))) {
                is McpClient.McpResult.Err -> return@withLock r
                is McpClient.McpResult.Ok -> r.value
            }
            if (McpClient.isToolError(listed)) {
                return@withLock McpClient.McpResult.Err(
                    McpClient.McpError.Rpc(0, McpClient.flattenContent(listed).take(300)),
                )
            }

            val addressId = firstAddressId(payloadOf(listed))
                ?: return@withLock McpClient.McpResult.Err(
                    McpClient.McpError.Transport(
                        "No saved delivery address on this Zepto account. Add one in the Zepto app first.",
                    ),
                )

            val selected = when (val r = client.callTool(selectTool, buildJsonObject { put("addressId", addressId) })) {
                is McpClient.McpResult.Err -> return@withLock r
                is McpClient.McpResult.Ok -> r.value
            }
            if (McpClient.isToolError(selected)) {
                return@withLock McpClient.McpResult.Err(
                    McpClient.McpError.Rpc(0, McpClient.flattenContent(selected).take(300)),
                )
            }

            selectedAddressId = addressId
            log.info("Store selected via saved address {}.", addressId)
            McpClient.McpResult.Ok(Unit)
        }
    }

    /**
     * Scores each tool for a role and returns the best, or null if nothing
     * scores above zero.
     *
     * Scoring rather than first-match because names overlap: `get_cart` and
     * `add_to_cart` both contain "cart", and a naive `contains` would bind
     * whichever came back first. Required terms must all be present; optional
     * ones only break ties.
     *
     * D-090: `required`/`preferred`/`excluded` are all matched by plain
     * substring, deliberately, over the tempting-looking alternative of
     * splitting each name into whole words and matching those instead.
     * Substring is what lets `"detail"` correctly exclude `get_product_details`
     * (a real ORDER_PLACE near-miss) despite the plural — whole-word matching
     * would need `"detail"` written as `"details"` to catch that, and would
     * simultaneously need `"address"` written as `"addresses"` *and*
     * `"address"` to catch both `list_saved_addresses` and
     * `select_saved_address`. Plurals make one blanket rule wrong in both
     * directions at once, so this is intentional per-pattern substring
     * matching, not an oversight — but it demands care from whoever adds a
     * pattern: an excluded term that is itself a substring of a *required*
     * term (`"add"` inside `"add`ress`"`) silently unbinds the role instead of
     * refining it, which is exactly what D-090 found and fixed. Internal
     * rather than private so [McpCommerceAdapterTest] can assert bindings
     * directly against fixture tool lists, not just the pure JSON readers.
     */
    internal fun bestMatch(role: Role, tools: List<McpClient.McpTool>): String? {
        val spec = ROLE_PATTERNS.getValue(role)
        return tools
            .mapNotNull { tool ->
                val haystack = "${tool.name} ${tool.description.orEmpty()}".lowercase()
                val name = tool.name.lowercase()

                if (spec.excluded.any { name.contains(it) }) return@mapNotNull null
                if (!spec.required.all { req -> req.any { name.contains(it) } }) return@mapNotNull null

                var score = 10
                score += spec.preferred.count { haystack.contains(it) } * 3
                // A term in the name is much stronger evidence than the same
                // term buried in prose.
                score += spec.preferred.count { name.contains(it) } * 2
                tool.name to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private suspend fun toolFor(role: Role): String? {
        if (!discovered) discover()
        return bindings[role]
    }

    // ── search ──────────────────────────────────────────────────────────────

    /**
     * D-091: every early-return here used to be `emptyList()` — an unbound
     * tool, a failed store selection, a transport error, and a genuine zero
     * matches were one indistinguishable outcome, and `CommerceTools` told
     * the user *"nothing matched"* for all four. Only the last one is that.
     */
    override suspend fun search(query: String, limit: Int): SearchOutcome {
        val tool = toolFor(Role.SEARCH)
            ?: return SearchOutcome.ProviderError("Zepto's search tool could not be found.")

        ensureStoreSelected().let {
            if (it is McpClient.McpResult.Err) {
                return SearchOutcome.ProviderError("Couldn't set a delivery location: ${it.error.readable()}")
            }
        }

        // D-089: exactly what search_products' own schema declares. The old
        // code sent q/search/limit alongside query on the theory that extra
        // fields are harmless - true, evidently, but there is no reason to
        // send three guesses when the real name is confirmed.
        val args = buildJsonObject { put("query", query); put("pageNumber", 0) }

        return when (val result = client.callTool(tool, args)) {
            is McpClient.McpResult.Err -> {
                log.warn("Search failed: {}", result.error)
                SearchOutcome.ProviderError(result.error.readable())
            }
            is McpClient.McpResult.Ok -> {
                if (McpClient.isToolError(result.value)) {
                    val message = McpClient.flattenContent(result.value)
                    log.warn("Search tool reported an error: {}", message)
                    // Zepto's own tool-level errors are things like "Store
                    // not selected" - a real failure, not "nothing matched
                    // this query". Session loss surfaces the same way here.
                    if (message.contains("session", ignoreCase = true) || message.contains("store", ignoreCase = true)) {
                        // D-091: a store selection lives on the MCP session,
                        // not on this object. Resetting the client's session
                        // without also forgetting selectedAddressId would
                        // leave the NEXT call skipping ensureStoreSelected's
                        // early-return check against a store the fresh
                        // session never actually selected.
                        client.resetSession()
                        selectedAddressId = null
                    }
                    return SearchOutcome.ProviderError(message.take(300))
                }
                val products = parseProducts(payloadOf(result.value)).take(limit)
                if (products.isEmpty()) SearchOutcome.NoMatch else SearchOutcome.Found(products)
            }
        }
    }

    private fun McpClient.McpError.readable(): String = when (this) {
        is McpClient.McpError.Unauthorized -> "not signed in: $message"
        is McpClient.McpError.SessionLost -> message
        is McpClient.McpError.Rpc -> message
        is McpClient.McpError.Transport -> message
    }

    // ── cart mutations ──────────────────────────────────────────────────────

    override suspend fun addToCart(productId: String, quantity: Int): CartMutation =
        writeCart(productId, quantity)

    override suspend fun updateQuantity(lineId: String, quantity: Int): CartMutation =
        // Defined by the port: zero means removal. D-089: update_cart's own
        // schema says the identical thing - "quantity: Use 0 to remove the
        // item" - so this is not even a routing decision any more, just the
        // one real call.
        writeCart(lineId, quantity)

    override suspend fun removeFromCart(lineId: String): CartMutation =
        writeCart(lineId, 0)

    /**
     * The one real cart-write call. D-089: `update_cart` takes a `deviceId`
     * and a `cartItems` array of `{productVariantId, storeProductId, quantity}`
     * — [compositeId] is where `productId`/`lineId` (a `"pvid|spid"` string,
     * see the class doc) gets split back into the two real fields.
     */
    private suspend fun writeCart(compositeIdString: String, quantity: Int): CartMutation {
        val tool = toolFor(Role.CART_WRITE)
            ?: return CartMutation.Rejected("This provider has no cart tool.")
        val (pvid, spid) = splitComposite(compositeIdString)
            ?: return CartMutation.Rejected("Not a valid product reference: $compositeIdString")

        ensureStoreSelected().let {
            if (it is McpClient.McpResult.Err) return CartMutation.Unknown("Could not select a store: ${it.error}")
        }

        val args = buildJsonObject {
            put("deviceId", deviceId)
            put("cartItems", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("productVariantId", pvid)
                    put("storeProductId", spid)
                    put("quantity", quantity)
                })
            })
        }

        return when (val result = client.callTool(tool, args)) {
            is McpClient.McpResult.Err -> when (val e = result.error) {
                is McpClient.McpError.Unauthorized -> CartMutation.NeedsReauth(e.message)
                is McpClient.McpError.SessionLost -> CartMutation.Unknown(e.message)
                // A transport failure mid-mutation is precisely EC-Z18. Never
                // "Rejected" - that would imply we know it did not apply.
                is McpClient.McpError.Transport -> CartMutation.Unknown(e.message)
                is McpClient.McpError.Rpc -> CartMutation.Rejected(e.message)
            }
            is McpClient.McpResult.Ok -> {
                if (McpClient.isToolError(result.value)) {
                    // A tool-level error IS a definite answer: the server
                    // understood and refused (out of stock, quantity cap).
                    return CartMutation.Rejected(McpClient.flattenContent(result.value).take(300))
                }
                // Prefer the cart the mutation returned; fall back to a read.
                val inline = runCatching { parseCart(payloadOf(result.value)) }.getOrNull()
                CartMutation.Applied(inline ?: readCart())
            }
        }
    }

    // ── cart read ───────────────────────────────────────────────────────────

    override suspend fun readCart(): Cart {
        val tool = toolFor(Role.CART_VIEW) ?: return Cart()
        ensureStoreSelected().let { if (it is McpClient.McpResult.Err) { log.warn("Store selection failed: {}", it.error); return Cart() } }

        return when (val result = client.callTool(tool, JsonObject(emptyMap()))) {
            is McpClient.McpResult.Err -> {
                log.warn("Cart read failed: {}", result.error)
                Cart()
            }
            is McpClient.McpResult.Ok -> {
                val cart = runCatching { parseCart(payloadOf(result.value)) }.getOrNull() ?: Cart()
                // EC-Z7: the first successful read of a session defines what
                // was already there. Everything after is "added this session".
                if (!baselineTaken) {
                    preExistingLineIds = cart.lines.map { it.lineId }.toSet()
                    baselineTaken = true
                    if (preExistingLineIds.isNotEmpty()) {
                        log.info("Cart already had {} line(s) before this session.", preExistingLineIds.size)
                    }
                }
                cart.copy(lines = cart.lines.map { it.copy(addedThisSession = it.lineId !in preExistingLineIds) })
            }
        }
    }

    // ── the irreversible one ────────────────────────────────────────────────

    override suspend fun placeOrder(cart: Cart, idempotencyKey: String): OrderOutcome {
        val tool = toolFor(Role.ORDER_PLACE)
            ?: return OrderOutcome.Failed("This provider exposes no order-placement tool.")

        // D-089: create_order's real schema has no payment-method flag at all
        // - COD is the default call shape, and useZeptoCash/riderTip are the
        // only payment-adjacent fields. EC-Z9's "COD only, never fall back"
        // is therefore automatic here, not something to select: there is no
        // other method being omitted, since online/wallet/UPI payment go
        // through entirely different tools (create_online_payment_order etc.)
        // this adapter never binds or calls.
        //
        // confirmOrder: true, directly, on the first and only call - Zepto's
        // own two-stage preview/commit flag is layered under our own gate,
        // and the human click that reached this function already WAS the
        // confirmation (R9). idempotencyKey has nowhere to go in this
        // schema - create_order exposes no idempotency field - so R5's
        // guarantee rests entirely on ActionLedger never calling this twice
        // for one proposal, same as it always has.
        val args = buildJsonObject {
            put("confirmOrder", true)
            selectedAddressId?.let { put("userAddressId", it) }
        }

        return when (val result = client.callTool(tool, args)) {
            is McpClient.McpResult.Err -> when (val e = result.error) {
                is McpClient.McpError.Unauthorized -> OrderOutcome.NeedsReauth(e.message)
                is McpClient.McpError.Rpc -> OrderOutcome.Failed(e.message)
                // EC-Z8. A lost response after the order call is the case the
                // whole ledger exists for: it may have gone through.
                is McpClient.McpError.Transport -> OrderOutcome.Unknown(e.message)
                is McpClient.McpError.SessionLost -> OrderOutcome.Unknown(e.message)
            }
            is McpClient.McpResult.Ok -> {
                val payload = payloadOf(result.value)
                if (McpClient.isToolError(result.value)) {
                    return OrderOutcome.Failed(McpClient.flattenContent(result.value).take(300))
                }
                val orderId = orderIdIn(payload)
                if (orderId.isNullOrBlank()) {
                    // Succeeded with nothing we can identify it by. Not a
                    // failure - we cannot claim it did not happen - so the user
                    // is told to check rather than being told either story.
                    OrderOutcome.Unknown("The order call succeeded but returned no order id.")
                } else {
                    OrderOutcome.Placed(orderId)
                }
            }
        }
    }

    // ── composite ids: D-089's "pvid|spid" encoding ─────────────────────────

    internal fun compositeId(pvid: String, spid: String): String = "$pvid|$spid"

    internal fun splitComposite(value: String): Pair<String, String>? {
        val parts = value.split("|", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }

    // ── response parsing ────────────────────────────────────────────────────

    /** Prefers `structuredContent`; falls back to parsing the text block as JSON. */
    private fun payloadOf(result: JsonObject): JsonElement {
        McpClient.structuredContent(result)?.let { return it }
        val text = McpClient.flattenContent(result)
        return runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
    }

    /**
     * D-089, confirmed live against `search_products`' real `structuredContent`:
     * `{products: [{productVariantId, storeProductId, name, price, mrp,
     * packSize, availableQuantity, ...}], query, totalCount}`. `price`/`mrp`
     * are raw JSON numbers already in paise — see [moneyOf], never
     * [Money.parse] here.
     */
    internal fun parseProducts(payload: JsonElement): List<Product> {
        val array = findArray(payload, "products", "items", "results", "data", "hits")
            ?: (payload as? JsonArray)
            ?: return emptyList()

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val pvid = firstString(obj, "productVariantId", "id", "variantId") ?: return@mapNotNull null
            val spid = firstString(obj, "storeProductId") ?: return@mapNotNull null
            val name = firstString(obj, "name", "title") ?: return@mapNotNull null
            // No silent zero: a product whose price we cannot read is dropped
            // rather than shown as free.
            val price = moneyOf(obj, "price") ?: return@mapNotNull null
            val availableQty = firstInt(obj, "availableQuantity")

            Product(
                id = compositeId(pvid, spid),
                name = name,
                size = firstString(obj, "packSize", "size", "unit"),
                price = price,
                available = availableQty == null || availableQty > 0,
            )
        }
    }

    /**
     * D-089: `view_cart`'s real `structuredContent` for an *empty* cart is
     * `{items: [], isEmpty: true, totalItems: 0}` — confirmed live. A
     * populated cart's item shape was never observed (the probed account's
     * cart was empty); this infers it from `update_cart`'s own input schema,
     * which a stored line should mirror, and stays tolerant of the field
     * names D-079's version already covered as a fallback.
     */
    internal fun parseCart(payload: JsonElement): Cart {
        val root = (payload as? JsonObject)?.let { obj ->
            (obj["cart"] as? JsonObject) ?: (obj["data"] as? JsonObject) ?: obj
        } ?: return Cart()

        val array = findArray(root, "items", "lines", "cartItems", "products") ?: JsonArray(emptyList())

        val lines = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val pvid = firstString(obj, "productVariantId", "variantId", "id") ?: return@mapNotNull null
            val spid = firstString(obj, "storeProductId") ?: return@mapNotNull null
            val name = firstString(obj, "name", "label", "title") ?: return@mapNotNull null
            val quantity = firstInt(obj, "quantity", "qty") ?: return@mapNotNull null
            val unitPrice = moneyOf(obj, "price", "unitPrice") ?: return@mapNotNull null

            CartLine(
                lineId = compositeId(pvid, spid),
                productId = compositeId(pvid, spid),
                name = name,
                size = firstString(obj, "packSize", "size"),
                unitPrice = unitPrice,
                quantity = quantity,
            )
        }

        // D-089: create_order's schema carries no COD-availability flag of
        // its own, and view_cart's confirmed empty-cart shape has none
        // either - unlike D-079's guess, there is no field to read this
        // from yet. Defaulting true rather than false: EC-Z9's actual guard
        // is placeOrder() itself refusing a non-COD outcome, not this flag,
        // so a wrong true here costs nothing my search couldn't already
        // catch at order time - a wrong false would incorrectly block every
        // order. Tighten the moment a real COD-unavailable cart is observed.
        return Cart(
            lines = lines,
            deliveryFee = moneyOf(root, "deliveryFee", "delivery_fee") ?: Money.ZERO,
            serverTotal = moneyOf(root, "total", "grandTotal", "payable"),
            codAvailable = true,
            codUnavailableReason = null,
        )
    }

    /**
     * D-089: reads a price the way the real API actually sends one — a raw
     * JSON number, already in paise (`29900` = ₹299.00) — never a string to
     * guess-parse. [Money.parse] exists for the *other* shape (a formatted
     * string like `"₹45"`) that D-079 could not rule out before a real
     * response existed; now that one has, this is the correct reader for
     * every field Zepto actually returns a price on.
     */
    internal fun moneyOf(obj: JsonObject, vararg keys: String): Money? {
        keys.forEach { key ->
            val prim = obj[key] as? JsonPrimitive ?: return@forEach
            if (prim.jsonPrimitive.content == "null") return@forEach
            prim.content.toLongOrNull()?.let { return Money.ofPaise(it) }
        }
        return null
    }

    // ── tolerant field access ───────────────────────────────────────────────

    /** D-089: `list_saved_addresses`' real `structuredContent.addresses[].id`. */
    internal fun firstAddressId(payload: JsonElement): String? {
        val addresses = findArray(payload, "addresses") ?: return null
        val first = addresses.firstOrNull() as? JsonObject ?: return null
        return firstString(first, "id", "addressId")
    }

    /**
     * The order id, wherever it turns out to live: at the top level, or nested
     * under `order`/`data`, which is the other common shape. Returns null
     * rather than picking something id-shaped at random — a wrong order id is
     * worse than none, because the user would go looking for it in the app.
     */
    private fun orderIdIn(payload: JsonElement): String? {
        val obj = payload as? JsonObject ?: return null
        val keys = arrayOf("order_id", "orderId", "id", "order_number", "orderNumber", "reference")
        firstString(obj, *keys)?.let { return it }
        listOf("order", "data", "result").forEach { wrapper ->
            (obj[wrapper] as? JsonObject)?.let { nested -> firstString(nested, *keys)?.let { return it } }
        }
        return null
    }

    private fun findArray(payload: JsonElement, vararg keys: String): JsonArray? {
        val obj = payload as? JsonObject ?: return payload as? JsonArray
        keys.forEach { key -> (obj[key] as? JsonArray)?.let { return it } }
        // One level down: {"data": {"products": [...]}}
        keys.forEach { key ->
            obj.values.filterIsInstance<JsonObject>().forEach { nested ->
                (nested[key] as? JsonArray)?.let { return it }
            }
        }
        return null
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        keys.forEach { key ->
            val value = obj[key] ?: return@forEach
            val primitive = value as? JsonPrimitive ?: return@forEach
            val content = primitive.content
            if (content.isNotBlank() && content != "null") return content
        }
        return null
    }

    private fun firstInt(obj: JsonObject, vararg keys: String): Int? {
        keys.forEach { key ->
            (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private data class RolePattern(
        /** Every group must have at least one term present in the tool NAME. */
        val required: List<List<String>>,
        /** Tie-breakers. */
        val preferred: List<String> = emptyList(),
        /** Any of these in the name disqualifies the tool outright. */
        val excluded: List<String> = emptyList(),
    )

    private companion object {
        val ROLE_PATTERNS: Map<Role, RolePattern> = mapOf(
            Role.SEARCH to RolePattern(
                required = listOf(listOf("search", "find", "query", "lookup", "browse", "product")),
                preferred = listOf("product", "catalog", "search"),
                // D-089: "multiple" excludes search_multiple_products - this
                // adapter searches one item at a time (matching CommerceTools'
                // own "search for one item at a time" prompt instruction), so
                // the single-product tool is the correct, deliberate bind.
                excluded = listOf("order", "cart", "multiple", "detail", "past"),
            ),
            Role.CART_VIEW to RolePattern(
                required = listOf(listOf("cart", "basket"), listOf("get", "view", "read", "show", "list", "fetch")),
                preferred = listOf("cart", "get", "view"),
                excluded = listOf("add", "remove", "delete", "update", "clear", "checkout"),
            ),
            // D-089: was three roles (CART_ADD/CART_UPDATE/CART_REMOVE). The
            // real API has one tool for all three - see the class doc.
            Role.CART_WRITE to RolePattern(
                required = listOf(listOf("cart", "basket"), listOf("update", "add", "modify", "change", "set", "quantity", "qty")),
                preferred = listOf("update", "cart"),
                excluded = listOf("view", "get", "read", "clear"),
            ),
            Role.ORDER_PLACE to RolePattern(
                required = listOf(listOf("order", "checkout", "place", "purchase")),
                preferred = listOf("create", "place", "checkout"),
                // D-089: excludes the non-COD payment tools (online/wallet/upi)
                // and every read (history/track/status/detail/past) - EC-Z9
                // never binds anything but the plain COD creator.
                excluded = listOf(
                    "history", "track", "status", "list", "get", "cancel", "past", "detail",
                    "online", "wallet", "upi", "payment", "drop_zone", "dropzone",
                ),
            ),
            // D-089: new. Store selection has no equivalent in D-079's design
            // because the need for it was unknown until a real search failed
            // with "Store not selected" - see ensureStoreSelected's doc.
            //
            // D-090: "add" is excluded as "add_" (trailing underscore), never
            // bare "add" - bare "add" is a substring of "address"/"addresses"
            // themselves ("**add**ress"), so it excluded list_saved_addresses
            // and select_saved_address from their OWN roles, leaving both
            // unbound and silently breaking every search/cart/order call
            // behind them. "add_" only matches an actual add_-prefixed tool
            // name (add_saved_address) - see bestMatch's own doc for why this
            // is a targeted fix rather than a switch to word-exact matching
            // (that breaks the equally-deliberate "detail" matching "details"
            // elsewhere in this table).
            Role.ADDRESS_LIST to RolePattern(
                required = listOf(listOf("address")),
                preferred = listOf("list", "saved"),
                excluded = listOf("select", "add_", "update", "drop_zone", "dropzone"),
            ),
            Role.ADDRESS_SELECT to RolePattern(
                required = listOf(listOf("address"), listOf("select", "set", "choose")),
                preferred = listOf("select", "saved"),
                excluded = listOf("list", "add_", "drop_zone", "dropzone"),
            ),
        )
    }
}
