package com.example.chatbar.data.snapshot

import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppDataSnapshotServiceTest {
    private lateinit var appDataRoot: Path
    private val json = Json { prettyPrint = true }

    @BeforeTest
    fun setUp() {
        appDataRoot = Files.createTempDirectory("app-data-snapshot-")
    }

    @AfterTest
    fun tearDown() {
        appDataRoot.toFile().deleteRecursively()
    }

    @Test
    fun `empty app root creates valid completed snapshot`() {
        val snapshot = service().createSnapshot()

        assertTrue(snapshot.validation.valid)
        assertTrue(Files.isRegularFile(snapshot.directory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)))
        assertTrue(Files.isDirectory(snapshot.directory.resolve(AppDataSnapshotService.PAYLOAD_DIRECTORY_NAME)))
        assertEquals(emptyList(), manifestPaths(snapshot.directory))
        assertEquals(listOf(snapshot.name), service().listSnapshots().map { it.name })
    }

    @Test
    fun `snapshot copies nested current and future data with relative manifest entries`() {
        val fixtures = mapOf(
            "entities/cards/card.json" to "{\"name\":\"card\"}".toByteArray(),
            "images/avatar.bin" to byteArrayOf(0, 1, 2, 3),
            "audio/voice.dat" to byteArrayOf(4, 5, 6),
            "documents/nested/info.txt" to "document".toByteArray(),
            "save_slot_packages/slot.cbsave" to byteArrayOf(7, 8, 9),
            "desktop-settings.json" to "{\"theme\":\"dark\"}".toByteArray(),
        )
        fixtures.forEach { (relative, bytes) -> writeSource(relative, bytes) }

        val snapshot = service().createSnapshot()

        assertEquals(fixtures.keys, manifestPaths(snapshot.directory).toSet())
        fixtures.forEach { (relative, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(payload(snapshot).resolve(relative)))
        }
        assertTrue(service().validateSnapshot(snapshot.directory).valid)
    }

    @Test
    fun `top-level backups are excluded from snapshot payload`() {
        writeSource("entities/item.json", "source".toByteArray())
        writeSource("backups/old-snapshot/payload/old.json", "old backup".toByteArray())

        val snapshot = service().createSnapshot()

        assertEquals(setOf("entities/item.json"), manifestPaths(snapshot.directory).toSet())
        assertFalse(Files.exists(payload(snapshot).resolve("backups")))
    }

    @Test
    fun `validation reports missing snapshot payload file`() {
        writeSource("entities/item.json", "source".toByteArray())
        val snapshot = service().createSnapshot()
        Files.delete(payload(snapshot).resolve("entities/item.json"))

        val validation = service().validateSnapshot(snapshot.directory)

        assertFalse(validation.valid)
        assertEquals(SnapshotValidationIssue.MISSING_FILE, validation.issue)
    }

    @Test
    fun `validation reports missing manifest`() {
        val snapshot = service().createSnapshot()
        Files.delete(snapshot.directory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME))

        val validation = service().validateSnapshot(snapshot.directory)

        assertFalse(validation.valid)
        assertEquals(SnapshotValidationIssue.MISSING_MANIFEST, validation.issue)
    }

    @Test
    fun `validation rejects unsupported snapshot format version`() {
        val snapshot = service().createSnapshot()
        rewriteManifestField(snapshot.directory, "formatVersion", JsonPrimitive(99))

        val validation = service().validateSnapshot(snapshot.directory)

        assertFalse(validation.valid)
        assertEquals(SnapshotValidationIssue.UNSUPPORTED_FORMAT_VERSION, validation.issue)
    }

    @Test
    fun `validation reports snapshot payload size mismatch`() {
        writeSource("entities/item.json", "short".toByteArray())
        val snapshot = service().createSnapshot()
        Files.writeString(payload(snapshot).resolve("entities/item.json"), "a much longer payload")

        val validation = service().validateSnapshot(snapshot.directory)

        assertFalse(validation.valid)
        assertEquals(SnapshotValidationIssue.SIZE_MISMATCH, validation.issue)
    }

    @Test
    fun `validation reports modified snapshot payload bytes`() {
        writeSource("entities/item.json", "same-size-a".toByteArray())
        val snapshot = service().createSnapshot()
        Files.writeString(payload(snapshot).resolve("entities/item.json"), "same-size-b")

        val validation = service().validateSnapshot(snapshot.directory)

        assertFalse(validation.valid)
        assertEquals(SnapshotValidationIssue.HASH_MISMATCH, validation.issue)
    }

    @Test
    fun `validation reports tampered manifest hash`() {
        writeSource("entities/item.json", "source".toByteArray())
        val snapshot = service().createSnapshot()
        rewriteFirstManifestEntry(snapshot.directory, "sha256", JsonPrimitive("0".repeat(64)))

        val validation = service().validateSnapshot(snapshot.directory)

        assertFalse(validation.valid)
        assertEquals(SnapshotValidationIssue.HASH_MISMATCH, validation.issue)
    }

    @Test
    fun `validation rejects unsafe manifest relative path`() {
        writeSource("entities/item.json", "source".toByteArray())
        val snapshot = service().createSnapshot()
        rewriteFirstManifestEntry(snapshot.directory, "path", JsonPrimitive("../escape.json"))

        val validation = service().validateSnapshot(snapshot.directory)

        assertFalse(validation.valid)
        assertEquals(SnapshotValidationIssue.UNSAFE_ENTRY_PATH, validation.issue)
    }

    @Test
    fun `listing excludes staging and contains malformed snapshots without failing`() {
        val completed = service().createSnapshot()
        val backups = appDataRoot.resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME)
        Files.createDirectories(backups.resolve(".snapshot-leftover.tmp"))
        val malformed = backups.resolve("malformed-snapshot")
        Files.createDirectories(malformed)
        Files.writeString(malformed.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME), "not-json")

        val listed = service().listSnapshots()

        assertEquals(setOf(completed.name, "malformed-snapshot"), listed.map { it.name }.toSet())
        assertFalse(listed.single { it.name == "malformed-snapshot" }.validation.valid)
        assertTrue(listed.none { it.name == ".snapshot-leftover.tmp" })
    }

    @Test
    fun `completed snapshots list newest first by manifest timestamp`() {
        val first = service(Instant.parse("2026-09-20T10:00:00Z"), "first").createSnapshot()
        val second = service(Instant.parse("2026-09-20T11:00:00Z"), "second").createSnapshot()

        val listed = service().listSnapshots()

        assertEquals(listOf(second.name, first.name), listed.map { it.name })
        assertTrue(first.name.matches(Regex("20260920T100000Z-[A-Za-z0-9]+")))
    }

    @Test
    fun `snapshot creation leaves source file unchanged`() {
        val source = writeSource("entities/item.json", "source bytes".toByteArray())
        val timestamp = FileTime.from(Instant.parse("2026-09-19T12:34:56Z"))
        Files.setLastModifiedTime(source, timestamp)
        val before = Files.readAllBytes(source)

        service().createSnapshot()

        assertContentEquals(before, Files.readAllBytes(source))
        assertEquals(timestamp, Files.getLastModifiedTime(source))
    }

    @Test
    fun `duplicate completed name is not overwritten`() {
        val service = service(Instant.parse("2026-09-20T10:00:00Z"), "same")
        val first = service.createSnapshot()

        val error = runCatching { service.createSnapshot() }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(first.validation.valid)
        assertEquals(listOf(first.name), service.listSnapshots().map { it.name })
    }

    @Test
    fun `symbolic link copy failure creates no completed snapshot and cleans staging when supported`() {
        val target = writeSource("target.txt", "target".toByteArray())
        val link = appDataRoot.resolve("linked.txt")
        try {
            Files.createSymbolicLink(link, target.fileName)
            println("SYMLINK_FIXTURE_SUPPORTED")
        } catch (_: UnsupportedOperationException) {
            println("SYMLINK_FIXTURE_UNAVAILABLE")
            return
        } catch (_: FileSystemException) {
            println("SYMLINK_FIXTURE_UNAVAILABLE")
            return
        }

        val error = runCatching { service().createSnapshot() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertContentEquals("target".toByteArray(), Files.readAllBytes(target))
        val backups = appDataRoot.resolve(AppDataSnapshotService.BACKUPS_DIRECTORY_NAME)
        assertTrue(service().listSnapshots().isEmpty())
        assertTrue(
            backups.toFile().listFiles().orEmpty().none {
                it.name.startsWith(".snapshot-") && it.name.endsWith(".tmp")
            },
        )
    }

    private fun service(
        instant: Instant = Instant.parse("2026-09-20T17:46:00Z"),
        id: String = "snapshotid",
    ) = AppDataSnapshotService(
        appDataRoot = appDataRoot,
        clock = Clock.fixed(instant, ZoneOffset.UTC),
        idSupplier = { id },
    )

    private fun writeSource(relative: String, bytes: ByteArray): Path {
        val path = appDataRoot.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.write(path, bytes)
    }

    private fun payload(snapshot: AppDataSnapshot): Path =
        snapshot.directory.resolve(AppDataSnapshotService.PAYLOAD_DIRECTORY_NAME)

    private fun manifestPaths(snapshotDirectory: Path): List<String> {
        val root = json.parseToJsonElement(
            Files.readString(snapshotDirectory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)),
        ).jsonObject
        return root.getValue("files").jsonArray.map { entry ->
            entry.jsonObject.getValue("path").jsonPrimitive.content
        }
    }

    private fun rewriteFirstManifestEntry(snapshotDirectory: Path, key: String, value: JsonPrimitive) {
        val manifestPath = snapshotDirectory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)
        val root = json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        val entries = root.getValue("files").jsonArray
        val first = entries.first().jsonObject.toMutableMap().apply { put(key, value) }
        val changed = root.toMutableMap().apply {
            put("files", JsonArray(listOf(JsonObject(first)) + entries.drop(1)))
        }
        Files.writeString(manifestPath, json.encodeToString(JsonObject(changed)))
    }

    private fun rewriteManifestField(snapshotDirectory: Path, key: String, value: JsonPrimitive) {
        val manifestPath = snapshotDirectory.resolve(AppDataSnapshotService.MANIFEST_FILE_NAME)
        val root = json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        val changed = root.toMutableMap().apply { put(key, value) }
        Files.writeString(manifestPath, json.encodeToString(JsonObject(changed)))
    }
}
