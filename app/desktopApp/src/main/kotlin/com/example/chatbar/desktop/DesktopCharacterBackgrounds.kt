package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.domain.card.PackagedImage
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Desktop-private preferences under entities: included in root moves/backups, never in Card packages. */
internal data class DesktopCharacterBackgroundLibrary(
    val images: List<String> = emptyList(), val preferred: String? = null,
)

internal fun decodeDesktopBackgroundLibrary(json: JsonObject): DesktopCharacterBackgroundLibrary = DesktopCharacterBackgroundLibrary(
    requireNotNull(json["images"]).jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content },
    requireNotNull(json["preferred"]).takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content,
).also { value ->
    require(value.images.distinct().size == value.images.size && (value.preferred == null || value.preferred in value.images))
    value.images.forEach { require(it.startsWith("images/") && !it.contains("..") && !it.contains('\\')) }
}

internal fun desktopEffectiveBackground(session: String?, preferred: String?, official: String?): Pair<String?, String> =
    when {
        !session.isNullOrBlank() -> session to "会话背景"
        !preferred.isNullOrBlank() -> preferred to "Desktop 首选角色背景"
        !official.isNullOrBlank() -> official to "角色卡跨平台背景"
        else -> null to "无背景"
    }

internal class DesktopCharacterBackgrounds(
    private val storage: JsonFileStorage,
    private val resources: DesktopCharacterResourceStore,
    private val coordinator: DesktopDataOperationCoordinator,
    private val cleanup: suspend (List<String>) -> Unit,
) {
    private val mutex = Mutex()
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    suspend fun load(id: String): DesktopCharacterBackgroundLibrary = when (val read = storage.readEntityStrict(KEY, id, JsonObject.serializer())) {
        EntityReadResult.Missing -> DesktopCharacterBackgroundLibrary()
        is EntityReadResult.Valid -> decodeDesktopBackgroundLibrary(read.value).also { value ->
            value.images.forEach { require(it.startsWith("images/")); resources.resolveOwnedReference(it) }
        }
        else -> error("Desktop 背景库不可读取；保留资源，使用角色卡背景")
    }
    suspend fun preferred(id: String): String? = load(id).preferred?.also { resources.readBytes(it) }
    private suspend fun save(id: String, value: DesktopCharacterBackgroundLibrary) = storage.saveEntity(KEY, id,
        buildJsonObject { put("images", JsonArray(value.images.map(::JsonPrimitive))); put("preferred", value.preferred?.let(::JsonPrimitive) ?: JsonNull) }, JsonObject.serializer())

    suspend fun import(id: String, bytes: ByteArray) = mutex.withLock {
        coordinator.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            val previous = load(id) // Strict authority before allocating anything.
            DesktopImageEditing.requireStatic(bytes)
            val normalized = DesktopImageEditing.png(DesktopImageEditing.decode(bytes))
            val path = resources.materializeImage(PackagedImage("background.png", Base64.getEncoder().encodeToString(normalized)),
                System.currentTimeMillis(), "p7background${UUID.randomUUID()}")
            // An indeterminate write retains its exact newly allocated resource.
            save(id, previous.copy(images = previous.images + path))
            revision.value++
        } }
    }
    suspend fun prefer(id: String, path: String?) = mutex.withLock {
        coordinator.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            val previous = load(id)
            require(path == null || path in previous.images)
            path?.let(resources::readBytes)
            save(id, previous.copy(preferred = path))
            revision.value++
        } }
    }
    suspend fun remove(id: String, path: String) = mutex.withLock {
        coordinator.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            val previous = load(id)
            require(path in previous.images)
            save(id, previous.copy(images = previous.images - path, preferred = previous.preferred.takeUnless { it == path }))
            revision.value++
        } }
        cleanup(listOf(path))
    }
    companion object { const val KEY = "desktop_character_backgrounds" }
}
