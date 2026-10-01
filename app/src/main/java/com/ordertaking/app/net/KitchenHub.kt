package com.ordertaking.app.net

import android.content.Context
import android.util.Log
import com.ordertaking.app.Sounds
import com.ordertaking.app.data.Ack
import com.ordertaking.app.data.AppJson
import com.ordertaking.app.data.AppPrefs
import com.ordertaking.app.data.HistoryEntry
import com.ordertaking.app.data.OrderHistory
import com.ordertaking.app.data.JsonFileStore
import com.ordertaking.app.data.KitchenTicket
import com.ordertaking.app.data.Order
import com.ordertaking.app.data.OrderStatusUpdate
import com.ordertaking.app.data.ReadyList
import com.ordertaking.app.data.SetStatus
import com.ordertaking.app.data.SubmitOrder
import com.ordertaking.app.data.TicketStatus
import com.ordertaking.app.data.WireMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Runs on the kitchen tablet. Accepts orders from cashier tablets over a WebSocket,
 * keeps the ticket list (persisted, so a restart doesn't lose orders) and pushes
 * "ready" notices back to the cashiers when a ticket is bumped.
 */
class KitchenHub(
    context: Context,
    private val prefs: AppPrefs,
    private val history: OrderHistory,
    private val sounds: Sounds,
) {
    private val store = JsonFileStore(
        File(context.filesDir, "kitchen_tickets.json"),
        ListSerializer(KitchenTicket.serializer()),
    ) { emptyList() }

    private val _tickets = MutableStateFlow(store.load())
    val tickets: StateFlow<List<KitchenTicket>> = _tickets.asStateFlow()

    private val _connectedTablets = MutableStateFlow(0)
    val connectedTablets: StateFlow<Int> = _connectedTablets.asStateFlow()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    private val _newTickets = MutableSharedFlow<KitchenTicket>(extraBufferCapacity = 32)
    val newTickets: SharedFlow<KitchenTicket> = _newTickets.asSharedFlow()

    private val advertiser = KitchenAdvertiser(context)

    init {
        prefs.readyTrackingSince // record the moment pickups started being tracked
    }
    private var server: Server? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val s = Server()
        s.isReuseAddr = true
        s.connectionLostTimeout = 10
        s.start()
        server = s
        advertiser.start()
    }

    @Synchronized
    fun stop() {
        advertiser.stop()
        runCatching { server?.stop(1000) }
        server = null
        _connectedTablets.value = 0
    }

    fun setStatus(orderId: String, status: TicketStatus) {
        var changed: KitchenTicket? = null
        update { list ->
            list.map {
                if (it.order.orderId == orderId) {
                    val finished = status == TicketStatus.DONE || status == TicketStatus.SERVED
                    it.copy(
                        status = status,
                        // Keep the original "ready" time when moving between DONE and SERVED (e.g. an undo).
                        bumpedAtMillis = if (!finished) null else it.bumpedAtMillis.takeIf { _ -> it.isFinished } ?: System.currentTimeMillis(),
                    ).also { t -> changed = t }
                } else it
            }
        }
        changed?.let { broadcast(it.toStatusUpdate()) }
    }

    /** Brings the most recently bumped ticket back onto the screen. */
    fun recallLast() {
        val last = _tickets.value.filter { it.isFinished }
            .maxByOrNull { it.bumpedAtMillis ?: 0 } ?: return
        setStatus(last.order.orderId, TicketStatus.IN_PROGRESS)
    }

    fun clearAll() {
        update { emptyList() }
        broadcast(ReadyList(emptyList()))
    }

    /** Orders cooked and waiting for pickup (ignoring any older than 12 hours). */
    private fun readyList(): ReadyList {
        val cutoff = maxOf(System.currentTimeMillis() - 12 * 60 * 60 * 1000L, prefs.readyTrackingSince)
        return ReadyList(
            _tickets.value.filter { it.status == TicketStatus.DONE && (it.bumpedAtMillis ?: 0) > cutoff }
                .sortedBy { it.bumpedAtMillis }
                .map { it.toStatusUpdate() },
        )
    }

    /** A cashier handed an order out (SERVED) or undid that (DONE). */
    private fun onCashierStatus(msg: SetStatus) {
        val ticket = _tickets.value.find { it.order.orderId == msg.orderId }
        when {
            ticket == null && msg.status == TicketStatus.SERVED ->
                // Ticket already cleared from the kitchen; still tell every cashier it's gone.
                broadcast(OrderStatusUpdate(msg.orderId, 0, "", TicketStatus.SERVED))
            ticket == null -> Unit
            msg.status == TicketStatus.SERVED && ticket.status == TicketStatus.DONE -> setStatus(msg.orderId, TicketStatus.SERVED)
            msg.status == TicketStatus.DONE && ticket.status == TicketStatus.SERVED -> setStatus(msg.orderId, TicketStatus.DONE)
            else -> broadcast(ticket.toStatusUpdate()) // nothing to change; resync the cashiers
        }
    }

    private fun receive(order: Order): KitchenTicket {
        synchronized(this) {
            _tickets.value.find { it.order.orderId == order.orderId }?.let { return it } // duplicate re-send
            val ticket = KitchenTicket(order, prefs.nextTicketNumber(), System.currentTimeMillis())
            // Record permanently before acknowledging, so an acknowledged order is never missing from the history.
            runCatching { history.append(HistoryEntry(order, ticket.receivedAtMillis, ticket.ticketNumber)) }
                .onFailure { Log.e("KitchenHub", "Could not save order to history", it) }
            update { it + ticket }
            _newTickets.tryEmit(ticket)
            // Played here rather than by the screen, so it sounds whatever the kitchen is showing.
            sounds.newOrder()
            return ticket
        }
    }

    @Synchronized
    private fun update(transform: (List<KitchenTicket>) -> List<KitchenTicket>) {
        val next = transform(_tickets.value)
        // Keep every open ticket, but only the 100 most recent finished ones (for recall).
        val done = next.filter { it.isFinished }
            .sortedByDescending { it.bumpedAtMillis ?: 0 }.drop(100).map { it.order.orderId }.toSet()
        val pruned = if (done.isEmpty()) next else next.filterNot { it.order.orderId in done }
        _tickets.value = pruned
        store.save(pruned)
    }

    private fun broadcast(msg: WireMessage) {
        runCatching { server?.broadcast(encode(msg)) }
    }

    private val KitchenTicket.isFinished get() = status == TicketStatus.DONE || status == TicketStatus.SERVED

    private fun KitchenTicket.toStatusUpdate() =
        OrderStatusUpdate(order.orderId, ticketNumber, order.origin, status, order.items, bumpedAtMillis ?: 0)

    private fun encode(msg: WireMessage) = AppJson.encodeToString(WireMessage.serializer(), msg)

    private inner class Server : WebSocketServer(InetSocketAddress(KITCHEN_PORT)) {
        private val clients = Collections.synchronizedSet(mutableSetOf<WebSocket>())

        override fun onStart() {
            _serverError.value = null
            Log.i("KitchenHub", "Listening on port $KITCHEN_PORT")
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            clients.add(conn)
            _connectedTablets.value = clients.size
            // Catch the cashier up on anything that became ready while it was away.
            runCatching { conn.send(encode(readyList())) }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            clients.remove(conn)
            _connectedTablets.value = clients.size
        }

        override fun onMessage(conn: WebSocket, message: String) {
            try {
                when (val msg = AppJson.decodeFromString(WireMessage.serializer(), message)) {
                    is SubmitOrder -> {
                        val ticket = receive(msg.order)
                        conn.send(encode(Ack(msg.order.orderId, ticket.ticketNumber)))
                    }
                    is SetStatus -> onCashierStatus(msg)
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e("KitchenHub", "Bad message: $message", e)
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e("KitchenHub", "Server error", ex)
            if (conn == null) {
                // Error on the server socket itself (e.g. port already in use).
                _serverError.value = ex.message ?: ex.javaClass.simpleName
                synchronized(this@KitchenHub) { if (server === this) server = null }
            }
        }
    }
}
