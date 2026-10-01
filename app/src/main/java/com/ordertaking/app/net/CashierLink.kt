package com.ordertaking.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.ordertaking.app.data.Ack
import com.ordertaking.app.data.AppJson
import com.ordertaking.app.data.AppPrefs
import com.ordertaking.app.data.CancelOrder
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
import kotlinx.serialization.builtins.serializer
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

sealed class LinkState {
    data object Searching : LinkState()
    data class Connecting(val host: String) : LinkState()
    data class Connected(val host: String) : LinkState()
    data class Offline(val host: String) : LinkState()
}

/** The open link to the kitchen, over Wi-Fi or Bluetooth. */
private interface KitchenConn {
    val isUp: Boolean
    fun sendText(text: String)
    fun shutdown()
}

/**
 * Runs on a cashier tablet. Keeps a connection to the kitchen open and delivers orders.
 *
 * Every order goes into a persisted outbox first and only leaves it once the kitchen
 * acknowledges it, so an order taken while the Wi-Fi blips is sent automatically
 * when the connection comes back.
 *
 * Talks to the kitchen over Wi-Fi by default, or over Bluetooth when that's switched on
 * in Settings (for when the Wi-Fi is unreliable).
 */
class CashierLink(context: Context, private val prefs: AppPrefs, private val history: OrderHistory) {
    private val appContext = context.applicationContext
    private val outboxStore = JsonFileStore(
        File(context.filesDir, "outbox.json"),
        ListSerializer(Order.serializer()),
    ) { emptyList() }

    private val _outbox = MutableStateFlow(outboxStore.load())
    val outbox: StateFlow<List<Order>> = _outbox.asStateFlow()

    private val _state = MutableStateFlow<LinkState>(LinkState.Searching)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** When the link to the kitchen was last lost (or the app started without one); null while connected. */
    private val _offlineSince = MutableStateFlow<Long?>(System.currentTimeMillis())
    val offlineSince: StateFlow<Long?> = _offlineSince.asStateFlow()

    /** The outage (its [offlineSince]) the "Wi-Fi isn't working" tip was dismissed for. */
    @Volatile var wifiTipDismissedFor: Long? = null

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

    /** Orders called off on this tablet that the kitchen hasn't confirmed yet. Saved; re-sent until confirmed. */
    private val cancelStore = JsonFileStore(
        File(context.filesDir, "cancels.json"),
        ListSerializer(String.serializer()),
    ) { emptyList() }
    private val cancelPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(cancelStore.load()) }

    /** Handed out on this tablet but not yet confirmed by the kitchen; re-sent on reconnect. */
    private val servedPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var discoveredHost: String? = null
    private val finder = KitchenFinder(context) { discoveredHost = it }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Sends one at a time, in order, off the main thread (a Bluetooth write can block). */
    private val sendQueue = Dispatchers.IO.limitedParallelism(1)
    private var loop: Job? = null
    @Volatile private var client: KitchenConn? = null
    @Volatile private var lastFlush = 0L
    /** Addresses that just failed, and when they may be tried again. */
    private val backoff = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastScanAt = 0L

    init {
        scope.launch {
            _state.collect { s ->
                if (s is LinkState.Connected) _offlineSince.value = null
                else if (_offlineSince.value == null) _offlineSince.value = System.currentTimeMillis()
            }
        }
    }

    @Synchronized
    fun start() {
        if (loop != null) return
        finder.start()
        loop = scope.launch {
            while (isActive) {
                val c = client
                if (c == null || !c.isUp) {
                    connectOnce()
                } else if ((_outbox.value.isNotEmpty() || servedPending.isNotEmpty() || cancelPending.isNotEmpty()) && System.currentTimeMillis() - lastFlush > 5_000) {
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
        client?.let { runCatching { it.shutdown() } }
        client = null
        _state.value = LinkState.Searching
    }

    /** Drop the current connection and try again right away (e.g. after the address changed). */
    fun reconnect() {
        client?.let { runCatching { it.shutdown() } }
        client = null
        if (loop != null) {
            stop()
            start()
        }
    }

    /** Queues an order. A changed order ([Order.replaces] set) takes the place of the original. */
    fun submit(order: Order) {
        synchronized(this) {
            if (order.replaces.isNotBlank()) {
                // The kitchen cancels the original when the new one arrives; here it's just taken off the record.
                markCancelledLocally(order.replaces)
            }
            val next = _outbox.value.filterNot { it.orderId == order.replaces } + order
            _outbox.value = next
            outboxStore.save(next)
        }
        client?.takeIf { it.isUp }?.let { c -> scope.launch { flush(c) } }
    }

    /** Calls an order off: it's crossed out on the kitchen screen and left out of sales. */
    fun cancel(orderId: String) {
        synchronized(this) {
            markCancelledLocally(orderId)
            val next = _outbox.value.filterNot { it.orderId == orderId }
            if (next.size != _outbox.value.size) {
                _outbox.value = next
                outboxStore.save(next)
            }
            // Sent even if it never left the outbox, in case it reached the kitchen just before.
            cancelPending.add(orderId)
            cancelStore.save(cancelPending.toList())
        }
        setReady(_ready.value.filterNot { it.orderId == orderId })
        val c = client?.takeIf { it.isUp } ?: return
        scope.launch(sendQueue) { runCatching { c.sendText(AppJson.encodeToString(WireMessage.serializer(), CancelOrder(orderId))) } }
    }

    private fun markCancelledLocally(orderId: String) {
        runCatching { history.markCancelled(orderId) }
            .onFailure { Log.e("CashierLink", "Could not mark order cancelled in history", it) }
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
        val c = client?.takeIf { it.isUp } ?: return
        scope.launch(sendQueue) {
            runCatching { c.sendText(AppJson.encodeToString(WireMessage.serializer(), SetStatus(orderId, status))) }
        }
    }

    private fun onStatus(u: OrderStatusUpdate) = synchronized(_ready) {
        val others = _ready.value.filterNot { it.orderId == u.orderId }
        when (u.status) {
            TicketStatus.CANCELLED -> {
                if (cancelPending.remove(u.orderId)) cancelStore.save(cancelPending.toList())
                setReady(others)
            }
            TicketStatus.DONE -> if (u.orderId !in servedPending && u.orderId !in cancelPending) {
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
        val next = list.orders.filter { it.orderId !in servedPending && it.orderId !in cancelPending }
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
        if (prefs.useBluetooth) return connectBluetooth()
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

    /** Connects to the kitchen tablet chosen in Settings over Bluetooth. */
    @SuppressLint("MissingPermission")
    private fun connectBluetooth() {
        val address = prefs.btKitchenAddress
        if (address.isBlank()) {
            _state.value = LinkState.Searching
            return
        }
        val label = "${prefs.btKitchenName.ifBlank { address }} (Bluetooth)"
        val adapter = bluetoothAdapter(appContext)
        if (adapter == null || !hasBluetoothPermission(appContext) || !adapter.isEnabled ||
            (backoff[address] ?: 0) > System.currentTimeMillis()
        ) {
            _state.value = LinkState.Offline(label)
            return
        }
        _state.value = LinkState.Connecting(label)
        val socket = runCatching {
            adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(KITCHEN_BT_UUID).also { it.connect() }
        }.getOrElse {
            Log.w("CashierLink", "Bluetooth connect failed: ${it.message}")
            backoff[address] = System.currentTimeMillis() + 5_000
            _state.value = LinkState.Offline(label)
            return
        }
        val line = BtLine(socket)
        val c = object : KitchenConn {
            override val isUp get() = line.isOpen
            override fun sendText(text: String) = line.send(text)
            override fun shutdown() = line.close()
        }
        client = c
        backoff.remove(address)
        _state.value = LinkState.Connected(label)
        thread(isDaemon = true, name = "cashier-bt") {
            line.readLoop(::onMessage)
            onDisconnected(c, label)
        }
        flush(c)
    }

    private fun onMessage(message: String) {
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

    private fun onDisconnected(c: KitchenConn, label: String) {
        if (client === c) {
            client = null
            _state.value = LinkState.Offline(label)
        }
    }

    private fun flush(c: KitchenConn) {
        lastFlush = System.currentTimeMillis()
        for (id in cancelPending) {
            runCatching { c.sendText(AppJson.encodeToString(WireMessage.serializer(), CancelOrder(id))) }
        }
        for (id in servedPending) {
            runCatching { c.sendText(AppJson.encodeToString(WireMessage.serializer(), SetStatus(id, TicketStatus.SERVED))) }
        }
        for (order in _outbox.value) {
            runCatching { c.sendText(AppJson.encodeToString(WireMessage.serializer(), SubmitOrder(order))) }
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

    private inner class Client(uri: URI, private val host: String) : WebSocketClient(uri), KitchenConn {
        override val isUp get() = isOpen
        override fun sendText(text: String) = send(text)
        override fun shutdown() = close()

        override fun onOpen(handshake: ServerHandshake) {}

        override fun onMessage(message: String) = this@CashierLink.onMessage(message)

        override fun onClose(code: Int, reason: String?, remote: Boolean) = onDisconnected(this, host)

        override fun onError(ex: Exception) {
            Log.w("CashierLink", "Connection error: ${ex.message}")
        }
    }
}
