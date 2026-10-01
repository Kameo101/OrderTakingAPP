package com.ordertaking.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ordertaking.app.App
import com.ordertaking.app.data.HistoryEntry
import com.ordertaking.app.data.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/** How far back the Recent list goes. */
private const val RECENT_HOURS = 12

/** One row of the Recent list: an order the kitchen has, or one still waiting to be sent. */
private data class RecentOrder(
    val order: Order,
    val ticketNumber: Int,
    val atMillis: Long,
    val cancelled: Boolean,
    val unsent: Boolean,
)

/**
 * Orders taken on this tablet in the last few hours, newest first, each with Change and Cancel.
 */
@Composable
fun RecentOrdersDialog(
    outbox: List<Order>,
    currency: String,
    onDismiss: () -> Unit,
    onCancel: (Order) -> Unit,
    onChange: (Order, ticketNumber: Int) -> Unit,
) {
    val app = App.instance
    var reloadKey by remember { mutableIntStateOf(0) }
    val sent by produceState<List<HistoryEntry>?>(null, reloadKey) {
        val since = System.currentTimeMillis() - RECENT_HOURS * 60 * 60 * 1000L
        value = withContext(Dispatchers.IO) { app.history.loadAll().filter { it.recordedAtMillis >= since } }
    }
    val rows = remember(sent, outbox) {
        val waiting = outbox.map { RecentOrder(it, 0, orderTime(it), cancelled = false, unsent = true) }
        val known = sent.orEmpty().map { RecentOrder(it.order, it.ticketNumber, it.recordedAtMillis, it.cancelled, unsent = false) }
        (waiting + known).distinctBy { it.order.orderId }.sortedByDescending { it.atMillis }
    }
    var confirmCancel by remember { mutableStateOf<RecentOrder?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recent orders", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Orders from this tablet in the last $RECENT_HOURS hours. Change puts the order back in the tray to fix and " +
                        "send again. Cancel crosses it out on the kitchen screen and takes it out of sales.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.padding(4.dp))
                when {
                    sent == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    rows.isEmpty() -> Text("No orders yet.", fontSize = 18.sp, modifier = Modifier.padding(vertical = 24.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rows, key = { it.order.orderId }) { r ->
                            RecentRow(
                                r, currency,
                                onCancel = { confirmCancel = r },
                                onChange = { onChange(r.order, r.ticketNumber) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    confirmCancel?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Cancel ${label(r)}?") },
            text = {
                Text(
                    if (r.unsent) "This order hasn't reached the kitchen yet. It won't be sent."
                    else "The kitchen will see the ticket crossed out. It won't count in sales.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCancel(r.order)
                        confirmCancel = null
                        reloadKey++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LateRed),
                ) { Text("Cancel order") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun RecentRow(r: RecentOrder, currency: String, onCancel: () -> Unit, onChange: () -> Unit) {
    val dim = if (r.cancelled) 0.5f else 1f
    val strike = if (r.cancelled) TextDecoration.LineThrough else null
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    label(r), fontWeight = FontWeight.Bold, fontSize = 17.sp, textDecoration = strike,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                Text(
                    r.order.items.joinToString { "${it.quantity}× ${it.name}" },
                    fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = strike,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.atMillis)) + " · " +
                        money(r.order.items.sumOf { it.price * it.quantity }, currency) + " · " +
                        when {
                            r.cancelled -> "Cancelled"
                            r.unsent -> "Waiting to send"
                            else -> "With the kitchen"
                        },
                    fontSize = 13.sp,
                    fontWeight = if (r.cancelled || r.unsent) FontWeight.Bold else FontWeight.Normal,
                    color = if (r.cancelled) LateRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            if (!r.cancelled) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onChange) { Text("Change") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) { Text("Cancel", color = LateRed) }
            }
        }
    }
}

private fun label(r: RecentOrder) =
    (if (r.ticketNumber > 0) "#${r.ticketNumber} · " else "") + r.order.origin

/** When an unsent order was taken, from its timestamp. */
private fun orderTime(o: Order): Long =
    runCatching { Instant.parse(o.timestamp).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
