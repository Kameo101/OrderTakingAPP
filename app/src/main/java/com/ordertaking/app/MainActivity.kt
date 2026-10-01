package com.ordertaking.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.ordertaking.app.data.DeviceMode
import com.ordertaking.app.ui.CashierScreen
import com.ordertaking.app.ui.KitchenScreen
import com.ordertaking.app.ui.ModeSelectScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both screens sit on a counter all day; never let them go to sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = App.instance
        setContent {
            var mode by remember { mutableStateOf(app.prefs.mode) }
            val switchMode = {
                app.cashierLink.stop()
                app.kitchenHub.stop()
                app.prefs.mode = null
                mode = null
            }
            SideEffect {
                // Dark status-bar icons on the light cashier screens, light icons on the dark kitchen screen.
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                    mode != DeviceMode.KITCHEN
            }
            when (mode) {
                null -> ModeSelectScreen { picked ->
                    app.prefs.mode = picked
                    mode = picked
                }
                DeviceMode.CASHIER -> CashierScreen(onSwitchMode = switchMode)
                DeviceMode.KITCHEN -> KitchenScreen(onSwitchMode = switchMode)
            }
        }
    }
}
