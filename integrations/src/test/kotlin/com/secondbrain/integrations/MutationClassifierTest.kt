package com.secondbrain.integrations

import com.secondbrain.model.ToolClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * CLAUDE.md's `:integrations` bar: *"Mutation Classifier over a fixture set of
 * tool names."*
 *
 * The fixture set is deliberately built from names a real quick-commerce MCP
 * plausibly exposes, including the ones that make ARCHITECTURE §5 WF-4's single
 * regex give the wrong answer — that being the point of EC-Z16.
 */
class MutationClassifierTest {

    private fun classOf(name: String, description: String? = null) =
        MutationClassifier.classify(name, description).toolClass

    // ── tier 1: money moves ─────────────────────────────────────────────────

    @Test
    fun `anything that places an order is gated`() {
        assertAll(
            { assertEquals(ToolClass.GATED, classOf("place_order")) },
            { assertEquals(ToolClass.GATED, classOf("placeOrder")) },
            { assertEquals(ToolClass.GATED, classOf("checkout")) },
            { assertEquals(ToolClass.GATED, classOf("create_order")) },
            { assertEquals(ToolClass.GATED, classOf("submit_cart")) },
            { assertEquals(ToolClass.GATED, classOf("pay")) },
            { assertEquals(ToolClass.GATED, classOf("purchase_items")) },
        )
    }

    /**
     * The tier ordering is the safety property, not an implementation detail:
     * `submit_cart` and `checkout_cart` both contain a cart noun, and if tier 2
     * ran first they would classify AUTONOMOUS and the model could spend money
     * without a gate. Tier 1 runs first precisely so it cannot.
     */
    @Test
    fun `a transaction verb beats a cart noun`() {
        assertAll(
            { assertEquals(ToolClass.GATED, classOf("submit_cart")) },
            { assertEquals(ToolClass.GATED, classOf("checkout_cart")) },
            { assertEquals(ToolClass.GATED, classOf("cart_place_order")) },
            { assertEquals(ToolClass.GATED, classOf("pay_for_basket")) },
        )
    }

    @Test
    fun `order history is gated too, and that is the intended trade`() {
        // R3: "a read-only tool wrongly gated costs one click." Rather than
        // hand-tune an exception for every read that happens to say "order",
        // the bare word gates - which is the cheap side of the trade.
        assertEquals(ToolClass.GATED, classOf("get_order_history"))
        assertEquals(ToolClass.GATED, classOf("track_order"))
    }

    // ── tier 2: reversible cart staging (EC-Z16) ────────────────────────────

    @Test
    fun `cart staging is autonomous so a voice session is not a clickfest`() {
        assertAll(
            { assertEquals(ToolClass.AUTONOMOUS, classOf("add_to_cart")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("cart_add_item")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("update_cart_quantity")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("remove_from_cart")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("get_cart")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("view_basket")) },
        )
    }

    /**
     * This is the case that motivates the whole class. Applying ARCHITECTURE
     * §5 WF-4's regex literally, `remove_from_cart` matches `remove` and is
     * GATED — a modal confirmation window per item removed.
     */
    @Test
    fun `remove_from_cart is autonomous even though 'remove' is in the doc's gate regex`() {
        val verdict = MutationClassifier.classify("remove_from_cart", "Remove an item from the shopping cart")
        assertEquals(ToolClass.AUTONOMOUS, verdict.toolClass)
        assertTrue(verdict.reason.contains("cart-staging"), "reason should say why: ${verdict.reason}")
    }

    // ── tier 3: other mutations ─────────────────────────────────────────────

    @Test
    fun `a mutation verb with no cart context is gated`() {
        assertAll(
            // No cart noun, so there is no evidence it is confined to the cart.
            { assertEquals(ToolClass.GATED, classOf("delete_address")) },
            { assertEquals(ToolClass.GATED, classOf("update_profile")) },
            { assertEquals(ToolClass.GATED, classOf("cancel_subscription")) },
            { assertEquals(ToolClass.GATED, classOf("apply_coupon")) },
        )
    }

    // ── tier 4/5: reads, and genuinely unknown tools ────────────────────────

    @Test
    fun `reads are autonomous`() {
        assertAll(
            { assertEquals(ToolClass.AUTONOMOUS, classOf("search_products")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("product_details")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("list_categories")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("check_availability")) },
            { assertEquals(ToolClass.AUTONOMOUS, classOf("suggest_alternatives")) },
        )
    }

    /**
     * EC-Z12 and R3. "Unknown" means *nothing to judge by* — not "did not match
     * a regex", which would gate every search tool ever written.
     */
    @Test
    fun `an unclassifiable tool fails closed`() {
        val verdict = MutationClassifier.classify("zx_9", null)
        assertEquals(ToolClass.GATED, verdict.toolClass)
        assertTrue(verdict.reason.contains("failing closed"), verdict.reason)
    }

    @Test
    fun `a description rescues an opaque name`() {
        // The name says nothing; the description says it searches.
        assertEquals(
            ToolClass.AUTONOMOUS,
            classOf("zx_9", "Search the catalogue for products matching a query"),
        )
        // And a description that says it orders gates it, name notwithstanding.
        assertEquals(
            ToolClass.GATED,
            classOf("zx_9", "Submit the basket and place the order for delivery"),
        )
    }

    // ── the startup table ───────────────────────────────────────────────────

    @Test
    fun `classifyAll returns one verdict per tool, each with a reason`() {
        val table = MutationClassifier.classifyAll(
            listOf(
                "search_products" to "Search Zepto's catalogue",
                "add_to_cart" to "Add a product to the cart",
                "place_order" to "Place the order",
            )
        )
        assertEquals(3, table.size)
        assertTrue(table.all { it.reason.isNotBlank() })
        assertEquals(ToolClass.GATED, table.single { it.toolName == "place_order" }.toolClass)
        assertEquals(ToolClass.AUTONOMOUS, table.single { it.toolName == "add_to_cart" }.toolClass)
    }

    /**
     * The property that actually matters, stated as a property rather than as
     * a list of examples: nothing that could spend money is ever autonomous.
     */
    @Test
    fun `no transaction-shaped name anywhere in a realistic tool list is autonomous`() {
        val realistic = listOf(
            "search_products", "product_details", "autocomplete", "get_cart", "add_to_cart",
            "update_cart_item", "remove_cart_item", "clear_cart", "place_order", "checkout",
            "order_history", "track_order", "list_addresses", "add_address", "apply_coupon",
        )
        val spendy = MutationClassifier.classifyAll(realistic.map { it to null })
            .filter { Regex("place|order|checkout|pay|purchase|buy|submit").containsMatchIn(it.toolName) }

        assertTrue(spendy.isNotEmpty(), "fixture should contain spend-capable names")
        assertTrue(
            spendy.all { it.toolClass == ToolClass.GATED },
            "these must all be GATED: " + spendy.filter { it.toolClass != ToolClass.GATED },
        )
    }
}
