package com.ordertaking.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ordertaking.app.data.DeviceMode

@Composable
fun ModeSelectScreen(onPick: (DeviceMode) -> Unit) {
    AppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("What is this tablet for?", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can change this later in settings. All tablets must be on the same Wi-Fi.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ModeCard(
                        "🧾", "Cashier",
                        "Take orders at the counter and send them to the kitchen.",
                        Brand,
                    ) { onPick(DeviceMode.CASHIER) }
                    ModeCard(
                        "👨‍🍳", "Kitchen display",
                        "Show incoming orders as tickets for the cooks.",
                        Color(0xFF37474F),
                    ) { onPick(DeviceMode.KITCHEN) }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(emoji: String, title: String, body: String, color: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.widthIn(max = 300.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.White),
    ) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 56.sp, modifier = Modifier.size(80.dp), textAlign = TextAlign.Center)
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(body, textAlign = TextAlign.Center)
        }
    }
}
