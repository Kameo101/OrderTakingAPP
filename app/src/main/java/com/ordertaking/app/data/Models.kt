package com.ordertaking.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON configuration for files on disk and messages on the wire. */
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

// ---------------------------------------------------------------------------
// Menu
// ---------------------------------------------------------------------------

@Serializable
data class ModifierOption(
    val name: String,
    val price: Double = 0.0,
)

@Serializable
data class ModifierGroup(
    val name: String,
    /** true = pick any number (checkboxes), false = pick one (radio buttons). */
    val multiSelect: Boolean = false,
    /** Single-choice groups that are required must be answered before adding to the tray. */
    val required: Boolean = false,
    val options: List<ModifierOption> = emptyList(),
)

@Serializable
data class MenuItem(
    val id: String,
    val name: String,
    val description: String = "",
    val price: Double = 0.0,
    val category: String = "Mains",
    /** Absolute path of a copy of the photo inside the app's private storage. */
    val imagePath: String? = null,
    val modifierGroups: List<ModifierGroup> = emptyList(),
    /** false = sold out; shown greyed-out and can't be tapped. */
    val available: Boolean = true,
)

// ---------------------------------------------------------------------------
// Order payload (matches the spec's JSON shape)
// ---------------------------------------------------------------------------

@Serializable
data class OrderItem(
    @SerialName("item_id") val itemId: String,
    val name: String,
    val quantity: Int,
    val modifiers: List<String> = emptyList(),
    val notes: String = "",
    /** Unit price including options, for sales reports. Not part of the original spec; 0 if unknown. */
    val price: Double = 0.0,
)

@Serializable
data class Order(
    @SerialName("order_id") val orderId: String,
    val timestamp: String,
    val origin: String,
    val items: List<OrderItem>,
    val status: String = "pending",
    @SerialName("taken_by") val takenBy: String = "",
    /** Set when this order is a corrected copy of an earlier one; the kitchen cancels that one. */
    val replaces: String = "",
)

// ---------------------------------------------------------------------------
// Kitchen-side ticket
// ---------------------------------------------------------------------------

@Serializable
enum class TicketStatus {
    PENDING,
    IN_PROGRESS,
    /** Cooked and waiting at the counter for the customer. */
    DONE,
    /** Handed to the customer by a cashier. */
    SERVED,
    /** Called off by a cashier (or replaced by a changed order). */
    CANCELLED,
}

@Serializable
data class KitchenTicket(
    val order: Order,
    /** Short, human-friendly number the kitchen assigns (resets daily). */
    val ticketNumber: Int,
    val receivedAtMillis: Long,
    val status: TicketStatus = TicketStatus.PENDING,
    val bumpedAtMillis: Long? = null,
    /** Cancelled while it was on the kitchen screen: shown crossed out until the kitchen taps OK. */
    val showCancelled: Boolean = false,
    /** Ticket number of the changed order that replaced this one, if any. */
    val replacedBy: Int = 0,
    /** Ticket number of the order this one replaces, if any. */
    val replacesTicket: Int = 0,
)

// ---------------------------------------------------------------------------
// Messages exchanged between cashier tablets and the kitchen tablet
// ---------------------------------------------------------------------------

@Serializable
sealed class WireMessage

/** Cashier -> kitchen. Re-sent until acknowledged; the kitchen ignores duplicates. */
@Serializable
@SerialName("SUBMIT_ORDER")
data class SubmitOrder(val order: Order) : WireMessage()

/** Kitchen -> cashier. Confirms the order is on the kitchen screen. */
@Serializable
@SerialName("ACK")
data class Ack(
    @SerialName("order_id") val orderId: String,
    @SerialName("ticket_number") val ticketNumber: Int,
) : WireMessage()

/** Kitchen -> all cashiers. Sent when a ticket changes state (DONE = ready for pickup, SERVED = handed out). */
@Serializable
@SerialName("ORDER_STATUS")
data class OrderStatusUpdate(
    @SerialName("order_id") val orderId: String,
    @SerialName("ticket_number") val ticketNumber: Int,
    val origin: String,
    val status: TicketStatus,
    /** What to hand over, so any cashier tablet can show it. */
    val items: List<OrderItem> = emptyList(),
    /** When the kitchen marked it ready. */
    @SerialName("ready_at") val readyAtMillis: Long = 0,
) : WireMessage()

/** Kitchen -> a cashier that just connected: every order currently waiting for pickup. */
@Serializable
@SerialName("READY_LIST")
data class ReadyList(val orders: List<OrderStatusUpdate>) : WireMessage()

/** Cashier -> kitchen: call off an order. Re-sent until the kitchen answers with a CANCELLED status. */
@Serializable
@SerialName("CANCEL_ORDER")
data class CancelOrder(@SerialName("order_id") val orderId: String) : WireMessage()

/** Cashier -> kitchen: an order was handed out (SERVED), or that was undone (DONE). */
@Serializable
@SerialName("SET_STATUS")
data class SetStatus(
    @SerialName("order_id") val orderId: String,
    val status: TicketStatus,
) : WireMessage()
