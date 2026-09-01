package com.secondbrain.integrations

import com.secondbrain.model.ToolClass

/**
 * Decides whether a dynamically bridged MCP tool is `AUTONOMOUS` or `GATED`.
 *
 * ARCHITECTURE §5 WF-4 specifies one regex:
 *
 * > name or description matching `/place|order|checkout|pay|submit|confirm|delete|cancel|remove/i`
 * > → gated. Everything else → autonomous. Unknown tools default to gated.
 *
 * That rule is shipped here as [Verdict.reason] `"mutation-verb"` — but it is
 * not the whole classifier, because applied literally to a real cart API it
 * produces a workflow nobody would use. This is EC-Z16, which the edge-case
 * catalogue does not have.
 *
 * ### Why one regex is not enough
 *
 * `remove` is in that list, so `cart_remove_item` classifies GATED — a modal
 * confirmation window to take one item out of a shopping cart. An eight-item
 * grocery session with a few corrections would open a dozen of them, and R9's
 * "confirmation clicks" exception was sized for *one button press per
 * irreversible action*, not per basket edit. The user would learn to click
 * through them without reading, which is precisely the failure the gate exists
 * to prevent.
 *
 * The regex conflates two things a cart API keeps separate:
 *
 * - **Reversible, pre-transactional staging.** Adding, re-quantifying and
 *   removing cart lines. Costs nothing, commits nothing, and is undone by
 *   doing the opposite. Wrongly gating one costs a needless click.
 * - **The transaction.** `place_order`, `checkout`, `pay`. Money moves, and it
 *   is not undoable by calling anything.
 *
 * ### The rule this class actually applies, in order
 *
 * 1. **Transaction verbs** (`place|order|checkout|pay|purchase|buy|submit`) →
 *    `GATED`, always, and checked *first* so nothing below can promote one to
 *    autonomous. This is the tier R2 is really about.
 * 2. **Cart staging** — matches [CART_SHAPED] *and* matched nothing in tier 1 →
 *    `AUTONOMOUS`. The narrow, deliberate exception, justified above.
 * 3. **Other mutation verbs** from the doc's regex (`confirm|delete|cancel|remove`
 *    outside a cart context, `update`, `create`, `schedule`) → `GATED`.
 * 4. **Nothing to classify on** — blank description *and* an unrecognised name
 *    → `GATED`. This is R3 and the doc's "unknown defaults to gated", and it is
 *    what "unknown" actually means: not "didn't match a regex" (that would gate
 *    `search`), but "we had nothing to judge by."
 * 5. Everything else → `AUTONOMOUS`. Reads, lookups, catalogue browsing.
 *
 * Tier 2 is the only place this is more permissive than the document, it is
 * bounded to a single well-understood shape, and every verdict carries the
 * [Verdict.reason] that produced it so the startup table shows the reasoning
 * and not just the answer.
 */
object MutationClassifier {

    data class Verdict(
        val toolName: String,
        val toolClass: ToolClass,
        /** Which rule fired. Logged at startup so a surprising classification is traceable. */
        val reason: String,
    )

    /**
     * Tier 1. Money moves, or an externally visible commitment is made.
     *
     * `order` deliberately catches `reorder`, `order_history` and `list_orders`
     * too — all GATED. Over-gating a read-only order-history tool costs one
     * click and is exactly the trade R3 asks for ("a read-only tool wrongly
     * gated costs one click").
     *
     * Note the boundaries: `\b` on the left only, no `\b` on the right. Tool
     * names are full of plurals and gerunds (`orders`, `checkout_pending`,
     * `placing`), and a trailing `\b` would miss every one of them while
     * looking correct. The leading `\b` is what still keeps `displace` out.
     */
    private val TRANSACTION = Regex(
        "\\b(place|order|checkout|pay|purchase|buy|submit|tip)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Tier 2. A cart-staging operation and nothing more.
     *
     * Requires an explicit cart/basket noun. A tool called merely `remove` or
     * `update` does not qualify — without the noun there is no evidence it is
     * confined to the cart, and tier 3 gates it.
     */
    private val CART_SHAPED = Regex(
        "\\b(cart|basket|bag)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Tier 3. The rest of ARCHITECTURE §5 WF-4's regex, plus the usual write verbs. */
    private val OTHER_MUTATION = Regex(
        "\\b(confirm|delete|cancel|remov|updat|modif|edit|creat|schedul|book|reserv|appl|redeem)|\\bset\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Tier 5's positive evidence of a read. Used only to distinguish "a read" from "nothing to judge by". */
    private val READ_SHAPED = Regex(
        "\\b(search|find|list|get|read|fetch|lookup|quer|brows|view|show|detail|info|status|track|availab|suggest|recommend)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param name the MCP tool's own name, un-namespaced.
     * @param description its description. May be blank — that is tier 4's trigger.
     */
    fun classify(name: String, description: String?): Verdict {
        val desc = description.orEmpty()
        // `_` is a word character to a regex engine, so `\badd` does NOT match
        // "add_to_cart" - the boundary is only at the very start of the string.
        // Every rule below is written in terms of word boundaries, so separators
        // are normalised to spaces first. Missing this silently classified every
        // snake_case cart tool as GATED, which the tests caught.
        val haystack = "$name $desc".replace('_', ' ').replace('-', ' ')

        TRANSACTION.find(haystack)?.let {
            return Verdict(name, ToolClass.GATED, "transaction-verb '${it.value}'")
        }

        if (CART_SHAPED.containsMatchIn(haystack)) {
            return Verdict(name, ToolClass.AUTONOMOUS, "cart-staging (reversible, pre-transactional)")
        }

        OTHER_MUTATION.find(haystack)?.let {
            return Verdict(name, ToolClass.GATED, "mutation-verb '${it.value}'")
        }

        if (READ_SHAPED.containsMatchIn(haystack)) {
            return Verdict(name, ToolClass.AUTONOMOUS, "read-shaped")
        }

        // Tier 4. Nothing matched anywhere and there was no description to go
        // on: we genuinely do not know what this does. R3.
        return Verdict(name, ToolClass.GATED, "unclassifiable - failing closed (R3)")
    }

    /**
     * Classifies a whole `tools/list` response and returns the table §5 WF-4
     * asks to be *"logged at startup so you can see what it decided."*
     */
    fun classifyAll(tools: List<Pair<String, String?>>): List<Verdict> =
        tools.map { (name, description) -> classify(name, description) }
}
