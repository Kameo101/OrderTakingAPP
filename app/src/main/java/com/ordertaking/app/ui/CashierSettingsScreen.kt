package com.ordertaking.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ordertaking.app.App
import com.ordertaking.app.data.MenuItem
import com.ordertaking.app.data.MenuRepository
import com.ordertaking.app.data.ModifierGroup
import com.ordertaking.app.net.BLUETOOTH_PERMISSION
import com.ordertaking.app.net.KITCHEN_PORT
import com.ordertaking.app.net.LinkState
import com.ordertaking.app.net.hasBluetoothPermission
import com.ordertaking.app.net.pairedDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CashierSettingsScreen(onBack: () -> Unit, onSwitchMode: () -> Unit) {
    val app = App.instance
    val prefs = app.prefs
    val menu by app.menu.items.collectAsStateWithLifecycle()
    val linkState by app.cashierLink.state.collectAsStateWithLifecycle()
    val outbox by app.cashierLink.outbox.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var host by remember { mutableStateOf(prefs.kitchenHost) }
    var staff by remember { mutableStateOf(prefs.staffName) }
    var currency by remember { mutableStateOf(prefs.currencySymbol) }
    var editing by remember { mutableStateOf<MenuItem?>(null) }
    var confirmSwitch by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    var bluetooth by remember { mutableStateOf(prefs.useBluetooth) }
    var btKitchen by remember { mutableStateOf(prefs.btKitchenAddress) }
    var btAllowed by remember { mutableStateOf(hasBluetoothPermission(app)) }
    var btTutorial by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(pairedDevices(app)) }
    val refreshPaired = {
        btAllowed = hasBluetoothPermission(app)
        paired = pairedDevices(app)
    }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPaired() }

    val leave = {
        prefs.staffName = staff
        prefs.currencySymbol = currency.ifBlank { "$" }
        onBack()
    }
    BackHandler(onBack = leave)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = Color.White) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SectionTitle("Kitchen connection") }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Connect by Bluetooth", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(
                                if (bluetooth) "On: orders go to the kitchen over Bluetooth instead of Wi-Fi."
                                else "Off: connects to the kitchen over Wi-Fi or a hotspot. Turn on if the Wi-Fi is giving trouble.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        Switch(checked = bluetooth, onCheckedChange = {
                            bluetooth = it
                            prefs.useBluetooth = it
                            app.cashierLink.reconnect()
                            if (it) btTutorial = true
                            if (it && !btAllowed && BLUETOOTH_PERMISSION != null) askBluetooth.launch(BLUETOOTH_PERMISSION)
                        })
                    }
                }
                item {
                    Text(
                        when (val s = linkState) {
                            is LinkState.Connected -> "✅ Connected to kitchen at ${s.host}"
                            is LinkState.Connecting -> "Connecting to ${s.host}…"
                            is LinkState.Searching ->
                                if (bluetooth) "Choose the kitchen tablet below." else "Searching the Wi-Fi for the kitchen tablet…"
                            is LinkState.Offline ->
                                if (bluetooth) "❌ Can't reach kitchen at ${s.host}. Check Bluetooth is on and the kitchen has Bluetooth switched on in its settings."
                                else "❌ Can't reach kitchen at ${s.host}"
                        },
                    )
                }
                if (bluetooth) {
                    if (!btAllowed) {
                        item {
                            Text("Bluetooth needs the \"Nearby devices\" permission.", color = LateRed)
                            OutlinedButton(onClick = { BLUETOOTH_PERMISSION?.let { askBluetooth.launch(it) } }) { Text("Allow Bluetooth") }
                        }
                    }
                    item {
                        Text(
                            "Kitchen tablet (pair it first in Android's Bluetooth settings, and turn on Bluetooth in the kitchen's settings):",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    items(paired, key = { it.address }) { device ->
                        Card(onClick = {
                            btKitchen = device.address
                            prefs.btKitchenAddress = device.address
                            prefs.btKitchenName = device.name
                            app.cashierLink.reconnect()
                        }) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = device.address == btKitchen, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(device.name, fontSize = 17.sp, modifier = Modifier.weight(1f))
                                Text(device.address, fontSize = 13.sp)
                            }
                        }
                    }
                    item {
                        if (btAllowed && paired.isEmpty()) Text("No paired Bluetooth devices yet.")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                            }) { Text("Open Bluetooth settings") }
                            OutlinedButton(onClick = refreshPaired) { Text("Refresh list") }
                            OutlinedButton(onClick = { btTutorial = true }) { Text("Show me how") }
                        }
                    }
                } else {
                    item {
                        Text(
                            "Leave the address blank to find the kitchen automatically. If that doesn't work, " +
                                "type the IP address shown at the top of the kitchen screen.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = host, onValueChange = { host = it },
                                label = { Text("Kitchen IP address (optional), e.g. 192.168.1.20") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = {
                                prefs.kitchenHost = host.trim().removePrefix("ws://").substringBefore(":$KITCHEN_PORT")
                                host = prefs.kitchenHost
                                app.cashierLink.reconnect()
                            }) { Text("Save & reconnect") }
                        }
                    }
                }
                if (outbox.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${outbox.size} order(s) waiting to be sent: " +
                                    outbox.joinToString { it.origin },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { confirmDiscard = true }) { Text("Discard", color = LateRed) }
                        }
                    }
                }

                item { SectionTitle("This tablet") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = staff, onValueChange = { staff = it },
                            label = { Text("Cashier name (shown on tickets)") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = currency, onValueChange = { currency = it.take(4) },
                            label = { Text("Currency") }, singleLine = true,
                            modifier = Modifier.width(120.dp),
                        )
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Menu (${menu.size} items)", Modifier.weight(1f))
                        Button(onClick = {
                            editing = MenuItem(id = MenuRepository.newId(), name = "", category = menu.lastOrNull()?.category ?: "Food")
                        }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add item")
                        }
                    }
                }
                items(menu, key = { it.id }) { item ->
                    Card(onClick = { editing = item }) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ItemImage(item, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(
                                    "${item.category} · ${money(item.price, prefs.currencySymbol)}" +
                                        if (item.modifierGroups.isNotEmpty()) " · ${item.modifierGroups.size} option group(s)" else "",
                                    fontSize = 13.sp,
                                )
                            }
                            Text(if (item.available) "Available" else "Sold out", fontSize = 13.sp)
                            Switch(
                                checked = item.available,
                                onCheckedChange = { app.menu.setAvailable(item.id, it) },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                item { SectionTitle("Device") }
                item {
                    OutlinedButton(onClick = { confirmSwitch = true }) { Text("Change what this tablet is used for…") }
                }
                item { Spacer(Modifier.height(48.dp)) }
            }
        }
    }

    editing?.let { item ->
        MenuItemEditor(
            initial = item,
            isNew = menu.none { it.id == item.id },
            categories = menu.map { it.category }.distinct(),
            onDismiss = { editing = null },
            onSave = { app.menu.upsert(it); editing = null },
            onDelete = { app.menu.delete(item.id); editing = null },
        )
    }

    if (btTutorial) {
        BluetoothTutorial(
            forKitchen = false,
            kitchenName = paired.find { it.address == btKitchen }?.name,
        ) {
            btTutorial = false
            refreshPaired() // they may have just paired the kitchen
        }
    }

    if (confirmSwitch) {
        AlertDialog(
            onDismissRequest = { confirmSwitch = false },
            title = { Text("Change tablet mode?") },
            text = { Text("You'll go back to the start screen to choose Cashier or Kitchen. The menu is kept.") },
            confirmButton = { Button(onClick = onSwitchMode) { Text("Change") } },
            dismissButton = { TextButton(onClick = { confirmSwitch = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsent orders?") },
            text = { Text("These orders have NOT reached the kitchen. They will be deleted and never sent.") },
            confirmButton = {
                Button(
                    onClick = { app.cashierLink.discardOutbox(); confirmDiscard = false },
                    colors = ButtonDefaults.buttonColors(containerColor = LateRed),
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 12.dp)) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

/** Editable copy of a modifier group; options are edited as one line of text per option. */
private class GroupDraft(name: String, multi: Boolean, required: Boolean, options: String) {
    var name by mutableStateOf(name)
    var multi by mutableStateOf(multi)
    var required by mutableStateOf(required)
    var options by mutableStateOf(options)
}

@Composable
private fun MenuItemEditor(
    initial: MenuItem,
    isNew: Boolean,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (MenuItem) -> Unit,
    onDelete: () -> Unit,
) {
    val app = App.instance
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var price by remember { mutableStateOf(if (isNew) "" else "%.2f".format(java.util.Locale.US, initial.price)) }
    var category by remember { mutableStateOf(initial.category) }
    var imagePath by remember { mutableStateOf(initial.imagePath) }
    val groups = remember {
        mutableStateListOf<GroupDraft>().apply {
            initial.modifierGroups.forEach {
                add(GroupDraft(it.name, it.multiSelect, it.required, MenuRepository.formatOptions(it.options)))
            }
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    // A photo imported in this editor but not saved yet; deleted if replaced or cancelled.
    fun setPhoto(path: String?) {
        imagePath?.takeIf { it != initial.imagePath }?.let { File(it).delete() }
        imagePath = path
    }
    fun import(uri: Uri) {
        importing = true
        scope.launch {
            val path = withContext(Dispatchers.IO) { app.menu.importImage(uri) }
            importing = false
            if (path != null) setPhoto(path)
            else Toast.makeText(context, "Couldn't use that photo", Toast.LENGTH_LONG).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) import(uri)
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = cameraUri
        if (taken && uri != null) import(uri)
    }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val cancel = {
        setPhoto(initial.imagePath)
        onDismiss()
    }

    val parsedPrice = price.replace(',', '.').toDoubleOrNull()
    val valid = name.isNotBlank() && parsedPrice != null && parsedPrice >= 0

    Dialog(
        onDismissRequest = cancel,
        // Don't lose a half-made item to a stray tap outside the editor; Cancel or Back closes it.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.95f).padding(vertical = 24.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(if (isNew) "New menu item" else "Edit menu item", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                                ItemImage(
                                    initial.copy(name = name.ifBlank { "?" }, imagePath = imagePath),
                                    Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)),
                                )
                                if (importing) CircularProgressIndicator(color = Color.White)
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, enabled = !importing, modifier = Modifier.width(160.dp)) { Text("🖼 Choose photo") }
                            if (hasCamera) {
                                OutlinedButton(onClick = {
                                    cameraUri = app.menu.newCameraUri().also { camera.launch(it) }
                                }, enabled = !importing, modifier = Modifier.width(160.dp)) { Text("📷 Take photo") }
                            }
                            if (imagePath != null) TextButton(onClick = { setPhoto(null) }) { Text("Remove photo", color = LateRed) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                name, { name = it }, label = { Text("Name") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    price, { price = it }, label = { Text("Price") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    isError = price.isNotEmpty() && parsedPrice == null,
                                    modifier = Modifier.width(140.dp),
                                )
                                OutlinedTextField(
                                    category, { category = it }, label = { Text("Category") }, singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (categories.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    categories.take(6).forEach { c ->
                                        TextButton(onClick = { category = c }) { Text(c, fontSize = 13.sp) }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        description, { description = it }, label = { Text("Description") },
                        minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )

                    Text("Options the cashier can pick (e.g. cook temperature, extras)", fontWeight = FontWeight.Bold)
                    groups.forEachIndexed { index, g ->
                        Card {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        g.name, { g.name = it }, label = { Text("Group name, e.g. Cook temperature") },
                                        singleLine = true, modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { groups.removeAt(index) }) { Icon(Icons.Default.Delete, "Remove group") }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(g.multi, { g.multi = it; if (it) g.required = false })
                                    Text(" Can pick several", Modifier.padding(end = 24.dp))
                                    Switch(g.required, { g.required = it; if (it) g.multi = false })
                                    Text(" Must pick one")
                                }
                                OutlinedTextField(
                                    g.options, { g.options = it },
                                    label = { Text("Options — one per line. Add a price like: Extra cheese +1.50") },
                                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = { groups.add(GroupDraft("", multi = false, required = false, options = "")) }) {
                        Icon(Icons.Default.Add, null)
                        Text(" Add option group")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete item", color = LateRed) }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = cancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = valid && !importing,
                        onClick = {
                            onSave(
                                initial.copy(
                                    name = name.trim(),
                                    description = description.trim(),
                                    price = parsedPrice ?: 0.0,
                                    category = category.trim().ifEmpty { "Other" },
                                    imagePath = imagePath,
                                    modifierGroups = groups.mapNotNull { g ->
                                        val opts = MenuRepository.parseOptions(g.options)
                                        if (g.name.isBlank() || opts.isEmpty()) null
                                        else ModifierGroup(g.name.trim(), g.multi, g.required, opts)
                                    },
                                ),
                            )
                        },
                    ) { Text("Save") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.name}?") },
            confirmButton = {
                Button(onClick = { setPhoto(initial.imagePath); onDelete() }, colors = ButtonDefaults.buttonColors(containerColor = LateRed)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
