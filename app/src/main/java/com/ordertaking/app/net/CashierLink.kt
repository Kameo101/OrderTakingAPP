package com.ordertaking.app.net

import android.content.Context
import android.util.Log
import com.ordertaking.app.data.Ack
import com.ordertaking.app.data.AppJson
import com.ordertaking.app.data.AppPrefs
import com.ordertaking.app.data.HistoryEntry
import com.ordertaking.app.data.OrderHistory
import com.ordertaking.app.data.JsonFileStore
import com.ordertaking.app.data.Order
import com.ordertaking.app.data.OrderStatusUpdate
import com.ordertaking.app.data.ReadyList
import com.ordertaking.app.data.SetStatus
import com.ordertaking.app.data.TicketStatus
import com.ordertaking.app.data.SubmitOrder
import com.ordertaking.app.data.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

sealed class LinkState {
    data object Searching : LinkState()
    data class Connecting(val host: String) : LinkState()
    data class Connected(val host: String) : LinkState()
    data class Offline(val host: String) : LinkState()
}

/**
 * Runs on a cashier tablet. Keeps a connection to the kitchen open and delivers orders.
 *
 * Every order goes into a persisted outbox first and only leaves it once the kitchen
 * acknowledges it, so an order taken while the Wi-Fi blips is sent automatically
 * when the connection comes back.
 */
class CashierLink(context: Context, private val prefs: AppPrefs, private val history: OrderHistory) {
    private val outboxStore = JsonFileStore(
        File(context.filesDir, "outbox.json"),
        ListSerializer(Order.serializer()),
    ) { emptyList() }

    private val _outbox = MutableStateFlow(outboxStore.load())
    val outbox: StateFlow<List<Order>> = _outbox.asStateFlow()

    private val _state = MutableStateFlow<LinkState>(LinkState.Searching)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _acks = MutableSharedFlow<Ack>(extraBufferCapacity = 32)
    val acks: SharedFlow<Ack> = _acks.asSharedFlow()

    // Orders the kitchen has finished, waiting to be handed to the customer. Saved so they
    // survive the app closing; the kitchen re-sends the full list whenever we reconnect.
    private val readyStore = JsonFileStore(
        File(context.filesDir, "ready.json"),
        ListSerializer(OrderStatusUpdate.serializer()),
    ) { emptyList() }
    private val _ready = MutableStateFlow(readyStore.load())
    val ready: StateFlow<List<OrderStatusUpdate>> = _ready.asStateFlow()

    /** Fires when an order newly becomes ready (for the chime). */
    private val _readyArrivals = MutableSharedFlow<OrderStatusUpdate>(extraBufferCapacity = 32)
    val readyArrivals: SharedFlow<OrderStatusUpdate> = _readyArrivals.asSharedFlow()

    /** Handed out on this tablet but not yet confirmed by the kitchen; re-sent on reconnect. */
    private val servedPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var discoveredHost: String? = null
    private val finder = KitchenFinder(context) { discoveredHost = it }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    @Volatile private var client: Client? = null
    @Volatile private var lastFlush = 0L
    /** Addresses that just failed, and when they may be tried again. */
    private val backoff = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastScanAt = 0L

    @Synchronized
    fun start() {
        if (loop != null) return
        finder.start()
        loop = scope.launch {
            while (isActive) {
                val c = client
                if (c == null || !c.isOpen) {
                    connectOnce()
                } else if ((_outbox.value.isNotEmpty() || servedPending.isNotEmpty()) && System.currentTimeMillis() - lastFlush > 5_000) {
                    flush(c) // No ack yet: resend. The kitchen ignores duplicates.
                }
                delay(2_000)
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        finder.stop()
        client?.let { runCatching { it.close() } }
        client = null
        _state.value = LinkState.Searching
    }

    /** Drop the current connection and try again right away (e.g. after the address changed). */
    fun reconnect() {
        client?.let { runCatching { it.close() } }
        client = null
        if (loop != null) {
            stop()
            start()
        }
    }

    fun submit(order: Order) {
        synchronized(this) {
            val next = _outbox.value + order
            _outbox.value = next
            outboxStore.save(next)
        }
        client?.takeIf { it.isOpen }?.let { c -> scope.launch { flush(c) } }
    }

    /** The order was handed to the customer: clear it here and on every other tablet. */
    fun markServed(order: OrderStatusUpdate) {
        servedPending.add(order.orderId)
        setReady(_ready.value.filterNot { it.orderId == order.orderId })
        sendStatus(order.orderId, TicketStatus.SERVED)
    }

    /** Undo an accidental "handed out". */
    fun undoServed(order: OrderStatusUpdate) {
        servedPending.remove(order.orderId)
        setReady(_ready.value.filterNot { it.orderId == order.orderId } + order)
        sendStatus(order.orderId, TicketStatus.DONE)
    }

    private fun sendStatus(orderId: String, status: TicketStatus) {
        client?.takeIf { it.isOpen }?.let { c ->
            runCatching { c.send(AppJson.encodeToString(WireMessage.serializer(), SetStatus(orderId, status))) }
        }
    }

    private fun onStatus(u: OrderStatusUpdate) = synchronized(_ready) {
        val others = _ready.value.filterNot { it.orderId == u.orderId }
        when (u.status) {
            TicketStatus.DONE -> if (u.orderId !in servedPending) {
                val isNew = others.size == _ready.value.size
                setReady(others + u)
                if (isNew) _readyArrivals.tryEmit(u)
            }
            TicketStatus.SERVED -> {
                servedPending.remove(u.orderId)
                setReady(others)
            }
            else -> setReady(others) // the kitchen recalled it to keep cooking
        }
    }

    private fun onReadyList(list: ReadyList) = synchronized(_ready) {
        val known = _ready.value.map { it.orderId }.toSet()
        val next = list.orders.filter { it.orderId !in servedPending }
        setReady(next)
        next.lastOrNull { it.orderId !in known }?.let { _readyArrivals.tryEmit(it) }
    }

    private fun setReady(list: List<OrderStatusUpdate>) {
        val sorted = list.sortedBy { it.readyAtMillis }
        _ready.value = sorted
        readyStore.save(sorted)
    }

    fun discardOutbox() = synchronized(this) {
        _outbox.value = emptyList()
        outboxStore.save(emptyList())
    }

    /** Manual address if set; otherwise auto-discovered, then last known, then a scan of the network. */
    private suspend fun pickHost(): String? {
        prefs.kitchenHost.ifBlank { null }?.let { return it }
        val now = System.currentTimeMillis()
        listOfNotNull(discoveredHost, prefs.lastKitchenHost.ifBlank { null })
            .firstOrNull { (backoff[it] ?: 0) < now }
            ?.let { return it }
        if (now - lastScanAt < 10_000) return null
        _state.value = LinkState.Searching
        lastScanAt = now
        return scanForKitchen()
    }

    private suspend fun connectOnce() {
        val host = pickHost()
        if (host == null) {
            _state.value = LinkState.Searching
            return
        }
        _state.value = LinkState.Connecting(host)
        val c = runCatching { Client(URI("ws://$host:$KITCHEN_PORT"), host) }.getOrNull()
        if (c == null) {
            _state.value = LinkState.Offline(host)
            return
        }
        c.connectionLostTimeout = 10
        val ok = runCatching { c.connectBlocking(4, TimeUnit.SECONDS) }.getOrDefault(false)
        if (ok) {
            client = c
            backoff.remove(host)
            prefs.lastKitchenHost = host
            _state.value = LinkState.Connected(host)
            flush(c)
        } else {
            runCatching { c.close() }
            backoff[host] = System.currentTimeMillis() + 15_000
            _state.value = LinkState.Offline(host)
        }
    }

    private fun flush(c: Client) {
        lastFlush = System.currentTimeMillis()
        for (id in servedPending) {
            runCatching { c.send(AppJson.encodeToString(WireMessage.serializer(), SetStatus(id, TicketStatus.SERVED))) }
        }
        for (order in _outbox.value) {
            runCatching { c.send(AppJson.encodeToString(WireMessage.serializer(), SubmitOrder(order))) }
                .onFailure { Log.w("CashierLink", "send failed", it); return }
        }
    }

    private fun onAck(ack: Ack) {
        synchronized(this) {
            val order = _outbox.value.find { it.orderId == ack.orderId } ?: return
            // This tablet's own record of orders it took, kept once the kitchen has confirmed them.
            runCatching { history.append(HistoryEntry(order, System.currentTimeMillis(), ack.ticketNumber)) }
                .onFailure { Log.e("CashierLink", "Could not save order to history", it) }
            val next = _outbox.value.filterNot { it.orderId == ack.orderId }
            _outbox.value = next
            outboxStore.save(next)
            _acks.tryEmit(ack)
        }
    }

    private inner class Client(uri: URI, private val host: String) : WebSocketClient(uri) {
        override fun onOpen(handshake: ServerHandshake) {}

        override fun onMessage(message: String) {
            try {
                when (val msg = AppJson.decodeFromString(WireMessage.serializer(), message)) {
                    is Ack -> onAck(msg)
                    is OrderStatusUpdate -> onStatus(msg)
                    is ReadyList -> onReadyList(msg)
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e("CashierLink", "Bad message: $message", e)
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            if (client === this) {
                client = null
                _state.value = LinkState.Offline(host)
            }
        }

        override fun onError(ex: Exception) {
            Log.w("CashierLink", "Connection error: ${ex.message}")
        }
    }
}
