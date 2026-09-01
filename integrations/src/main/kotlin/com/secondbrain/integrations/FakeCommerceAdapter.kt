package com.secondbrain.integrations

import com.secondbrain.model.Cart
import com.secondbrain.model.CartLine
import com.secondbrain.model.CartMutation
import com.secondbrain.model.CommerceAvailability
import com.secondbrain.model.Money
import com.secondbrain.model.OrderOutcome
import com.secondbrain.model.Product
import com.secondbrain.ports.CommercePort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deterministic commerce backend, so WF-4 is demoable and testable without
 * an internet connection, an Indian phone number, or real money.
 *
 * §7 Step 7 asks for exactly this: *"`FakeCommerceAdapter` (deterministic
 * catalogue, seeded stock-outs and price changes, so every edge case is
 * testable offline)"*, and makes it a hard exit criterion — *"a 5-item list
 * produces a cart matching the list, with a seeded stock-out correctly
 * announced before the total."*
 *
 * ### The seeds, and which edge case each one exists to prove
 *
 * | Seed | Proves |
 * |---|---|
 * | `paneer` is out of stock | EC-Z5 — add fails, item lands in the failed list |
 * | `saffron` returns nothing | EC-Z2 — zero results, offer to skip, never substitute |
 * | `atta` has three pack sizes | EC-Z3/EC-Z20 — ranking, and pack-size arithmetic |
 * | `tomato` price rises on the third read | EC-Z6 — server truth beats our snapshot |
 * | a pre-seeded line of `Amul Butter` | EC-Z7 — a cart carried over from "yesterday" |
 * | totals above the ceiling are reachable with ~4 items | EC-Z17 — the ceiling actually fires |
 *
 * These are fixed, not random. A fake that behaves differently per run cannot
 * be asserted against, and CLAUDE.md's `:integrations` bar is
 * *"`FakeCommerceAdapter` with seeded stock-outs and price changes."*
 *
 * Nothing here talks to a network. [isFake] is true, and §7 Step 7 requires the
 * UI to say so whenever it is: *"never demo a fake without saying so."*
 */
class FakeCommerceAdapter(
    /** EC-Z7: seeded true by default, so the "someone else's cart" case is the default demo, not an afterthought. */
    seedPreExistingLine: Boolean = true,
) : CommercePort {

    private val log = LoggerFactory.getLogger(FakeCommerceAdapter::class.java)

    override val isFake: Boolean = true
    override val displayName: String = "Demo catalogue (no real orders)"

    private val mutex = Mutex()
    private val lines = mutableListOf<CartLine>()
    private val nextLineId = AtomicInteger(1)
    private var tomatoReads = 0

    init {
        if (seedPreExistingLine) {
            lines += CartLine(
                lineId = "seeded-1",
                productId = "amul-butter-100",
                name = "Amul Butter",
                size = "100 g",
                unitPrice = Money.ofRupees(62),
                quantity = 1,
                // EC-Z7: this is the whole point of the seed.
                addedThisSession = false,
            )
        }
    }

    override suspend fun availability(): CommerceAvailability = CommerceAvailability.Ready

    // ── catalogue ───────────────────────────────────────────────────────────

    override suspend fun search(query: String, limit: Int): List<Product> {
        val q = query.lowercase().trim()

        // EC-Z2: a real zero-result answer, not an empty catalogue by accident.
        if (CATALOGUE.none { it.matches(q) }) {
            log.info("Fake search '{}': no results (seeded)", query)
            return emptyList()
        }

        return CATALOGUE
            .filter { it.matches(q) }
            .map { it.product(priceFor(it)) }
            .take(limit)
    }

    /** EC-Z6: tomatoes get more expensive the longer you take, so a stale snapshot is visibly stale. */
    private fun priceFor(entry: CatalogueEntry): Money =
        if (entry.id == "tomato-1kg") {
            tomatoReads++
            if (tomatoReads >= 3) Money.ofRupees(48) else Money.ofRupees(32)
        } else entry.price

    // ── cart ────────────────────────────────────────────────────────────────

    override suspend fun addToCart(productId: String, quantity: Int): CartMutation = mutex.withLock {
        val entry = CATALOGUE.firstOrNull { it.id == productId }
            ?: return CartMutation.Rejected("No such product: $productId")

        // EC-Z5: the seeded stock-out.
        if (!entry.inStock) {
            log.info("Fake add '{}': out of stock (seeded)", entry.name)
            return CartMutation.Rejected("${entry.name} is out of stock.")
        }
        if (quantity < 1) return CartMutation.Rejected("Quantity must be at least 1.")
        if (quantity > MAX_QTY_PER_LINE) {
            return CartMutation.Rejected("At most $MAX_QTY_PER_LINE of ${entry.name} per order.")
        }

        val existing = lines.indexOfFirst { it.productId == productId }
        if (existing >= 0) {
            val line = lines[existing]
            lines[existing] = line.copy(quantity = line.quantity + quantity, addedThisSession = true)
        } else {
            lines += CartLine(
                lineId = "line-${nextLineId.getAndIncrement()}",
                productId = entry.id,
                name = entry.name,
                size = entry.size,
                unitPrice = priceFor(entry),
                quantity = quantity,
            )
        }
        CartMutation.Applied(snapshot())
    }

    override suspend fun updateQuantity(lineId: String, quantity: Int): CartMutation = mutex.withLock {
        if (quantity == 0) return removeLocked(lineId)
        val index = lines.indexOfFirst { it.lineId == lineId }
        if (index < 0) return CartMutation.Rejected("That item is not in the cart.")
        if (quantity > MAX_QTY_PER_LINE) {
            return CartMutation.Rejected("At most $MAX_QTY_PER_LINE of ${lines[index].name} per order.")
        }
        lines[index] = lines[index].copy(quantity = quantity)
        CartMutation.Applied(snapshot())
    }

    override suspend fun removeFromCart(lineId: String): CartMutation = mutex.withLock { removeLocked(lineId) }

    private fun removeLocked(lineId: String): CartMutation {
        val removed = lines.removeIf { it.lineId == lineId }
        return if (removed) CartMutation.Applied(snapshot())
        else CartMutation.Rejected("That item is not in the cart.")
    }

    override suspend fun readCart(): Cart = mutex.withLock { snapshot() }

    private fun snapshot(): Cart {
        val subtotal = lines.fold(Money.ZERO) { acc, l -> acc + l.lineTotal }
        // EC-Z9/EC-Z21: COD has a minimum order value, so revising a cart down
        // can withdraw it. Modelled here precisely so that path is testable.
        val codOk = subtotal >= COD_MINIMUM || lines.isEmpty()
        return Cart(
            lines = lines.toList(),
            deliveryFee = if (subtotal >= FREE_DELIVERY_ABOVE || lines.isEmpty()) Money.ZERO else Money.ofRupees(25),
            codAvailable = codOk,
            codUnavailableReason = if (codOk) null
            else "Cash on delivery needs a subtotal of at least ${COD_MINIMUM.format()}.",
        )
    }

    // ── the irreversible one ────────────────────────────────────────────────

    override suspend fun placeOrder(cart: Cart, idempotencyKey: String): OrderOutcome = mutex.withLock {
        if (lines.isEmpty()) return OrderOutcome.Failed("The cart is empty.")
        if (!snapshot().codAvailable) {
            // EC-Z9: never fall back to another payment method. Stop.
            return OrderOutcome.Failed(snapshot().codUnavailableReason ?: "Cash on delivery is not available.")
        }
        val orderId = "FAKE-" + idempotencyKey.takeLast(8).uppercase()
        log.info("Fake order placed: {} ({} items, {})", orderId, cart.itemCount, cart.total.format())
        lines.clear()
        OrderOutcome.Placed(orderId)
    }

    // ── the catalogue ───────────────────────────────────────────────────────

    private data class CatalogueEntry(
        val id: String,
        val name: String,
        val size: String?,
        val price: Money,
        val keywords: List<String>,
        val inStock: Boolean = true,
    ) {
        fun matches(query: String): Boolean =
            keywords.any { query.contains(it) || it.contains(query) } || name.lowercase().contains(query)

        fun product(price: Money) = Product(id = id, name = name, size = size, price = price, available = inStock)
    }

    private companion object {
        const val MAX_QTY_PER_LINE = 10
        val COD_MINIMUM = Money.ofRupees(99)
        val FREE_DELIVERY_ABOVE = Money.ofRupees(199)

        val CATALOGUE = listOf(
            // EC-Z3/EC-Z20: three pack sizes of the same thing, so ranking and
            // "two kilos" vs "one 2 kg pack" both have something to bite on.
            CatalogueEntry("atta-1kg", "Aashirvaad Whole Wheat Atta", "1 kg", Money.ofRupees(62), listOf("atta", "flour", "wheat")),
            CatalogueEntry("atta-5kg", "Aashirvaad Whole Wheat Atta", "5 kg", Money.ofRupees(285), listOf("atta", "flour", "wheat")),
            CatalogueEntry("atta-10kg", "Aashirvaad Whole Wheat Atta", "10 kg", Money.ofRupees(545), listOf("atta", "flour", "wheat")),

            CatalogueEntry("milk-500", "Amul Taaza Toned Milk", "500 ml", Money.ofRupees(28), listOf("milk", "doodh", "taaza")),
            CatalogueEntry("milk-1l", "Amul Gold Full Cream Milk", "1 L", Money.ofRupees(74), listOf("milk", "doodh", "gold")),
            CatalogueEntry("bread-400", "Britannia Brown Bread", "400 g", Money.ofRupees(45), listOf("bread", "brown bread", "pav")),
            CatalogueEntry("eggs-6", "Farm Fresh Eggs", "pack of 6", Money.ofRupees(54), listOf("egg", "eggs", "anda")),
            CatalogueEntry("tomato-1kg", "Tomato (Local)", "1 kg", Money.ofRupees(32), listOf("tomato", "tamatar")),
            CatalogueEntry("onion-1kg", "Onion", "1 kg", Money.ofRupees(38), listOf("onion", "pyaz", "pyaaz")),
            CatalogueEntry("rice-5kg", "India Gate Basmati Rice", "5 kg", Money.ofRupees(620), listOf("rice", "basmati", "chawal")),
            CatalogueEntry("dal-1kg", "Toor Dal", "1 kg", Money.ofRupees(165), listOf("dal", "toor", "arhar", "lentil")),
            CatalogueEntry("oil-1l", "Fortune Sunflower Oil", "1 L", Money.ofRupees(148), listOf("oil", "sunflower", "tel")),
            CatalogueEntry("sugar-1kg", "Sugar", "1 kg", Money.ofRupees(46), listOf("sugar", "cheeni", "chini")),
            CatalogueEntry("tea-250", "Red Label Tea", "250 g", Money.ofRupees(135), listOf("tea", "chai", "red label")),
            CatalogueEntry("biscuit-200", "Britannia Good Day Cashew", "200 g", Money.ofRupees(45), listOf("biscuit", "good day", "cookies")),
            CatalogueEntry("curd-400", "Amul Masti Dahi", "400 g", Money.ofRupees(42), listOf("curd", "dahi", "yogurt")),

            // EC-Z5: the seeded stock-out. Searchable, addable-looking, and
            // rejected on add - which is the sequence a real stock-out follows.
            CatalogueEntry("paneer-200", "Amul Malai Paneer", "200 g", Money.ofRupees(95), listOf("paneer", "cottage cheese"), inStock = false),
        )
    }
}
