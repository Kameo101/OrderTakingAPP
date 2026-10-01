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

    private val _statusUpdates = MutableSharedFlow<OrderStatusUpdate>(extraBufferCapacity = 32)
    val statusUpdates: SharedFlow<OrderStatusUpdate> = _statusUpdates.asSharedFlow()

    @Volatile private var discoveredHost: String? = null
    private val finder = KitchenFinder(context) { discoveredHost = it }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    @Volatile private var client: Client? = null
    @Volatile private var lastFlush = 0L

    @Synchronized
    fun start() {
        if (loop != null) return
        finder.start()
        loop = scope.launch {
            while (isActive) {
                val c = client
                if (c == null || !c.isOpen) {
                    connectOnce()
                } else if (_outbox.value.isNotEmpty() && System.currentTimeMillis() - lastFlush > 5_000) {
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

    fun discardOutbox() = synchronized(this) {
        _outbox.value = emptyList()
        outboxStore.save(emptyList())
    }

    private fun connectOnce() {
        val host = prefs.kitchenHost.ifBlank { null } ?: discoveredHost
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
            _state.value = LinkState.Connected(host)
            flush(c)
        } else {
            runCatching { c.close() }
            _state.value = LinkState.Offline(host)
        }
    }

    private fun flush(c: Client) {
        lastFlush = System.currentTimeMillis()
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
                    is OrderStatusUpdate -> _statusUpdates.tryEmit(msg)
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
