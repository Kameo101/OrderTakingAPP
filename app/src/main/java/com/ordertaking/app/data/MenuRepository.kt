package com.ordertaking.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

class MenuRepository(private val context: Context) {
    private val store = JsonFileStore(
        File(context.filesDir, "menu.json"),
        ListSerializer(MenuItem.serializer()),
    ) { sampleMenu() }

    private val _items = MutableStateFlow(store.load())
    val items: StateFlow<List<MenuItem>> = _items.asStateFlow()

    private val imageDir = File(context.filesDir, "images").apply { mkdirs() }

    init {
        if (!store.exists()) store.save(_items.value)
    }

    @Synchronized
    fun upsert(item: MenuItem) {
        val old = _items.value.find { it.id == item.id }
        if (old?.imagePath != null && old.imagePath != item.imagePath) File(old.imagePath).delete()
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id == item.id }
        if (i >= 0) list[i] = item else list.add(item)
        commit(list)
    }

    @Synchronized
    fun delete(id: String) {
        _items.value.find { it.id == id }?.imagePath?.let { File(it).delete() }
        commit(_items.value.filterNot { it.id == id })
    }

    @Synchronized
    fun setAvailable(id: String, available: Boolean) {
        commit(_items.value.map { if (it.id == id) it.copy(available = available) else it })
    }

    /** Copies a picked photo into private storage so it survives the gallery copy being deleted. */
    fun importImage(uri: Uri): String? = runCatching {
        val dest = File(imageDir, "${UUID.randomUUID()}.img")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        dest.absolutePath
    }.getOrNull()

    private fun commit(list: List<MenuItem>) {
        _items.value = list
        store.save(list)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)

        fun sampleMenu(): List<MenuItem> = listOf(
            MenuItem(
                id = "101", name = "Classic Cheeseburger", price = 12.50, category = "Mains",
                description = "Beef patty, cheddar, lettuce, tomato, house sauce on a brioche bun.",
                modifierGroups = listOf(
                    ModifierGroup(
                        "Cook temperature", multiSelect = false, required = true,
                        options = listOf("Rare", "Medium rare", "Medium", "Well done").map { ModifierOption(it) },
                    ),
                    ModifierGroup(
                        "Extras", multiSelect = true,
                        options = listOf(
                            ModifierOption("Extra cheese", 1.00),
                            ModifierOption("Bacon", 2.00),
                            ModifierOption("No onions"),
                            ModifierOption("No pickles"),
                        ),
                    ),
                ),
            ),
            MenuItem(
                id = "102", name = "Margherita Pizza", price = 14.00, category = "Mains",
                description = "San Marzano tomato, fresh mozzarella, basil.",
                modifierGroups = listOf(
                    ModifierGroup(
                        "Add toppings", multiSelect = true,
                        options = listOf(
                            ModifierOption("Mushrooms", 1.50),
                            ModifierOption("Pepperoni", 2.00),
                            ModifierOption("Olives", 1.00),
                        ),
                    ),
                ),
            ),
            MenuItem(
                id = "103", name = "Caesar Salad", price = 9.50, category = "Mains",
                description = "Romaine, parmesan, croutons, Caesar dressing.",
                modifierGroups = listOf(
                    ModifierGroup(
                        "Add protein", multiSelect = false,
                        options = listOf(ModifierOption("Grilled chicken", 3.00), ModifierOption("Shrimp", 4.00)),
                    ),
                ),
            ),
            MenuItem(
                id = "204", name = "Truffle Fries", price = 6.00, category = "Sides",
                description = "Hand-cut fries, truffle oil, parmesan.",
            ),
            MenuItem(
                id = "205", name = "Onion Rings", price = 5.00, category = "Sides",
                description = "Beer-battered with chipotle mayo.",
            ),
            MenuItem(
                id = "301", name = "Lemonade", price = 3.50, category = "Drinks",
                description = "Fresh squeezed.",
                modifierGroups = listOf(
                    ModifierGroup(
                        "Size", multiSelect = false, required = true,
                        options = listOf(ModifierOption("Regular"), ModifierOption("Large", 1.00)),
                    ),
                ),
            ),
            MenuItem(id = "302", name = "Iced Tea", price = 3.00, category = "Drinks", description = "Unsweetened."),
            MenuItem(
                id = "401", name = "Chocolate Brownie", price = 5.50, category = "Desserts",
                description = "Warm, with vanilla ice cream.",
            ),
        )

        private val priceSuffix = Regex("""^(.*?)\s*\+\s*\D?\s*(\d+(?:[.,]\d{1,2})?)\s*$""")

        /** "Extra cheese +1.50" -> ModifierOption("Extra cheese", 1.5). One option per line. */
        fun parseOptions(text: String): List<ModifierOption> = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val m = priceSuffix.matchEntire(line)
                if (m != null && m.groupValues[1].isNotBlank()) {
                    ModifierOption(m.groupValues[1].trim(), m.groupValues[2].replace(',', '.').toDouble())
                } else {
                    ModifierOption(line)
                }
            }

        fun formatOptions(options: List<ModifierOption>): String = options.joinToString("\n") {
            if (it.price > 0) "${it.name} +${"%.2f".format(java.util.Locale.US, it.price)}" else it.name
        }
    }
}
