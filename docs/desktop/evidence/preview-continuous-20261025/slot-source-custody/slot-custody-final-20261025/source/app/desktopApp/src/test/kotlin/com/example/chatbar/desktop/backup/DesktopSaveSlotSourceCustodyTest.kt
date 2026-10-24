package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.desktop.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopSaveSlotSourceCustodyTest {
    private suspend fun fixture(action: suspend (Path, Path, Path, ByteArray) -> Unit) {
        val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/slot-custody").toAbsolutePath()), "case-")
        val root = Files.createDirectory(base.resolve("root"))
        val acquired = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired
        acquired.ownership.use {
            val source = SyntheticSaveSlotCorpus.archive(base.resolve("source.cbsave"))
            val slot = SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("synthetic.cbsave", 1, byteLength = Files.size(source)))
            val raw = (" \n" + backupJson.encodeToJsonElement(SaveSlot.serializer(), slot).toString() + "\n ").toByteArray(Charsets.UTF_8)
            action(base, root, source, raw)
        }
    }
    private suspend fun preserve(root: Path, source: Path, raw: ByteArray, beforePublish: suspend () -> Unit = {}) =
        DesktopSaveSlotSourceCustody.preserve(root, "entities/save_slots/slot.json", raw, source, "a".repeat(64), 84, 1, null, beforePublish)
    private suspend fun hashes(root: Path): Map<String, String> {
        val result = linkedMapOf<String, String>()
        Files.walk(root).use { stream -> for (p in stream.filter { Files.isRegularFile(it) }.toList()) {
            result[root.relativize(p).joinToString("/")] = DesktopArchiveFiles.hash(p, DesktopSaveSlotArchive.MAX_ARCHIVE)
        } }
        return result
    }

    @Test fun `raw outer record original package snapshot and Desktop full backup remain exact`() = runBlocking<Unit> {
        fixture { base, root, source, raw ->
            val before = Files.readAllBytes(source); val seal = preserve(root, source, raw)
            val home = root.resolve("${DesktopSaveSlotSourceCustody.ROOT}/$seal")
            assertContentEquals(raw, Files.readAllBytes(home.resolve("record.json"))); assertContentEquals(before, Files.readAllBytes(home.resolve("source.cbsave")))
            val expected = hashes(root.resolve(DesktopSaveSlotSourceCustody.ROOT))
            assertEquals(seal, preserve(root, source, raw)); assertEquals(expected, hashes(root.resolve(DesktopSaveSlotSourceCustody.ROOT)))
            val snapshotService = AppDataSnapshotService(root); val snapshot = snapshotService.createSnapshot()
            assertTrue(snapshotService.validateSnapshot(snapshot.directory).valid)
            assertEquals(expected, hashes(snapshot.directory.resolve("payload/${DesktopSaveSlotSourceCustody.ROOT}")))
            val work = Files.createDirectory(base.resolve("work")); val target = Files.createDirectory(base.resolve("target"))
            DesktopFullBackupMapping(root).export(work).use { export ->
                assertTrue(export.files.keys.all(DesktopBackupPolicy::isDesktopOnly))
                val archive = DesktopFullBackupArchive(work); val output = base.resolve("synthetic.cbbackup")
                archive.export(export.files, output, "synthetic", null)
                archive.inspect(output, null).use { inspected -> DesktopFullBackupMapping(target).restore(inspected).use { restored ->
                    DesktopFullBackupGraphValidation.validate(restored.directory)
                    assertEquals(expected, hashes(restored.directory.resolve(DesktopSaveSlotSourceCustody.ROOT)))
                } }
            }
            assertContentEquals(before, Files.readAllBytes(source))
        }
    }

    @Test fun `changed source bytes unknown index fields and extra files refuse entire inventory`() = runBlocking<Unit> {
        for (variant in 0..2) fixture { _, root, source, raw ->
            val seal = preserve(root, source, raw); val home = root.resolve("${DesktopSaveSlotSourceCustody.ROOT}/$seal")
            when (variant) {
                0 -> Files.write(home.resolve("source.cbsave"), byteArrayOf(1,2,3))
                1 -> Files.writeString(home.resolve("index.json"), Files.readString(home.resolve("index.json")).dropLast(1) + ",\"future\":true}")
                else -> Files.writeString(home.resolve("unexpected.txt"), "Synthetic")
            }
            assertFails { DesktopSaveSlotSourceCustody.validate(root) }
            assertFails { DesktopFullBackupMapping(root).inventory() }
        }
    }

    @Test fun `cancelled custody remains unpublished and never deletes original source`() = runBlocking<Unit> {
        fixture { _, root, source, raw ->
            val before = Files.readAllBytes(source)
            assertFailsWith<CancellationException> { preserve(root, source, raw) { throw CancellationException("Synthetic") } }
            assertContentEquals(before, Files.readAllBytes(source))
            assertFails { DesktopSaveSlotSourceCustody.validate(root) }
            assertFails { preserve(root, source, raw) }
            val domain = root.resolve(DesktopSaveSlotSourceCustody.ROOT)
            assertTrue(Files.walk(domain).use { stream -> stream.anyMatch { it.fileName.toString() == "index.part" } })
        }
    }

    @Test fun `whole root graph uses existing explicit historical SaveSlot admission`() = runBlocking<Unit> {
        fixture { base, root, _, _ ->
            val voice = GeneratedVoiceMessage(id = "voice", sessionId = "s", messageId = "m", messageVersionId = "pruned-version", anchorId = null,
                sourceOrder = 1, sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic", sourceText = "Old synthetic", taggedText = "Old synthetic",
                characterId = "c", characterName = "Synthetic", voice = FishAudioVoiceBinding("fake", "Synthetic"), fishModelId = "synthetic",
                audioPath = "chatbar-save-slot-audio:audio", durationMs = 100, byteLength = fishPreviewMp3().size.toLong(), createdAt = 1, updatedAt = 1)
            val source = SyntheticSaveSlotCorpus.archive(base.resolve("historical.cbsave"), voices = listOf(voice), media = mapOf("media/audio/audio.mp3" to fishPreviewMp3()))
            val slot = SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("slot.cbsave", 1, audioCount = 1, byteLength = Files.size(source)))
            val raw = backupJson.encodeToJsonElement(SaveSlot.serializer(), slot).toString().toByteArray(Charsets.UTF_8)
            preserve(root, source, raw)
            Files.createDirectories(root.resolve("entities/chat_sessions")); Files.createDirectories(root.resolve("entities/save_slots")); Files.createDirectories(root.resolve("save_slot_packages"))
            Files.writeString(root.resolve("entities/chat_sessions/s.json"), backupJson.encodeToJsonElement(ChatSession.serializer(), ChatSession("s", "c", "Synthetic", createdAt = 1, updatedAt = 1)).toString())
            Files.write(root.resolve("entities/save_slots/slot.json"), raw); Files.copy(source, root.resolve("save_slot_packages/slot.cbsave"))
            DesktopFullBackupGraphValidation.validate(root)
        }
    }
}
