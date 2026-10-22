package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.desktop.backup.DesktopFullBackupArchive
import com.example.chatbar.desktop.backup.DesktopFullBackupMapping
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** G0R observes existing deletion contracts; no seal schema, queue replica or ambient root. */
@OptIn(ExperimentalCoroutinesApi::class)
class P11S2OwnedMediaSafetyProbeTest {
    private class Fixture(val name: String) : AutoCloseable {
        val base = Files.createTempDirectory("p11-s2-g0r-").toAbsolutePath().normalize()
        val root = Files.createDirectory(base.resolve("root"))
        val bytes = byteArrayOf(0, 1, 2, 3, -1, 10) // Deliberately fake, NOT a decodable image.
        val image = "images/synthetic.png"
        val gate = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(root, gate)
        val resources = DesktopCharacterResourceStore(root)
        val cleanup = DesktopOwnedImageCleanup(root, resources, gate)
        val out = Files.createDirectories(Path.of("build", "p11-s2-g0r-media-probe"))
        private val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json")))).ownership

        init {
            put("entities/app_settings.json", "{}".toByteArray())
            put(image, bytes)
            Files.write(out.resolve("$name-media.fixture.bin"), bytes)
            capture("created")
        }
        fun put(relative: String, value: ByteArray): Path {
            val path = root.resolve(relative).normalize()
            require(path.startsWith(root) && path != root)
            Files.createDirectories(path.parent)
            return Files.write(path, value)
        }
        suspend fun message(id: String, images: List<String>) {
            val message = SyntheticSaveSlotCorpus.message().copy(id = id, images = images)
            storage.saveEntity("chat_messages", id, message, ChatMessage.serializer())
        }
        fun manifest(): Path = put("synthetic-seal-fixture/manifest.json",
            buildJsonObject { put("testOnly", true); put("reference", image) }.toString().toByteArray())
        fun copy(): Path {
            val destination = root.resolve("synthetic-seal-fixture/media/copy.bin")
            Files.createDirectories(destination.parent)
            Files.copy(root.resolve(image), destination) // CREATE_NEW, no hardlink or shared file identity.
            assertFalse(Files.isSameFile(root.resolve(image), destination))
            assertContentEquals(bytes, Files.readAllBytes(destination))
            return destination
        }
        fun present(path: String = image) = assertContentEquals(bytes, Files.readAllBytes(root.resolve(path)))
        fun capture(stage: String) {
            val records = Files.walk(base).use { paths -> paths.sorted().toList().map { path ->
                buildJsonObject {
                    put("path", base.relativize(path).joinToString("/"))
                    put("kind", if (Files.isDirectory(path, NOFOLLOW_LINKS)) "DIRECTORY" else "FILE")
                    check(!Files.isSymbolicLink(path))
                    if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        put("length", Files.size(path))
                        if (path.fileName.toString() == DesktopDataRootOwnership.LOCK_FILE_NAME && DesktopDataRootOwnership.isHeld(root))
                            put("digestStatus", "OWNERSHIP_LOCK_LENGTH_ONLY")
                        else put("sha256", digest(Files.readAllBytes(path)))
                    }
                }
            } }
            Files.writeString(out.resolve("$name-$stage.json"), buildJsonObject {
                put("stage", stage); put("rootOwned", DesktopDataRootOwnership.isHeld(root))
                put("coordinator", gate.state.name); put("entries", JsonArray(records))
            }.toString())
        }
        fun outcome(value: String) {
            println("G0R $name SYNTHETIC_RUNTIME_OBSERVED $value")
            Files.writeString(out.resolve("$name-outcome.txt"), value)
        }
        override fun close() {
            try { runBlocking { gate.closeAndDrain() } } finally { owner.close() }
            assertFalse(DesktopDataRootOwnership.isHeld(root))
            assertEquals(DesktopDataOperationCoordinatorState.CLOSED, gate.state)
            capture("released-final")
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
                DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json")))).ownership.close()
            require(base.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
            require(base.fileName.toString().startsWith("p11-s2-g0r-"))
            check(base.toFile().deleteRecursively())
            Files.writeString(out.resolve("$name-cleanup.txt"), "OWNED_TEMP_REMOVED=${!Files.exists(base)} ROOT_LOCK_RELEASED=true COORDINATOR=CLOSED")
        }
    }

    @Test fun `live message retains candidate until its image reference is removed`() = runBlocking<Unit> {
        Fixture("live-owner").use { f ->
            f.message("m", listOf(f.image)); f.capture("referenced-before")
            f.cleanup.deleteUnreferenced(listOf(f.image)); f.present(); f.capture("referenced-after")
            f.message("m", emptyList()); f.capture("unreferenced-before")
            f.cleanup.deleteUnreferenced(listOf(f.image, f.image))
            assertFalse(Files.exists(f.root.resolve(f.image))); f.capture("deleted-after")
            f.outcome("LIVE_OWNER_PROTECTED -> CANDIDATE_DELETED after durable reference removal")
        }
    }

    @Test fun `last of two live owners must release image before GC removes it`() = runBlocking<Unit> {
        Fixture("two-owners").use { f ->
            f.message("a", listOf(f.image)); f.message("b", listOf(f.image)); f.capture("both-before")
            f.message("a", emptyList()); f.capture("one-before")
            f.cleanup.deleteUnreferenced(listOf(f.image)); f.present(); f.capture("one-after")
            f.message("b", emptyList()); f.capture("none-before")
            f.cleanup.deleteUnreferenced(listOf(f.image)); assertFalse(Files.exists(f.root.resolve(f.image)))
            f.capture("none-after"); f.outcome("LIVE_OWNER_PROTECTED until last live reference removed")
        }
    }

    @Test fun `external manifest alone cannot retain live candidate and is not itself a candidate`() = runBlocking<Unit> {
        Fixture("external-only").use { f ->
            val manifest = f.manifest(); val original = Files.readAllBytes(manifest); f.capture("before")
            f.cleanup.deleteUnreferenced(listOf(f.image, "synthetic-seal-fixture/manifest.json"))
            assertFalse(Files.exists(f.root.resolve(f.image)))
            assertContentEquals(original, Files.readAllBytes(manifest)); f.capture("after")
            f.outcome("OBSERVED_UNSAFE_CURRENT_BEHAVIOR S2_DOMAIN_UNPROTECTED manifest survives, referenced image deleted")
        }
    }

    @Test fun `isolated physical copy survives GC deletion of live original`() = runBlocking<Unit> {
        Fixture("isolated-copy").use { f ->
            val copy = f.copy(); val manifest = f.manifest(); val originalManifest = Files.readAllBytes(manifest)
            f.capture("before")
            f.cleanup.deleteUnreferenced(listOf(f.image, "synthetic-seal-fixture/media/copy.bin"))
            assertFalse(Files.exists(f.root.resolve(f.image))); assertContentEquals(f.bytes, Files.readAllBytes(copy))
            assertContentEquals(originalManifest, Files.readAllBytes(manifest)); f.capture("after")
            f.outcome("ISOLATED_COPY_SURVIVES independent bytes only; production seal NOT_IMPLEMENTED")
        }
    }

    @Test fun `resource unlink primitive has no live reference fence and independent copy survives`() = runBlocking<Unit> {
        Fixture("unlink-primitive").use { f ->
            f.message("m", listOf(f.image)); val copy = f.copy(); f.capture("before")
            f.resources.deleteOwned(f.image)
            assertFalse(Files.exists(f.root.resolve(f.image))); assertContentEquals(f.bytes, Files.readAllBytes(copy))
            f.capture("after"); f.outcome("DELETE_OWNED_IS_PRIMITIVE no reference scan; NOT a pending queue execution")
        }
    }

    @Test fun `corrupt authority prevents cleanup of every candidate`() = runBlocking<Unit> {
        Fixture("corrupt-authority").use { f ->
            f.put("images/second.png", f.bytes)
            f.put("entities/chat_messages/broken.json", "{".toByteArray()); f.capture("before")
            assertFails { f.cleanup.deleteUnreferenced(listOf(f.image, "images/second.png")) }
            f.present(); f.present("images/second.png"); f.capture("after")
            f.outcome("FAIL_CLOSED stage=REFERENCE_PARSE ALL_CANDIDATES_RETAINED")
        }
    }

    @Test fun `repeated exact cleanup is idempotent for duplicate and already absent candidates`() = runBlocking<Unit> {
        Fixture("repeated-cleanup").use { f ->
            val copy = f.copy(); f.capture("before-first")
            f.cleanup.deleteUnreferenced(listOf(f.image, f.image)); f.capture("after-first")
            f.cleanup.deleteUnreferenced(listOf(f.image, f.image)); f.capture("after-second")
            assertFalse(Files.exists(f.root.resolve(f.image))); assertContentEquals(f.bytes, Files.readAllBytes(copy))
            f.outcome("EXACT_CLEANUP_IDEMPOTENT NOT pending-deletion retry proof")
        }
    }

    @Test fun `cancel while real cleanup waits for maintenance leaves media intact`() = runTest {
        Fixture("cancel-cleanup").use { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val normal = launch { f.gate.withNormalOperation { entered.complete(Unit); release.await() } }
            try {
                entered.await(); f.capture("before-queue")
                val cleaning = launch { f.cleanup.deleteUnreferenced(listOf(f.image)) }
                try {
                    runCurrent(); assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, f.gate.state)
                    f.capture("waiting"); cleaning.cancelAndJoin(); f.present(); f.capture("cancelled")
                } finally { cleaning.cancelAndJoin() }
            } finally { release.complete(Unit); normal.cancelAndJoin() }
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.gate.state)
            f.outcome("CANCELLED_BEFORE_EXCLUSIVE media unchanged; NOT pending-deletion cancellation proof")
        }
    }

    @Test fun `unlink failure can follow a completed unlink without rollback while sealed copy survives`() = runBlocking<Unit> {
        Fixture("partial-unlink").use { f ->
            val copy = f.copy(); Files.createDirectory(f.root.resolve("images/not-file.png")); f.capture("before")
            assertFailsWith<IllegalArgumentException> {
                f.cleanup.deleteUnreferenced(listOf(f.image, "images/not-file.png"))
            }
            assertFalse(Files.exists(f.root.resolve(f.image)))
            assertTrue(Files.isDirectory(f.root.resolve("images/not-file.png")))
            assertContentEquals(f.bytes, Files.readAllBytes(copy)); f.capture("failed-after-first-unlink")
            Files.delete(f.root.resolve("images/not-file.png")); f.capture("before-repeat")
            f.cleanup.deleteUnreferenced(listOf(f.image, "images/not-file.png"))
            assertContentEquals(f.bytes, Files.readAllBytes(copy)); f.capture("after-repeat")
            f.outcome("OBSERVED_NONATOMIC_EXACT_CLEANUP stage=DELETE_OWNED_TYPE_CHECK ISOLATED_COPY_SURVIVES; not crash/queue proof")
        }
    }

    @Test fun `registered normal media enters authenticated full backup with exact fake bytes`() = runBlocking<Unit> {
        Fixture("backup-registered").use { f ->
            f.capture("before"); assertTrue(f.image in DesktopFullBackupMapping(f.root).inventory())
            val focus = DesktopAudioFocus()
            val service = DesktopFullBackupService(f.root, f.gate, DesktopAudioMaintenance({ focus }) {})
            val output = f.base.resolve("export.cbbackup")
            service.export(output, null); f.capture("exported")
            val work = Files.createDirectory(f.base.resolve("inspect"))
            DesktopFullBackupArchive(work).inspect(output, null).use { prepared ->
                assertTrue(f.image in prepared.records)
                assertContentEquals(f.bytes, Files.readAllBytes(prepared.files.getValue(f.image)))
                f.capture("authenticated-inspection")
            }
            assertEquals(0L, Files.list(work).use { it.count() }); f.present(); f.capture("released-inspection")
            Files.copy(output, f.out.resolve("registered.fixture.cbbackup"), REPLACE_EXISTING)
            f.outcome("KNOWN_INCLUDED authenticated inventory and bytes; fake media decode/restore NOT_TESTED")
        }
    }

    @Test fun `unknown top level copy namespace blocks full backup instead of partial success`() = runBlocking<Unit> {
        Fixture("backup-unknown").use { f ->
            val copy = f.copy(); f.capture("before")
            val focus = DesktopAudioFocus()
            val service = DesktopFullBackupService(f.root, f.gate, DesktopAudioMaintenance({ focus }) {})
            val output = f.base.resolve("export.cbbackup")
            assertEquals("UNCLASSIFIED_ROOT_ENTRY", assertFailsWith<IllegalStateException> { service.export(output, null) }.message)
            assertFalse(Files.exists(output)); f.present(); assertContentEquals(f.bytes, Files.readAllBytes(copy))
            f.capture("rejected"); f.outcome("REJECTED UNCLASSIFIED_ROOT_ENTRY NEEDS_ADAPTER no archive produced")
        }
    }

    @Test fun `hidden media is skipped although normal media and export succeed`() = runBlocking<Unit> {
        Fixture("backup-hidden").use { f ->
            val hidden = f.put("images/.synthetic-seal-fixture/copy.bin", f.bytes); f.capture("before")
            val inventory = DesktopFullBackupMapping(f.root).inventory()
            assertTrue(f.image in inventory); assertFalse("images/.synthetic-seal-fixture/copy.bin" in inventory)
            val focus = DesktopAudioFocus()
            val service = DesktopFullBackupService(f.root, f.gate, DesktopAudioMaintenance({ focus }) {})
            val output = f.base.resolve("export.cbbackup"); service.export(output, null); f.capture("exported")
            val work = Files.createDirectory(f.base.resolve("inspect"))
            DesktopFullBackupArchive(work).inspect(output, null).use { prepared ->
                assertTrue(f.image in prepared.records)
                assertTrue(prepared.records.keys.none { it.contains(".synthetic-seal-fixture") })
                assertContentEquals(f.bytes, Files.readAllBytes(prepared.files.getValue(f.image)))
                f.capture("authenticated-omission")
            }
            f.present(); assertContentEquals(f.bytes, Files.readAllBytes(hidden)); f.capture("released-inspection")
            Files.copy(output, f.out.resolve("hidden.fixture.cbbackup"), REPLACE_EXISTING)
            f.outcome("OBSERVED_UNSAFE_CURRENT_BEHAVIOR SKIPPED hidden bytes absent from successful archive; normal=KNOWN_INCLUDED")
        }
    }

    companion object {
        private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }
}
