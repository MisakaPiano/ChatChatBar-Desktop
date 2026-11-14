package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.repository.CharacterRepository
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Legacy Desktop library is retained; new galleries live on the portable character card. */
internal data class DesktopCharacterBackgroundLibrary(
    val images: List<String> = emptyList(), val preferred: String? = null, val migrated: Boolean = false,
)

internal fun decodeDesktopBackgroundLibrary(json: JsonObject): DesktopCharacterBackgroundLibrary = DesktopCharacterBackgroundLibrary(
    requireNotNull(json["images"]).jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content },
    requireNotNull(json["preferred"]).takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content,
    json["migrated"]?.jsonPrimitive?.also { require(!it.isString && it.booleanOrNull != null) }?.boolean ?: false,
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
    private val characters: CharacterRepository? = null,
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
    suspend fun currentDefault(id: String, fallback: String?): String? =
        if (characters == null) fallback else characters.getById(id)?.chatBackground
    /** Read-only projection keeps old libraries portable without changing originals or default selection. */
    suspend fun exportBackgrounds(card: CharacterCard): List<String> = load(card.id).let {
        if (it.migrated) emptyList() else it.images
    }
    suspend fun catalog(id: String): DesktopCharacterBackgroundLibrary {
        val legacy = load(id)
        val card = characters?.getById(id) ?: return legacy
        return legacy.copy(images = (card.chatBackgroundImages + if (legacy.migrated) emptyList() else legacy.images).distinct())
    }
    private suspend fun updateCard(id: String, transform: (CharacterCard) -> CharacterCard): CharacterCard {
        var published: CharacterCard? = null
        storage.mutateEntitiesAtomicallyStrictFields("character_cards", setOf(id), CharacterCard.serializer()) { records ->
            val current = (records[id] as? EntityReadResult.Valid<CharacterCard>)?.value ?: error("角色卡不可读取")
            val next = transform(current).copy(updatedAt = System.currentTimeMillis())
            require(next.id == current.id)
            published = next
            mapOf(id to next)
        }
        requireNotNull(characters).refreshFromStorage()
        return requireNotNull(published)
    }
    private suspend fun migratedCard(id: String): CharacterCard {
        val repository = requireNotNull(characters)
        val card = requireNotNull(repository.getById(id)) { "角色卡不存在" }
        val old = load(id)
        if (old.migrated || old.images.isEmpty()) return card
        old.images.forEach(resources::readBytes)
        val next = updateCard(id) { it.copy(chatBackgroundImages = (it.chatBackgroundImages + old.images).distinct()) }
        // Publish card first. If this marker write is interrupted, read-only projection only repeats references.
        save(id, old.copy(migrated = true))
        return next
    }
    suspend fun migrate(id: String) = mutex.withLock {
        coordinator.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) { migratedCard(id); revision.value++ } }
    }
    suspend fun setDefault(id: String, path: String?) = mutex.withLock {
        coordinator.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            migratedCard(id)
            path?.let(resources::readBytes)
            updateCard(id) { card ->
                require(path == null || path in card.chatBackgroundImages || path == card.chatBackground)
                card.copy(chatBackground = path)
            }
            revision.value++
        } }
    }
    private suspend fun save(id: String, value: DesktopCharacterBackgroundLibrary) = storage.saveEntity(KEY, id,
        buildJsonObject { put("images", JsonArray(value.images.map(::JsonPrimitive))); put("preferred", value.preferred?.let(::JsonPrimitive) ?: JsonNull)
            if (value.migrated) put("migrated", true) }, JsonObject.serializer())

    suspend fun import(id: String, bytes: ByteArray): String = mutex.withLock {
        coordinator.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            val previous = load(id) // Strict authority before allocating anything.
            DesktopImageEditing.requireStatic(bytes)
            if (characters != null) {
                migratedCard(id)
                DesktopImageEditing.decode(bytes)
                val path = resources.materializeImage(PackagedImage("background.${desktopImageResourceExtension(bytes)}", Base64.getEncoder().encodeToString(bytes)),
                    System.currentTimeMillis(), "gallery${UUID.randomUUID()}")
                updateCard(id) { it.copy(chatBackgroundImages = it.chatBackgroundImages + path) }
                revision.value++
                return@withContext path
            }
            val normalized = DesktopImageEditing.png(DesktopImageEditing.decode(bytes))
            val path = resources.materializeImage(PackagedImage("background.png", Base64.getEncoder().encodeToString(normalized)),
                System.currentTimeMillis(), "p7background${UUID.randomUUID()}")
            // An indeterminate write retains its exact newly allocated resource.
            save(id, previous.copy(images = previous.images + path))
            revision.value++
            path
        } }
    }
    suspend fun prefer(id: String, path: String?) = mutex.withLock {
        coordinator.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            val previous = load(id)
            require(path == null || path in catalog(id).images || path in previous.images)
            path?.let(resources::readBytes)
            save(id, previous.copy(images = (previous.images + listOfNotNull(path)).distinct(), preferred = path,
                migrated = previous.migrated || (characters != null && previous.images.isEmpty())))
            revision.value++
        } }
    }
    suspend fun remove(id: String, path: String) = mutex.withLock {
        coordinator.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            val previous = load(id)
            if (characters != null) {
                migratedCard(id)
                updateCard(id) { card ->
                    require(path in card.chatBackgroundImages)
                    card.copy(chatBackgroundImages = card.chatBackgroundImages - path)
                }
                // Retained legacy assets/records remain recovery evidence; only the portable gallery selection changes.
                if (previous.preferred == path) save(id, load(id).copy(preferred = null))
            } else {
                require(path in previous.images)
                save(id, previous.copy(images = previous.images - path, preferred = previous.preferred.takeUnless { it == path }))
            }
            revision.value++
        } }
        cleanup(listOf(path))
    }
    companion object { const val KEY = "desktop_character_backgrounds" }
}
