package com.secondbrain.agent

import com.secondbrain.model.Cart
import com.secondbrain.model.CartMutation
import com.secondbrain.model.CommerceAvailability
import com.secondbrain.model.CommerceConfig
import com.secondbrain.model.FailedItem
import com.secondbrain.model.LedgerKind
import com.secondbrain.model.Money
import com.secondbrain.model.NoteDraft
import com.secondbrain.model.NoteSource
import com.secondbrain.model.OrderOutcome
import com.secondbrain.model.OrderProposal
import com.secondbrain.model.SearchOutcome
import com.secondbrain.ports.CommercePort
import com.secondbrain.ports.VaultStore
import com.secondbrain.ports.WriteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * WF-4, as tools.
 *
 * Six autonomous, one gated. The split is the argument made in
 * `MutationClassifier` and it is the whole reason this workflow is usable by
 * voice: **a cart is a staging area, not a transaction.** Adding, re-quantifying
 * and removing lines costs nothing and is undone by doing the opposite, so
 * gating each one would put a modal window between the user and every
 * correction — eight items with two changes of mind is a dozen clicks, and R9
 * sized its "confirmation clicks" exception at *one press per irreversible
 * action*. Exactly one thing here is irreversible, and exactly one thing here
 * is gated: [proposeOrder].
 *
 * ### Three rules this file exists to enforce, none of which are in the prompt
 *
 * **The cart is never remembered, only read.** Every mutation returns the whole
 * server cart and [proposeOrder] re-reads before proposing regardless of what
 * just happened. ARCHITECTURE asks for the re-read "before proposing the order"
 * (EC-Z6/EC-Z7); this goes further because R8's 8-turn window will drop the
 * early items of a long grocery session out of context, and a model reasoning
 * from its own memory would eventually misreport a cart a human is about to
 * approve (EC-Z19).
 *
 * **Failures are announced before the total, never after** (EC-Z10). The
 * speech summary is built failures-first, so "I couldn't get the paneer" is
 * heard before "that's 640 rupees" rather than trailing it.
 *
 * **A total above the ceiling needs a second acknowledgement** (EC-Z17). R7
 * puts the number in [CommerceConfig], not in a prompt asking the model to be
 * careful. This is the control that catches "twenty kilos of rice" misheard
 * from "two kilos": the resulting cart is perfectly valid and nothing else in
 * the pipeline has any reason to question it.
 */
class CommerceTools(
    private val commerce: CommercePort,
    private val gate: ConfirmationGate,
    private val config: CommerceConfig,
    /** EC-Z1: the parsed list is written here before any commerce call, so an outage never loses it. */
    private val vault: VaultStore,
) {
    private val log = LoggerFactory.getLogger(CommerceTools::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun register(builder: ToolRegistry.Builder): ToolRegistry.Builder = builder
        .autonomous("commerce_save_list", SAVE_LIST_DESC, SAVE_LIST_SCHEMA) { saveList(it) }
        .autonomous("commerce_search", SEARCH_DESC, SEARCH_SCHEMA) { search(it) }
        .autonomous("commerce_cart_view", CART_VIEW_DESC, EMPTY_SCHEMA) { cartView() }
        .autonomous("commerce_cart_add", CART_ADD_DESC, CART_ADD_SCHEMA) { cartAdd(it) }
        .autonomous("commerce_cart_update", CART_UPDATE_DESC, CART_UPDATE_SCHEMA) { cartUpdate(it) }
        .autonomous("commerce_cart_remove", CART_REMOVE_DESC, CART_REMOVE_SCHEMA) { cartRemove(it) }
        .gated("commerce_propose_order", PROPOSE_ORDER_DESC, PROPOSE_ORDER_SCHEMA) { proposeOrder(it) }

    // ── EC-Z1: save the list first ──────────────────────────────────────────

    /**
     * Writes the parsed list to the vault as a note before anything touches the
     * network.
     *
     * EC-Z1's guarantee — *"the parsed grocery list is written to the vault as
     * a note first, so nothing is lost"* — matters most for a list read off a
     * photo, where losing it means re-photographing and re-parsing. It also
     * gives EC-Z23 its answer: this note is an immutable record of *what was
     * asked for*, and the cart is the live object. "Remove the milk" always
     * means the cart. The note is never edited to match.
     */
    private suspend fun saveList(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val items = obj["items"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }.orEmpty()
        if (items.isEmpty()) {
            return error("empty_list", "No items to save.")
        }

        val title = obj["title"]?.jsonPrimitive?.content?.ifBlank { null } ?: "Grocery list"
        val draft = NoteDraft(
            folder = "Lists",
            title = title,
            tags = listOf("grocery", "list"),
            summary = "${items.size} items to buy.",
            bodyMarkdown = items.joinToString("\n") { "- $it" },
            source = NoteSource.VOICE,
        )

        // confirmNew = true: a weekly shop legitimately repeats last week's
        // list almost verbatim, and EC-N9's duplicate gate would reject it as a
        // near-duplicate. Here that gate is actively wrong — a second grocery
        // list is a second grocery list — and letting it block the write would
        // cost the EC-Z1 guarantee this whole tool exists to provide.
        val result = runCatching { vault.writeNote(draft, confirmNew = true) }.getOrElse {
            // Not fatal: failing to save the note must not stop someone
            // shopping. Say so rather than pretending it worked.
            log.warn("Could not save the grocery list note: {}", it.message)
            return ToolOutcome(
                buildJsonObject {
                    put("saved", false)
                    put("message", "Couldn't save the list to the vault, but you can carry on shopping.")
                }.toString()
            )
        }

        return when (result) {
            is WriteResult.Written -> {
                log.info("Grocery list saved to {} ({} items)", result.path, items.size)
                ToolOutcome(
                    buildJsonObject {
                        put("saved", true)
                        put("path", result.path)
                        put("item_count", items.size)
                    }.toString(),
                    notePath = result.path,
                )
            }
            is WriteResult.Rejected -> ToolOutcome(
                buildJsonObject {
                    put("saved", false)
                    put("reason", result.reason)
                    put("message", result.detail)
                    put("next_step", "Carry on with the shopping - a missing list note does not block anything.")
                }.toString()
            )
        }
    }

    // ── search ──────────────────────────────────────────────────────────────

    private suspend fun search(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val query = obj["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isBlank()) return error("invalid_input", "'query' is required.")

        unavailable()?.let { return it }

        // D-091: SearchOutcome replaces a bare List<Product> specifically so
        // this branch can exist. A ProviderError used to look identical to
        // zero matches - the model would tell the user "nothing matched" for
        // a store-selection or session failure, which not only was
        // misleading but told them to do the one thing (try a different
        // name) that could never fix it.
        val results = when (val outcome = commerce.search(query, config.maxSearchResults)) {
            is SearchOutcome.ProviderError -> return ToolOutcome(
                buildJsonObject {
                    put("error", "search_failed")
                    put("message", outcome.reason)
                    put(
                        "next_step",
                        "This is not 'nothing matched' - the search itself failed. Tell the user what went wrong " +
                            "and that trying a different product name will not help. Do not say nothing was found.",
                    )
                }.toString(),
                isError = true,
            )
            SearchOutcome.NoMatch -> emptyList()
            is SearchOutcome.Found -> outcome.products
        }

        // EC-Z2: zero results is a normal, speakable answer. Never substitute
        // something else and never quietly move on.
        if (results.isEmpty()) {
            return ToolOutcome(
                buildJsonObject {
                    put("results", buildJsonArray { })
                    put("count", 0)
                    put(
                        "next_step",
                        "Nothing matched '$query'. Tell the user and ask whether to skip it or try a different " +
                            "name. Do not substitute something else.",
                    )
                }.toString()
            )
        }

        return ToolOutcome(
            buildJsonObject {
                put("count", results.size)
                put("results", buildJsonArray {
                    results.forEach { p ->
                        add(buildJsonObject {
                            put("product_id", p.id)
                            put("name", p.name)
                            p.size?.let { put("size", it) }
                            put("price", p.price.format())
                            put("price_spoken", p.price.spoken())
                            put("available", p.available)
                            // WF-4: "never silently substitute." The exact
                            // sentence to say is handed over pre-built so the
                            // size and price cannot be dropped from it.
                            put("read_back", p.readBack())
                        })
                    }
                })
                put(
                    "next_step",
                    "Read the best match aloud using its read_back text - name, size and price - before adding " +
                        "anything. If several pack sizes match, say which one you picked.",
                )
            }.toString()
        )
    }

    // ── cart ────────────────────────────────────────────────────────────────

    private suspend fun cartView(): ToolOutcome {
        unavailable()?.let { return it }
        return ToolOutcome(describeCart(commerce.readCart()).toString())
    }

    private suspend fun cartAdd(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val productId = obj["product_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (productId.isBlank()) return error("invalid_input", "'product_id' is required - use one from commerce_search.")
        val quantity = obj["quantity"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        if (quantity < 1) return error("invalid_input", "'quantity' must be at least 1.")

        unavailable()?.let { return it }
        return mutationOutcome(commerce.addToCart(productId, quantity))
    }

    private suspend fun cartUpdate(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val lineId = obj["line_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (lineId.isBlank()) return error("invalid_input", "'line_id' is required - use one from commerce_cart_view.")
        val quantity = obj["quantity"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return error("invalid_input", "'quantity' is required. Use 0 to remove the item entirely.")
        if (quantity < 0) return error("invalid_input", "'quantity' cannot be negative.")

        unavailable()?.let { return it }
        return mutationOutcome(commerce.updateQuantity(lineId, quantity))
    }

    private suspend fun cartRemove(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val lineId = obj["line_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (lineId.isBlank()) return error("invalid_input", "'line_id' is required - use one from commerce_cart_view.")

        unavailable()?.let { return it }
        return mutationOutcome(commerce.removeFromCart(lineId))
    }

    /**
     * Every cart mutation answers with the whole cart, never a delta.
     *
     * [CartMutation.Unknown] is EC-Z18, which the edge-case catalogue covers
     * for orders (EC-Z8) but not for cart writes: a request that times out
     * mid-flight may or may not have applied, and retrying it blind is how a
     * cart ends up with two of something. The instruction back to the model is
     * therefore always "look, do not retry".
     */
    private suspend fun mutationOutcome(mutation: CartMutation): ToolOutcome = when (mutation) {
        is CartMutation.Applied -> ToolOutcome(describeCart(mutation.cart).toString())

        // A definite no. Announce it; it becomes a failed item at proposal time.
        is CartMutation.Rejected -> ToolOutcome(
            buildJsonObject {
                put("applied", false)
                put("reason", mutation.reason)
                put(
                    "next_step",
                    "Tell the user this exactly, then move on. Do not substitute a different product and do not " +
                        "retry the same one. Include it in failed_items when you call commerce_propose_order.",
                )
            }.toString()
        )

        is CartMutation.Unknown -> ToolOutcome(
            buildJsonObject {
                put("applied", "unknown")
                put("reason", mutation.reason)
                put(
                    "next_step",
                    "This may or may not have gone through. Call commerce_cart_view and look at what is actually " +
                        "there before doing anything else. Never repeat the call blind.",
                )
            }.toString()
        )

        is CartMutation.NeedsReauth -> ToolOutcome(
            buildJsonObject {
                put("error", "needs_login")
                put("message", mutation.reason)
                put("next_step", "Tell the user they need to sign in to ${commerce.displayName} again from the app.")
            }.toString(),
            isError = true,
        )
    }

    private fun describeCart(cart: Cart) = buildJsonObject {
        put("item_count", cart.itemCount)
        put("lines", buildJsonArray {
            cart.lines.forEach { line ->
                add(buildJsonObject {
                    // The handle every later mutation needs. Named in the
                    // schema of update/remove so the model always has it.
                    put("line_id", line.lineId)
                    put("name", line.name)
                    line.size?.let { put("size", it) }
                    put("quantity", line.quantity)
                    put("unit_price", line.unitPrice.format())
                    put("line_total", line.lineTotal.format())
                    // EC-Z7: a line the cart already had is flagged, so it can
                    // be mentioned rather than passed off as something we added.
                    put("added_this_session", line.addedThisSession)
                })
            }
        })
        put("subtotal", cart.subtotal.format())
        put("delivery_fee", cart.deliveryFee.format())
        put("total", cart.total.format())
        put("total_spoken", cart.total.spoken())
        put("cod_available", cart.codAvailable)
        cart.codUnavailableReason?.let { put("cod_unavailable_reason", it) }
        if (cart.preExistingLines.isNotEmpty()) {
            put(
                "note",
                "${cart.preExistingLines.size} line(s) were already in the cart before this session. " +
                    "Mention them to the user - they may not expect them.",
            )
        }
    }

    // ── the gated one ───────────────────────────────────────────────────────

    private suspend fun proposeOrder(input: String): ToolOutcome {
        val obj = json.parseToJsonElement(input).jsonObject
        val failedItems = obj["failed_items"]?.jsonArray?.mapNotNull { element ->
            val item = element.jsonObject
            val requested = item["requested"]?.jsonPrimitive?.content ?: return@mapNotNull null
            FailedItem(requested, item["reason"]?.jsonPrimitive?.content ?: "Couldn't be added.")
        }.orEmpty()

        unavailable()?.let { return it }

        // EC-Z6/EC-Z7/EC-Z19. Unconditional, no matter what the model believes
        // it just did. This is the cart the user is shown and the cart that
        // gets ordered.
        val cart = commerce.readCart()

        if (cart.isEmpty) {
            return error("empty_cart", "The cart is empty. There is nothing to order.")
        }

        // EC-Z9 + EC-Z21: checked here, on the *freshly read* cart, rather than
        // once at the start - COD eligibility usually depends on the total, so
        // revising a cart downward can withdraw it after it was already
        // established. Never fall back to another payment method (ARCHITECTURE
        // section 8 puts every other method out of scope for v1).
        if (!cart.codAvailable) {
            return ToolOutcome(
                buildJsonObject {
                    put("error", "cod_unavailable")
                    put("message", cart.codUnavailableReason ?: "Cash on delivery isn't available for this cart.")
                    put(
                        "next_step",
                        "Tell the user and stop. Do not offer or use any other payment method.",
                    )
                }.toString(),
                isError = true,
            )
        }

        val overCeiling = cart.total > config.orderCeiling
        if (overCeiling) {
            log.warn(
                "Order total {} exceeds the configured ceiling {}. Requiring explicit acknowledgement (EC-Z17).",
                cart.total.format(), config.orderCeiling.format(),
            )
        }

        val proposal = OrderProposal(
            cart = cart,
            failedItems = failedItems,
            overCeiling = overCeiling,
            ceiling = config.orderCeiling,
            isFake = commerce.isFake,
            speechSummary = speechSummary(cart, failedItems, overCeiling),
        )

        log.info(
            "commerce_propose_order: {} item(s), {}, {} failed, fake={}",
            cart.itemCount, cart.total.format(), failedItems.size, commerce.isFake,
        )

        val outcome = gate.submit(
            kind = LedgerKind.ORDER_PLACE,
            proposal = proposal,
            // EC-Z15: deliberately none. A cart line is not an editable string
            // — changing it is a server call that can be refused. Revision goes
            // through ConfirmationGate.requestRevision, which hands the user's
            // own words back to this loop.
            fields = emptyList(),
        ) { proposalId, approved ->
            val order = approved as OrderProposal
            when (val result = commerce.placeOrder(order.cart, idempotencyKey = proposalId)) {
                is OrderOutcome.Placed -> ConfirmationGate.ExecutorResult.Success(result.orderId)
                is OrderOutcome.Failed -> ConfirmationGate.ExecutorResult.Failed(result.reason)
                // EC-Z8: "I'm not sure that went through. Check the Zepto app
                // before ordering again." Never retried, here or anywhere.
                is OrderOutcome.Unknown -> ConfirmationGate.ExecutorResult.Unknown(result.reason)
                is OrderOutcome.NeedsReauth -> ConfirmationGate.ExecutorResult.NeedsReauth(result.reason)
            }
        }
        return outcome.toToolOutcome()
    }

    /**
     * EC-Z10, mechanically: failures first, total last.
     *
     * The catalogue says failures are *"announced before the total, not buried
     * after it"*, and the reliable way to guarantee that is to build the
     * sentence in that order here rather than to ask the model to remember.
     */
    private fun speechSummary(cart: Cart, failed: List<FailedItem>, overCeiling: Boolean): String = buildString {
        if (failed.isNotEmpty()) {
            append(
                if (failed.size == 1) "I couldn't get ${failed.first().requested}. "
                else "I couldn't get ${failed.size} things: ${failed.joinToString(", ") { it.requested }}. "
            )
        }
        val preExisting = cart.preExistingLines
        if (preExisting.isNotEmpty()) {
            append("${preExisting.size} item${if (preExisting.size == 1) "" else "s"} were already in the cart. ")
        }
        append("${cart.itemCount} item${if (cart.itemCount == 1) "" else "s"}, ")
        append(cart.total.spoken())
        append(", cash on delivery.")
        if (overCeiling) append(" That's over your usual limit, so check it carefully.")
    }

    // ── shared ──────────────────────────────────────────────────────────────

    /** EC-Z1/EC-Z22: a clear, speakable reason, never a stack trace. */
    private suspend fun unavailable(): ToolOutcome? = when (val status = commerce.availability()) {
        is CommerceAvailability.Ready -> null
        is CommerceAvailability.NeedsLogin -> ToolOutcome(
            buildJsonObject {
                put("error", "needs_login")
                put("message", status.reason)
                put("next_step", "Tell the user to sign in to ${commerce.displayName} from the app, then try again.")
            }.toString(),
            isError = true,
        )
        is CommerceAvailability.Unavailable -> ToolOutcome(
            buildJsonObject {
                put("error", "commerce_unavailable")
                put("message", status.reason)
                put(
                    "next_step",
                    "Tell the user ${commerce.displayName} isn't reachable right now. If they dictated a list, " +
                        "save it with commerce_save_list so nothing is lost.",
                )
            }.toString(),
            isError = true,
        )
    }

    private fun error(code: String, message: String) = ToolOutcome(
        buildJsonObject { put("error", code); put("message", message) }.toString(),
        isError = true,
    )

    private companion object {
        const val EMPTY_SCHEMA = """{"type":"object","properties":{},"required":[]}"""

        const val SAVE_LIST_DESC =
            "Save a grocery list to the vault as a note. Call this FIRST, before any searching, whenever the user " +
                "dictates or shows a list of more than one item - it means an outage can never lose the list. " +
                "The note is a record of what was asked for; it is not updated afterwards."
        const val SAVE_LIST_SCHEMA = """
            {"type":"object","properties":{
              "items":{"type":"array","items":{"type":"string"},"description":"The items as the user said them, including any quantity words: '2 kg onions', 'a packet of bread'."},
              "title":{"type":"string","description":"Optional title. Defaults to 'Grocery list'."}
            },"required":["items"]}
        """

        const val SEARCH_DESC =
            "Search the store's catalogue for one item. Returns candidates with name, size and price. Read the " +
                "chosen one aloud using its read_back text before adding it - never add something the user has " +
                "not heard described. Zero results means say so and ask; it never means pick something similar."
        const val SEARCH_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"One product, in plain words: 'brown bread', 'toor dal'. Search one item at a time."}
            },"required":["query"]}
        """

        const val CART_VIEW_DESC =
            "Read the cart from the server. This is the only thing that knows what is in the cart - never rely on " +
                "your own memory of what you added, especially in a long conversation. Call it whenever you are " +
                "about to tell the user what is in the cart, and after anything unexpected."

        const val CART_ADD_DESC =
            "Add a product to the cart. Reversible and free - nothing is charged until the order is placed. " +
                "Use a product_id from commerce_search, never a guessed one."
        const val CART_ADD_SCHEMA = """
            {"type":"object","properties":{
              "product_id":{"type":"string","description":"From commerce_search results."},
              "quantity":{"type":"integer","description":"Number of PACKS, not the user's units. Two kilos of a 1 kg pack is quantity 2. Defaults to 1."}
            },"required":["product_id"]}
        """

        const val CART_UPDATE_DESC =
            "Change the quantity of something already in the cart. Use this when the user says 'make it two' or " +
                "'only one of those'. A quantity of 0 removes the item. Reversible and free."
        const val CART_UPDATE_SCHEMA = """
            {"type":"object","properties":{
              "line_id":{"type":"string","description":"From commerce_cart_view. Not the product_id."},
              "quantity":{"type":"integer","description":"New number of packs. 0 removes the item entirely."}
            },"required":["line_id","quantity"]}
        """

        const val CART_REMOVE_DESC =
            "Take something out of the cart entirely. Use this when the user says 'drop the milk' or 'I don't " +
                "need that any more'. Reversible and free - it can be added back."
        const val CART_REMOVE_SCHEMA = """
            {"type":"object","properties":{
              "line_id":{"type":"string","description":"From commerce_cart_view. Not the product_id."}
            },"required":["line_id"]}
        """

        const val PROPOSE_ORDER_DESC =
            "Show the user the finished cart and ask them to approve the order. This does NOT place anything - it " +
                "opens a confirmation window that only the user can complete. Call it once the cart is right. " +
                "The cart is re-read from the server first, so the totals shown are the real ones. If the user " +
                "asks for changes instead of approving, you will get their words back and can edit the cart and " +
                "call this again."
        const val PROPOSE_ORDER_SCHEMA = """
            {"type":"object","properties":{
              "failed_items":{"type":"array","description":"Everything the user asked for that is NOT in the cart, with the reason. These are spoken before the total.","items":{"type":"object","properties":{
                "requested":{"type":"string","description":"What the user asked for, in their words."},
                "reason":{"type":"string","description":"Why it isn't there: 'out of stock', 'no match found'."}
              },"required":["requested"]}}
            },"required":[]}
        """
    }
}
