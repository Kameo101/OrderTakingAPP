package com.ordertaking.app

import com.ordertaking.app.data.Ack
import com.ordertaking.app.data.AppJson
import com.ordertaking.app.data.MenuRepository
import com.ordertaking.app.data.ModifierOption
import com.ordertaking.app.data.Order
import com.ordertaking.app.data.OrderItem
import com.ordertaking.app.data.SubmitOrder
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
