package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.*
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopPreservationStoreTest {
    private val post = """{ "id":"p", "characterCardId":"card", "sessionId":"s", "senderName":"Synthetic", "text":"synthetic dormant post", "senderAvatar":"chatbar-backup-file:images/avatar.bin", "imagePath":"chatbar-backup-file:images/body.bin", "generatedImageMetadata":{"imagePath":"chatbar-backup-file:images/metadata.bin","baseCaption":"synthetic","negativePrompt":"","sizePreset":"synthetic","width":1,"height":1}, "scheduledAt":1, "generatedAt":2 }""".toByteArray()
    private val task = """{ "id":"t", "characterCardId":"card", "sessionId":"s", "scheduledAt":1, "postId":"p", "status":"RUNNING", "createdAt":1, "updatedAt":2 }""".toByteArray()
    private fun corpus() = linkedMapOf("entities/moment_posts/p.json" to post, "entities/moment_tasks/t.json" to task,
        "images/avatar.bin" to byteArrayOf(1,2,3), "images/body.bin" to byteArrayOf(4,5,6), "images/metadata.bin" to byteArrayOf(7,8,9))

    @Test fun originalRecordBytesAndAllThreeImagesAreIndependentlyOwnedAndIdempotent() = runBlocking<Unit> {
        fixture { root, staged, _ ->
            val first = DesktopPreservationStore.preserveMoments(staged, root)
            assertEquals(2, first.records); assertEquals(3, first.mediaCopies); assertEquals(0, first.unavailableSourceMedia)
            val seal = root.resolve(DesktopPreservationStore.ROOT).resolve(staged.source.sourceHash)
            for (raw in listOf(post, task)) assertContentEquals(raw, Files.readAllBytes(seal.resolve("records/${CensusWireFixtures.digest(raw)}.json")))
            for (raw in corpus().filterKeys { it.startsWith("images/") }.values)
                assertContentEquals(raw, Files.readAllBytes(seal.resolve("media/${CensusWireFixtures.digest(raw)}.bin")))
            val before = fileHashes(root.resolve(DesktopPreservationStore.ROOT))
            assertEquals(2, DesktopPreservationStore.preserveMoments(staged, root).records)
            assertEquals(before, fileHashes(root.resolve(DesktopPreservationStore.ROOT)))
            assertFalse(Files.exists(root.resolve("entities/moment_tasks")))
            assertFalse(Files.exists(root.resolve("entities/moment_posts")))
            DesktopFullBackupGraphValidation.validate(root)
        }
    }

    @Test fun liveGcSnapshotAndWholeBackupPreserveIndependentDormantCopies() = runBlocking<Unit> {
        fixture { root, staged, base ->
            DesktopPreservationStore.preserveMoments(staged, root)
            Files.createDirectories(root.resolve("images"))
            val live = Files.write(root.resolve("images/live.bin"), byteArrayOf(4,5,6))
            val expected = fileHashes(root.resolve(DesktopPreservationStore.ROOT))
            DesktopOwnedImageCleanup(root, DesktopCharacterResourceStore(root), DesktopDataOperationCoordinator())
                .deleteUnreferenced(listOf("images/live.bin", "${DesktopPreservationStore.ROOT}/unknown"))
            assertFalse(Files.exists(live))
            assertEquals(expected, fileHashes(root.resolve(DesktopPreservationStore.ROOT)))
            val snapshotService = AppDataSnapshotService(root)
            val snapshot = snapshotService.createSnapshot()
            assertTrue(snapshotService.validateSnapshot(snapshot.directory).valid)
            assertEquals(expected, fileHashes(snapshot.directory.resolve("payload/${DesktopPreservationStore.ROOT}")))
            val work = Files.createDirectory(base.resolve("backup-work"))
            val target = Files.createDirectory(base.resolve("roundtrip-target"))
            DesktopFullBackupMapping(root).export(work).use { exported ->
                assertTrue(exported.files.keys.any(DesktopBackupPolicy::isDesktopOnly))
                val archive = DesktopFullBackupArchive(work)
                val output = base.resolve("synthetic-preservation.cbbackup")
                archive.export(exported.files, output, root.toString(), null, exported.preferences)
                archive.inspect(output, null).use { prepared ->
                    DesktopFullBackupMapping(target).restore(prepared).use { restored ->
                        DesktopFullBackupGraphValidation.validate(restored.directory)
                        assertEquals(expected, fileHashes(restored.directory.resolve(DesktopPreservationStore.ROOT)))
                    }
                }
            }
        }
    }

    @Test fun changedBytesUnknownIndexFieldsAndExtraFilesNeverPassInventory() = runBlocking<Unit> {
        for (variant in 0..2) fixture { root, staged, _ ->
            DesktopPreservationStore.preserveMoments(staged, root)
            val seal = root.resolve(DesktopPreservationStore.ROOT).resolve(staged.source.sourceHash)
            when (variant) {
                0 -> Files.write(seal.resolve("media/${CensusWireFixtures.digest(byteArrayOf(4,5,6))}.bin"), byteArrayOf(9))
                1 -> Files.writeString(seal.resolve("index.json"), Files.readString(seal.resolve("index.json")).dropLast(1) + ",\"future\":true}")
                else -> Files.writeString(seal.resolve("unregistered.txt"), "synthetic")
            }
            assertFails { DesktopPreservationStore.validate(root) }
            assertFails { DesktopFullBackupMapping(root).inventory() }
        }
    }

    @Test fun missingResourcesAndDanglingTaskFailWithoutActivatingOrDiscardingSource() = runBlocking<Unit> {
        val variants = listOf(corpus() - "images/body.bin", corpus().toMutableMap().apply {
            this["entities/moment_tasks/t.json"] = task.toString(Charsets.UTF_8).replace("\"postId\":\"p\"", "\"postId\":\"missing\"").toByteArray()
        })
        for (files in variants) fixture(files) { root, staged, _ ->
            assertFails { DesktopPreservationStore.preserveMoments(staged, root) }
            staged.verifyUnchanged()
            assertFails { DesktopPreservationStore.validate(root) }
            assertFalse(Files.exists(root.resolve("entities/moment_tasks")))
        }
    }

    @Test fun cancellationAndPublicationFailureRetainUnpublishedEvidenceButBlockReuse() = runBlocking<Unit> {
        for (cancel in listOf(false, true)) fixture { root, staged, _ ->
            if (cancel) {
                val entered = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.IO) { DesktopPreservationStore.preserveMoments(staged, root) {
                    entered.complete(Unit); awaitCancellation()
                } }
                withTimeout(5000) { entered.await() }; job.cancelAndJoin()
            } else assertFailsWith<java.io.IOException> {
                DesktopPreservationStore.preserveMoments(staged, root) { throw java.io.IOException("SYNTHETIC_PUBLICATION_FAILURE") }
            }
            val seal = root.resolve(DesktopPreservationStore.ROOT).resolve(staged.source.sourceHash)
            assertTrue(Files.exists(seal.resolve("index.part")))
            assertFalse(Files.exists(seal.resolve("index.json")))
            assertFails { DesktopPreservationStore.validate(root) }
            assertFails { DesktopPreservationStore.preserveMoments(staged, root) }
            staged.verifyUnchanged()
        }
    }

    @Test fun noMomentsWritesNoDomainAndUnownedRootIsRejected() = runBlocking<Unit> {
        fixture(emptyMap()) { root, staged, base ->
            assertEquals(0, DesktopPreservationStore.preserveMoments(staged, root).records)
            assertFalse(Files.exists(root.resolve(DesktopPreservationStore.ROOT)))
            assertFails { DesktopPreservationStore.preserveMoments(staged, Files.createDirectory(base.resolve("unowned"))) }
        }
    }

    private fun fileHashes(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).joinToString("/") to CensusWireFixtures.digest(Files.readAllBytes(it)) }
    }
    private suspend fun fixture(files: Map<String, ByteArray> = corpus(), action: suspend (Path, DesktopFullBackupStaging, Path) -> Unit) {
        val base = Files.createTempDirectory("desktop-dormant-synthetic-").toAbsolutePath()
        try {
            val root = Files.createDirectory(base.resolve("root")); Files.createDirectory(root.resolve("entities"))
            val acquired = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
                DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired
            acquired.ownership.use {
                CensusWireFixtures.input(CensusWireFixtures.bytes(prefs = "{}", files = files), "preservation-${CensusWireFixtures.digest(files.values.fold(byteArrayOf()) { a,b -> a+b }).take(12)}") { source ->
                    DesktopFullBackupStaging.prepare(source, null, Files.createDirectory(base.resolve("staging"))).use { staged -> action(root, staged, base) }
                }
            }
        } finally { check(base.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()); base.toFile().deleteRecursively() }
    }
}
