package com.ordertaking.app.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.KSerializer
import java.io.File
import java.time.LocalDate

/** Small JSON file wrapper with atomic writes so a crash never leaves a half-written file. */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    fun exists() = file.exists()

    fun load(): T = try {
        if (file.exists()) AppJson.decodeFromString(serializer, file.readText()) else default()
    } catch (e: Exception) {
        Log.e("JsonFileStore", "Could not read ${file.name}, using defaults", e)
        default()
    }

    @Synchronized
    fun save(value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

enum class DeviceMode { CASHIER, KITCHEN }

class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: DeviceMode?
        get() = sp.getString("mode", null)?.let { runCatching { DeviceMode.valueOf(it) }.getOrNull() }
        set(v) = sp.edit().putString("mode", v?.name).apply()

    /** Manual kitchen address. Blank = find the kitchen automatically on the Wi-Fi. */
    var kitchenHost: String
        get() = sp.getString("kitchen_host", "") ?: ""
        set(v) = sp.edit().putString("kitchen_host", v.trim()).apply()

    /** Last address the kitchen was reached at; tried first after a restart. */
    var lastKitchenHost: String
        get() = sp.getString("last_kitchen_host", "") ?: ""
        set(v) = sp.edit().putString("last_kitchen_host", v).apply()

    /** Cashier setting. On: talk to the kitchen over Bluetooth instead of Wi-Fi. */
    var useBluetooth: Boolean
        get() = sp.getBoolean("use_bluetooth", false)
        set(v) = sp.edit().putBoolean("use_bluetooth", v).apply()

    /** Bluetooth address and name of the kitchen tablet this cashier connects to. */
    var btKitchenAddress: String
        get() = sp.getString("bt_kitchen_address", "") ?: ""
        set(v) = sp.edit().putString("bt_kitchen_address", v).apply()

    var btKitchenName: String
        get() = sp.getString("bt_kitchen_name", "") ?: ""
        set(v) = sp.edit().putString("bt_kitchen_name", v).apply()

    /** Kitchen setting. On: also accept cashier tablets over Bluetooth (Wi-Fi keeps working). */
    var kitchenBluetooth: Boolean
        get() = sp.getBoolean("kitchen_bluetooth", false)
        set(v) = sp.edit().putBoolean("kitchen_bluetooth", v).apply()

    /** Name of the cashier using this tablet; printed on tickets as "taken by". */
    var staffName: String
        get() = sp.getString("staff_name", "") ?: ""
        set(v) = sp.edit().putString("staff_name", v.trim()).apply()

    var currencySymbol: String
        get() = sp.getString("currency", "$") ?: "$"
        set(v) = sp.edit().putString("currency", v).apply()

    /**
     * When this kitchen started tracking pickups (version 1.4). Orders finished before that
     * were already handed out the old way, so they never show as "ready for pickup".
     */
    val readyTrackingSince: Long
        @Synchronized get() = sp.getLong("ready_since", 0).takeIf { it > 0 }
            ?: System.currentTimeMillis().also { sp.edit().putLong("ready_since", it).apply() }

    /**
     * Kitchen setting. Off (default): the kitchen hands orders out, so DONE finishes the order.
     * On: DONE puts the order on the cashiers' "Ready for pickup" board until a cashier hands it out.
     */
    var cashierHandsOut: Boolean
        get() = sp.getBoolean("cashier_hands_out", false)
        set(v) = sp.edit().putBoolean("cashier_hands_out", v).apply()

    /** Ticket numbers restart at 1 every day. */
    @Synchronized
    fun nextTicketNumber(): Int {
        val today = LocalDate.now().toString()
        val n = if (sp.getString("ticket_day", null) == today) sp.getInt("ticket_counter", 0) + 1 else 1
        sp.edit().putString("ticket_day", today).putInt("ticket_counter", n).apply()
        return n
    }
}
