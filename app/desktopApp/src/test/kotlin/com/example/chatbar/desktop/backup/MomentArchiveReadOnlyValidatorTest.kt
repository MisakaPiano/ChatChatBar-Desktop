package com.example.chatbar.desktop.backup

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class MomentArchiveReadOnlyValidatorTest {
    @Test fun `R1 unknown family blocks a closed known graph while S0 remains readable`() = runBlocking<Unit> {
        for (code in 84..86) for (encrypted in listOf(false, true)) {
            val extension = """{"id":"x","future":{"resource":"synthetic-future-ref","behavior":"synthetic-future-action"}}""".toByteArray()
            val files = owners() + mapOf("entities/moment_posts/p.json" to post.toByteArray(), "entities/moment_extension/x.json" to extension)
            val bytes = archive(code, encrypted, files)
            captureR1("known-and-unknown-$code-$encrypted", bytes)
            isolated(bytes) { source ->
                val password = if (encrypted) "synthetic-password".toCharArray() else null
                val s0 = DesktopBackupReadOnlyInspector().inspect(source, password)
                assertTrue(s0.formatReadable); assertTrue(s0.uninterpretedEntities >= 1)
                val s1 = DesktopBackupReadOnlyInspector().inspectMoments(source, password)
                println("R1 known-plus-unknown code=$code encrypted=$encrypted state=${s1.state}")
                assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, s1.state)
                assertEquals("UNKNOWN_ENTITY_FAMILY", s1.failure)
                assertFalse(s1.toString().contains("synthetic-future"))
            }
        }
    }
    @Test fun `R1 unknown only cannot masquerade as not applicable`() = runBlocking<Unit> {
        for (code in 84..86) for (encrypted in listOf(false, true)) {
            val bytes = archive(code, encrypted, mapOf("entities/moment_future/x.json" to """{"future":{"path":"synthetic-future-ref"}}""".toByteArray()))
            captureR1("unknown-only-$code-$encrypted", bytes)
            isolated(bytes) { source ->
                val password = if (encrypted) "synthetic-password".toCharArray() else null
                assertTrue(DesktopBackupReadOnlyInspector().inspect(source, password).uninterpretedEntities >= 1)
                val s1 = DesktopBackupReadOnlyInspector().inspectMoments(source, password)
                println("R1 unknown-only code=$code encrypted=$encrypted state=${s1.state}")
                assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, s1.state)
                assertEquals("UNKNOWN_ENTITY_FAMILY", s1.failure)
            }
        }
    }
    @Test fun `R1 full archive missing card cannot label sender unselected`() = runBlocking<Unit> {
        val bytes = archive(code = 84, files = mapOf("entities/moment_posts/p.json" to record("senderCharacterId" to JsonPrimitive("sender"))))
        captureR1("v1-missing-card-sender", bytes)
        isolated(bytes) { source ->
            val result = DesktopBackupReadOnlyInspector().inspectMoments(source, null)
            assertEquals(MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES, result.state)
            assertEquals(MomentArchiveReadOnlyValidator.Relation.MISSING, result.links.single { it.field == MomentArchiveReadOnlyValidator.LinkField.CARD }.state)
            val sender = result.links.single { it.field == MomentArchiveReadOnlyValidator.LinkField.SENDER }.state
            println("R1 v1 missing card sender=$sender")
            assertEquals(MomentArchiveReadOnlyValidator.Relation.MISSING, sender)
        }
    }
    @Test fun `R1 registered non moment families stay known and arbitrary new families block`() = runBlocking<Unit> {
        val known = owners() + ("entities/app_settings.json" to officialWireJson.encodeToString(com.example.chatbar.data.local.entity.AppSettings()).toByteArray())
        assertEquals(MomentArchiveReadOnlyValidator.State.SOURCE_GRAPH_VERIFIED_WITH_SCOPE, report(known + ("entities/moment_posts/p.json" to post.toByteArray())).state)
        assertEquals(MomentArchiveReadOnlyValidator.State.NOT_APPLICABLE, report(known).state)
        val unknown = report(known + ("entities/future_settings/x.json" to "{}".toByteArray()))
        assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, unknown.state)
        assertEquals("UNKNOWN_ENTITY_FAMILY", unknown.failure)
        assertFalse(unknown.arbitraryTextCredentialFree); assertFalse(unknown.restoreSupported)
        assertFalse(unknown.persistedSeal); assertFalse(unknown.taskRunnable); assertFalse(unknown.schedulerActivated)
    }
    @Test fun `R1 v2 counts cannot prove selection of a missing specific card`() = runBlocking<Unit> {
        val raw = record("senderCharacterId" to JsonPrimitive("sender"))
        val otherCard = officialWireJson.encodeToString(com.example.chatbar.data.local.entity.CharacterCard("other", "synthetic", createdAt = 1, updatedAt = 1)).toByteArray()
        for (code in 85..86) for (anyCardSelected in listOf(false, true)) {
            val files = mapOf("entities/moment_posts/p.json" to raw) + if (anyCardSelected) mapOf("entities/character_cards/other.json" to otherCard) else emptyMap()
            val bytes = archive(code = code, files = files)
            captureR1("v2-count-only-$code-$anyCardSelected", bytes)
            isolated(bytes) { source ->
                val result = DesktopBackupReadOnlyInspector().inspectMoments(source, null)
                assertEquals(MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES, result.state)
                val expected = if (anyCardSelected) MomentArchiveReadOnlyValidator.Relation.UNKNOWN_SELECTION else MomentArchiveReadOnlyValidator.Relation.NOT_SELECTED
                for (field in listOf(MomentArchiveReadOnlyValidator.LinkField.CARD, MomentArchiveReadOnlyValidator.LinkField.SENDER))
                    assertEquals(expected, result.links.single { it.field == field }.state)
            }
        }
    }
    @Test fun `R1 known sender identity conflict and historical snapshot remain conservative`() = runBlocking<Unit> {
        for (matches in 0..2) {
            val card = com.example.chatbar.data.local.entity.CharacterCard("c", "synthetic",
                characters = List(matches) { com.example.chatbar.data.local.entity.CharacterInfo("sender", "synthetic") }, createdAt = 1, updatedAt = 1)
            val files = owners() + mapOf("entities/character_cards/c.json" to officialWireJson.encodeToString(card).toByteArray(),
                "entities/moment_posts/p.json" to record("senderCharacterId" to JsonPrimitive("sender")))
            val result = report(files)
            val expected = listOf(MomentArchiveReadOnlyValidator.Relation.MISSING, MomentArchiveReadOnlyValidator.Relation.IN_ARCHIVE, MomentArchiveReadOnlyValidator.Relation.CONFLICT)[matches]
            assertEquals(expected, result.links.single { it.field == MomentArchiveReadOnlyValidator.LinkField.SENDER }.state)
            assertEquals(listOf(MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES, MomentArchiveReadOnlyValidator.State.SOURCE_GRAPH_VERIFIED_WITH_SCOPE, MomentArchiveReadOnlyValidator.State.BLOCKED)[matches], result.state)
        }
        for (code in 84..86) {
            val snapshot = report(mapOf("entities/moment_posts/p.json" to post.toByteArray()), code)
            assertEquals(MomentArchiveReadOnlyValidator.Relation.SNAPSHOT_ONLY, snapshot.links.single { it.field == MomentArchiveReadOnlyValidator.LinkField.SENDER }.state)
            assertEquals(MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES, snapshot.state)
        }
    }
    private fun captureR1(label: String, bytes: ByteArray) {
        val out = Files.createDirectories(Path.of("build", "s1-r1-fixtures"))
        Files.write(out.resolve("$label.cbbackup"), bytes)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val post = """{ "id":"p", "characterCardId":"c", "sessionId":"s", "senderName":"synthetic", "text":"synthetic", "scheduledAt":1, "generatedAt":2 }"""
    private fun archive(code: Int = 86, encrypted: Boolean = false, files: Map<String, ByteArray> = mapOf("entities/moment_posts/p.json" to post.toByteArray()), images: Boolean = true, moments: Boolean = true, omitImages: Boolean = false): ByteArray {
        val version = if (code == 84) 1 else 2
        val prefs = if (code == 84) officialWireJson.encodeToString(Official145Preferences()) else officialWireJson.encodeToString(OfficialModernPreferences())
        val counts = files.keys.filter { it.startsWith("entities/") }.groupingBy { it.removePrefix("entities/").substringBefore('/').removeSuffix(".json") }.eachCount()
        val manifest = if (code == 84) officialWireJson.encodeToString(Official145Manifest(1, code, "synthetic", 1, "synthetic", counts, files.size, files.values.sumOf { it.size.toLong() }, sha(prefs.toByteArray()))) else
            officialWireJson.encodeToString(OfficialModernManifest(2, code, "synthetic", 1, "synthetic", counts, files.size, files.values.sumOf { it.size.toLong() }, sha(prefs.toByteArray()),
                OfficialWireScope(emptySet(), counts["character_cards"] ?: 0, counts["chat_sessions"] ?: 0, 0, 0, 0, false, false, true, moments, images)))
        return ByteArrayOutputStream().also { out -> ZipOutputStream(Official147FixtureCodec.encrypting(out, if (encrypted) "synthetic-password".toCharArray() else null, version)).use { zip ->
            fun entry(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            files.forEach { (name, data) -> entry("files/$name", data) }
            entry("preferences.json", prefs.toByteArray())
            entry("inventory.jsonl", files.map { (name, data) -> officialWireJson.encodeToString(OfficialWireFileRecord(name, data.size.toLong(), sha(data))) }.joinToString("\n").toByteArray())
            val wireManifest = if (omitImages) {
                val obj = officialWireJson.parseToJsonElement(manifest).jsonObject
                JsonObject(obj + ("scope" to JsonObject(obj.getValue("scope").jsonObject - "momentsImages"))).toString()
            } else manifest
            entry("manifest.json", wireManifest.toByteArray())
        } }.toByteArray()
    }
    private suspend fun <T> isolated(bytes: ByteArray, block: suspend (Path) -> T): T {
        val root = Files.createTempDirectory("s1-synthetic-")
        try {
            val source = Files.write(root.resolve("source.cbbackup"), bytes)
            Files.writeString(root.resolve("journal"), "synthetic-existing")
            fun inventory() = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to sha(Files.readAllBytes(it)) } }
            val before = inventory()
            try { return block(source) } finally { assertEquals(before, inventory()) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `S0 cannot prove moments graph`() = runBlocking<Unit> {
        isolated(archive()) { source ->
            assertFalse(DesktopBackupReadOnlyInspector().inspect(source, null).resourceGraphVerified)
            assertEquals(MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES, DesktopBackupReadOnlyInspector().inspectMoments(source, null).state)
        }
    }
    private fun record(vararg fields: Pair<String, JsonElement>): ByteArray = JsonObject(officialWireJson.parseToJsonElement(post).jsonObject + fields.toMap()).toString().toByteArray()
    private fun ref(name: String) = JsonPrimitive("chatbar-backup-file:images/$name")
    private fun metadata(name: String): JsonObject = buildJsonObject {
        put("imagePath", ref(name)); put("baseCaption", "synthetic"); put("negativePrompt", "synthetic")
        put("sizePreset", "synthetic"); put("width", 1); put("height", 1)
    }
    private fun owners() = mapOf(
        "entities/character_cards/c.json" to officialWireJson.encodeToString(com.example.chatbar.data.local.entity.CharacterCard("c", "synthetic", createdAt = 1, updatedAt = 1)).toByteArray(),
        "entities/chat_sessions/s.json" to officialWireJson.encodeToString(com.example.chatbar.data.local.entity.ChatSession("s", "c", "synthetic", createdAt = 1, updatedAt = 1)).toByteArray())
    private suspend fun report(files: Map<String, ByteArray>, code: Int = 86, encrypted: Boolean = false, images: Boolean = true): MomentArchiveReadOnlyValidator.Report = isolated(archive(code, encrypted, files, images)) {
        DesktopBackupReadOnlyInspector().inspectMoments(it, if (encrypted) "synthetic-password".toCharArray() else null)
    }
    @Test fun `three independent edges shared and distinct bytes across all official versions`() = runBlocking<Unit> {
        for (code in 84..86) for (encrypted in listOf(false, true)) for (shared in listOf(false, true)) {
            val raw = record("senderAvatar" to ref("a"), "imagePath" to ref(if (shared) "a" else "b"), "generatedImageMetadata" to metadata(if (shared) "a" else "c"))
            val files = owners() + mapOf("entities/moment_posts/p.json" to raw, "images/a" to byteArrayOf(1), "images/b" to byteArrayOf(2), "images/c" to byteArrayOf(3))
            val archive = archive(code, encrypted, files)
            isolated(archive) { path ->
                val result = DesktopBackupReadOnlyInspector().inspectMoments(path, if (encrypted) "synthetic-password".toCharArray() else null)
                assertEquals(MomentArchiveReadOnlyValidator.State.SOURCE_GRAPH_VERIFIED_WITH_SCOPE, result.state)
                assertEquals(3, result.edges.size); assertTrue(result.edges.all { it.state == MomentArchiveReadOnlyValidator.Media.PRESENT })
                assertEquals(if (shared) 1 else 3, result.edges.map { it.resourceSha256 }.distinct().size)
                assertEquals(sha(raw), result.records.single().recordSha256); assertEquals(raw.size.toLong(), result.records.single().length)
                assertEquals(sha(archive), result.records.single().sourceSha256)
                assertFalse(result.restoreSupported); assertFalse(result.fullPortabilityAccepted); assertFalse(result.persistedSeal)
                assertFalse(result.taskRunnable); assertFalse(result.schedulerActivated); assertFalse(result.arbitraryTextCredentialFree)
                assertFalse(result.toString().contains("synthetic")); assertFalse(result.toString().contains("images/"))
            }
            val out = Files.createDirectories(Path.of("build", "s1-fixtures"))
            Files.write(out.resolve("code$code-encrypted$encrypted-shared$shared.cbbackup"), archive)
            Files.write(out.resolve("code$code-encrypted$encrypted-shared$shared.post.json"), raw)
        }
    }
    @Test fun `each media edge is independently required with versioned avatar exception`() = runBlocking<Unit> {
        for (code in 84..86) for (field in listOf("senderAvatar", "imagePath", "generatedImageMetadata")) {
            val raw = record(field to if (field == "generatedImageMetadata") metadata("missing") else ref("missing"))
            val result = report(owners() + ("entities/moment_posts/p.json" to raw), code)
            assertEquals(if (field == "senderAvatar" && code > 84) MomentArchiveReadOnlyValidator.State.SOURCE_GRAPH_VERIFIED_WITH_SCOPE else MomentArchiveReadOnlyValidator.State.BLOCKED, result.state)
            assertEquals(MomentArchiveReadOnlyValidator.State.SOURCE_GRAPH_VERIFIED_WITH_SCOPE, report(owners() + mapOf("entities/moment_posts/p.json" to raw, "images/missing" to byteArrayOf(1)), code).state)
        }
        val result = report(owners() + mapOf("entities/moment_posts/p.json" to record("senderAvatar" to ref("missing"), "generatedImageMetadata" to metadata("real")), "images/real" to byteArrayOf(1)))
        assertEquals(MomentArchiveReadOnlyValidator.Media.MISSING_AT_SOURCE, result.edges.first().state)
        assertEquals(MomentArchiveReadOnlyValidator.Media.PRESENT, result.edges.last().state)
    }
    @Test fun `scope projection is distinct from empty and dependencies never borrow live IDs`() = runBlocking<Unit> {
        for (code in 85..86) {
            isolated(archive(code = code, files = owners() + ("entities/moment_posts/p.json" to post.toByteArray()), omitImages = true)) {
                assertTrue(DesktopBackupReadOnlyInspector().inspectMoments(it, null).edges.none { edge -> edge.state == MomentArchiveReadOnlyValidator.Media.OMITTED_BY_SCOPE })
            }
            val projected = report(owners() + ("entities/moment_posts/p.json" to post.toByteArray()), code, images = false)
            assertEquals(2, projected.edges.count { it.state == MomentArchiveReadOnlyValidator.Media.OMITTED_BY_SCOPE })
            assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, report(owners() + ("entities/moment_posts/p.json" to record("imagePath" to ref("a"))), code, images = false).state)
        }
        assertEquals(MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES, report(mapOf("entities/moment_posts/p.json" to post.toByteArray())).state)
        assertEquals(MomentArchiveReadOnlyValidator.Relation.NOT_SELECTED, report(mapOf("entities/moment_posts/p.json" to post.toByteArray())).relations.keys.first())
        assertTrue(MomentArchiveReadOnlyValidator.Relation.MISSING in report(mapOf("entities/moment_posts/p.json" to post.toByteArray()), 84).relations)
        assertEquals(MomentArchiveReadOnlyValidator.State.NOT_APPLICABLE, report(emptyMap()).state)
        isolated(archive(moments = false)) { assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { DesktopBackupReadOnlyInspector().inspectMoments(it, null) } }
    }
    @Test fun `task identity statuses orphan null and conflict are classified without activation`() = runBlocking<Unit> {
        for (status in listOf("PENDING", "RUNNING", "COMPLETED", "SKIPPED", "FAILED", "UNKNOWN")) for (postId in listOf("null", "\"p\"", "\"missing\"")) {
            val task = """{"id":"t","characterCardId":"c","sessionId":"s","scheduledAt":-1,"status":"$status","postId":$postId,"createdAt":1,"updatedAt":2}""".toByteArray()
            val result = report(owners() + mapOf("entities/moment_posts/p.json" to post.toByteArray(), "entities/moment_tasks/t.json" to task))
            assertEquals(when { status == "UNKNOWN" -> MomentArchiveReadOnlyValidator.State.BLOCKED; postId.contains("missing") -> MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES; else -> MomentArchiveReadOnlyValidator.State.SOURCE_GRAPH_VERIFIED_WITH_SCOPE }, result.state)
            assertFalse(result.taskRunnable)
            if (status != "UNKNOWN") assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, report(owners() + mapOf("entities/moment_tasks/t.json" to task, "entities/moment_tasks/other.json" to task)).state)
        }
        assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, report(owners() + mapOf("entities/moment_posts/p.json" to post.toByteArray(), "entities/moment_posts/q.json" to post.toByteArray())).state)
        val conflict = """{"id":"t","characterCardId":"other","sessionId":"s","scheduledAt":999999999,"postId":"p"}""".toByteArray()
        assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, report(owners() + mapOf("entities/moment_posts/p.json" to post.toByteArray(), "entities/moment_tasks/t.json" to conflict)).state)
    }
    @Test fun `unknown fields types nested metadata duplicate keys and utf8 fail closed`() = runBlocking<Unit> {
        val values = listOf(record("future" to JsonPrimitive("synthetic")), record("scheduledAt" to JsonPrimitive("bad")),
            record("generatedImageMetadata" to JsonObject(metadata("x") + ("futurePath" to ref("x")))),
            post.replace("\"id\":\"p\"", "\"id\":\"p\",\"id\":\"q\"").toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))
        for (raw in values) {
            try { assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, report(mapOf("entities/moment_posts/p.json" to raw)).state) }
            catch (failure: DesktopBackupReadOnlyInspector.InspectionFailure) { assertNull(failure.cause); assertFalse(failure.toString().contains("synthetic")) }
        }
    }
    @Test fun `unsafe external and case alias references never resolve through host IO`() = runBlocking<Unit> {
        for (path in listOf("http://127.0.0.1:1/a", "file:///C:/a", "content://a", "C:\\a", "\\\\host\\a", "chatbar-backup-file:images/../a", "chatbar-backup-file:images/a:ads", "chatbar-backup-file:images/%2e%2e/a", "chatbar-backup-file:images/A")) {
            assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, report(owners() + mapOf("entities/moment_posts/p.json" to record("senderAvatar" to JsonPrimitive(path)), "images/a" to byteArrayOf(1))).state)
        }
    }
    @Test fun `S1 authentication failure cancellation and post auth exception preserve source and journal`() = runBlocking<Unit> {
        val bytes = archive(encrypted = true)
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })) isolated(bad) {
            assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { DesktopBackupReadOnlyInspector().inspectMoments(it, "synthetic-password".toCharArray()) }
        }
        isolated(bytes) { source ->
            assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { DesktopBackupReadOnlyInspector().inspectMoments(source, "wrong".toCharArray()) }
            val job = Job()
            assertFailsWith<CancellationException> { withContext(job) { DesktopBackupReadOnlyInspector { job.cancel() }.inspectMoments(source, "synthetic-password".toCharArray()) } }
            val error = assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { DesktopBackupReadOnlyInspector { error("synthetic-private") }.inspectMoments(source, "synthetic-password".toCharArray()) }
            assertNull(error.cause); assertFalse(error.toString().contains("synthetic-private"))
        }
    }
    @Test fun `sender identity source namespaces and known secrets remain explicit`() = runBlocking<Unit> {
        val files = owners() + ("entities/moment_posts/p.json" to record("senderCharacterId" to JsonPrimitive("missing")))
        val result = report(files)
        assertEquals(MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES, result.state)
        assertTrue(result.links.any { it.field == MomentArchiveReadOnlyValidator.LinkField.SENDER && it.state == MomentArchiveReadOnlyValidator.Relation.MISSING })
        val first = report(owners() + ("entities/moment_posts/p.json" to post.toByteArray()))
        val second = report(owners() + ("entities/moment_posts/p.json" to (post + " ").toByteArray()))
        assertNotEquals(first.records.single(), second.records.single())
        val secretSettings = officialWireJson.encodeToString(com.example.chatbar.data.local.entity.AppSettings(siliconFlowApiKey = "synthetic-key"))
        val blocked = report(owners() + mapOf("entities/moment_posts/p.json" to post.toByteArray(), "entities/app_settings.json" to secretSettings.toByteArray()))
        assertEquals(MomentArchiveReadOnlyValidator.State.BLOCKED, blocked.state)
        assertFalse(blocked.toString().contains("synthetic-key"))
    }
    @Test fun `isolated child exits after authentication before S1 and leaves no files`() = runBlocking<Unit> {
        isolated(archive(encrypted = true)) { source ->
            val types = listOf(S1CrashChild::class.java, DesktopBackupReadOnlyInspector::class.java,
                com.example.chatbar.data.local.entity.AppSettings::class.java, Unit::class.java,
                kotlinx.coroutines.Job::class.java, Json::class.java, kotlinx.serialization.KSerializer::class.java,
                com.sun.jna.Native::class.java, com.sun.jna.platform.win32.Kernel32::class.java,
                com.google.crypto.tink.KeysetHandle::class.java, com.google.crypto.tink.proto.Keyset::class.java,
                com.google.crypto.tink.proto.Keyset::class.java.superclass)
            val cp = types.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
            val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
            val process = ProcessBuilder(java.toString(), "-Xmx128m", "-cp", cp, S1CrashChild::class.java.name, source.toString()).redirectErrorStream(true).start()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))
            assertEquals(73, process.exitValue()); assertFalse(process.isAlive)
            println("S1 child exit=${process.exitValue()} alive=${process.isAlive}")
            assertEquals(MomentArchiveReadOnlyValidator.State.UNRESOLVED_DEPENDENCIES, DesktopBackupReadOnlyInspector().inspectMoments(source, "synthetic-password".toCharArray()).state)
        }
    }
}

object S1CrashChild {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        DesktopBackupReadOnlyInspector { Runtime.getRuntime().halt(73) }.inspectMoments(Path.of(args.single()), "synthetic-password".toCharArray())
    }
}
