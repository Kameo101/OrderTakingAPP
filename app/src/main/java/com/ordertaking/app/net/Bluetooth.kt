package com.ordertaking.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

/** Fixed service id: the kitchen listens on it and cashiers connect to it. */
val KITCHEN_BT_UUID: UUID = UUID.fromString("6f1c2a4e-8d3b-4b7a-9e51-0c7d2f9a8b13")
const val KITCHEN_BT_SERVICE = "OrderTaking kitchen"

/** Runtime permission Bluetooth needs on Android 12+, or null on older versions (granted at install). */
val BLUETOOTH_PERMISSION: String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

data class PairedDevice(val name: String, val address: String)

fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter

fun hasBluetoothPermission(context: Context): Boolean =
    BLUETOOTH_PERMISSION == null ||
        ContextCompat.checkSelfPermission(context, BLUETOOTH_PERMISSION) == PackageManager.PERMISSION_GRANTED

/** This tablet's Bluetooth name, as other tablets see it when pairing. */
@SuppressLint("MissingPermission")
fun bluetoothName(context: Context): String? =
    if (!hasBluetoothPermission(context)) null else runCatching { bluetoothAdapter(context)?.name }.getOrNull()

/** Devices paired with this tablet in Android's Bluetooth settings. */
@SuppressLint("MissingPermission")
fun pairedDevices(context: Context): List<PairedDevice> {
    if (!hasBluetoothPermission(context)) return emptyList()
    return runCatching {
        bluetoothAdapter(context)?.bondedDevices.orEmpty()
            .map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())
}

/**
 * One Bluetooth connection between a cashier and the kitchen. Carries the same JSON
 * messages as the Wi-Fi link, one per line (the JSON itself never contains a newline).
 */
class BtLine(private val socket: BluetoothSocket) {
    private val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)

    @Volatile var isOpen = true
        private set

    fun send(text: String) {
        try {
            synchronized(writer) {
                writer.write(text)
                writer.write("\n")
                writer.flush()
            }
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    /** Reads messages until the connection drops. Blocks, so call it on its own thread. */
    fun readLoop(onMessage: (String) -> Unit) {
        try {
            socket.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { if (it.isNotBlank()) onMessage(it) }
        } catch (_: IOException) {
            // Connection dropped.
        } finally {
            close()
        }
    }

    fun close() {
        isOpen = false
        runCatching { socket.close() }
    }
}
