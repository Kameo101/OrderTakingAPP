package com.ordertaking.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ordertaking.app.App
import com.ordertaking.app.data.MenuItem
import com.ordertaking.app.data.ModifierOption
import com.ordertaking.app.data.OrderStatusUpdate
import com.ordertaking.app.net.LinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CashierScreen(onSwitchMode: () -> Unit, vm: CashierViewModel = viewModel()) {
    val app = App.instance
    LaunchedEffect(Unit) { app.cashierLink.start() }

    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    AppTheme {
        when {
            showHistory -> HistoryScreen(
                dark = false,
                scopeNote = "Orders taken on this tablet (the kitchen tablet has every order)",
                onBack = { showHistory = false },
            )
            showSettings -> CashierSettingsScreen(onBack = { showSettings = false }, onSwitchMode = onSwitchMode)
            else -> CashierMain(vm, onSettings = { showSettings = true }, onHistory = { showHistory = true })
        }
    }
}

@Composable
private fun CashierMain(vm: CashierViewModel, onSettings: () -> Unit, onHistory: () -> Unit) {
    val app = App.instance
    run {
        val menu by vm.menu.collectAsStateWithLifecycle()
        val cart by vm.cart.collectAsStateWithLifecycle()
        val linkState by vm.link.state.collectAsStateWithLifecycle()
        val outbox by vm.link.outbox.collectAsStateWithLifecycle()
        val ready by vm.link.ready.collectAsStateWithLifecycle()
        val currency = app.prefs.currencySymbol
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(15_000)
            }
        }

        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var optionsFor by remember { mutableStateOf<MenuItem?>(null) }
        var noteFor by remember { mutableStateOf<CartLine?>(null) }
        var sending by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            vm.link.acks.collect { snackbar.showSnackbar("✓ Kitchen received ticket #${it.ticketNumber}") }
        }
        LaunchedEffect(Unit) { vm.link.readyArrivals.collect { app.sounds.orderReady() } }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding()) {
                Column(Modifier.fillMaxSize()) {
                    CashierTopBar(linkState, outbox.size, onSettings, onHistory)
                    AnimatedVisibility(visible = ready.isNotEmpty()) {
                        ReadyStrip(ready, now) { order ->
                            vm.link.markServed(order)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                val result = snackbar.showSnackbar(
                                    "${order.origin} — handed out",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) vm.link.undoServed(order)
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val cartCounts = cart.groupBy { it.item.id }.mapValues { e -> e.value.sumOf { it.quantity } }
                        val onItemTap: (MenuItem) -> Unit = { item ->
                            if (item.modifierGroups.isEmpty()) vm.add(item) else optionsFor = item
                        }
                        val tray = @Composable { m: Modifier ->
                            TrayPane(
                                cart = cart,
                                currency = currency,
                                onInc = { vm.changeQuantity(it, 1) },
                                onDec = { vm.changeQuantity(it, -1) },
                                onNote = { noteFor = it },
                                onClear = vm::clear,
                                onSend = { sending = true },
                                modifier = m,
                            )
                        }
                        val width = maxWidth
                        if (width >= 700.dp) {
                            Row(Modifier.fillMaxSize()) {
                                MenuPane(menu, cartCounts, currency, onItemTap, onSettings, Modifier.weight(1f).fillMaxHeight())
                                tray(Modifier.width(if (width >= 1000.dp) 420.dp else 360.dp).fillMaxHeight())
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                MenuPane(menu, cartCounts, currency, onItemTap, onSettings, Modifier.weight(1.2f).fillMaxWidth())
                                tray(Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }

        optionsFor?.let { item ->
            ItemOptionsDialog(
                item = item,
                currency = currency,
                onDismiss = { optionsFor = null },
                onAdd = { mods, qty, notes ->
                    vm.add(item, mods, qty, notes)
                    optionsFor = null
                },
            )
        }

        noteFor?.let { line ->
            NoteDialog(
                title = "Note for ${line.item.name}",
                initial = line.notes,
                onDismiss = { noteFor = null },
                onSave = {
                    vm.setNotes(line.key, it)
                    noteFor = null
                },
            )
        }

        if (sending) {
            SendOrderDialog(
                onDismiss = { sending = false },
                onSend = { origin ->
                    sending = false
                    val online = linkState is LinkState.Connected
                    vm.send(origin)
                    if (!online) {
                        scope.launch {
                            snackbar.showSnackbar(
                                "Kitchen not reachable — order saved and will be sent automatically",
                                duration = SnackbarDuration.Long,
                            )
                        }
                    }
                },
            )
        }
    }
}

/** Orders the kitchen has finished, shown until a cashier hands them out. Hidden when empty. */
@Composable
private fun ReadyStrip(ready: List<OrderStatusUpdate>, now: Long, onHandedOut: (OrderStatusUpdate) -> Unit) {
    Surface(color = Color(0xFFE8F5E9), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 8.dp)) {
            Text(
                "🔔 Ready for pickup (${ready.size}) — call the name and hand it over",
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ready, key = { it.orderId }) { order -> ReadyCard(order, now) { onHandedOut(order) } }
            }
        }
    }
}

@Composable
private fun ReadyCard(order: OrderStatusUpdate, now: Long, onHandedOut: () -> Unit) {
    val minutes = if (order.readyAtMillis > 0) ((now - order.readyAtMillis) / 60_000).coerceAtLeast(0) else 0
    Card(
        modifier = Modifier.width(250.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(2.dp, if (minutes >= 10) CookingAmber else SendGreen),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.origin, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (order.ticketNumber > 0) Text("#${order.ticketNumber}", fontSize = 15.sp, color = Color.Gray)
            }
            Text(
                order.items.joinToString(" · ") { "${it.quantity}× ${it.name}" }.ifEmpty { "—" },
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(40.dp),
            )
            Text(
                if (minutes < 1) "Ready just now" else "Waiting $minutes min",
                fontSize = 12.sp,
                color = if (minutes >= 10) Color(0xFFE65100) else Color.Gray,
                fontWeight = if (minutes >= 10) FontWeight.Bold else FontWeight.Normal,
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onHandedOut,
                colors = ButtonDefaults.buttonColors(containerColor = SendGreen, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("✓ Handed out", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CashierTopBar(state: LinkState, queued: Int, onSettings: () -> Unit, onHistory: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = Color.White) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("New Order", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val (dot, label) = when (state) {
                is LinkState.Connected -> Color(0xFF69F0AE) to "Kitchen connected"
                is LinkState.Connecting -> Color(0xFFFFD740) to "Connecting to kitchen…"
                is LinkState.Searching -> Color(0xFFFFD740) to "Looking for kitchen…"
                is LinkState.Offline -> Color(0xFFFF5252) to "Kitchen offline"
            }
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.2f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                    if (queued > 0) Text("  ·  $queued waiting to send", fontWeight = FontWeight.Bold)
                }
            }
            TextButton(onClick = onHistory, modifier = Modifier.padding(start = 8.dp)) {
                Text("📊 Sales", color = Color.White, fontSize = 17.sp)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        }
    }
}

@Composable
private fun MenuPane(
    menu: List<MenuItem>,
    cartCounts: Map<String, Int>,
    currency: String,
    onTap: (MenuItem) -> Unit,
    onAddFirst: () -> Unit,
    modifier: Modifier,
) {
    val categories = remember(menu) { listOf("All") + menu.map { it.category }.distinct() }
    var selected by remember { mutableStateOf("All") }
    if (selected !in categories) selected = "All"
    val shown = if (selected == "All") menu else menu.filter { it.category == selected }

    Column(modifier) {
        if (menu.isNotEmpty()) LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories) { c ->
                FilterChip(
                    selected = c == selected,
                    onClick = { selected = c },
                    label = { Text(c, fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) },
                )
            }
        }
        if (menu.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your menu is empty", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Add the food and drinks you sell, with a photo and price for each.")
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onAddFirst, modifier = Modifier.height(56.dp)) {
                        Text("+ Add your first menu item", fontSize = 18.sp)
                    }
                }
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.id }) { item ->
                ItemCard(item, cartCounts[item.id] ?: 0, currency) { onTap(item) }
            }
        }
    }
}

@Composable
private fun ItemCard(item: MenuItem, inCart: Int, currency: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = item.available,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (inCart > 0) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box {
            Column {
                ItemImage(item, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                Column(Modifier.padding(10.dp)) {
                    Text(item.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.description.isNotBlank()) {
                        Text(
                            item.description, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (item.available) money(item.price, currency) else "SOLD OUT",
                        fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = if (item.available) MaterialTheme.colorScheme.primary else LateRed,
                    )
                }
            }
            if (inCart > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("$inCart", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun ItemImage(item: MenuItem, modifier: Modifier) {
    if (item.imagePath != null && File(item.imagePath).exists()) {
        AsyncImage(
            model = File(item.imagePath),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        val hues = listOf(0xFFEF6C00, 0xFF6D4C41, 0xFF2E7D32, 0xFF00838F, 0xFF5E35B1, 0xFFAD1457, 0xFF455A64)
        val color = Color(hues[Math.floorMod(item.name.hashCode(), hues.size)])
        Box(modifier.background(color), contentAlignment = Alignment.Center) {
            Text(
                item.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TrayPane(
    cart: List<CartLine>,
    currency: String,
    onInc: (String) -> Unit,
    onDec: (String) -> Unit,
    onNote: (CartLine) -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Order tray (${cart.sumOf { it.quantity }})",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                if (cart.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
            }
            if (cart.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Tap a menu item to add it",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(cart, key = { it.key }) { line ->
                        CartLineRow(line, currency, { onInc(line.key) }, { onDec(line.key) }, { onNote(line) })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row {
                Text("Total", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Text(money(cart.sumOf { it.total }, currency), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSend,
                enabled = cart.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = SendGreen, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(76.dp),
            ) { Text("SEND TO KITCHEN  ➜", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CartLineRow(line: CartLine, currency: String, onInc: () -> Unit, onDec: () -> Unit, onNote: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onNote)) {
                Text(line.item.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (line.modifiers.isNotEmpty()) {
                    Text(line.modifiers.joinToString(", ") { it.name }, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                }
                if (line.notes.isNotBlank()) {
                    Text("“${line.notes}”", fontSize = 13.sp, fontStyle = FontStyle.Italic, color = LateRed)
                } else {
                    Text("+ add note", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                Text(money(line.total, currency), fontSize = 13.sp)
            }
            StepperButton("−", onDec)
            Text("${line.quantity}", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepperButton("+", onInc)
        }
    }
}

@Composable
fun StepperButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        shape = CircleShape,
        modifier = Modifier.size(44.dp),
    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ItemOptionsDialog(
    item: MenuItem,
    currency: String,
    onDismiss: () -> Unit,
    onAdd: (List<ModifierOption>, Int, String) -> Unit,
) {
    // group index -> chosen option indexes
    val chosen = remember { mutableStateMapOf<Int, Set<Int>>() }
    var qty by remember { mutableIntStateOf(1) }
    var notes by remember { mutableStateOf("") }

    val selectedOptions = item.modifierGroups.flatMapIndexed { gi, g ->
        (chosen[gi] ?: emptySet()).sorted().map { g.options[it] }
    }
    val missing = item.modifierGroups.withIndex().any { (gi, g) -> g.required && chosen[gi].isNullOrEmpty() }
    val total = (item.price + selectedOptions.sumOf { it.price }) * qty

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                item.modifierGroups.forEachIndexed { gi, group ->
                    Text(
                        group.name + when {
                            group.required -> "  (required)"
                            group.multiSelect -> "  (choose any)"
                            else -> "  (optional)"
                        },
                        fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    group.options.forEachIndexed { oi, opt ->
                        val isOn = chosen[gi]?.contains(oi) == true
                        val toggle = {
                            val cur = chosen[gi] ?: emptySet()
                            chosen[gi] = when {
                                group.multiSelect -> if (isOn) cur - oi else cur + oi
                                isOn && !group.required -> emptySet()
                                else -> setOf(oi)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (group.multiSelect) Checkbox(isOn, { toggle() }) else RadioButton(isOn, { toggle() })
                            Text(opt.name, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (opt.price > 0) Text("+" + money(opt.price, currency))
                        }
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Special instructions (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    StepperButton("−") { if (qty > 1) qty-- }
                    Text("$qty", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    StepperButton("+") { qty++ }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(selectedOptions, qty, notes) }, enabled = !missing) {
                Text(if (missing) "Choose required options" else "Add to tray · ${money(total, currency)}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NoteDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("e.g. No onions, sauce on the side") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SendOrderDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val prefs = App.instance.prefs
    var name by remember { mutableStateOf("") }
    var staff by remember { mutableStateOf(prefs.staffName) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val origin = name.trim().ifEmpty { null }
    val send = {
        if (origin != null) {
            prefs.staffName = staff
            onSend(origin)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customer name", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    placeholder = { Text("e.g. Maria") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 24.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = staff, onValueChange = { staff = it },
                    label = { Text("Taken by (cashier, optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = send,
                enabled = origin != null,
                colors = ButtonDefaults.buttonColors(containerColor = SendGreen),
                modifier = Modifier.height(56.dp),
            ) { Text("SEND TO KITCHEN", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}
