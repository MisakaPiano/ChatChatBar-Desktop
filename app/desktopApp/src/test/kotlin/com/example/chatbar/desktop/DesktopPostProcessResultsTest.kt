package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopPostProcessResultsTest {
    private val input = DesktopImageEditing.png(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))
    private val output = DesktopImageEditing.png(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))

    @Test fun `durable paid records retain owned images through cleanup reopen invalid output and corrupt record refusal`() = runBlocking {
        val root = Files.createTempDirectory("post-process-results-")
        try {
            val gate = DesktopDataOperationCoordinator(); val storage = JsonFileStorage(root)
            val resources = DesktopCharacterResourceStore(root); val store = DesktopPostProcessResults(storage, resources, gate)
            store.load()
            val saved = store.persist(input, output, NovelAiPostProcessTab.UPSCALE, NovelAiEnhanceOptions())
            val bytes = Files.readAllBytes(root.resolve("entities/${DesktopPostProcessResults.KEY}/${saved.id}.json"))
            assertFails { store.persist(input, byteArrayOf(1, 2, 3), NovelAiPostProcessTab.UPSCALE, NovelAiEnhanceOptions()) }
            DesktopOwnedImageCleanup(root, resources, gate).deleteUnreferenced(listOf(saved.imagePath))
            val reopened = DesktopPostProcessResults(JsonFileStorage(root), resources, gate); reopened.load()
            assertEquals(listOf(saved), reopened.records.value); assertContentEquals(output, reopened.read(saved))
            assertContentEquals(bytes, Files.readAllBytes(root.resolve("entities/${DesktopPostProcessResults.KEY}/${saved.id}.json")))
            val broken = root.resolve("entities/${DesktopPostProcessResults.KEY}/broken.json")
            Files.writeString(broken, "{not-json")
            assertFails { reopened.load() }
            assertEquals(listOf(saved), reopened.records.value); assertContentEquals(output, resources.readBytes(saved.imagePath))
            assertEquals("{not-json", Files.readString(broken))
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `actual all light and excluded Studio history backups retain typed results and restore selected owned bytes`() = runBlocking {
        val base = Files.createTempDirectory("post-process-backup-")
        try {
            val root = Files.createDirectory(base.resolve("source")); val gate = DesktopDataOperationCoordinator()
            val resources = DesktopCharacterResourceStore(root)
            val saved = DesktopPostProcessResults(JsonFileStorage(root), resources, gate)
                .persist(input, output, NovelAiPostProcessTab.UPSCALE, NovelAiEnhanceOptions())
            val mapping = DesktopFullBackupMapping(root)
            val catalog = BackupExportCatalog.read(root.toFile(), mapping.inventory().keys.associateWith(root::resolve)) {}
            assertEquals(1, catalog.historyCount)
            val entity = "entities/${DesktopPostProcessResults.KEY}/${saved.id}.json"
            for (mode in listOf("all", "light", "off")) {
                val selection = when (mode) {
                    "all" -> catalog.allSelection()
                    "light" -> catalog.lightSelection()
                    else -> catalog.allSelection().copy(studioHistory = false)
                }
                val work = Files.createDirectory(base.resolve("export-$mode"))
                mapping.export(work, CURRENT_DESKTOP_BACKUP_CODE, selection = selection).use { snapshot ->
                    val file = base.resolve("$mode.cbbackup")
                    try { DesktopFullBackupWriter.export(snapshot.files, file, null, selection = selection) }
                    catch (failure: Exception) { throw AssertionError("Post-process backup mode=$mode", failure) }
                    DesktopFullBackupStaging.prepare(file, null, Files.createDirectory(base.resolve("inspect-$mode"))).use { staged ->
                        if (mode == "off") {
                            assertTrue(staged.entities.keys.none { it.contains(DesktopPostProcessResults.KEY) })
                            assertTrue(staged.files.keys.none { it.startsWith("images/") })
                        } else {
                            val record = staged.entities.getValue(entity)
                            assertEquals(mode == "all", staged.source.manifest.scope!!.studioHistoryImages)
                            if (mode == "light") {
                                assertTrue(record.getValue("imagePath").jsonPrimitive.content.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX))
                                assertTrue(staged.files.keys.none { it.startsWith("images/") })
                            }
                            val target = Files.createDirectory(base.resolve("target-$mode"))
                            DesktopFullBackupMapping(target).restoreMerged(staged.files, Files.createDirectory(base.resolve("restore-$mode")), BackupPreferences()).use { restored ->
                                for ((relative, source) in restored.files) {
                                    val destination = target.resolve(relative); Files.createDirectories(destination.parent)
                                    Files.copy(source, destination)
                                }
                                val reopened = DesktopPostProcessResults(JsonFileStorage(target), DesktopCharacterResourceStore(target), DesktopDataOperationCoordinator())
                                reopened.load(); val reloaded = reopened.records.value.single()
                                assertEquals(saved.sourceSha256, reloaded.sourceSha256)
                                assertEquals(saved.operation, reloaded.operation)
                                if (mode == "all") assertContentEquals(output, reopened.read(reloaded))
                                else assertFails { reopened.read(reloaded) }
                            }
                        }
                    }
                }
            }
            assertContentEquals(output, resources.readBytes(saved.imagePath))
        } finally { check(base.toFile().deleteRecursively()) }
    }
}
