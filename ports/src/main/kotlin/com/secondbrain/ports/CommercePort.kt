package com.secondbrain.ports

import com.secondbrain.model.Cart
import com.secondbrain.model.CartMutation
import com.secondbrain.model.CommerceAvailability
import com.secondbrain.model.OrderOutcome
import com.secondbrain.model.Product

/**
 * WF-4's commerce dependency, as a port.
 *
 * ARCHITECTURE §1: *"Every external dependency in this system is either untested
 * (Zepto MCP), likely to change, or something we may want to run offline."*
 * Zepto is the untested one, so it gets the treatment that buys the most: two
 * implementations, one real (`McpCommerceAdapter`) and one deterministic
 * (`FakeCommerceAdapter`), and an `:agent` that cannot tell them apart.
 *
 * ### Why this port is narrow when the MCP bridge is wide
 *
 * §5 WF-4 asks for *"every tool available in the MCP"* to be bridged
 * dynamically into the registry as `zepto__*`. **That is not built yet** — see
 * D-082. This port is the separate, deliberately small surface for the six
 * operations WF-4's flow actually depends on, so that flow keeps working
 * against the fake and so a schema change on the far side breaks one adapter
 * rather than the workflow. The dynamic bridge would sit alongside it, not
 * replace it, and `MutationClassifier` already exists for exactly that job —
 * today it only classifies for the startup log.
 *
 * ### The rule every implementation must honour
 *
 * **[readCart] is the only source of truth about the cart, at every point, not
 * merely before proposing.** ARCHITECTURE says re-read "before proposing the
 * order" (EC-Z6/EC-Z7); that is necessary and not sufficient. R8's phase window
 * drops the early turns of a long grocery session, so a model reasoning from
 * its own memory of what it added will eventually be wrong about a cart the
 * user is asked to approve (EC-Z19). Every mutation here therefore returns the
 * *whole* server cart rather than a delta, and nothing accumulates locally.
 */
interface CommercePort {

    /**
     * True for `FakeCommerceAdapter`.
     *
     * §7 Step 7: the demo toggle must be *"visibly labelled in the UI"* when the
     * fake is active. This is what carries that to `OrderProposal.isFake`, and
     * it is on the port rather than inferred by `instanceof` so the label cannot
     * be forgotten by a future third implementation.
     */
    val isFake: Boolean

    /** Human name for the UI banner and the spoken read-back. "Zepto", "Demo catalogue". */
    val displayName: String

    /**
     * EC-Z1/EC-Z22. Checked before the grocery flow starts *and* re-checkable
     * mid-flow, because a session that dies holding a half-built cart is a real
     * case the doc does not cover.
     */
    suspend fun availability(): CommerceAvailability

    /** EC-Z2/EC-Z3: zero results is a normal answer, not an error. Never substitute. */
    suspend fun search(query: String, limit: Int): List<Product>

    /** EC-Z5: rejection (out of stock) is an outcome, not an exception. */
    suspend fun addToCart(productId: String, quantity: Int): CartMutation

    /**
     * Changes the quantity of a line already in the cart.
     *
     * A [quantity] of 0 is defined as removal, so "make it none" and "drop it"
     * cannot diverge into two code paths that behave differently. Implementations
     * that have no zero-quantity call route it to [removeFromCart] themselves.
     */
    suspend fun updateQuantity(lineId: String, quantity: Int): CartMutation

    suspend fun removeFromCart(lineId: String): CartMutation

    /** Server truth. See the class doc — this is the only thing that knows what is in the cart. */
    suspend fun readCart(): Cart

    /**
     * The one irreversible call in this file. Reached only from a resolved
     * `ConfirmationGate`, never from a model tool call (R2).
     *
     * [idempotencyKey] is the ledger's `proposal_id` (R5). A lost response is
     * [OrderOutcome.Unknown] and is *never* retried automatically — EC-Z8, and
     * the reason the ledger exists at all.
     */
    suspend fun placeOrder(cart: Cart, idempotencyKey: String): OrderOutcome
}
