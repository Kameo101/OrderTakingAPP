package com.ordertaking.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
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
    ) { emptyList() }

    private val _items = MutableStateFlow(store.load())
    val items: StateFlow<List<MenuItem>> = _items.asStateFlow()

    private val imageDir = File(context.filesDir, "images").apply { mkdirs() }

    init {
        if (!store.exists()) {
            store.save(_items.value)
        } else {
            // Earlier versions shipped example items. Remove any that were never edited.
            val examples = legacySampleMenu()
            val cleaned = _items.value.filterNot { it in examples }
            if (cleaned.size != _items.value.size) commit(cleaned)
        }
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

    /**
     * Copies a photo into private storage (so it survives the original being deleted),
     * shrunk to at most [MAX_IMAGE_SIDE] pixels and turned upright. Call off the main thread.
     */
    fun importImage(uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        var bitmap = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }!!

        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val rotation = runCatching {
            resolver.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        if (scale < 1f || rotation != 0f) {
            val m = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                postRotate(rotation)
            }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }

        val dest = File(imageDir, "${UUID.randomUUID()}.jpg")
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        dest.absolutePath
    }.onFailure { Log.e("MenuRepository", "Could not import image", it) }.getOrNull()

    /** A temporary file the camera app can save a new photo into. */
    fun newCameraUri(): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture.jpg").apply { delete() }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun commit(list: List<MenuItem>) {
        _items.value = list
        store.save(list)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)

        private const val MAX_IMAGE_SIDE = 1200

        /** Example items that version 1.0/1.1 put on new tablets; removed automatically if unedited. */
        fun legacySampleMenu(): List<MenuItem> = listOf(
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
