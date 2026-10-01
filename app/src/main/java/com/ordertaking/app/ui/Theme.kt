package com.ordertaking.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.Locale

val Brand = Color(0xFFD84315)
val SendGreen = Color(0xFF2E7D32)
val PendingBlue = Color(0xFF1565C0)
val CookingAmber = Color(0xFFFF8F00)
val LateRed = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574C),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
    surfaceVariant = Color(0xFFF5DED7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59F),
    onPrimary = Color(0xFF5F1600),
    background = Color(0xFF121416),
    surface = Color(0xFF121416),
    surfaceVariant = Color(0xFF263238),
    onSurfaceVariant = Color(0xFFECEFF1),
)

@Composable
fun AppTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

fun money(amount: Double, symbol: String): String = symbol + String.format(Locale.US, "%,.2f", amount)
