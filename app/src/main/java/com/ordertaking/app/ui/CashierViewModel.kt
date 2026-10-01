package com.ordertaking.app.ui

import androidx.lifecycle.ViewModel
import com.ordertaking.app.App
import com.ordertaking.app.data.MenuItem
import com.ordertaking.app.data.ModifierOption
import com.ordertaking.app.data.Order
import com.ordertaking.app.data.OrderItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class CartLine(
    val key: String,
    val item: MenuItem,
    val quantity: Int,
    val modifiers: List<ModifierOption>,
    val notes: String,
) {
    val unitPrice: Double get() = item.price + modifiers.sumOf { it.price }
    val total: Double get() = unitPrice * quantity
}

class CashierViewModel : ViewModel() {
    private val app = App.instance
    val menu = app.menu.items
    val link = app.cashierLink

    private val _cart = MutableStateFlow<List<CartLine>>(emptyList())
    val cart: StateFlow<List<CartLine>> = _cart.asStateFlow()

    /** Adds to the tray. An identical line (same item, options and note) just gets its quantity bumped. */
    fun add(item: MenuItem, modifiers: List<ModifierOption> = emptyList(), quantity: Int = 1, notes: String = "") {
        val existing = _cart.value.find { it.item.id == item.id && it.modifiers == modifiers && it.notes == notes }
        _cart.value = if (existing != null) {
            _cart.value.map { if (it.key == existing.key) it.copy(quantity = it.quantity + quantity) else it }
        } else {
            _cart.value + CartLine(UUID.randomUUID().toString(), item, quantity, modifiers, notes.trim())
        }
    }

    fun changeQuantity(key: String, delta: Int) {
        _cart.value = _cart.value.mapNotNull {
            if (it.key != key) it else it.copy(quantity = it.quantity + delta).takeIf { l -> l.quantity > 0 }
        }
    }

    fun setNotes(key: String, notes: String) {
        _cart.value = _cart.value.map { if (it.key == key) it.copy(notes = notes.trim()) else it }
    }

    fun clear() {
        _cart.value = emptyList()
    }

    /** Builds the order payload, hands it to the outbox and empties the tray. */
    fun send(origin: String): Order? {
        val lines = _cart.value
        if (lines.isEmpty()) return null
        val order = Order(
            orderId = "ORD-" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase(),
            timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            origin = origin,
            takenBy = app.prefs.staffName,
            items = lines.map { l ->
                OrderItem(
                    itemId = l.item.id,
                    name = l.item.name,
                    quantity = l.quantity,
                    modifiers = l.modifiers.map { it.name },
                    notes = l.notes,
                )
            },
        )
        link.submit(order)
        clear()
        return order
    }
}
