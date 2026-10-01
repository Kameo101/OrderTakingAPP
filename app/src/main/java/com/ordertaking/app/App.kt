package com.ordertaking.app

import android.app.Application
import android.media.RingtoneManager
import com.ordertaking.app.data.AppPrefs
import com.ordertaking.app.data.MenuRepository
import com.ordertaking.app.data.OrderHistory
import com.ordertaking.app.net.CashierLink
import com.ordertaking.app.net.KitchenHub

/** Holds the app-wide singletons so they survive screen rotation. */
class App : Application() {
    lateinit var prefs: AppPrefs
        private set
    val menu by lazy { MenuRepository(this) }
    val history by lazy { OrderHistory(java.io.File(filesDir, "order_history.jsonl")) }
    val cashierLink by lazy { CashierLink(this, prefs, history) }
    val kitchenHub by lazy { KitchenHub(this, prefs, history) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs(this)
    }

    /** Plays the device's notification sound (new ticket in the kitchen, order ready at the counter). */
    fun chime() {
        runCatching {
            RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
