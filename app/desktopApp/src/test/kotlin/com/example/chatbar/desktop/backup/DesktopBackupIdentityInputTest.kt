package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopBackupIdentityInputTest {
    private fun card(id: String) = backupJson.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, "Synthetic card", createdAt = 1, updatedAt = 1)).jsonObject
    private val sink = object : DesktopFullBackupSource.TentativeSink {
        override fun entity(path: String, original: JsonObject, raw: ByteArray) {}
        override fun preferences(original: JsonObject) {}
        override fun media(path: String, input: InputStream) { input.copyTo(java.io.OutputStream.nullOutputStream()) }
    }

    // Independent test-only wire creation bypasses every production writer/validator under test.
    private fun wire(root: Path, label: String, values: Map<String, JsonObject>, legacy: Boolean = false): Path {
        val files = values.mapValues { it.value.toString().toByteArray() }
        val prefs = (if (legacy) backupJson.encodeToString(BackupPreferences.serializer(), BackupPreferences())
            else backupJson.encodeToString(InspectionPreferences.serializer(), InspectionPreferences())).toByteArray()
        val counts = files.keys.groupingBy(DesktopBackupPolicy::entityType).eachCount()
        val scope = InspectionScope(inspectionGlobalOptions, counts["character_cards"] ?: 0, 0, 0, 0, 0,
            studioSettings = false, studioHistory = false, studioHistoryImages = false, moments = false)
        val manifest = InspectionManifest(if (legacy) 1 else 2, if (legacy) 84 else CURRENT_DESKTOP_BACKUP_CODE, "synthetic-only", 1, "synthetic-source", counts,
            files.size, files.values.sumOf { it.size.toLong() }, DesktopBackupPolicy.digest(prefs), if (legacy) null else scope)
        val path = root.resolve("$label.cbbackup")
        ZipOutputStream(Official147FixtureCodec.encrypting(Files.newOutputStream(path), null, if (legacy) 1 else 2)).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            files.forEach { (name, bytes) -> entry("files/$name", bytes) }
            entry("preferences.json", prefs)
            entry("inventory.jsonl", files.map { (name, bytes) -> backupJson.encodeToString(AppBackupFileRecord.serializer(),
                AppBackupFileRecord(name, bytes.size.toLong(), DesktopBackupPolicy.digest(bytes))) }.joinToString("\n").toByteArray())
            entry("manifest.json", backupJson.encodeToString(InspectionManifest.serializer(), manifest).toByteArray())
        }
        return path
    }

    @Test fun `authenticated readers and direct writer reject blank duplicate identities across typed families without rewriting input`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("backup-identities-input-")
        try {
            val inspector = DesktopBackupReadOnlyInspector()
            val valid = wire(root, "valid", mapOf("entities/character_cards/a.json" to card("a")))
            DesktopFullBackupSource.verify(valid, null, sink)
            assertTrue(inspector.inspect(valid, null).formatReadable)
            val archive = DesktopFullBackupArchive(Files.createDirectory(root.resolve("legacy-work")))
            val validLegacy = wire(root, "valid-legacy", mapOf("entities/character_cards/a.json" to card("a")), legacy = true)
            archive.inspect(validLegacy, null).use { assertEquals(1, it.files.size) }
            val families = mapOf(
                "character_cards" to card("same"),
                "format_cards" to backupJson.encodeToJsonElement(FormatCard.serializer(), FormatCard("same", "Synthetic format", "Synthetic content", createdAt = 1)).jsonObject,
                "world_books" to backupJson.encodeToJsonElement(WorldBook.serializer(), WorldBook("same", "Synthetic world")).jsonObject,
                "model_configs" to backupJson.encodeToJsonElement(ModelConfig.serializer(), ModelConfig("same", "Synthetic model", "https://invalid.example", "", "synthetic", createdAt = 1)).jsonObject)
            var sequence = 0
            for ((type, value) in families) for (blank in listOf(false, true)) {
                val values = if (blank) mapOf("entities/$type/a.json" to JsonObject(value + ("id" to JsonPrimitive(" "))))
                    else mapOf("entities/$type/a.json" to value, "entities/$type/b.json" to value)
                val input = wire(root, "bad-${sequence++}", values)
                val original = Files.readAllBytes(input)
                assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(input, null, sink) }
                assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { inspector.inspect(input, null) }
                val files = values.mapValues { (name, item) -> Files.writeString(root.resolve("record-${sequence++}.json"), item.toString()) }
                val output = root.resolve("output-${sequence++}.cbbackup")
                assertFailsWith<DesktopFullBackupWriter.Refusal> { DesktopFullBackupWriter.export(files, output, null) }
                assertFalse(Files.exists(output)); assertContentEquals(original, Files.readAllBytes(input))
                if (type == "character_cards") {
                    val legacy = wire(root, "bad-legacy-${sequence++}", values, legacy = true)
                    val originalLegacy = Files.readAllBytes(legacy)
                    assertFails { archive.inspect(legacy, null).use {} }
                    assertContentEquals(originalLegacy, Files.readAllBytes(legacy))
                }
            }
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `inline slot validation uses per slot collection identities and rejects missing resource filenames in authenticated input`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("backup-inline-input-")
        try {
            val message = ChatMessage("m", "s", MessageRole.USER, "Synthetic", createdAt = 1, updatedAt = 1)
            val slot = backupJson.encodeToJsonElement(SaveSlot.serializer(), SaveSlot(schemaVersion = 7,
                id = "slot", sessionId = "s", name = "Synthetic", messages = listOf(message), createdAt = 1)).jsonObject
            val repeated = backupJson.encodeToJsonElement(VectorChunk.serializer(), VectorChunk("v", ChunkSourceType.CHAT_MEMORY, "s", content = "Synthetic", embedding = emptyList(), createdAt = 1))
            val variants = listOf(
                JsonObject(slot + ("messages" to JsonArray(listOf(slot.getValue("messages").jsonArray.single(), slot.getValue("messages").jsonArray.single())))),
                JsonObject(slot + ("vectorChunks" to JsonArray(listOf(repeated, repeated)))),
                JsonObject(slot + ("imageResources" to buildJsonObject { put("image", buildJsonObject { put("fileName", " "); put("data", "") }) })),
                JsonObject(slot + ("audioResources" to buildJsonObject { put("audio", buildJsonObject { put("fileName", ""); put("data", "") }) })))
            for ((index, value) in variants.withIndex()) {
                val input = wire(root, "bad-inline-$index", mapOf("entities/save_slots/slot.json" to value))
                assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(input, null, sink) }
                assertFailsWith<DesktopBackupReadOnlyInspector.InspectionFailure> { DesktopBackupReadOnlyInspector().inspect(input, null) }
            }
            val valid = wire(root, "independent-slots", mapOf("entities/save_slots/slot.json" to slot,
                "entities/save_slots/second.json" to JsonObject(slot + ("id" to JsonPrimitive("second")))))
            DesktopFullBackupSource.verify(valid, null, sink)
            assertTrue(DesktopBackupReadOnlyInspector().inspect(valid, null).formatReadable)
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
