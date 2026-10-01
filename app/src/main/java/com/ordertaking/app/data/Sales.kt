package com.ordertaking.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ItemSales(val name: String, val quantity: Int, val revenue: Double)

data class DaySales(val date: LocalDate, val orders: Int, val items: Int, val revenue: Double)

data class SalesSummary(
    val orders: Int,
    val itemsSold: Int,
    val revenue: Double,
    /** Best sellers first. */
    val byItem: List<ItemSales>,
    /** Option picks such as "Medium rare" or "Extra cheese", most popular first. */
    val byModifier: List<Pair<String, Int>>,
    /** Newest day first. */
    val byDay: List<DaySales>,
) {
    val averageOrder: Double get() = if (orders == 0) 0.0 else revenue / orders
}

enum class SalesPeriod(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    ALL_TIME("All time");

    /** Inclusive start and end dates, or null for no limit. */
    fun range(today: LocalDate): Pair<LocalDate, LocalDate>? = when (this) {
        TODAY -> today to today
        YESTERDAY -> today.minusDays(1) to today.minusDays(1)
        LAST_7_DAYS -> today.minusDays(6) to today
        LAST_30_DAYS -> today.minusDays(29) to today
        ALL_TIME -> null
    }
}

object Sales {
    fun dateOf(entry: HistoryEntry, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(entry.recordedAtMillis).atZone(zone).toLocalDate()

    fun filter(
        entries: List<HistoryEntry>,
        period: SalesPeriod,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<HistoryEntry> {
        val (from, to) = period.range(today) ?: return entries
        return entries.filter { val d = dateOf(it, zone); !d.isBefore(from) && !d.isAfter(to) }
    }

    /** Cancelled orders are listed in the history but never counted. */
    fun summarize(all: List<HistoryEntry>, zone: ZoneId = ZoneId.systemDefault()): SalesSummary {
        val entries = all.filterNot { it.cancelled }
        val lines = entries.flatMap { it.order.items }
        val byItem = lines.groupBy { it.name }
            .map { (name, ls) -> ItemSales(name, ls.sumOf { it.quantity }, ls.sumOf { it.price * it.quantity }) }
            .sortedWith(compareByDescending<ItemSales> { it.quantity }.thenBy { it.name })
        val byModifier = lines.flatMap { l -> l.modifiers.map { it to l.quantity } }
            .groupBy({ it.first }, { it.second })
            .map { (name, qs) -> name to qs.sum() }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        val byDay = entries.groupBy { dateOf(it, zone) }
            .map { (date, es) ->
                val ls = es.flatMap { it.order.items }
                DaySales(date, es.size, ls.sumOf { it.quantity }, ls.sumOf { it.price * it.quantity })
            }
            .sortedByDescending { it.date }
        return SalesSummary(
            orders = entries.size,
            itemsSold = lines.sumOf { it.quantity },
            revenue = lines.sumOf { it.price * it.quantity },
            byItem = byItem,
            byModifier = byModifier,
            byDay = byDay,
        )
    }

    /** One row per item line; opens in Excel / Google Sheets. Cancelled orders are left out. */
    fun toCsv(entries: List<HistoryEntry>, zone: ZoneId = ZoneId.systemDefault()): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder("date_time,order_id,ticket,for,taken_by,item,quantity,unit_price,line_total,options,notes\n")
        for (e in entries.filterNot { it.cancelled }) {
            for (i in e.order.items) {
                sb.append(
                    listOf(
                        fmt.format(Instant.ofEpochMilli(e.recordedAtMillis)),
                        e.order.orderId,
                        if (e.ticketNumber > 0) e.ticketNumber.toString() else "",
                        e.order.origin,
                        e.order.takenBy,
                        i.name,
                        i.quantity.toString(),
                        "%.2f".format(java.util.Locale.US, i.price),
                        "%.2f".format(java.util.Locale.US, i.price * i.quantity),
                        i.modifiers.joinToString("; "),
                        i.notes,
                    ).joinToString(",") { esc(it) },
                ).append('\n')
            }
        }
        return sb.toString()
    }
}
