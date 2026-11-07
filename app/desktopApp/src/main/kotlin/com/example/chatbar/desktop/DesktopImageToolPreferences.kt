package com.example.chatbar.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize
import com.example.chatbar.data.local.JsonFileStorage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal val LocalDesktopImageToolPreferences = staticCompositionLocalOf<DesktopImageToolPreferences?> { null }

internal data class DesktopBrushPreferences(val sizeDp: Float = 36f, val type: String = "Mosaic", val saved: Boolean = false)

/** The portable preference is a displayed diameter; paint uses a source-short-side radius. */
internal fun desktopBrushRadius(sizeDp: Float, canvas: IntSize, density: Float): Float {
    require(sizeDp.isFinite() && density.isFinite() && density > 0)
    return (sizeDp.coerceIn(16f, 72f) * density / 2f / minOf(canvas.width, canvas.height).coerceAtLeast(1))
        .coerceIn(.0001f, .25f)
}

internal class DesktopImageToolPreferences(private val storage: JsonFileStorage,
    private val gate: DesktopDataOperationCoordinator) {
    private val mutex = Mutex()
    private val key = "desktop_backup_preferences"
    private fun project(value: JsonObject?): DesktopBrushPreferences {
        val size = value?.get("brushSize")?.let { (it as? JsonPrimitive)?.floatOrNull
            ?.takeIf { n -> n.isFinite() && n > 0 } ?: error("IMAGE_BRUSH_SIZE_INVALID") } ?: 36f
        val type = value?.get("brushType")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: error("IMAGE_BRUSH_TYPE_INVALID") } ?: "Mosaic"
        // Preserve an unknown transported type until the user explicitly chooses a supported one.
        return DesktopBrushPreferences(size.coerceIn(16f, 72f), type, value != null)
    }
    suspend fun load(): DesktopBrushPreferences = gate.withNormalOperation {
        mutex.withLock { project(storage.loadSingleton(key, JsonObject.serializer())) }
    }
    suspend fun save(sizeDp: Float? = null, type: String? = null) = gate.withNormalOperation {
        mutex.withLock {
            require(sizeDp == null || sizeDp.isFinite())
            require(type == null || type in setOf("Mosaic", "Black", "White"))
            val old = storage.loadSingleton(key, JsonObject.serializer())
            project(old) // A failed read/invalid value must not become a new default file.
            val next = JsonObject(old.orEmpty() + buildMap<String, JsonElement> {
                sizeDp?.let { put("brushSize", JsonPrimitive(it.coerceIn(16f, 72f))) }
                type?.let { put("brushType", JsonPrimitive(it)) }
            })
            withContext(NonCancellable) { storage.saveSingleton(key, next, JsonObject.serializer()) }
        }
    }
}
