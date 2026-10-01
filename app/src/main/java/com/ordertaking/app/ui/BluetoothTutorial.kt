package com.ordertaking.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val BluetoothBlue = Color(0xFF1E88E5)

private class TutorialStep(val title: String, val body: String, val picture: @Composable () -> Unit)

/**
 * Step-by-step guide shown when Bluetooth is switched on. [forKitchen] picks the wording for the
 * tablet it's shown on; [kitchenName] is the kitchen tablet's Bluetooth name, if known.
 */
@Composable
fun BluetoothTutorial(forKitchen: Boolean, kitchenName: String?, onClose: () -> Unit) {
    val kitchen = kitchenName ?: "Kitchen tablet"
    val steps = listOf(
        TutorialStep(
            "Turn on Bluetooth on both tablets",
            "Swipe down from the top of the screen and tap Bluetooth so it's on — on the kitchen tablet " +
                "and on ${if (forKitchen) "every cashier tablet" else "this cashier tablet"}.",
        ) {
            TabletPair(
                left = { BluetoothTile() },
                right = { BluetoothTile() },
            )
        },
        TutorialStep(
            "Pair the tablets",
            "On one tablet open Android Settings → Bluetooth (or Connected devices) → Pair new device, " +
                "and tap the other tablet's name. Check the same code shows on both screens, then tap Pair on both. " +
                "You only do this once.",
        ) {
            TabletPair(
                left = {
                    MockHeader("Bluetooth")
                    MockRow("+  Pair new device")
                    MockRow("Cashier tablet", highlighted = true)
                },
                right = {
                    Text("Pair with $kitchen?", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Code: 482 913", fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    MockButton("Pair")
                },
            )
        },
        TutorialStep(
            "Switch on Bluetooth in the kitchen",
            (if (forKitchen) "You've done this one: " else "On the kitchen tablet, ") +
                "tap ⚙ → Kitchen settings → turn on \"Bluetooth connection\". " +
                "If Android asks about \"Nearby devices\", tap Allow.",
        ) {
            SingleTablet("Kitchen") {
                MockHeader("Kitchen settings")
                MockSwitchRow("Bluetooth connection", highlighted = true)
                MockRow("Allow \"Nearby devices\"")
            }
        },
        TutorialStep(
            "Pick the kitchen on the cashier",
            (if (forKitchen) "On each cashier tablet, " else "On this tablet, ") +
                "open ⚙ Settings → turn on \"Connect by Bluetooth\" → tap \"$kitchen\" in the list of paired devices.",
        ) {
            SingleTablet("Cashier") {
                MockHeader("Settings")
                MockSwitchRow("Connect by Bluetooth")
                Row(
                    Modifier.fillMaxWidth().highlight(true).padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = true, onClick = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(kitchen, fontSize = 11.sp)
                }
            }
        },
        TutorialStep(
            "Check it's connected",
            "The cashier's top bar shows a green \"Kitchen connected\". The kitchen's top bar shows " +
                "\"Bluetooth: Ready\" and how many cashier tablets are connected. Send a test order to be sure.",
        ) {
            TabletPair(
                left = {
                    Text("Bluetooth: Ready", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("1 cashier tablet(s) connected", fontSize = 11.sp)
                },
                right = {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Brand).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF69F0AE)))
                        Spacer(Modifier.width(4.dp))
                        Text("Kitchen connected", fontSize = 11.sp, color = Color.White)
                    }
                },
            )
        },
    )

    var step by remember { mutableIntStateOf(0) }
    val current = steps[step]
    val last = step == steps.lastIndex

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Card(Modifier.padding(24.dp).widthIn(max = 600.dp)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BluetoothBadge(32.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Set up Bluetooth", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Step ${step + 1} of ${steps.size}", fontSize = 14.sp)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 190.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE3F2FD))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { current.picture() }
                Text(current.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(current.body, fontSize = 16.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    steps.indices.forEach { i ->
                        Box(
                            Modifier.padding(end = 6.dp).size(10.dp).clip(CircleShape)
                                .background(if (i <= step) BluetoothBlue else Color(0xFFBDBDBD)),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                    Spacer(Modifier.width(8.dp))
                    if (last) Button(onClick = onClose) { Text("Close") }
                    else Button(onClick = { step++ }) { Text("Next") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Little drawings of tablet screens for the tutorial
// ---------------------------------------------------------------------------

@Composable
private fun TabletPair(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MockTablet("Kitchen", left)
        Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BluetoothBadge(28.dp)
            Text("· · ·", color = BluetoothBlue, fontWeight = FontWeight.Bold)
        }
        MockTablet("Cashier", right)
    }
}

@Composable
private fun SingleTablet(label: String, content: @Composable ColumnScope.() -> Unit) {
    MockTablet(label, content, width = 240)
}

@Composable
private fun MockTablet(label: String, content: @Composable ColumnScope.() -> Unit, width: Int = 170) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF263238),
            modifier = Modifier.width(width.dp),
        ) {
            // Light theme inside, whatever screen the tutorial is shown on.
            AppTheme(dark = false) {
                Column(
                    Modifier.padding(8.dp).fillMaxWidth().heightIn(min = 110.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.White).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content,
                )
            }
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF37474F), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MockHeader(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MockRow(text: String, highlighted: Boolean = false) {
    Text(
        text, fontSize = 11.sp, color = Color.Black,
        modifier = Modifier.fillMaxWidth().highlight(highlighted).padding(4.dp),
    )
}

@Composable
private fun MockSwitchRow(text: String, highlighted: Boolean = false) {
    Row(Modifier.fillMaxWidth().highlight(highlighted).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 11.sp, color = Color.Black, modifier = Modifier.weight(1f))
        Switch(checked = true, onCheckedChange = null)
    }
}

@Composable
private fun MockButton(text: String) {
    Text(
        text, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.highlight(true).clip(RoundedCornerShape(50)).background(BluetoothBlue)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The quick-settings Bluetooth tile, switched on. */
@Composable
private fun BluetoothTile() {
    Row(
        Modifier.highlight(true).clip(RoundedCornerShape(16.dp)).background(BluetoothBlue).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(BluetoothIcon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Column {
            Text("Bluetooth", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Text("On", fontSize = 10.sp, color = Color.White)
        }
    }
}

@Composable
private fun BluetoothBadge(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(BluetoothBlue), contentAlignment = Alignment.Center) {
        Image(BluetoothIcon, null, Modifier.size(size * 0.65f))
    }
}

/** Amber outline around the thing to tap. */
private fun Modifier.highlight(on: Boolean) =
    if (on) border(BorderStroke(2.dp, CookingAmber), RoundedCornerShape(6.dp)) else this

/** The Bluetooth rune, in white. */
private val BluetoothIcon: ImageVector by lazy {
    ImageVector.Builder("Bluetooth", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(
            "M17.71 7.71L12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4.3-4.29 4.3-4.29z" +
                "M13 5.83l1.88 1.88L13 9.59V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z",
        ).toNodes(),
        fill = SolidColor(Color.White),
    ).build()
}
