package com.ordertaking.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ordertaking.app.App
import com.ordertaking.app.data.KitchenTicket
import com.ordertaking.app.data.TicketStatus
import com.ordertaking.app.net.KITCHEN_PORT
import com.ordertaking.app.net.localIpAddresses
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minutes after which a ticket's timer turns yellow / red. */
private const val WARN_MINUTES = 10
private const val LATE_MINUTES = 20

@Composable
fun KitchenScreen(onSwitchMode: () -> Unit) {
    val app = App.instance
    val hub = app.kitchenHub
    LaunchedEffect(Unit) { hub.start() }

    val tickets by hub.tickets.collectAsStateWithLifecycle()
    val tablets by hub.connectedTablets.collectAsStateWithLifecycle()
    val error by hub.serverError.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var ips by remember { mutableStateOf(localIpAddresses()) }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            now = System.currentTimeMillis()
            if (tick++ % 15 == 0) ips = localIpAddresses() // Wi-Fi may reconnect with a new address
            delay(1000)
        }
    }
    LaunchedEffect(Unit) { hub.newTickets.collect { app.chime() } }

    val active = tickets.filter { it.status != TicketStatus.DONE }.sortedBy { it.receivedAtMillis }
    val hasDone = tickets.any { it.status == TicketStatus.DONE }

    if (showHistory) {
        HistoryScreen(dark = true, scopeNote = "Every order received by this kitchen", onBack = { showHistory = false })
        return
    }

    AppTheme(dark = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                // Header bar
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF1E272C)).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Kitchen · ${active.size} open", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            if (error != null) "⚠ Not receiving orders: $error"
                            else "Address: ${ips.firstOrNull() ?: "no Wi-Fi"}  ·  $tablets cashier tablet(s) connected",
                            fontSize = 14.sp,
                            color = if (error != null) Color(0xFFFF8A80) else Color(0xFFB0BEC5),
                        )
                    }
                    if (error != null) {
                        Button(onClick = { hub.start() }) { Text("Retry") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)),
                        fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    OutlinedButton(onClick = { showHistory = true }) { Text("📊 Sales", color = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { hub.recallLast() }, enabled = hasDone) { Text("↶ Recall last") }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                    }
                }

                if (active.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No open orders", fontSize = 32.sp, color = Color(0xFF78909C))
                            Text("New orders will appear here automatically.", color = Color(0xFF78909C))
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(active, key = { it.order.orderId }) { t ->
                            TicketCard(
                                t, now,
                                onStart = { hub.setStatus(t.order.orderId, TicketStatus.IN_PROGRESS) },
                                onUndoStart = { hub.setStatus(t.order.orderId, TicketStatus.PENDING) },
                                onBump = { hub.setStatus(t.order.orderId, TicketStatus.DONE) },
                                modifier = Modifier.width(300.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }

        if (showSettings) {
            var confirmClear by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Kitchen settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cashier tablets find this kitchen automatically on the same Wi-Fi.")
                        Text("If they don't, enter this address in the cashier's settings:")
                        ips.forEach { Text("$it   (port $KITCHEN_PORT)", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                        if (ips.isEmpty()) Text("This tablet isn't connected to Wi-Fi.", color = LateRed)
                        Spacer(Modifier.height(8.dp))
                        var currency by remember { mutableStateOf(app.prefs.currencySymbol) }
                        androidx.compose.material3.OutlinedTextField(
                            value = currency,
                            onValueChange = { currency = it.take(4); app.prefs.currencySymbol = currency.ifBlank { "$" } },
                            label = { Text("Currency for sales reports") }, singleLine = true,
                        )
                        OutlinedButton(onClick = { app.chime() }) { Text("Test sound") }
                        OutlinedButton(onClick = { confirmClear = true }) { Text("Clear all tickets…") }
                        OutlinedButton(onClick = onSwitchMode) { Text("Change what this tablet is used for…") }
                    }
                },
                confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Close") } },
            )
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Clear every ticket?") },
                    text = { Text("This removes all open and finished tickets from this screen.") },
                    confirmButton = {
                        Button(
                            onClick = { hub.clearAll(); confirmClear = false; showSettings = false },
                            colors = ButtonDefaults.buttonColors(containerColor = LateRed),
                        ) { Text("Clear all") }
                    },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable
private fun TicketCard(
    t: KitchenTicket,
    now: Long,
    onStart: () -> Unit,
    onUndoStart: () -> Unit,
    onBump: () -> Unit,
    modifier: Modifier,
) {
    val cooking = t.status == TicketStatus.IN_PROGRESS
    val elapsedSec = ((now - t.receivedAtMillis) / 1000).coerceAtLeast(0)
    val minutes = elapsedSec / 60
    val timerColor = when {
        minutes >= LATE_MINUTES -> Color(0xFFFF5252)
        minutes >= WARN_MINUTES -> Color(0xFFFFD740)
        else -> Color.White
    }
    val headerColor = if (cooking) CookingAmber else PendingBlue

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (cooking) Color(0xFF3E2F14) else Color(0xFF263238),
            contentColor = Color.White,
        ),
        border = if (minutes >= LATE_MINUTES) BorderStroke(3.dp, LateRed) else null,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(headerColor).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${t.ticketNumber}", fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text(
                        "%d:%02d".format(minutes, elapsedSec % 60),
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = timerColor,
                    )
                }
                Text(t.order.origin, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t.receivedAtMillis)))
                        if (t.order.takenBy.isNotBlank()) append(" · by ${t.order.takenBy}")
                        if (cooking) append(" · COOKING")
                    },
                    fontSize = 14.sp,
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                t.order.items.forEach { item ->
                    Column {
                        Row {
                            Text(
                                "${item.quantity}×", fontSize = 22.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.width(44.dp),
                            )
                            Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        item.modifiers.forEach {
                            Text("• $it", fontSize = 17.sp, color = Color(0xFFB2EBF2), modifier = Modifier.padding(start = 44.dp))
                        }
                        if (item.notes.isNotBlank()) {
                            Text(
                                "⚠ ${item.notes}", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD740), modifier = Modifier.padding(start = 44.dp),
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cooking) {
                    OutlinedButton(onClick = onUndoStart, modifier = Modifier.height(64.dp)) {
                        Text("Undo", color = Color.White)
                    }
                } else {
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = CookingAmber, contentColor = Color.Black),
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) { Text("START", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
                Button(
                    onClick = onBump,
                    colors = ButtonDefaults.buttonColors(containerColor = SendGreen, contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text("DONE ✓", fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            }
        }
    }
}
