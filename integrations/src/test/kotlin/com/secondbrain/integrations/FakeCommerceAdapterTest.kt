package com.secondbrain.integrations

import com.secondbrain.model.CartMutation
import com.secondbrain.model.Money
import com.secondbrain.model.OrderOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The seeded edge cases, asserted.
 *
 * These exist so §7 Step 7's exit criteria can be met offline: *"Against
 * `FakeCommerceAdapter`: a 5-item list produces a cart matching the list, with
 * a seeded stock-out correctly announced before the total."*
 */
class FakeCommerceAdapterTest {

    private fun adapter(seed: Boolean = false) = FakeCommerceAdapter(seedPreExistingLine = seed)

    @Test
    fun `search returns products with a name, size and price for the read-back`() = runTest {
        val results = adapter().search("bread", 8)
        assertTrue(results.isNotEmpty())
        val bread = results.first()
        // WF-4's "never silently substitute" rule needs all three present.
        assertTrue(bread.name.isNotBlank())
        assertNotNull(bread.size)
        assertTrue(bread.price.paise > 0)
        assertTrue(bread.readBack().contains(bread.name))
        assertTrue(bread.readBack().contains("rupees"))
    }

    /**
     * EC-Z2. A real zero, distinguishable from a broken adapter.
     *
     * Both fixtures share no substring with any catalogue keyword. "truffle
     * oil" would be a bad fixture and was tried: the fake matches on
     * substrings, so it returns sunflower oil — which is arguably what a real
     * store would do too, and is a substitution question rather than a
     * zero-results one.
     */
    @Test
    fun `a term with no match returns nothing rather than something close`() = runTest {
        assertTrue(adapter().search("quinoa", 8).isEmpty())
        assertTrue(adapter().search("kimchi", 8).isEmpty())
    }

    /** EC-Z3/EC-Z20: several pack sizes of one thing, so ranking has something to do. */
    @Test
    fun `atta comes back in three pack sizes`() = runTest {
        val sizes = adapter().search("atta", 8).mapNotNull { it.size }.toSet()
        assertEquals(setOf("1 kg", "5 kg", "10 kg"), sizes)
    }

    /** EC-Z5: the stock-out is rejected at add time, which is when a real one surfaces. */
    @Test
    fun `an out-of-stock item is found by search but refused on add`() = runTest {
        val a = adapter()
        assertTrue(a.search("paneer", 8).isNotEmpty(), "it should be findable")

        val result = a.addToCart("paneer-200", 1)
        val rejected = assertInstanceOf(CartMutation.Rejected::class.java, result)
        assertTrue(rejected.reason.contains("out of stock", ignoreCase = true))
        assertTrue(a.readCart().isEmpty, "a refused add must not leave a line behind")
    }

    @Test
    fun `adding the same product twice increases the quantity rather than duplicating the line`() = runTest {
        val a = adapter()
        a.addToCart("milk-1l", 1)
        a.addToCart("milk-1l", 2)

        val cart = a.readCart()
        assertEquals(1, cart.lines.size)
        assertEquals(3, cart.lines.single().quantity)
        assertEquals(3, cart.itemCount)
    }

    // ── the cart-edit flow this whole step was asked for ────────────────────

    @Test
    fun `quantity can be reduced after the fact`() = runTest {
        val a = adapter()
        a.addToCart("bread-400", 4)
        val lineId = a.readCart().lines.single().lineId

        a.updateQuantity(lineId, 2)

        assertEquals(2, a.readCart().lines.single().quantity)
    }

    @Test
    fun `an item can be removed by name after being added`() = runTest {
        val a = adapter()
        a.addToCart("milk-1l", 1)
        a.addToCart("bread-400", 1)
        val milkLine = a.readCart().lines.single { it.name.contains("Milk") }

        a.removeFromCart(milkLine.lineId)

        val remaining = a.readCart()
        assertEquals(1, remaining.lines.size)
        assertFalse(remaining.lines.any { it.name.contains("Milk") })
    }

    /**
     * The port defines quantity 0 as removal so that "make it none" and "drop
     * it" cannot diverge into two behaviours.
     */
    @Test
    fun `updating quantity to zero removes the line`() = runTest {
        val a = adapter()
        a.addToCart("bread-400", 2)
        val lineId = a.readCart().lines.single().lineId

        val result = a.updateQuantity(lineId, 0)

        assertInstanceOf(CartMutation.Applied::class.java, result)
        assertTrue(a.readCart().isEmpty)
    }

    @Test
    fun `mutating a line that is not there is refused, not silently ignored`() = runTest {
        val a = adapter()
        assertInstanceOf(CartMutation.Rejected::class.java, a.updateQuantity("no-such-line", 2))
        assertInstanceOf(CartMutation.Rejected::class.java, a.removeFromCart("no-such-line"))
    }

    // ── EC-Z7 ───────────────────────────────────────────────────────────────

    @Test
    fun `a pre-existing line is flagged as not from this session`() = runTest {
        val a = adapter(seed = true)
        a.addToCart("bread-400", 1)

        val cart = a.readCart()
        assertEquals(1, cart.preExistingLines.size)
        assertEquals("Amul Butter", cart.preExistingLines.single().name)
        assertTrue(cart.lines.single { it.name.contains("Bread") }.addedThisSession)
    }

    // ── EC-Z6 ───────────────────────────────────────────────────────────────

    @Test
    fun `the tomato price moves, so a stale local snapshot would be visibly wrong`() = runTest {
        val a = adapter()
        val first = a.search("tomato", 8).single().price
        a.search("tomato", 8)
        val third = a.search("tomato", 8).single().price

        assertTrue(third > first, "seeded price change should have fired by the third read")
    }

    // ── EC-Z9 / EC-Z21 ──────────────────────────────────────────────────────

    @Test
    fun `COD is withdrawn when the cart falls under the minimum`() = runTest {
        val a = adapter()
        a.addToCart("milk-500", 1) // Rs 28, under the Rs 99 COD minimum

        val cart = a.readCart()
        assertFalse(cart.codAvailable)
        assertNotNull(cart.codUnavailableReason)
    }

    /**
     * EC-Z21, which ARCHITECTURE.md misses: COD was available, then the user
     * revised the cart downward and it stopped being available. A check done
     * once at the start would not have caught this.
     */
    @Test
    fun `COD can lapse after a revision that reduces the cart`() = runTest {
        val a = adapter()
        a.addToCart("rice-5kg", 1)
        assertTrue(a.readCart().codAvailable, "a Rs 620 cart should qualify")

        val lineId = a.readCart().lines.single().lineId
        a.removeFromCart(lineId)
        a.addToCart("milk-500", 1)

        assertFalse(a.readCart().codAvailable, "after revising down it must be re-checked, not assumed")
    }

    @Test
    fun `an order is refused when COD is unavailable, never silently switched to another method`() = runTest {
        val a = adapter()
        a.addToCart("milk-500", 1)

        val outcome = a.placeOrder(a.readCart(), "proposal-1")

        val failed = assertInstanceOf(OrderOutcome.Failed::class.java, outcome)
        assertTrue(failed.reason.contains("Cash on delivery", ignoreCase = true))
    }

    // ── totals and ordering ─────────────────────────────────────────────────

    @Test
    fun `totals are the sum of the lines plus delivery`() = runTest {
        val a = adapter()
        a.addToCart("milk-500", 2) // 28 * 2 = 56
        a.addToCart("bread-400", 1) // 45

        val cart = a.readCart()
        assertEquals(Money.ofRupees(101), cart.subtotal)
        // Under the free-delivery threshold, so a fee applies.
        assertEquals(Money.ofRupees(25), cart.deliveryFee)
        assertEquals(Money.ofRupees(126), cart.total)
    }

    @Test
    fun `delivery is free above the threshold`() = runTest {
        val a = adapter()
        a.addToCart("rice-5kg", 1)
        assertEquals(Money.ZERO, a.readCart().deliveryFee)
    }

    @Test
    fun `placing an order returns an id derived from the idempotency key and empties the cart`() = runTest {
        val a = adapter()
        a.addToCart("rice-5kg", 1)

        val outcome = a.placeOrder(a.readCart(), "proposal-abcd1234")

        val placed = assertInstanceOf(OrderOutcome.Placed::class.java, outcome)
        assertTrue(placed.orderId.contains("ABCD1234"))
        assertTrue(a.readCart().isEmpty)
    }

    @Test
    fun `ordering an empty cart fails rather than placing nothing`() = runTest {
        val a = adapter()
        assertInstanceOf(OrderOutcome.Failed::class.java, a.placeOrder(a.readCart(), "proposal-1"))
    }

    /** §7 Step 7's own exit criterion, run end to end against the fake. */
    @Test
    fun `a five-item list with one stock-out produces a four-line cart and one announced failure`() = runTest {
        val a = adapter()
        val requested = listOf("milk-1l", "bread-400", "eggs-6", "paneer-200", "dal-1kg")

        val failures = requested.mapNotNull { id ->
            when (val result = a.addToCart(id, 1)) {
                is CartMutation.Rejected -> id to result.reason
                else -> null
            }
        }

        val cart = a.readCart()
        assertEquals(4, cart.lines.size)
        assertEquals(1, failures.size)
        assertEquals("paneer-200", failures.single().first)
        assertFalse(cart.lines.any { it.name.contains("Paneer") })
    }

    @Test
    fun `is marked as fake so the UI can say so`() {
        val a = adapter()
        assertTrue(a.isFake)
        assertTrue(a.displayName.contains("Demo", ignoreCase = true))
    }
}
