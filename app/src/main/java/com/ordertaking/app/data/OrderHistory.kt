package com.ordertaking.app.data

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

@Serializable
data class HistoryEntry(
    val order: Order,
    val recordedAtMillis: Long,
    val ticketNumber: Int = 0,
    /** Cancelled after it was sent; kept for the record but left out of sales. */
    val cancelled: Boolean = false,
)

/**
 * Permanent record of every order, one JSON object per line.
 *
 * Appending a line (and syncing it to disk) means an order is safe the moment it is recorded,
 * even if the app is closed or the tablet loses power right after. Nothing is ever pruned.
 */
class OrderHistory(private val file: File) {

    @Synchronized
    fun append(entry: HistoryEntry) {
        FileOutputStream(file, true).use { out ->
            out.write((AppJson.encodeToString(HistoryEntry.serializer(), entry) + "\n").toByteArray())
            out.fd.sync()
        }
    }

    /**
     * Removes one order (e.g. one entered by mistake). The file is rewritten to a temporary
     * copy first and then swapped in, so a power cut can't leave the history half-written.
     */
    @Synchronized
    fun delete(orderId: String): Boolean {
        if (!file.exists()) return false
        val lines = file.readLines()
        val keep = lines.filter { line ->
            line.isNotBlank() && runCatching {
                AppJson.decodeFromString(HistoryEntry.serializer(), line).order.orderId != orderId
            }.getOrDefault(true)
        }
        if (keep.size == lines.count { it.isNotBlank() }) return false
        return rewrite(keep)
    }

    /** Marks an order as cancelled. Returns false if it isn't in the history (or already cancelled). */
    @Synchronized
    fun markCancelled(orderId: String): Boolean {
        if (!file.exists()) return false
        var changed = false
        val lines = file.readLines().filter { it.isNotBlank() }.map { line ->
            val e = runCatching { AppJson.decodeFromString(HistoryEntry.serializer(), line) }.getOrNull()
            if (e != null && e.order.orderId == orderId && !e.cancelled) {
                changed = true
                AppJson.encodeToString(HistoryEntry.serializer(), e.copy(cancelled = true))
            } else line
        }
        return changed && rewrite(lines)
    }

    private fun rewrite(keep: List<String>): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(keep.joinToString("") { it + "\n" }.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return false
        }
        return true
    }

    /** All recorded orders, oldest first. A damaged line (e.g. power cut mid-write) is skipped. */
    @Synchronized
    fun loadAll(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        val seen = HashSet<String>()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { AppJson.decodeFromString(HistoryEntry.serializer(), line) }.getOrNull()
        }.filter { seen.add(it.order.orderId) }
    }
}
