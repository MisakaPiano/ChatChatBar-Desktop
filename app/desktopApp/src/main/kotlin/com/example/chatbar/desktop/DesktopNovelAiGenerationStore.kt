package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.JsonFileStorage.EntityReadResult
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import java.util.Base64
import kotlinx.coroutines.*

/** Shared history entity; a single durable record owns an entire new batch. */
internal class DesktopNovelAiGenerationStore(
    private val storage: JsonFileStorage,
    private val resources: DesktopCharacterResourceStore,
    private val gate: AppDataOperationGate,
) {
    suspend fun persist(images: List<ByteArray>, recipe: NovelAiGenerationRecipe): NovelAiGenerationHistoryEntry =
        gate.withNormalOperation { withContext(NonCancellable + Dispatchers.IO) {
            require(images.size == recipe.settings.count && images.size in 1..4)
            val entry = NovelAiGenerationHistoryEntry(recipe = recipe)
            check(storage.readEntityStrict(KEY, entry.id, NovelAiGenerationHistoryEntry.serializer()) == EntityReadResult.Missing)
            val paths = mutableListOf<String>()
            try {
                images.forEachIndexed { index, bytes ->
                    DesktopImageEditing.decode(bytes)
                    paths += resources.materializeImage(PackagedImage("result.png", Base64.getEncoder().encodeToString(bytes)),
                        entry.createdAt, "nai${entry.id}_$index")
                }
                val durable = entry.copy(images = novelAiHistoryImages(paths, recipe.settings.seed))
                storage.saveEntity(KEY, entry.id, durable, NovelAiGenerationHistoryEntry.serializer())
                durable
            } catch (failure: Throwable) {
                // Exact new candidates only. Unknown/corrupt authority is retained for recovery.
                when (val read = storage.readEntityStrict(KEY, entry.id, NovelAiGenerationHistoryEntry.serializer())) {
                    EntityReadResult.Missing -> paths.forEach(resources::deleteOwned)
                    is EntityReadResult.Valid -> paths.filterNot { path -> read.value.images.any { it.path == path } }
                        .forEach(resources::deleteOwned)
                    else -> Unit
                }
                throw failure
            }
        } }

    companion object { const val KEY = "novelai_generation_history" }
}
