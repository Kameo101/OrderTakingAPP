package com.ordertaking.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ordertaking.app.App
import com.ordertaking.app.data.HistoryEntry
import com.ordertaking.app.data.Sales
import com.ordertaking.app.data.SalesPeriod
import com.ordertaking.app.data.SalesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Order history and sales report.
 * [scopeNote] says whose orders these are (all orders on the kitchen, this tablet's on a cashier).
 */
@Composable
fun HistoryScreen(dark: Boolean, scopeNote: String, onBack: () -> Unit) {
    val app = App.instance
    val currency = app.prefs.currencySymbol
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var reloadKey by remember { mutableIntStateOf(0) }
    var period by remember { mutableStateOf(SalesPeriod.TODAY) }
    val all by produceState<List<HistoryEntry>?>(null, reloadKey) {
        value = withContext(Dispatchers.IO) { app.history.loadAll() }
    }
    val entries = remember(all, period) { all?.let { Sales.filter(it, period) } ?: emptyList() }
    // Deleting a mistaken order: step 1 asks, step 2 confirms.
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleteConfirmed by remember { mutableStateOf(false) }
    val summary = remember(entries) { Sales.summarize(entries) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val rows = entries
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(Sales.toCsv(rows).toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "Exported ${rows.size} orders" else "Export failed", Toast.LENGTH_LONG).show()
        }
    }

    AppTheme(dark = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Surface(color = if (dark) Color(0xFF1E272C) else MaterialTheme.colorScheme.primary, contentColor = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text("Sales & order history", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(scopeNote, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                        }
                        IconButton(onClick = { reloadKey++ }) { Icon(Icons.Default.Refresh, "Refresh") }
                        Button(
                            onClick = { exporter.launch("orders-${period.name.lowercase()}-${LocalDate.now()}.csv") },
                            enabled = entries.isNotEmpty(),
                        ) { Text("Export to spreadsheet") }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SalesPeriod.entries) { p ->
                        FilterChip(
                            selected = p == period,
                            onClick = { period = p },
                            label = { Text(p.label, fontSize = 16.sp, modifier = Modifier.padding(vertical = 6.dp)) },
                        )
                    }
                }

                when {
                    all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (all!!.isEmpty()) "No orders recorded yet." else "No orders in this period.",
                            fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val pad = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
                        if (maxWidth >= 900.dp) {
                            Row(Modifier.fillMaxSize()) {
                                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = pad) {
                                    summarySections(summary, currency, showDays = period != SalesPeriod.TODAY && period != SalesPeriod.YESTERDAY)
                                }
                                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = pad) {
                                    orderSection(entries, currency) { deleting = it }
                                }
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                                summarySections(summary, currency, showDays = period != SalesPeriod.TODAY && period != SalesPeriod.YESTERDAY)
                                orderSection(entries, currency) { deleting = it }
                            }
                        }
                    }
                }
            }
        }

        deleting?.let { entry ->
            DeleteOrderDialogs(
                entry = entry,
                confirmed = deleteConfirmed,
                currency = currency,
                onFirstYes = { deleteConfirmed = true },
                onCancel = { deleting = null; deleteConfirmed = false },
                onDelete = {
                    deleting = null
                    deleteConfirmed = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { app.history.delete(entry.order.orderId) }
                        Toast.makeText(context, if (ok) "Order deleted" else "Couldn't delete the order", Toast.LENGTH_SHORT).show()
                        reloadKey++
                    }
                },
            )
        }
    }
}

@Composable
private fun DeleteOrderDialogs(
    entry: HistoryEntry,
    confirmed: Boolean,
    currency: String,
    onFirstYes: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val label = (if (entry.ticketNumber > 0) "Ticket #${entry.ticketNumber} · " else "") + entry.order.origin
    val details = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(entry.recordedAtMillis)) +
        " · " + money(entry.order.items.sumOf { it.price * it.quantity }, currency)
    if (!confirmed) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Delete this order?") },
            text = {
                Column {
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(details)
                    Spacer(Modifier.height(6.dp))
                    entry.order.items.forEach { Text("${it.quantity}× ${it.name}") }
                }
            },
            confirmButton = { Button(onClick = onFirstYes) { Text("Delete order") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Are you sure you want to delete this order from the history?") },
            text = {
                Text(
                    "$label ($details) will be removed and no longer counted in your sales. " +
                        "This can't be undone.\n\nThis only changes the history on this tablet.",
                )
            },
            confirmButton = {
                Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = LateRed, contentColor = Color.White)) {
                    Text("Yes, delete it")
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("No, keep it") } },
        )
    }
}

private fun LazyListScope.sectionHeader(text: String) {
    item {
        Column(Modifier.padding(top = 16.dp, bottom = 6.dp)) {
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(top = 4.dp))
        }
    }
}

private fun LazyListScope.summarySections(s: SalesSummary, currency: String, showDays: Boolean) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            StatTile("Orders", "${s.orders}", Modifier.weight(1f))
            StatTile("Items sold", "${s.itemsSold}", Modifier.weight(1f))
            StatTile("Sales", money(s.revenue, currency), Modifier.weight(1f))
            StatTile("Avg order", money(s.averageOrder, currency), Modifier.weight(1f))
        }
    }

    sectionHeader("Best sellers")
    item { TableRow("#", "Item", "Sold", "Sales", header = true) }
    val top = s.byItem.firstOrNull()?.quantity ?: 1
    items(s.byItem.withIndex().toList()) { (i, it) ->
        Column {
            TableRow("${i + 1}", it.name, "${it.quantity}", money(it.revenue, currency))
            Box(
                Modifier.padding(start = 40.dp, bottom = 4.dp).fillMaxWidth(it.quantity.toFloat() / top * 0.6f).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            )
        }
    }

    if (s.byModifier.isNotEmpty()) {
        sectionHeader("Popular options")
        items(s.byModifier.take(15)) { (name, count) -> TableRow("", name, "$count", "") }
    }

    if (showDays && s.byDay.size > 1) {
        sectionHeader("By day")
        item { TableRow("", "Day", "Orders", "Sales", header = true) }
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
        items(s.byDay) { d -> TableRow("", d.date.format(fmt), "${d.orders}", money(d.revenue, currency)) }
    }
}

private fun LazyListScope.orderSection(entries: List<HistoryEntry>, currency: String, onLongPress: (HistoryEntry) -> Unit) {
    sectionHeader("Orders (${entries.size}) — newest first")
    item {
        Text(
            "Tap an order to see its items. Press and hold to delete a mistaken order.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    items(entries.asReversed(), key = { it.order.orderId }) { OrderRow(it, currency) { onLongPress(it) } }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun TableRow(rank: String, name: String, qty: String, amount: String, header: Boolean = false) {
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    val size = if (header) 14.sp else 17.sp
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rank, Modifier.width(40.dp), fontSize = size, fontWeight = weight)
        Text(name, Modifier.weight(1f), fontSize = size, fontWeight = weight)
        Text(qty, Modifier.width(70.dp), fontSize = size, fontWeight = if (header) weight else FontWeight.Bold, textAlign = TextAlign.End)
        Text(amount, Modifier.width(110.dp), fontSize = size, fontWeight = weight, textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrderRow(e: HistoryEntry, currency: String, onLongPress: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val total = e.order.items.sumOf { it.price * it.quantity }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(
            onClick = { open = !open },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (e.ticketNumber > 0) "#${e.ticketNumber} · " else "") + e.order.origin,
                        fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    )
                    Text(
                        SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(e.recordedAtMillis)) +
                            (if (e.order.takenBy.isNotBlank()) " · by ${e.order.takenBy}" else "") +
                            " · ${e.order.items.sumOf { it.quantity }} item(s)",
                        fontSize = 13.sp,
                    )
                }
                Text(money(total, currency), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(if (open) "  ▲" else "  ▼", fontSize = 13.sp)
            }
            if (open) {
                Spacer(Modifier.height(6.dp))
                e.order.items.forEach { i ->
                    Row {
                        Text("${i.quantity}×", Modifier.width(40.dp), fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text(i.name)
                            if (i.modifiers.isNotEmpty()) Text(i.modifiers.joinToString(", "), fontSize = 13.sp)
                            if (i.notes.isNotBlank()) Text("“${i.notes}”", fontSize = 13.sp)
                        }
                        Text(money(i.price * i.quantity, currency))
                    }
                }
                Text("Order ID ${e.order.orderId}", fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
