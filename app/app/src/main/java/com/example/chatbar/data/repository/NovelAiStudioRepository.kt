package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.domain.image.NovelAiHistoryFoldPreference
import com.example.chatbar.domain.image.NovelAiHistoryImageDeleteResult
import com.example.chatbar.domain.image.NovelAiHistoryImageSelection
import com.example.chatbar.domain.image.NovelAiHistoryDeletionPolicy
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.NovelAiStudioDraft
import com.example.chatbar.domain.image.NovelAiStudioUndoDraft
import com.example.chatbar.domain.image.NovelAiGuidanceEditorCheckpoint
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.applyDesignedPromptPlan
import com.example.chatbar.domain.image.applyReversePromptPlan
import com.example.chatbar.domain.image.applyHistoryRecipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NovelAiStudioRepository(
    storage: JsonFileStorage,
    private val imageStorage: NovelAiImageStorage
) : NovelAiStudioStateRepository(storage) {
    suspend fun deleteHistoryImages(
        selections: List<NovelAiHistoryImageSelection>
    ): NovelAiHistoryImageDeleteResult = historyMutex.withLock {
        val uniqueSelections = selections.distinctBy { it.entryId to it.imagePath }
        require(uniqueSelections.isNotEmpty()) { "未选择历史图片" }
        val originals = storage.loadAll(HISTORY_ENTITY, NovelAiGenerationHistoryEntry.serializer())
            .associateBy(NovelAiGenerationHistoryEntry::id)
        val mutations = NovelAiHistoryDeletionPolicy.apply(originals.values.toList(), uniqueSelections)

        val staged = mutableListOf<com.example.chatbar.domain.image.NovelAiStagedImageDeletion>()
        try {
            withContext(Dispatchers.IO) {
                uniqueSelections.forEach { selection ->
                    imageStorage.stageImageDelete(selection.imagePath)?.let(staged::add)
                }
            }
            mutations.forEach { (entryId, remaining) ->
                if (remaining == null) {
                    storage.deleteEntity<NovelAiGenerationHistoryEntry>(HISTORY_ENTITY, entryId)
                } else {
                    storage.saveEntity(
                        HISTORY_ENTITY,
                        entryId,
                        remaining,
                        NovelAiGenerationHistoryEntry.serializer()
                    )
                }
            }
        } catch (error: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            mutations.keys.forEach { entryId ->
                originals[entryId]?.let { original ->
                    runCatching {
                        storage.saveEntity(
                            HISTORY_ENTITY,
                            entryId,
                            original,
                            NovelAiGenerationHistoryEntry.serializer()
                        )
                    }.exceptionOrNull()?.let(rollbackFailures::add)
                }
            }
            withContext(Dispatchers.IO) {
                staged.asReversed().forEach { deletion ->
                    if (!imageStorage.restoreStagedImage(deletion)) {
                        rollbackFailures += IllegalStateException("图片恢复失败：${deletion.original.name}")
                    }
                }
            }
            rollbackFailures.forEach(error::addSuppressed)
            throw error
        }

        val cleanupFailures = withContext(Dispatchers.IO) {
            staged.count { !imageStorage.commitStagedImageDelete(it) }
        }
        NovelAiHistoryImageDeleteResult(
            deletedCount = uniqueSelections.size,
            cleanupFailureCount = cleanupFailures
        )
    }

    companion object {
        private const val DRAFT_ENTITY = "novelai_studio_draft"
        private const val UNDO_ENTITY = "novelai_studio_history_undo"
        private const val GUIDANCE_CHECKPOINT_ENTITY = "novelai_studio_guidance_checkpoint"
        private const val HISTORY_ENTITY = "novelai_generation_history"
        private const val HISTORY_FOLD_ENTITY = "novelai_history_fold_preferences"
    }
}
