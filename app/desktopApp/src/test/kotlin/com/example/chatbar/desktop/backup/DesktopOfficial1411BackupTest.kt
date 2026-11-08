package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopOfficial1411BackupTest {
    private class Sink : DesktopFullBackupSource.TentativeSink {
        val entities = linkedMapOf<String, JsonObject>()
        override fun entity(path: String, original: JsonObject, raw: ByteArray) { entities[path] = original }
        override fun preferences(original: JsonObject) {}
        override fun media(path: String, input: InputStream) {}
    }
    private val message = """{"id":"m","sessionId":"s","role":"ASSISTANT","content":"old","alternatives":["old","new"],"alternativeVersionIds":["v1","v2"],"currentAlternativeIndex":0,"currentAlternativeVersionId":"v1","narrativeMode":null,"alternativeNarrativeModes":[null,"ADVANCE"],"createdAt":1,"updatedAt":1}"""
    private val scope = OfficialWireScope(setOf("disableNarrativeModes"), 1, 1, 1, 1, 1,
        false, false, false, false, sessionDefaultChoice = OfficialWireSessionChoice())
    private val files get() = mapOf(
        "entities/app_settings.json" to """{"disableNarrativeModes":true}""".toByteArray(),
        "entities/character_cards/c.json" to """{"id":"c","name":"Synthetic","createdAt":1,"updatedAt":1}""".toByteArray(),
        "entities/chat_sessions/s.json" to """{"id":"s","characterCardId":"c","title":"Synthetic","narrativeMode":"ADVANCE","createdAt":1,"updatedAt":1}""".toByteArray(),
        "entities/chat_messages/s_m.json" to message.toByteArray(),
        "entities/save_slots/slot.json" to """{"schemaVersion":7,"id":"slot","sessionId":"s","name":"Saved","messages":[$message],"createdAt":1}""".toByteArray())

    @Test fun `plain and encrypted official1410 and1411 retain disabled settings and nested nullable candidate modes through staging writer and reread`() = runBlocking {
        for (code in 89..90) for (encrypted in listOf(false, true)) for (disabled in listOf(false, true)) {
            val selected = files + ("entities/app_settings.json" to """{"disableNarrativeModes":$disabled}""".toByteArray())
            val bytes = CensusWireFixtures.bytes(version = 2, code = code, files = selected, prefs = "{}", encrypted = encrypted, scope = scope)
            CensusWireFixtures.input(bytes, "official1410-and1411-$code-$encrypted-$disabled") { path ->
                fun password() = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
                val before = Files.readAllBytes(path)
                val sink = Sink()
                assertEquals(code, DesktopFullBackupSource.verify(path, password(), sink).manifest.appVersionCode)
                assertEquals(code, DesktopBackupReadOnlyInspector().inspect(path, password()).appVersionCode)
                DesktopFullBackupStaging.prepare(path, password(), Files.createDirectory(path.parent.resolve("stage"))).use { staged ->
                    val output = path.parent.resolve("roundtrip.cbbackup")
                    DesktopFullBackupWriter.export(staged.files, output, null)
                    val reread = Sink()
                    assertEquals(90, DesktopFullBackupSource.verify(output, null, reread).manifest.appVersionCode)
                    assertEquals(JsonPrimitive(disabled), reread.entities.getValue("entities/app_settings.json")["disableNarrativeModes"])
                    for (name in listOf("entities/chat_messages/s_m.json", "entities/save_slots/slot.json"))
                        assertEquals(sink.entities.getValue(name), reread.entities.getValue(name))
                }
                assertContentEquals(before, Files.readAllBytes(path))
            }
        }
    }

    @Test fun `pre89 wire versions reject new disable flag and explicit null modes including nested slots while89 and90 stay strict`() {
        val cases = listOf("app_settings" to """{"disableNarrativeModes":false}""",
            "chat_messages" to message,
            "save_slots" to files.getValue("entities/save_slots/slot.json").toString(Charsets.UTF_8))
        for ((type, text) in cases) {
            val value = backupJson.parseToJsonElement(text).jsonObject
            for (code in 89..90) DesktopOfficialBackupEntitySchema.validate(type, value, code)
            for (code in 84..88) assertFails { DesktopOfficialBackupEntitySchema.validate(type, value, code) }
            assertFails { DesktopOfficialBackupEntitySchema.validate(type, JsonObject(value + ("unknownField" to JsonPrimitive(true))), 90) }
            assertFails { DesktopOfficialBackupEntitySchema.validate(type, value, 91) }
        }
        for (text in listOf("""{"disableNarrativeModes":"false"}""", """{"disableNarrativeModes":null}"""))
            assertFails { DesktopOfficialBackupEntitySchema.validate("app_settings", backupJson.parseToJsonElement(text).jsonObject, 90) }
        assertFails { DesktopOfficialBackupEntitySchema.validate("chat_messages", backupJson.parseToJsonElement(message.replace("ADVANCE", "UNKNOWN")).jsonObject, 90) }
        for (code in 84..90) DesktopOfficialBackupEntitySchema.validate("chat_messages",
            backupJson.encodeToJsonElement(ChatMessage.serializer(), ChatMessage("old", "s", MessageRole.ASSISTANT, "legacy", createdAt = 1, updatedAt = 1)).jsonObject, code)
    }

    @Test fun `copy merge remaps identity without converting null candidate modes to focus`() {
        val incoming = files.mapValues { backupJson.parseToJsonElement(it.value.toString(Charsets.UTF_8)).jsonObject }
        val before = incoming.toString()
        val selected = DesktopMergePreview.create(emptyMap(), incoming, emptyMap(), emptyMap())
            .modePlan(DesktopBackupRestoreMode.ADD_COPIES).selection
        DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap()).use { plan ->
            val mapped = plan.entities.values.single { it["alternativeVersionIds"] != null }
            assertNotEquals(JsonPrimitive("m"), mapped["id"])
            assertEquals(JsonNull, mapped.getValue("narrativeMode"))
            assertEquals(JsonArray(listOf(JsonNull, JsonPrimitive("ADVANCE"))), mapped.getValue("alternativeNarrativeModes"))
            val slot = plan.entities.values.single { it["schemaVersion"] == JsonPrimitive(7) }
            val nested = slot.getValue("messages").jsonArray.single().jsonObject
            assertEquals(JsonNull, nested.getValue("narrativeMode"))
            assertEquals(mapped.getValue("alternativeNarrativeModes"), nested.getValue("alternativeNarrativeModes"))
        }
        assertEquals(before, incoming.toString())
    }

    @Test fun `authenticated older manifests cannot smuggle new modes even when entity entries precede metadata`() = runBlocking {
        for (encrypted in listOf(false, true)) CensusWireFixtures.input(CensusWireFixtures.bytes(version = 2, code = 88,
            files = files, prefs = "{}", scope = scope, encrypted = encrypted), "pre1410-$encrypted") { path ->
            fun password() = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
            val before = Files.readAllBytes(path)
            assertEquals("ENTITY_SCHEMA", assertFailsWith<DesktopFullBackupSource.Refusal> {
                DesktopFullBackupSource.verify(path, password(), Sink()) }.stage)
            assertFails { DesktopBackupReadOnlyInspector().inspect(path, password()) }
            assertContentEquals(before, Files.readAllBytes(path))
        }
    }
}
