package com.ordertaking.app

import com.ordertaking.app.data.Ack
import com.ordertaking.app.data.AppJson
import com.ordertaking.app.data.CancelOrder
import com.ordertaking.app.data.MenuRepository
import com.ordertaking.app.data.ModifierOption
import com.ordertaking.app.data.Order
import com.ordertaking.app.data.OrderItem
import com.ordertaking.app.data.OrderStatusUpdate
import com.ordertaking.app.data.ReadyList
import com.ordertaking.app.data.SetStatus
import com.ordertaking.app.data.SubmitOrder
import com.ordertaking.app.data.TicketStatus
import com.ordertaking.app.data.WireMessage
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class PayloadTest {
    private val order = Order(
        orderId = "ORD-98342",
        timestamp = "2026-10-01T08:40:00Z",
        origin = "Table 4",
        items = listOf(
            OrderItem("101", "Classic Cheeseburger", 2, notes = "No onions"),
            OrderItem("204", "Truffle Fries", 1),
        ),
    )

    @Test
    fun orderUsesSpecFieldNames() {
        val json = AppJson.parseToJsonElement(AppJson.encodeToString(Order.serializer(), order)).jsonObject
        assertEquals("ORD-98342", json["order_id"]!!.jsonPrimitive.content)
        assertEquals("Table 4", json["origin"]!!.jsonPrimitive.content)
        assertEquals("pending", json["status"]!!.jsonPrimitive.content)
        val first = json["items"]!!.jsonArray[0].jsonObject
        assertEquals("101", first["item_id"]!!.jsonPrimitive.content)
        assertEquals("2", first["quantity"]!!.jsonPrimitive.content)
        assertEquals("No onions", first["notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun specExamplePayloadDecodes() {
        val spec = """
            {"order_id":"ORD-98342","timestamp":"2026-10-01T08:40:00Z","origin":"Table 4",
             "items":[{"item_id":"101","name":"Classic Cheeseburger","quantity":2,"notes":"No onions"},
                      {"item_id":"204","name":"Truffle Fries","quantity":1,"notes":""}],
             "status":"pending"}
        """
        assertEquals(order, AppJson.decodeFromString(Order.serializer(), spec))
    }

    @Test
    fun wireMessagesRoundTrip() {
        val submit: WireMessage = SubmitOrder(order)
        val text = AppJson.encodeToString(WireMessage.serializer(), submit)
        assertEquals("SUBMIT_ORDER", AppJson.parseToJsonElement(text).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(submit, AppJson.decodeFromString(WireMessage.serializer(), text))

        val ack: WireMessage = Ack("ORD-1", 7)
        assertEquals(ack, AppJson.decodeFromString(WireMessage.serializer(), AppJson.encodeToString(WireMessage.serializer(), ack)))
    }

    @Test
    fun readyAndServedMessagesRoundTrip() {
        val ready = OrderStatusUpdate("ORD-1", 12, "Maria", TicketStatus.DONE, order.items, 1_700_000_000_000)
        val messages: List<WireMessage> = listOf(
            ready,
            ReadyList(listOf(ready)),
            SetStatus("ORD-1", TicketStatus.SERVED),
        )
        for (m in messages) {
            val text = AppJson.encodeToString(WireMessage.serializer(), m)
            assertEquals(m, AppJson.decodeFromString(WireMessage.serializer(), text))
        }
        val json = AppJson.parseToJsonElement(AppJson.encodeToString(WireMessage.serializer(), messages[2])).jsonObject
        assertEquals("SET_STATUS", json["type"]!!.jsonPrimitive.content)
        assertEquals("SERVED", json["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun cancelAndChangedOrdersRoundTrip() {
        val cancel: WireMessage = CancelOrder("ORD-1")
        val text = AppJson.encodeToString(WireMessage.serializer(), cancel)
        assertEquals("CANCEL_ORDER", AppJson.parseToJsonElement(text).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(cancel, AppJson.decodeFromString(WireMessage.serializer(), text))

        val changed: WireMessage = SubmitOrder(order.copy(orderId = "ORD-2", replaces = "ORD-1"))
        assertEquals(changed, AppJson.decodeFromString(WireMessage.serializer(), AppJson.encodeToString(WireMessage.serializer(), changed)))

        val cancelled: WireMessage = OrderStatusUpdate("ORD-1", 12, "Maria", TicketStatus.CANCELLED)
        assertEquals(cancelled, AppJson.decodeFromString(WireMessage.serializer(), AppJson.encodeToString(WireMessage.serializer(), cancelled)))
    }

    @Test
    fun statusUpdateFromOlderKitchenStillDecodes() {
        val old = """{"type":"ORDER_STATUS","order_id":"ORD-1","ticket_number":3,"origin":"Table 4","status":"DONE"}"""
        val msg = AppJson.decodeFromString(WireMessage.serializer(), old) as OrderStatusUpdate
        assertEquals(emptyList<OrderItem>(), msg.items)
        assertEquals(0L, msg.readyAtMillis)
    }

    @Test
    fun parsesOptionLines() {
        val opts = MenuRepository.parseOptions("Rare\n  Extra cheese +1.50 \nBacon + 2\nLarge +$1,25\n\nNo onions")
        assertEquals(
            listOf(
                ModifierOption("Rare"),
                ModifierOption("Extra cheese", 1.5),
                ModifierOption("Bacon", 2.0),
                ModifierOption("Large", 1.25),
                ModifierOption("No onions"),
            ),
            opts,
        )
        assertEquals(opts, MenuRepository.parseOptions(MenuRepository.formatOptions(opts)))
    }
}
