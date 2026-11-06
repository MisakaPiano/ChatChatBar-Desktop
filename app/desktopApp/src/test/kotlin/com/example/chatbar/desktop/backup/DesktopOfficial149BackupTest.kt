package com.example.chatbar.desktop.backup

import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopOfficial149BackupTest {
    private class Sink : DesktopFullBackupSource.TentativeSink {
        val entities = linkedMapOf<String, JsonObject>()
        override fun entity(path: String, original: JsonObject, raw: ByteArray) { entities[path] = original }
        override fun preferences(original: JsonObject) {}
        override fun media(path: String, input: InputStream) {}
    }
    private val message = """{"id":"m","sessionId":"s","role":"ASSISTANT","content":"b","alternatives":["a","b"],"alternativeVersionIds":["v1","v2"],"currentAlternativeIndex":1,"currentAlternativeVersionId":"v2","narrativeMode":"ADVANCE","alternativeNarrativeModes":["FOCUS","ADVANCE"],"createdAt":1,"updatedAt":1}"""
    private val session = """{"id":"s","characterCardId":"c","title":"Synthetic","narrativeMode":"ADVANCE","createdAt":1,"updatedAt":1}"""
    private val slot = """{"schemaVersion":7,"id":"slot","sessionId":"s","name":"Saved","narrativeMode":"ADVANCE","messages":[$message],"createdAt":1}"""

    @Test fun `88 reads encrypted or plain narrative fields and reexports them without losing nested candidate modes`() = runBlocking {
        for (encrypted in listOf(false, true)) {
            val files = mapOf("entities/app_settings.json" to """{"experimentalApiModeEnabled":true}""".toByteArray(),
                "entities/character_cards/c.json" to """{"id":"c","name":"Synthetic","createdAt":1,"updatedAt":1}""".toByteArray(),
                "entities/chat_sessions/s.json" to session.toByteArray(),
                "entities/chat_messages/s_m.json" to message.toByteArray(),
                "entities/save_slots/slot.json" to slot.toByteArray())
            val scope = OfficialWireScope(setOf("experimentalApiModeEnabled"), 1, 1, 1, 1, 1,
                false, false, false, false, sessionDefaultChoice = OfficialWireSessionChoice())
            val bytes = CensusWireFixtures.bytes(version = 2, code = 88, files = files, prefs = "{}", scope = scope, encrypted = encrypted)
            CensusWireFixtures.input(bytes, "official149-$encrypted") { path ->
                fun password() = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
                val sink = Sink()
                assertEquals(88, DesktopFullBackupSource.verify(path, password(), sink).manifest.appVersionCode)
                assertEquals(88, DesktopBackupReadOnlyInspector().inspect(path, password()).appVersionCode)
                DesktopFullBackupStaging.prepare(path, password(), Files.createDirectory(path.parent.resolve("stage"))).use { staged ->
                    val output = path.parent.resolve("reexport.cbbackup")
                    DesktopFullBackupWriter.export(staged.files, output, null)
                    val reread = Sink()
                    assertEquals(88, DesktopFullBackupSource.verify(output, null, reread).manifest.appVersionCode)
                    for ((name, value) in sink.entities) assertEquals(value, reread.entities[name], name)
                    assertEquals(JsonPrimitive("ADVANCE"), reread.entities.getValue("entities/save_slots/slot.json")["narrativeMode"])
                }
            }
        }
    }

    @Test fun `pre88 refuses every new field even with default value and 88 remains strict for invalid enums and future fields`() {
        val cases = mapOf("app_settings" to """{"experimentalApiModeEnabled":false}""",
            "chat_sessions" to session.replace("ADVANCE", "FOCUS"),
            "chat_messages" to message.replace("ADVANCE", "FOCUS"), "save_slots" to slot.replace("ADVANCE", "FOCUS"))
        for ((type, text) in cases) {
            val value = backupJson.parseToJsonElement(text).jsonObject
            DesktopOfficialBackupEntitySchema.validate(type, value, 88)
            for (code in 84..87) assertFails { DesktopOfficialBackupEntitySchema.validate(type, value, code) }
            assertFails { DesktopOfficialBackupEntitySchema.validate(type, value, 89) }
            assertFails { DesktopOfficialBackupEntitySchema.validate(type, JsonObject(value + ("futureField" to JsonPrimitive(true))), 88) }
        }
        assertFails { DesktopOfficialBackupEntitySchema.validate("chat_sessions",
            backupJson.parseToJsonElement(session.replace("ADVANCE", "FUTURE_MODE")).jsonObject, 88) }
        assertFails { DesktopOfficialBackupEntitySchema.validate("app_settings",
            backupJson.parseToJsonElement("""{"experimentalApiModeEnabled":"false"}""").jsonObject, 88) }
    }

    @Test fun `modern settings carry explicit disabled experimental mode and selective omission resets only transport default`() = runBlocking {
        CensusWireFixtures.input(CensusWireFixtures.bytes(files = emptyMap(), prefs = "{}"), "modern149-settings") { source ->
            val root = Files.createDirectory(source.parent.resolve("root"))
            Files.createDirectories(root.resolve("entities"))
            val settings = Files.writeString(root.resolve("entities/app_settings.json"),
                """{"experimentalApiModeEnabled":true,"defaultModelId":null}""")
            val selection = AppBackupSelection(globalOptions = setOf("defaultModelId"))
            DesktopFullBackupMapping(root).export(Files.createDirectory(source.parent.resolve("work")), 88,
                StrictBackupZipReader.Limits().expanded, selection = selection).use { mapped ->
                val output = source.parent.resolve("selected.cbbackup")
                DesktopFullBackupWriter.export(mapped.files, output, null, mapped.preferences, selection = selection)
                val sink = Sink()
                val proof = DesktopFullBackupSource.verify(output, null, sink)
                assertEquals(88, proof.manifest.appVersionCode)
                assertEquals(JsonPrimitive(false), sink.entities.getValue("entities/app_settings.json")["experimentalApiModeEnabled"])
                assertFalse("experimentalApiModeEnabled" in proof.manifest.scope!!.globalOptions)
                assertTrue(Files.readString(settings).contains("true"))
            }
            val disabled = Files.writeString(source.parent.resolve("disabled.json"), "{}")
            val output = source.parent.resolve("disabled.cbbackup")
            DesktopFullBackupWriter.export(mapOf("entities/app_settings.json" to disabled), output, null)
            val sink = Sink()
            DesktopFullBackupSource.verify(output, null, sink)
            assertEquals(JsonPrimitive(false), sink.entities.getValue("entities/app_settings.json")["experimentalApiModeEnabled"])
        }
    }
}
