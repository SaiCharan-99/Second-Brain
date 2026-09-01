package com.secondbrain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Step 7's data model: the grocery list that comes in, the catalogue products
 * that come back, and the cart that lives on the server.
 *
 * Pure data, no I/O — `:model`'s rule. The one piece of logic here is [Money],
 * which exists because the alternative was `Double` and money in a `Double` is
 * a bug waiting for a rounding boundary.
 *
 * ### The rule that shapes every type in this file
 *
 * **The cart is server state, not ours.** [Cart] is a *snapshot of what the
 * server said*, never an accumulator we maintain. Nothing in this file has a
 * mutator; the adapter re-reads instead. That is EC-Z6/EC-Z7 in the doc, and
 * EC-Z19 in practice: R8's 8-turn rolling window will drop the early items of
 * a long grocery session out of the model's context, so a model that believed
 * its own memory of the cart would confidently misreport it. It never gets the
 * chance — every read is a round-trip.
 */

/**
 * Rupees, stored as paise.
 *
 * Integer arithmetic, formatted only at the edges. A cart total is a sum of
 * line totals is a product of unit price and quantity; doing any of that in
 * `Double` gives you ₹1,234.9999999998 eventually, and this is a number the
 * user is asked to approve before real money moves.
 */
@Serializable
@JvmInline
value class Money(val paise: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(paise + other.paise)
    operator fun times(quantity: Int) = Money(paise * quantity)
    override fun compareTo(other: Money): Int = paise.compareTo(other.paise)

    /** "₹45", or "₹45.50" when there are paise. Spoken and displayed. */
    fun format(): String {
        val rupees = paise / 100
        val remainder = paise % 100
        return if (remainder == 0L) "₹$rupees" else "₹$rupees.${remainder.toString().padStart(2, '0')}"
    }

    /** For TTS. "45 rupees" reads better than "₹45", which some voices spell out. */
    fun spoken(): String {
        val rupees = paise / 100
        val remainder = paise % 100
        return if (remainder == 0L) "$rupees rupees" else "$rupees rupees $remainder paise"
    }

    companion object {
        val ZERO = Money(0)

        fun ofRupees(rupees: Long) = Money(rupees * 100)

        /**
         * Parses whatever a commerce API decided to send.
         *
         * Deliberately permissive about the *container* (`"₹45"`, `"45.00"`,
         * `45`, `"Rs. 45"`) and strict about the *value*: an unparseable price
         * returns null rather than 0, because a silent zero would show a free
         * item in a cart the user is about to approve.
         *
         * The integer/decimal distinction matters and is guessed the only way
         * available: a bare integer is rupees (`45` = ₹45), a decimal is rupees
         * with paise (`45.50` = ₹45.50). Servers that send paise as an integer
         * would be misread by this — see [ofPaise] for that case, and the
         * adapter picks which to use based on what the live schema turns out to
         * say (recorded in DECISIONS.md once observed).
         */
        fun parse(raw: String?): Money? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim()
                .removePrefix("₹").removePrefix("Rs.").removePrefix("Rs").removePrefix("INR")
                .trim()
                .replace(",", "")
            if (cleaned.isEmpty()) return null
            return if (cleaned.contains('.')) {
                cleaned.toDoubleOrNull()?.let { Money(Math.round(it * 100)) }
            } else {
                cleaned.toLongOrNull()?.let { Money(it * 100) }
            }
        }

        fun ofPaise(paise: Long) = Money(paise)
    }
}

/** One product as the catalogue describes it. Never invented, never edited by us. */
@Serializable
data class Product(
    val id: String,
    val name: String,
    /**
     * "500 g", "1 L", "pack of 6". Load-bearing, not decoration: WF-4's
     * "never silently substitute" rule requires size in every read-back, and
     * EC-Z20 makes it the only way the user can catch a pack-size mismatch
     * ("two kilos" against a 1 kg pack is quantity 2, not one 2 kg pack).
     */
    val size: String?,
    val price: Money,
    val available: Boolean = true,
    /** Some catalogues report this; used only to warn, never to block. */
    @SerialName("in_stock_hint") val inStockHint: String? = null,
) {
    /** WF-4: "read aloud with name, size and price before it goes in." */
    fun readBack(): String = buildString {
        append(name)
        size?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        append(", ").append(price.spoken())
    }
}

/**
 * One line of the server's cart.
 *
 * [lineId] is what mutations address. It is deliberately distinct from
 * [productId]: some carts key lines by their own id, and assuming the two are
 * interchangeable breaks the moment the same product appears twice.
 */
@Serializable
data class CartLine(
    @SerialName("line_id") val lineId: String,
    @SerialName("product_id") val productId: String,
    val name: String,
    val size: String? = null,
    @SerialName("unit_price") val unitPrice: Money,
    val quantity: Int,
    /**
     * EC-Z7: false for anything the server already had when this session
     * started. Shown in the proposal window so a cart that quietly carried over
     * from yesterday cannot be mistaken for what was just built.
     */
    @SerialName("added_this_session") val addedThisSession: Boolean = true,
) {
    val lineTotal: Money get() = unitPrice * quantity
}

/**
 * A snapshot of the server's cart at one instant. Never mutated locally.
 *
 * [codAvailable] is checked here rather than at order time because EC-Z9 wants
 * it known *before* proposing — and EC-Z21, which the doc misses, is why it
 * lives on the snapshot rather than being asked once: COD eligibility usually
 * depends on the cart total, so revising the cart down past a minimum order
 * value can withdraw it. Every re-read re-answers it.
 */
@Serializable
data class Cart(
    val lines: List<CartLine> = emptyList(),
    @SerialName("delivery_fee") val deliveryFee: Money = Money.ZERO,
    /**
     * The server's own total when it gives one. Null means "we sum the lines".
     * Preferred over our sum when present: the server knows about discounts,
     * handling fees and surge that our arithmetic cannot see, and the user is
     * approving *its* number, not ours.
     */
    @SerialName("server_total") val serverTotal: Money? = null,
    @SerialName("cod_available") val codAvailable: Boolean = true,
    @SerialName("cod_unavailable_reason") val codUnavailableReason: String? = null,
) {
    val subtotal: Money get() = lines.fold(Money.ZERO) { acc, line -> acc + line.lineTotal }

    val total: Money get() = serverTotal ?: (subtotal + deliveryFee)

    val itemCount: Int get() = lines.sumOf { it.quantity }

    val isEmpty: Boolean get() = lines.isEmpty()

    /** EC-Z7: the lines that were already there before this session touched anything. */
    val preExistingLines: List<CartLine> get() = lines.filterNot { it.addedThisSession }
}

// GroceryItem/GroceryList (a typed vision-extraction result) lived here until
// D-084 superseded them: Step 8 feeds a photo to Claude directly as an
// LlmBlock.Image rather than through a separate extraction step, so the model
// calls commerce_save_list off what it sees in one pass instead of emitting a
// GroceryList a second tool would have to consume. Removed rather than left
// unused — CLAUDE.md: "never design ahead of what is validated."

/**
 * What a cart mutation reports back.
 *
 * [Unknown] is EC-Z18, which ARCHITECTURE.md covers for `place_order` (EC-Z8)
 * but not for `add_to_cart`: a request that times out mid-flight may or may not
 * have added the item, and retrying blindly is how a cart ends up with two
 * packets of milk. The caller's only correct response is to re-read the cart
 * and look, which is what [CommercePort] documents.
 */
sealed interface CartMutation {
    /** The cart as the server reports it *after* the mutation. Server truth, always. */
    data class Applied(val cart: Cart) : CartMutation

    /** EC-Z5: out of stock, quantity limit, item no longer sold. Announced, never silently skipped. */
    data class Rejected(val reason: String) : CartMutation

    /** EC-Z18. Never auto-retried; the caller re-reads instead. */
    data class Unknown(val reason: String) : CartMutation

    data class NeedsReauth(val reason: String) : CartMutation
}

/** Whether commerce can be used at all right now (EC-Z1, EC-Z22). */
sealed interface CommerceAvailability {
    data object Ready : CommerceAvailability

    /**
     * EC-Z1: the grocery list is written to the vault before any commerce call,
     * so this never loses anything. Retry is a separate, explicit user action.
     */
    data class Unavailable(val reason: String) : CommerceAvailability

    /** Configured but the user has never signed in, or the refresh token died. */
    data class NeedsLogin(val reason: String) : CommerceAvailability
}

/** Mirrors `InsertOutcome`/`SendOutcome` so the gate's executor mapping is identical everywhere. */
sealed interface OrderOutcome {
    data class Placed(val orderId: String) : OrderOutcome
    data class Failed(val reason: String) : OrderOutcome

    /** EC-Z8: "I'm not sure that went through. Check the Zepto app before ordering again." */
    data class Unknown(val reason: String) : OrderOutcome
    data class NeedsReauth(val reason: String) : OrderOutcome
}
