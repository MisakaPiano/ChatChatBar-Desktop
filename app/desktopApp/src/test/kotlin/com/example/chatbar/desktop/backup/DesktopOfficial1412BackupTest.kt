package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.SaveSlot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopOfficial1412BackupTest {
    private val participation = """[{"cardId":"c","disabledPersonIds":["p"],"automaticallyPausedPersonIds":["p"]}]"""
    private val session get() = """{"id":"s","characterCardId":"c","title":"c p remain literal","characterParticipation":$participation,"createdAt":1,"updatedAt":1}"""
    private val slot get() = """{"schemaVersion":7,"id":"slot","sessionId":"s","name":"Synthetic","characterParticipation":$participation,"chatBackground":"chatbar-backup-file:images/two.png","messages":[],"createdAt":1}"""
    private val scope = OfficialWireScope(emptySet(), 1, 1, 1, 1, 1, false, false, false, false,
        sessionDefaultChoice = OfficialWireSessionChoice())
    private fun png(color: Int) = ByteArrayOutputStream().also { out ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, color)
        assertTrue(ImageIO.write(image, "png", out))
    }.toByteArray()
    private fun files() = linkedMapOf(
        "entities/character_cards/c.json" to """{"id":"c","name":"Synthetic","characters":[{"id":"p","name":"Person","enabled":false}],"chatBackground":"chatbar-backup-file:images/two.png","chatBackgroundImages":["chatbar-backup-file:images/one.png","chatbar-backup-file:images/two.png"],"createdAt":1,"updatedAt":1}""".toByteArray(),
        "entities/chat_sessions/s.json" to session.toByteArray(),
        "entities/save_slots/slot.json" to slot.toByteArray(),
        "images/one.png" to png(0x112233), "images/two.png" to png(0x445566),
    )
    private class Sink : DesktopFullBackupSource.TentativeSink {
        val entities = linkedMapOf<String, JsonObject>()
        val media = linkedMapOf<String, ByteArray>()
        override fun entity(path: String, original: JsonObject, raw: ByteArray) { entities[path] = original }
        override fun preferences(original: JsonObject) {}
        override fun media(path: String, input: InputStream) { media[path] = input.readBytes() }
    }

    @Test fun `official and legacy participation names decode without changing the existing local spelling`() {
        for (name in listOf("characterParticipation", "desktopCharacterParticipation")) {
            val value = backupJson.parseToJsonElement(session.replace("characterParticipation", name)).jsonObject
            DesktopOfficialBackupEntitySchema.validate("chat_sessions", value, 91)
            val decoded = backupJson.decodeFromJsonElement(ChatSession.serializer(), value)
            assertEquals(setOf("p"), decoded.desktopCharacterParticipation.single().disabledPersonIds)
            val written = backupJson.encodeToJsonElement(ChatSession.serializer(), decoded).jsonObject
            assertTrue("desktopCharacterParticipation" in written)
            assertFalse("characterParticipation" in written)
        }
        val emptyScope = JsonArray(listOf(buildJsonObject { put("cardId", "c") }))
        val emptySession = JsonObject(backupJson.parseToJsonElement(session).jsonObject + ("characterParticipation" to emptyScope))
        assertTrue(backupJson.decodeFromJsonElement(ChatSession.serializer(), emptySession).desktopCharacterParticipation.single().disabledPersonIds.isEmpty())
        val emptySlot = JsonObject(backupJson.parseToJsonElement(slot).jsonObject + ("characterParticipation" to emptyScope))
        assertTrue(backupJson.decodeFromJsonElement(SaveSlot.serializer(), emptySlot).desktopCharacterParticipation.single().disabledPersonIds.isEmpty())
    }

    @Test fun `plain and encrypted code91 authenticate stage reexport and retain nested participation and every gallery image`() = runBlocking {
        for (encrypted in listOf(false, true)) {
            val input = files()
            val bytes = CensusWireFixtures.bytes(version = 2, code = 91, files = input, prefs = "{}", scope = scope,
                encrypted = encrypted, appVersionName = "1.4.12")
            CensusWireFixtures.input(bytes, "official1412-$encrypted") { path ->
                fun password() = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
                val before = Files.readAllBytes(path)
                val first = Sink()
                assertEquals(91, DesktopFullBackupSource.verify(path, password(), first).manifest.appVersionCode)
                assertEquals(91, DesktopBackupReadOnlyInspector().inspect(path, password()).appVersionCode)
                DesktopFullBackupStaging.prepare(path, password(), Files.createDirectory(path.parent.resolve("stage"))).use { staged ->
                    val output = path.parent.resolve("return.cbbackup")
                    DesktopFullBackupWriter.export(staged.files, output, null)
                    val reread = Sink()
                    assertEquals(91, DesktopFullBackupSource.verify(output, null, reread).manifest.appVersionCode)
                    assertEquals(first.entities, reread.entities)
                    for (name in listOf("images/one.png", "images/two.png")) assertContentEquals(input.getValue(name), reread.media.getValue(name))
                    val saved = backupJson.decodeFromJsonElement(SaveSlot.serializer(), reread.entities.getValue("entities/save_slots/slot.json"))
                    assertEquals(setOf("p"), saved.desktopCharacterParticipation.single().automaticallyPausedPersonIds)
                    val target = Files.createDirectory(path.parent.resolve("target"))
                    DesktopFullBackupMapping(target).restoreMerged(staged.files, path.parent, BackupPreferences()).use { mapped ->
                        val card = backupJson.parseToJsonElement(Files.readString(mapped.files.getValue("entities/character_cards/c.json"))).jsonObject
                        val gallery = card.getValue("chatBackgroundImages").jsonArray.map { it.jsonPrimitive.content }
                        assertEquals(2, gallery.size)
                        assertEquals(gallery.last(), card.getValue("chatBackground").jsonPrimitive.content)
                        for ((index, name) in listOf("images/one.png", "images/two.png").withIndex()) {
                            assertFalse(java.nio.file.Path.of(gallery[index]).isAbsolute)
                            val relative = gallery[index].replace('\\', '/')
                            assertContentEquals(input.getValue(name), Files.readAllBytes(mapped.files.getValue(relative)))
                        }
                        val mappedSlot = backupJson.decodeFromString(SaveSlot.serializer(), Files.readString(mapped.files.getValue("entities/save_slots/slot.json")))
                        assertEquals(gallery.last(), mappedSlot.chatBackground)
                        assertEquals(saved.desktopCharacterParticipation, mappedSlot.desktopCharacterParticipation)
                    }
                    staged.verifyUnchanged()
                }
                assertContentEquals(before, Files.readAllBytes(path))
            }
        }
    }

    @Test fun `pre91 manifests cannot smuggle the new official spelling even when metadata follows records`() = runBlocking {
        val record = backupJson.parseToJsonElement(session).jsonObject
        for (code in 84..90) assertFails { DesktopOfficialBackupEntitySchema.validate("chat_sessions", record, code) }
        for (encrypted in listOf(false, true)) {
            val bytes = CensusWireFixtures.bytes(version = 2, code = 90, files = files(), prefs = "{}", scope = scope, encrypted = encrypted)
            CensusWireFixtures.input(bytes, "pre1412-alias-$encrypted") { path ->
                fun password() = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
                assertEquals("ENTITY_SCHEMA", assertFailsWith<DesktopFullBackupSource.Refusal> {
                    DesktopFullBackupSource.verify(path, password(), Sink())
                }.stage)
                assertFails { DesktopBackupReadOnlyInspector().inspect(path, password()) }
            }
        }
    }

    @Test fun `both spellings unknown fields and future codes remain refused including inline slots`() {
        for ((type, text) in listOf("chat_sessions" to session, "save_slots" to slot)) {
            val value = backupJson.parseToJsonElement(text).jsonObject
            assertFails { DesktopOfficialBackupEntitySchema.validate(type, JsonObject(value + ("desktopCharacterParticipation" to JsonArray(emptyList()))), 91) }
            assertFails { DesktopOfficialBackupEntitySchema.validate(type, JsonObject(value + ("unknown" to JsonPrimitive(true))), 91) }
            assertFails { DesktopOfficialBackupEntitySchema.validate(type, value, 92) }
            val bad = JsonArray(listOf(buildJsonObject { put("cardId", ""); put("disabledPersonIds", JsonArray(emptyList())) }))
            assertFails { DesktopOfficialBackupEntitySchema.validate(type, JsonObject(value + ("characterParticipation" to bad)), 91) }
        }
    }

    @Test fun `copy identities rebind official participation card and person scopes while preserving prose and source`() {
        for ((type, text) in listOf("chat_sessions" to session, "save_slots" to slot)) {
            val value = backupJson.parseToJsonElement(text).jsonObject
            val record = DesktopMergeRecord("entities/$type/record.json", value)
            val ids = DesktopMergeIdentity().apply {
                bind("chat_sessions", "s", "copy-s"); bind("save_slots", "slot", "copy-slot")
                bind("character_cards", "c", "copy-c"); bind("characters", "p", "copy-p")
            }
            val mapped = DesktopMergeGraphRemapper(listOf(record), ids).rewrite(record)
            val participation = mapped.getValue("characterParticipation").jsonArray.single().jsonObject
            assertEquals("copy-c", participation.getValue("cardId").jsonPrimitive.content)
            for (field in listOf("disabledPersonIds", "automaticallyPausedPersonIds")) assertEquals(listOf("copy-p"), participation.getValue(field).jsonArray.map { it.jsonPrimitive.content })
            if (type == "chat_sessions") assertEquals("c p remain literal", mapped.getValue("title").jsonPrimitive.content)
            assertEquals(value, backupJson.parseToJsonElement(text).jsonObject)
        }
    }
}
