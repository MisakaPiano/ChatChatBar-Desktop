package com.example.chatbar.lanSync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioRepository
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.lanSync.LanSyncOperationGate
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual Android owner and private synthetic roots; no UI, provider or network calls. */
class LanSyncStudioForeignOwnerTest {
    @Test fun actualAndroidOwnerPreservesImportedDesktopTimelineAcrossStagingFlushAndReopen(): Unit = runBlocking {
        withTimeout(20_000) {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "native-foreign-studio-").toRealPath()
            val context = object : ContextWrapper(app) { override fun getFilesDir() = root.toFile() }
            val storage = JsonFileStorage(root, LanSyncOperationGate())
            val payload = "synthetic-native-foreign-vibe-".repeat(300)
            val reference = NovelAiInlinePayloadStore(root.toFile()).retain(payload)
            assertTrue(reference.startsWith(NovelAiInlinePayloadStore.PREFIX))
            val first = NovelAiStudioDraft(basePrompt = "桌面合成原文\n第二行", negativePrompt = "合成负面原文",
                contentRevision = 10, promptContentRevision = 10, updatedAt = 1,
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
                    effort = NovelAiEffort.HIGH, sampler = NovelAiSampler.DDIM, transparentBackground = true),
                imageGuidance = NovelAiImageGuidanceDraft(vibes = listOf(
                    NovelAiVibeReferenceDraft("native-scoped-vibe", encodedVibe = reference))))
            fun snapshot(value: NovelAiStudioDraft) = value.toEditHistorySnapshot(storage::retainStudioPayload)
            storage.saveSingleton(DRAFT, first, NovelAiStudioDraft.serializer())
            storage.saveSingleton(JOURNAL, NovelAiStudioHistoryJournal(
                NovelAiStudioHistoryTimeline(listOf(snapshot(first)))), NovelAiStudioHistoryJournal.serializer())
            val repository = NovelAiStudioRepository(storage, NovelAiImageStorage(context))
            assertEquals(first, repository.loadDraft())
            val before = Files.readAllBytes(root.resolve("entities/$DRAFT.json"))
            val second = repository.stageDraft(resetPromptEditors = true) { it.copy(basePrompt = "手机原生编辑\n保留换行") }
            assertArrayEquals(before, Files.readAllBytes(root.resolve("entities/$DRAFT.json")))
            assertEquals(second, repository.flushLatestDraft())
            val committed = requireNotNull(storage.loadSingleton(JOURNAL, NovelAiStudioHistoryJournal.serializer()))
            assertNull(committed.pending)
            assertEquals(listOf(snapshot(first), snapshot(second)), committed.committed.entries)
            assertEquals(1, committed.committed.cursor)
            val reopenedStorage = JsonFileStorage(root, LanSyncOperationGate())
            val reopened = NovelAiStudioRepository(reopenedStorage, NovelAiImageStorage(context))
            assertEquals(second, reopened.loadDraft())
            val third = reopened.updateDraft(resetPromptEditors = true) { it.copy(extraPrompt = "重开后追加的合成内容") }
            val history = requireNotNull(reopenedStorage.loadSingleton(JOURNAL, NovelAiStudioHistoryJournal.serializer()))
            assertNull(history.pending)
            assertEquals(listOf(snapshot(first), snapshot(second), snapshot(third)), history.committed.entries)
            assertEquals(2, history.committed.cursor)
            assertEquals(snapshot(third), history.resolved(snapshot(third)).current())
            history.committed.entries.forEach { entry ->
                assertEquals(first.negativePrompt, entry.negativePrompt)
                assertEquals(first.v5Settings, entry.v5Settings)
                assertEquals("native-scoped-vibe", entry.imageGuidance.vibes.single().id)
                assertEquals(payload, NovelAiInlinePayloadStore(root.toFile()).resolve(
                    requireNotNull(entry.imageGuidance.vibes.single().encodedVibe)))
            }
            val ordinaryRoot = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "native-ordinary-studio-").toRealPath()
            val ordinaryContext = object : ContextWrapper(app) { override fun getFilesDir() = ordinaryRoot.toFile() }
            val ordinaryStorage = JsonFileStorage(ordinaryRoot, LanSyncOperationGate())
            val ordinary = NovelAiStudioRepository(ordinaryStorage, NovelAiImageStorage(ordinaryContext))
            ordinary.loadDraft()
            ordinary.updateDraft { it.copy(basePrompt = "普通安卓草稿") }
            assertFalse(Files.exists(ordinaryRoot.resolve("entities/$JOURNAL.json")))
        }
    }

    companion object {
        private const val DRAFT = "novelai_studio_draft"
        private const val JOURNAL = "desktop_studio_edit_history"
    }
}
