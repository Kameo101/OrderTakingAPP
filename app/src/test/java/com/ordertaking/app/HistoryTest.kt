package com.ordertaking.app

import com.ordertaking.app.data.HistoryEntry
import com.ordertaking.app.data.Order
import com.ordertaking.app.data.OrderHistory
import com.ordertaking.app.data.OrderItem
import com.ordertaking.app.data.Sales
import com.ordertaking.app.data.SalesPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryTest {
    private val utc = ZoneOffset.UTC
    private fun millis(date: String, hour: Int = 12) =
        LocalDate.parse(date).atTime(hour, 0).toInstant(utc).toEpochMilli()

    private fun entry(id: String, date: String, vararg items: OrderItem) =
        HistoryEntry(Order(id, "${date}T12:00:00Z", "Table 1", items.toList()), millis(date), 1)

    private val burger = OrderItem("101", "Burger", 2, listOf("Medium rare", "Extra cheese"), price = 13.5)
    private val fries = OrderItem("204", "Fries", 1, price = 6.0)

    @Test
    fun appendedOrdersSurviveReloadAndDamagedLines() {
        val f = File.createTempFile("history", ".jsonl").apply { deleteOnExit() }
        val h = OrderHistory(f)
        h.append(entry("A", "2026-10-01", burger))
        f.appendText("{\"order\": {\"order_id\": \"broken\"\n") // power cut mid-write
        h.append(entry("B", "2026-10-01", fries))
        h.append(entry("B", "2026-10-01", fries)) // duplicate is ignored

        val loaded = OrderHistory(f).loadAll()
        assertEquals(listOf("A", "B"), loaded.map { it.order.orderId })
        assertEquals(burger, loaded[0].order.items[0])
    }

    @Test
    fun cancelledOrdersAreKeptButNotCounted() {
        val f = File.createTempFile("history", ".jsonl").apply { deleteOnExit() }
        val h = OrderHistory(f)
        h.append(entry("A", "2026-10-01", burger))
        h.append(entry("B", "2026-10-01", fries))

        assertTrue(h.markCancelled("A"))
        assertFalse(h.markCancelled("A")) // already cancelled
        assertFalse(h.markCancelled("nope"))

        val loaded = OrderHistory(f).loadAll()
        assertEquals(listOf("A", "B"), loaded.map { it.order.orderId })
        assertTrue(loaded[0].cancelled)
        val s = Sales.summarize(loaded, utc)
        assertEquals(1, s.orders)
        assertEquals(6.0, s.revenue, 0.001)
        assertFalse(Sales.toCsv(loaded, utc).contains(",A,"))
    }

    @Test
    fun deleteRemovesOnlyThatOrderAndSurvivesReload() {
        val f = File.createTempFile("history", ".jsonl").apply { deleteOnExit() }
        val h = OrderHistory(f)
        h.append(entry("A", "2026-10-01", burger))
        h.append(entry("B", "2026-10-01", fries))
        h.append(entry("C", "2026-10-01", fries))

        assertTrue(h.delete("B"))
        assertEquals(listOf("A", "C"), OrderHistory(f).loadAll().map { it.order.orderId })
        assertFalse(h.delete("B")) // already gone
        assertFalse(h.delete("nope"))

        h.append(entry("D", "2026-10-01", burger)) // appending still works after a delete
        assertEquals(listOf("A", "C", "D"), OrderHistory(f).loadAll().map { it.order.orderId })
        assertEquals(13.5 * 2 + 6.0 + 13.5 * 2, Sales.summarize(h.loadAll(), utc).revenue, 0.001)
    }

    @Test
    fun summaryCountsItemsRevenueOptionsAndDays() {
        val entries = listOf(
            entry("A", "2026-09-30", burger, fries),
            entry("B", "2026-10-01", fries.copy(quantity = 3)),
            entry("C", "2026-10-01", burger.copy(quantity = 1, modifiers = listOf("Medium rare"))),
        )
        val s = Sales.summarize(entries, utc)
        assertEquals(3, s.orders)
        assertEquals(7, s.itemsSold)
        assertEquals(27.0 + 6.0 + 18.0 + 13.5, s.revenue, 0.001)
        assertEquals(listOf("Fries" to 4, "Burger" to 3), s.byItem.map { it.name to it.quantity })
        assertEquals(24.0, s.byItem[0].revenue, 0.001)
        assertEquals(listOf("Medium rare" to 3, "Extra cheese" to 2), s.byModifier)
        assertEquals(listOf(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-09-30")), s.byDay.map { it.date })
        assertEquals(2, s.byDay[0].orders)

        val today = Sales.filter(entries, SalesPeriod.TODAY, LocalDate.parse("2026-10-01"), utc)
        assertEquals(listOf("B", "C"), today.map { it.order.orderId })
        val yesterday = Sales.filter(entries, SalesPeriod.YESTERDAY, LocalDate.parse("2026-10-01"), utc)
        assertEquals(listOf("A"), yesterday.map { it.order.orderId })
    }

    @Test
    fun ordersFromBeforePricesExistStillLoad() {
        val old = """{"order":{"order_id":"OLD","timestamp":"t","origin":"Table 2","items":[{"item_id":"1","name":"Tea","quantity":1}]},"recordedAtMillis":0}"""
        val f = File.createTempFile("history", ".jsonl").apply { deleteOnExit(); writeText(old + "\n") }
        val e = OrderHistory(f).loadAll().single()
        assertEquals(0.0, e.order.items[0].price, 0.0)
    }

    @Test
    fun csvEscapesCommasAndQuotes() {
        val csv = Sales.toCsv(listOf(entry("A", "2026-10-01", burger.copy(notes = "No onions, \"well\" cut"))), utc)
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("2026-10-01 12:00:00,A,1,Table 1,,Burger,2,13.50,27.00,Medium rare; Extra cheese,"))
        assertTrue(lines[1].endsWith("\"No onions, \"\"well\"\" cut\""))
    }
}
