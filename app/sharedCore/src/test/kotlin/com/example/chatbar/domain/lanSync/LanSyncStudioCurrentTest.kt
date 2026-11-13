package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.domain.image.*
import java.nio.file.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncStudioCurrentTest {
    private fun root() = Files.createTempDirectory("lan-studio-current-").toRealPath()
    private fun draft(text: String, model: String? = null) = NovelAiStudioDraft(basePrompt = text,
        negativePrompt = "合成负面原文", aiDesignModelId = model, contentRevision = 31, promptContentRevision = 29, updatedAt = 2)
    private fun record(type: String, body: JsonObject) = LanSyncRecord(type, "singleton", LanSyncScope.normalize(type, body))
    private fun records(value: NovelAiStudioDraft): List<LanSyncRecord> {
        val snapshot = value.copy(contentRevision = 0, promptContentRevision = 0, updatedAt = 0)
        return listOf(
            record(LanSyncStudioCurrent.DRAFT, lanSyncJson.encodeToJsonElement(NovelAiStudioDraft.serializer(), value).jsonObject),
            record(LanSyncStudioCurrent.UNDO, lanSyncJson.encodeToJsonElement(NovelAiStudioUndoDraft.serializer(), NovelAiStudioUndoDraft(snapshot.copy(basePrompt = "旧撤销"))).jsonObject),
            record(LanSyncStudioCurrent.CHECKPOINT, lanSyncJson.encodeToJsonElement(NovelAiGuidanceEditorCheckpoint.serializer(), NovelAiGuidanceEditorCheckpoint(value.imageGuidance)).jsonObject),
            record(LanSyncStudioCurrent.HISTORY, lanSyncJson.encodeToJsonElement(NovelAiStudioHistoryJournal.serializer(),
                NovelAiStudioHistoryJournal(NovelAiStudioHistoryTimeline(listOf(snapshot.copy(basePrompt = "旧撤销"), snapshot), 1))).jsonObject))
    }

    @Test fun oneExplicitChoiceKeepsAllFourRecordsTogetherAndLocalRevisionsNeverCreateConflicts() {
        val left = LanSyncSnapshot(deviceId = "pc", records = records(draft("电脑选择", "pc-model")))
        val right = left.copy(deviceId = "phone", records = records(draft("手机选择", "phone-model")))
        val baseline = LanSyncBaseline("phone")
        val conflict = LanSyncCompare.compare(left, right, baseline).conflicts.single()
        assertEquals(LanSyncStudioCurrent.UNIT, conflict.unit); assertFalse(conflict.canKeepBoth)
        for (choice in listOf(LanSyncChoice.KEEP_COMPUTER, LanSyncChoice.KEEP_PHONE)) {
            val result = LanSyncPlanner.plan(left, right, baseline, mapOf(conflict.unit to choice), UUID.randomUUID().toString())
            assertEquals(if (choice == LanSyncChoice.KEEP_COMPUTER) left.records else right.records, result.snapshot.records)
            LanSyncScope.validateGraph(result.snapshot)
        }
        assertFails { LanSyncPlanner.plan(left, right, baseline, mapOf(conflict.unit to LanSyncChoice.KEEP_BOTH), UUID.randomUUID().toString()) }
        val same = left.copy(deviceId = "phone", records = records(draft("电脑选择", "other-model").copy(contentRevision = 400, promptContentRevision = 390, updatedAt = 200)))
        assertEquals(left.hashes(), same.hashes())
        assertFails { LanSyncScope.validateGraph(left.copy(records = left.records.filterNot { it.type == LanSyncStudioCurrent.DRAFT })) }
        assertFails { LanSyncScope.validateGraph(left.copy(records = left.records.map { if (it.type == LanSyncStudioCurrent.DRAFT)
            it.copy(body = JsonObject(it.body + ("basePrompt" to JsonPrimitive("不匹配的草稿")))) else it })) }
    }

    @Test fun actualWalPublicationReloadAndReverseEditRetainNativeResourcesTimelineAndLocalModel() = runBlocking {
        val a = root(); val b = root(); val stateA = root(); val stateB = root()
        try {
            val storageA = JsonFileStorage(a); val storageB = JsonFileStorage(b)
            val image = a.resolve("images/guidance.png"); Files.createDirectories(image.parent); Files.write(image, LanSyncPreviewSample.image())
            val vibe = "合成跨端工作室大编码".repeat(800)
            val payload = NovelAiInlinePayloadStore(a.toFile()).retain(vibe)
            val original = draft("原草稿\n逐字保全", "pc-only-model").copy(imageGuidance = NovelAiImageGuidanceDraft(
                baseImage = NovelAiStudioAssetRef(id = "scoped-base", path = image.toString(), sha256 = lanSyncHash(Files.readAllBytes(image)), width = 32, height = 32),
                vibes = listOf(NovelAiVibeReferenceDraft("scoped-vibe", encodedVibe = payload))))
            val localBody = lanSyncJson.encodeToJsonElement(NovelAiStudioDraft.serializer(), original).jsonObject
            for (row in records(original)) storageA.saveSingleton(row.type,
                LanSyncStudioCurrent.restoreLocal(row.type, row.body, localBody), JsonObject.serializer())
            // Preserve real device-local IDs in the physical source, independently of wire projection.
            storageA.saveSingleton(LanSyncStudioCurrent.DRAFT, original, NovelAiStudioDraft.serializer())
            val owner = NovelAiStudioStateRepository(storageB, preserveForeignEditHistory = true)
            owner.loadDraft(); owner.updateDraft { it.copy(basePrompt = "原手机编辑", aiDesignModelId = "phone-only-model") }
            val wsA = LanSyncWorkspace(a, stateA, { Paths.get(it) })
            val wsB = LanSyncWorkspace(b, stateB, { Paths.get(it) })
            wsA.capture("pc").use { source -> wsB.capture("phone").use { before ->
                val plan = LanSyncPlan(UUID.randomUUID().toString(), source.snapshot, emptyMap())
                wsB.apply(plan, before.snapshot.hashes(), "phone", source.blobs)
                owner.reloadAfterLanSync()
                assertEquals(original.basePrompt, owner.draft.value?.basePrompt)
                assertEquals("phone-only-model", owner.draft.value?.aiDesignModelId)
                val imported = requireNotNull(owner.draft.value)
                assertContentEquals(Files.readAllBytes(image), Files.readAllBytes(Paths.get(requireNotNull(imported.imageGuidance.baseImage).path)))
                assertEquals(vibe, NovelAiInlinePayloadStore(b.toFile()).resolve(requireNotNull(imported.imageGuidance.vibes.single().encodedVibe)))
                owner.stageDraft { it.copy(extraPrompt = "手机后续输入") }; owner.prepareForLanSync()
                val history = requireNotNull(storageB.loadSingleton(LanSyncStudioCurrent.HISTORY, NovelAiStudioHistoryJournal.serializer()))
                assertEquals(3, history.committed.entries.size); assertNull(history.pending)
                wsB.capture("phone").use { returned -> wsA.capture("pc").use { existing ->
                    wsA.apply(LanSyncPlan(UUID.randomUUID().toString(), returned.snapshot, emptyMap()), existing.snapshot.hashes(), "pc", returned.blobs)
                    wsA.capture("pc").use { final -> assertEquals(returned.snapshot.hashes(), final.snapshot.hashes()) }
                } }
            } }
            val final = requireNotNull(storageA.loadSingleton(LanSyncStudioCurrent.DRAFT, NovelAiStudioDraft.serializer()))
            assertEquals("手机后续输入", final.extraPrompt); assertEquals("pc-only-model", final.aiDesignModelId)
        } finally { listOf(a, b, stateA, stateB).forEach { check(it.toFile().deleteRecursively()) } }
    }

    @Test fun preparationDoesNotInventDefaultsAndUncommittedEditorRefusesBeforeFlushingStagedText() = runBlocking {
        val root = root()
        try {
            val storage = JsonFileStorage(root); val owner = NovelAiStudioStateRepository(storage, preserveForeignEditHistory = true)
            owner.loadDraft(); owner.prepareForLanSync()
            assertFalse(Files.exists(root.resolve("entities/${LanSyncStudioCurrent.DRAFT}.json")))
            owner.stageDraft { it.copy(basePrompt = "尚未落盘的输入") }
            val editor = Any(); owner.registerLanSyncEditorGuard(editor) { true }
            assertFails { owner.prepareForLanSync() }
            assertFalse(Files.exists(root.resolve("entities/${LanSyncStudioCurrent.DRAFT}.json")))
            owner.unregisterLanSyncEditorGuard(editor); owner.prepareForLanSync()
            assertEquals("尚未落盘的输入", storage.loadSingleton(LanSyncStudioCurrent.DRAFT, NovelAiStudioDraft.serializer())?.basePrompt)
            val before = requireNotNull(owner.draft.value)
            owner.reloadAfterLanSync(); assertEquals(before, owner.draft.value)
            storage.saveSingleton(LanSyncStudioCurrent.DRAFT, before.copy(basePrompt = "对端选定的新草稿", contentRevision = 0, promptContentRevision = 0), NovelAiStudioDraft.serializer())
            owner.reloadAfterLanSync()
            assertTrue(requireNotNull(owner.draft.value).promptContentRevision > before.promptContentRevision)
            owner.flushLatestDraft()
            assertEquals("对端选定的新草稿", storage.loadSingleton(LanSyncStudioCurrent.DRAFT, NovelAiStudioDraft.serializer())?.basePrompt)
            assertFalse(Files.exists(root.resolve("entities/${LanSyncStudioCurrent.HISTORY}.json")))
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
