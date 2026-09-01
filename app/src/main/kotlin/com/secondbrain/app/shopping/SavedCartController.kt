package com.secondbrain.app.shopping

import com.secondbrain.agent.SavedCartStore
import com.secondbrain.model.CartMutation
import com.secondbrain.model.SavedItem
import com.secondbrain.ports.CommercePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Stage 5 (D-099): the persistent Saved Cart screen and its checkout bridge.
 *
 * ### The one rule this class exists to enforce
 *
 * **This class never calls [CommercePort.placeOrder].** R2's *"no `place_order`
 * tool that the model can invoke directly"* is written about tools, but the
 * principle underneath it — an irreversible action fires only from
 * [com.secondbrain.agent.ConfirmationGate]'s resolved callback, a click on the
 * proposal itself — applies just as much to a second UI surface reaching the
 * same port. [checkoutSelected] therefore does exactly one thing: it adds the
 * selected [SavedItem]s to the *live* Zepto cart via the same
 * [CommercePort.addToCart] call `commerce_cart_add` already uses, using
 * [CartMutation]'s existing four outcomes unchanged, then stops and tells the
 * user to go approve the order from Voice — the existing gated
 * `commerce_propose_order` -> `ConfirmationGate` -> `ActionLedger` path,
 * untouched by any of this.
 */
class SavedCartController(
    private val scope: CoroutineScope,
    private val store: SavedCartStore,
    /** Null when commerce is off entirely — see `Main.kt`'s `commercePort`. Checkout is disabled in that state. */
    private val commerce: CommercePort?,
) {
    private val log = LoggerFactory.getLogger(SavedCartController::class.java)

    data class UiState(
        val items: List<SavedItem> = emptyList(),
        val selected: Set<Long> = emptySet(),
        val commerceAvailable: Boolean = false,
        val statusLine: String = "",
        val checkingOut: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState(commerceAvailable = commerce != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** [SavedCartController] and the comparison window both write to the same [store]; call this whenever this screen becomes visible. */
    fun refresh() {
        val items = store.all()
        _state.update { it.copy(items = items, selected = it.selected.filter { id -> items.any { i -> i.id == id } }.toSet()) }
    }

    fun toggleSelected(id: Long) {
        _state.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }

    fun selectAll() {
        _state.update { it.copy(selected = it.items.map { i -> i.id }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun updateQuantity(id: Long, quantity: Int) {
        store.updateQuantity(id, quantity)
        refresh()
    }

    fun remove(id: Long) {
        store.remove(id)
        refresh()
    }

    val totalPaise: Long get() = _state.value.items.filter { it.id in _state.value.selected }.sumOf { it.unitPrice.paise * it.quantity }

    /**
     * Adds every selected line to the live Zepto cart, one [CommercePort.addToCart]
     * call per line — reversible, free, the same call `commerce_cart_add` makes.
     * Applied lines are removed from the Saved Cart; rejected/unknown lines stay
     * put rather than vanish, matching [CartMutation.Unknown]'s "never blind" rule.
     */
    fun checkoutSelected() {
        val port = commerce ?: run {
            _state.update { it.copy(statusLine = "Commerce isn't configured, so there's nothing to check out to.") }
            return
        }
        val toCheckout = _state.value.items.filter { it.id in _state.value.selected }
        if (toCheckout.isEmpty()) {
            _state.update { it.copy(statusLine = "Select at least one item first.") }
            return
        }
        _state.update { it.copy(checkingOut = true, statusLine = "Adding ${toCheckout.size} item(s) to the cart…") }
        scope.launch {
            val applied = mutableListOf<Long>()
            val rejected = mutableListOf<String>()
            val unknown = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (item in toCheckout) {
                    when (val mutation = runCatching { port.addToCart(item.productId, item.quantity) }.getOrNull()) {
                        is CartMutation.Applied -> applied += item.id
                        is CartMutation.Rejected -> rejected += "${item.name} (${mutation.reason})"
                        is CartMutation.Unknown -> unknown += item.name
                        is CartMutation.NeedsReauth -> {
                            unknown += item.name
                            log.warn("Checkout hit NeedsReauth for {}: {}", item.name, mutation.reason)
                        }
                        null -> unknown += item.name
                    }
                }
            }
            store.removeAll(applied)
            val summary = buildString {
                if (applied.isNotEmpty()) append("${applied.size} item(s) added to your Zepto cart. ")
                if (rejected.isNotEmpty()) append("Couldn't add: ${rejected.joinToString(", ")}. ")
                if (unknown.isNotEmpty()) append("Not sure about: ${unknown.joinToString(", ")} - check the cart before retrying. ")
                if (applied.isNotEmpty()) append("Open Voice and say \"place my order\" when you're ready to review and confirm.")
            }
            log.info("Checkout: {} applied, {} rejected, {} unknown", applied.size, rejected.size, unknown.size)
            refresh()
            _state.update { it.copy(checkingOut = false, statusLine = summary, selected = emptySet()) }
        }
    }
}
