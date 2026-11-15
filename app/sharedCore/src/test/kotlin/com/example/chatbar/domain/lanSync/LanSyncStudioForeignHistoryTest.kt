package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.ForeignStudioEditHistoryCustody
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.domain.image.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.*

class LanSyncStudioForeignHistoryTest {
    private val draftKey = "novelai_studio_draft"
    private val journalKey = "desktop_studio_edit_history"
    private fun snapshot(storage: JsonFileStorage, draft: NovelAiStudioDraft) = draft.toEditHistorySnapshot(storage::retainStudioPayload)
    private suspend fun write(storage: JsonFileStorage, draft: NovelAiStudioDraft, journal: NovelAiStudioHistoryJournal) {
        storage.saveSingleton(draftKey, draft, NovelAiStudioDraft.serializer())
        storage.saveSingleton(journalKey, journal, NovelAiStudioHistoryJournal.serializer())
    }
    private fun newRoot() = Files.createTempDirectory("lan-foreign-studio-").toRealPath()
    private fun draft() = NovelAiStudioDraft(basePrompt = "合成电脑草稿", negativePrompt = "合成负面原文", contentRevision = 10,
        promptContentRevision = 10, updatedAt = 1, v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
            effort = NovelAiEffort.HIGH, sampler = NovelAiSampler.DDIM, transparentBackground = true))

    @Test fun importedTimelineSurvivesAndroidStyleStagingFlushReopenAndLargeVibeWithoutCreatingOrdinaryAndroidHistory() = runBlocking {
        val root = newRoot(); val storage = JsonFileStorage(root)
        try {
            val vibe = "synthetic-foreign-studio-vibe-".repeat(200)
            val ref = NovelAiInlinePayloadStore(root.toFile()).retain(vibe)
            val first = draft().copy(imageGuidance = NovelAiImageGuidanceDraft(vibes = listOf(NovelAiVibeReferenceDraft("scoped-vibe", encodedVibe = ref))))
            write(storage, first, NovelAiStudioHistoryJournal(NovelAiStudioHistoryTimeline(listOf(snapshot(storage, first)))))
            val repository = NovelAiStudioStateRepository(storage, preserveForeignEditHistory = true)
            repository.loadDraft()
            val before = Files.readAllBytes(root.resolve("entities/$draftKey.json"))
            val next = repository.stageDraft(resetPromptEditors = true) { it.copy(basePrompt = "合成手机编辑\n第二行") }
            assertContentEquals(before, Files.readAllBytes(root.resolve("entities/$draftKey.json")))
            assertEquals(next, repository.flushLatestDraft())
            val saved = requireNotNull(storage.loadSingleton(journalKey, NovelAiStudioHistoryJournal.serializer()))
            assertNull(saved.pending); assertEquals(listOf(snapshot(storage, first), snapshot(storage, next)), saved.committed.entries)
            assertEquals(1, saved.committed.cursor)
            val restarted = NovelAiStudioStateRepository(JsonFileStorage(root), preserveForeignEditHistory = true)
            assertEquals(next, restarted.loadDraft())
            val third = restarted.updateDraft(resetPromptEditors = true) { it.copy(extraPrompt = "后续编辑") }
            val final = requireNotNull(storage.loadSingleton(journalKey, NovelAiStudioHistoryJournal.serializer()))
            assertEquals(listOf(snapshot(storage, first), snapshot(storage, next), snapshot(storage, third)), final.committed.entries)
            assertEquals(first.v5Settings, third.v5Settings)
            final.committed.entries.forEach { value ->
                assertEquals(vibe, NovelAiInlinePayloadStore(root.toFile()).resolve(requireNotNull(value.imageGuidance.vibes.single().encodedVibe)))
            }
            val ordinaryRoot = newRoot(); val ordinaryStorage = JsonFileStorage(ordinaryRoot)
            try {
                val ordinary = NovelAiStudioStateRepository(ordinaryStorage, preserveForeignEditHistory = true)
                ordinary.loadDraft(); ordinary.updateDraft { it.copy(basePrompt = "仅有普通安卓草稿", negativePrompt = "合成负面") }
                assertFalse(Files.exists(ordinaryRoot.resolve("entities/$journalKey.json")))
            } finally { check(ordinaryRoot.toFile().deleteRecursively()) }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun interruptedForeignPublicationChoosesOnlyTheActualDraftAndReopensWithoutDuplicateHistory() = runBlocking {
        for (publish in listOf(false, true)) {
            val root = newRoot(); val storage = JsonFileStorage(root)
            try {
                val first = draft(); val next = first.copy(basePrompt = "合成写入目标", contentRevision = 11, promptContentRevision = 11)
                val old = NovelAiStudioHistoryTimeline(listOf(snapshot(storage, first)))
                write(storage, first, NovelAiStudioHistoryJournal(old))
                val custody = ForeignStudioEditHistoryCustody(storage)
                assertFailsWith<IOException> { custody.persist(next) {
                    if (publish) storage.saveSingleton(draftKey, next, NovelAiStudioDraft.serializer())
                    throw IOException("synthetic-publication-boundary")
                } }
                val retained = requireNotNull(storage.loadSingleton(journalKey, NovelAiStudioHistoryJournal.serializer()))
                assertNotNull(retained.pending)
                assertEquals(listOf(snapshot(storage, first), snapshot(storage, next)), retained.pending.entries)
                val outcome = custody.outcomeAfterFailure(next)
                assertIs<ForeignStudioEditHistoryCustody.Outcome.Proved>(outcome)
                assertEquals(if (publish) next else first, outcome.draft)
                assertEquals(publish, outcome.requestedWasPublished)
                val reopened = NovelAiStudioStateRepository(JsonFileStorage(root), preserveForeignEditHistory = true)
                assertEquals(outcome.draft, reopened.loadDraft()); reopened.flushLatestDraft()
                val recovered = requireNotNull(storage.loadSingleton(journalKey, NovelAiStudioHistoryJournal.serializer()))
                assertNull(recovered.pending)
                assertEquals(if (publish) 2 else 1, recovered.committed.entries.size)
                assertEquals(snapshot(storage, outcome.draft), recovered.committed.current())
            } finally { check(root.toFile().deleteRecursively()) }
        }
    }

    @Test fun mismatchedOrUnknownForeignEvidenceRefusesBeforeAnyWriteAndRetainsUnsavedText() = runBlocking {
        for (unknown in listOf(false, true)) {
            val root = newRoot(); val storage = JsonFileStorage(root)
            try {
                val first = draft(); val different = first.copy(basePrompt = "不能推测的实际草稿")
                write(storage, different, NovelAiStudioHistoryJournal(NovelAiStudioHistoryTimeline(listOf(snapshot(storage, first)))))
                val journal = root.resolve("entities/$journalKey.json")
                if (unknown) {
                    val raw = Files.readString(journal)
                    Files.writeString(journal, raw.trimEnd().dropLast(1) + ",\"futureOwnedField\":\"must remain\"}")
                }
                val originalDraft = Files.readAllBytes(root.resolve("entities/$draftKey.json")); val originalJournal = Files.readAllBytes(journal)
                val repository = NovelAiStudioStateRepository(storage, preserveForeignEditHistory = true); repository.loadDraft()
                assertFails { repository.updateDraft { it.copy(basePrompt = "保留的尚未保存输入") } }
                assertContentEquals(originalDraft, Files.readAllBytes(root.resolve("entities/$draftKey.json")))
                assertContentEquals(originalJournal, Files.readAllBytes(journal))
                assertEquals("保留的尚未保存输入", repository.draft.value?.basePrompt)
            } finally { check(root.toFile().deleteRecursively()) }
        }
    }

    @Test fun failedForeignWriteKeepsTextAlreadyStagedBeforeTheFailedOperation() = runBlocking {
        val root = newRoot(); val storage = JsonFileStorage(root)
        try {
            val first = draft()
            write(storage, first, NovelAiStudioHistoryJournal(NovelAiStudioHistoryTimeline(listOf(snapshot(storage, first)))))
            val repository = NovelAiStudioStateRepository(storage, preserveForeignEditHistory = true); repository.loadDraft()
            val staged = repository.stageDraft { it.copy(basePrompt = "必须保留的先前输入") }
            val file = root.resolve("entities/$draftKey.json"); val journal = root.resolve("entities/$journalKey.json")
            val bytes = Files.readAllBytes(file); val journalBytes = Files.readAllBytes(journal)
            val parent = file.parent
            assumeTrue("This physical write-denial probe requires POSIX file permissions", Files.getFileStore(parent).supportsFileAttributeView("posix"))
            val permissions = Files.getPosixFilePermissions(parent)
            try {
                Files.setPosixFilePermissions(parent, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
                assumeTrue("The runner must not bypass the physical write denial", !Files.isWritable(parent))
                assertFails { repository.updateDraft { it.copy(extraPrompt = "此次写入应失败") } }
                assertEquals(staged, repository.draft.value)
                assertContentEquals(bytes, Files.readAllBytes(file)); assertContentEquals(journalBytes, Files.readAllBytes(journal))
            } finally { Files.setPosixFilePermissions(parent, permissions) }
            repository.flushLatestDraft()
            assertEquals(staged, storage.loadSingleton(draftKey, NovelAiStudioDraft.serializer()))
            val history = requireNotNull(storage.loadSingleton(journalKey, NovelAiStudioHistoryJournal.serializer()))
            assertEquals(listOf(snapshot(storage, first), snapshot(storage, staged)), history.committed.entries)
            assertNull(history.pending)
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
