package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopHistoryBatchExportTest {
    private class Fixture {
        val root = Files.createTempDirectory("ccb-batch-save-synthetic-")
        val data = Files.createDirectories(root.resolve("data"))
        val out = Files.createDirectory(root.resolve("out"))
        val gate = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(data, gate)
        val resources = DesktopCharacterResourceStore(data)
        val repo = DesktopStudioHistoryRepository(storage)
        val bytes = listOf(ByteArray(70000) { (it % 251).toByte() }, "second-original".toByteArray())
        val selected = listOf(NovelAiHistoryImageSelection("first", "images/first.png"), NovelAiHistoryImageSelection("second", "images/second.webp"))
        var picks = 0
        fun exporter(hook: suspend (Int) -> Unit = {}) = DesktopHistoryBatchExport(storage, resources, gate,
            DesktopDirectoryPicker { picks++; out }, hook)
        suspend fun seed() {
            Files.createDirectories(data.resolve("images"))
            selected.forEachIndexed { index, item ->
                Files.write(data.resolve(item.imagePath), bytes[index])
                repo.saveHistory(NovelAiGenerationHistoryEntry(id = item.entryId, createdAt = 1773000000123L + index,
                    images = listOf(NovelAiGenerationHistoryImage(item.imagePath, index.toLong()))))
            }
            repo.loadDraft()
            repo.updateDraft { it.copy(basePrompt = "synthetic untouched draft") }
        }
        fun outputs(): List<Path> = Files.list(out).use { it.toList() }
        fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun `explicit batch preview preserves order and names and requires overwrite or skip before original byte publication`() = runBlocking {
        val f = Fixture()
        try {
            f.seed(); val original = f.repo.history.first(); val draft = f.repo.loadDraft()
            val exporter = f.exporter()
            val defaultPlan = assertNotNull(exporter.prepare(f.selected.reversed()))
            assertTrue(defaultPlan.items.first().name.startsWith("CCBImage_")); assertTrue(defaultPlan.items.first().name.endsWith("_1.webp"))
            val plan = assertNotNull(exporter.prepare(f.selected, "  night:scene.png  "))
            assertEquals(listOf("night_scene_1.png", "night_scene_2.webp"), plan.items.map { it.name })
            assertTrue(f.outputs().isEmpty())
            val first = exporter.execute(plan, emptyMap())
            assertEquals(2, first.saved); assertEquals(0, first.skipped)
            plan.items.forEachIndexed { index, item -> assertContentEquals(f.bytes[index], Files.readAllBytes(f.out.resolve(item.name))) }
            Files.write(f.out.resolve(plan.items[0].name), "old-one".toByteArray())
            Files.write(f.out.resolve(plan.items[1].name), "old-two".toByteArray())
            val conflict = assertNotNull(exporter.prepare(f.selected, "night:scene"))
            assertEquals(2, conflict.conflicts.size)
            assertFailsWith<IllegalArgumentException> { exporter.execute(conflict, emptyMap()) }
            assertEquals("old-one", Files.readString(f.out.resolve(plan.items[0].name)))
            val result = exporter.execute(conflict, mapOf(f.selected[0] to DesktopHistoryBatchExport.Decision.OVERWRITE,
                f.selected[1] to DesktopHistoryBatchExport.Decision.SKIP))
            assertEquals(1, result.saved); assertEquals(1, result.skipped)
            assertContentEquals(f.bytes[0], Files.readAllBytes(f.out.resolve(plan.items[0].name)))
            assertEquals("old-two", Files.readString(f.out.resolve(plan.items[1].name)))
            assertNull(result.recoveryDirectory); assertEquals(2, f.outputs().size)
            assertEquals(original, f.repo.history.first()); assertEquals(draft, f.repo.loadDraft())
            f.selected.forEachIndexed { index, item -> assertContentEquals(f.bytes[index], Files.readAllBytes(f.data.resolve(item.imagePath))) }
        } finally { f.close() }
    }

    @Test fun `omitted and missing sources refuse the whole selection before chooser while stale source target and history previews refuse writes`() = runBlocking {
        val f = Fixture()
        try {
            f.seed(); val exporter = f.exporter()
            val omitted = NovelAiHistoryImageSelection("omitted", OMITTED_SAVE_SLOT_IMAGE_PREFIX + "synthetic")
            f.repo.saveHistory(NovelAiGenerationHistoryEntry(id = omitted.entryId, images = listOf(NovelAiGenerationHistoryImage(omitted.imagePath))))
            assertEquals("HISTORY_EXPORT_ORIGINAL_OMITTED", assertFailsWith<IllegalArgumentException> { exporter.prepare(f.selected + omitted) }.message)
            assertEquals(0, f.picks); assertTrue(f.outputs().isEmpty())
            Files.delete(f.data.resolve(f.selected[1].imagePath))
            assertFails { exporter.prepare(f.selected) }; assertEquals(0, f.picks); assertTrue(f.outputs().isEmpty())
            Files.write(f.data.resolve(f.selected[1].imagePath), f.bytes[1])
            val target = assertNotNull(exporter.prepare(f.selected, "target"))
            val foreign = f.out.resolve(target.items[1].name); Files.writeString(foreign, "foreign")
            assertFails { exporter.execute(target, emptyMap()) }; assertEquals(listOf(foreign), f.outputs())
            Files.delete(foreign)
            val source = assertNotNull(exporter.prepare(f.selected, "source"))
            Files.writeString(f.data.resolve(f.selected[1].imagePath), "source changed")
            assertFails { exporter.execute(source, emptyMap()) }; assertTrue(f.outputs().isEmpty())
            Files.write(f.data.resolve(f.selected[1].imagePath), f.bytes[1])
            val history = assertNotNull(exporter.prepare(f.selected, "history"))
            f.repo.saveHistory(f.repo.history.first().first { it.id == "second" }.copy(createdAt = 999))
            assertFails { exporter.execute(history, emptyMap()) }; assertTrue(f.outputs().isEmpty())
        } finally { f.close() }
    }

    @Test fun `failure and cancellation roll back new outputs and explicit replacements while foreign changes retain recovery originals`() = runBlocking {
        for (overwrite in listOf(false, true)) for (cancel in listOf(false, true)) {
            val f = Fixture()
            try {
                f.seed(); val before = f.repo.history.first(); val draft = f.repo.loadDraft()
                val exporter = f.exporter { if (cancel) throw CancellationException("synthetic-stop") else error("synthetic-failure") }
                val names = assertNotNull(exporter.prepare(f.selected, "rollback")).items.map { it.name }
                if (overwrite) names.forEach { Files.writeString(f.out.resolve(it), "old-$it") }
                val plan = assertNotNull(exporter.prepare(f.selected, "rollback"))
                val choices = plan.conflicts.associate { it.key to DesktopHistoryBatchExport.Decision.OVERWRITE }
                if (cancel) assertFailsWith<CancellationException> { exporter.execute(plan, choices) }
                else assertFailsWith<IllegalStateException> { exporter.execute(plan, choices) }
                assertEquals(if (overwrite) 2 else 0, f.outputs().size)
                if (overwrite) names.forEach { assertEquals("old-$it", Files.readString(f.out.resolve(it))) }
                assertEquals(before, f.repo.history.first()); assertEquals(draft, f.repo.loadDraft())
            } finally { f.close() }
        }
        val f = Fixture()
        try {
            f.seed(); val name = "foreign_1.png"; val target = f.out.resolve(name); Files.writeString(target, "retained-original")
            val exporter = f.exporter { Files.writeString(target, "foreign modification"); error("synthetic-publish-failure") }
            val plan = assertNotNull(exporter.prepare(f.selected, "foreign"))
            val refusal = assertFailsWith<DesktopHistoryBatchExport.Failure> { exporter.execute(plan, mapOf(f.selected[0] to DesktopHistoryBatchExport.Decision.OVERWRITE)) }
            assertEquals("foreign modification", Files.readString(target))
            assertTrue(Files.isDirectory(refusal.recoveryDirectory))
            assertTrue(Files.list(refusal.recoveryDirectory).use { files -> files.anyMatch { Files.readAllBytes(it).contentEquals("retained-original".toByteArray()) } })
            assertFalse(Files.exists(f.out.resolve("foreign_2.webp")))
        } finally { f.close() }
    }
}
