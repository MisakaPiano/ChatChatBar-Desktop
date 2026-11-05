package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.image.NovelAiEffort
import com.example.chatbar.domain.image.NovelAiStudioDraft
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Exact synthetic output from the frozen published production writer, not a relabeled current archive. */
internal fun published1238StudioBackup(): ByteArray {
    val text = requireNotNull(DesktopOfficial148BackupTest::class.java.getResourceAsStream("/interop/published1238-studio-v5.fixture.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    val fixture = backupJson.parseToJsonElement(text).jsonObject
    val bytes = Base64.getDecoder().decode(fixture.getValue("originalBase64").jsonPrimitive.content)
    assertEquals(2407, bytes.size)
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    assertEquals("d01775e0695b1413905f98cbf32756061116a5cd3c72e9c13032eba5fb603cc0", sha)
    return bytes
}

class DesktopOfficial148BackupTest {
    private class Sink : DesktopFullBackupSource.TentativeSink {
        val entities = linkedMapOf<String, JsonObject>()
        override fun entity(path: String, original: JsonObject, raw: ByteArray) { entities[path] = original }
        override fun preferences(original: JsonObject) {}
        override fun media(path: String, input: InputStream) {}
    }
    private val card = """{"id":"c","name":"Synthetic","defaultNovelAiImageModel":"V5_FULL","defaultImageGenerationSettings":{"steps":31,"guidance":6.5,"cfgRescale":0.4,"effort":"MEDIUM","transparentBackground":true},"createdAt":1,"updatedAt":1}"""
    private val cardScope = OfficialWireScope(emptySet(), 1, 0, 0, 0, 0, false, false, false, false)

    @Test fun `known84 through87 stay readable but unknown versions refuse without changing source`() = runBlocking<Unit> {
        for (code in listOf(83, 84, 85, 86, 87, 88)) {
            val bytes = CensusWireFixtures.bytes(version = if (code < 85) 1 else 2, code = code, files = emptyMap(), prefs = "{}")
            CensusWireFixtures.input(bytes, "known-version-$code") { path ->
                if (code in 84..87) {
                    assertEquals(code, DesktopFullBackupSource.verify(path, null, Sink()).manifest.appVersionCode)
                    assertEquals(code, DesktopBackupReadOnlyInspector().inspect(path, null).appVersionCode)
                } else {
                    assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(path, null, Sink()) }
                    assertFails { DesktopBackupReadOnlyInspector().inspect(path, null) }
                }
            }
        }
    }

    @Test fun `official87 card fields survive actual stream staging and truthful modern reexport`() = runBlocking<Unit> {
        for (encrypted in listOf(false, true)) {
            val bytes = CensusWireFixtures.bytes(version = 2, code = 87, encrypted = encrypted,
                files = mapOf("entities/character_cards/c.json" to card.toByteArray()), prefs = "{}", scope = cardScope)
            CensusWireFixtures.input(bytes, "official87-card-$encrypted") { path ->
                fun password() = if (encrypted) CensusWireFixtures.PASSWORD.toCharArray() else null
                val sink = Sink()
                val proof = DesktopFullBackupSource.verify(path, password(), sink)
                assertEquals(87, proof.manifest.appVersionCode); assertFalse(proof.historicalDesktopV5Settings)
                val selected = backupJson.decodeFromJsonElement<CharacterCard>(sink.entities.values.single()).defaultImageGenerationSettings!!
                assertEquals(NovelAiEffort.MEDIUM, selected.effort); assertTrue(selected.transparentBackground)
                assertEquals(87, DesktopBackupReadOnlyInspector().inspect(path, password()).appVersionCode)
                DesktopFullBackupStaging.prepare(path, password(), Files.createDirectory(path.parent.resolve("staging"))).use { staged ->
                    val output = path.parent.resolve("reexport.cbbackup")
                    DesktopFullBackupWriter.export(staged.files, output, null)
                    val reread = Sink(); val after = DesktopFullBackupSource.verify(output, null, reread)
                    assertEquals(87, after.manifest.appVersionCode)
                    assertEquals(selected, backupJson.decodeFromJsonElement<CharacterCard>(reread.entities.values.single()).defaultImageGenerationSettings)
                }
            }
        }
    }

    @Test fun `old official declarations and spoofed historical header cannot claim new card fields`() = runBlocking<Unit> {
        for (code in listOf(84, 85, 86)) for (name in listOf("synthetic official", "CCB Desktop / official 1.4.7 transport compatibility")) {
            val bytes = CensusWireFixtures.bytes(version = if (code == 84) 1 else 2, code = code,
                files = mapOf("entities/character_cards/c.json" to card.toByteArray()), prefs = "{}", scope = cardScope, appVersionName = name)
            CensusWireFixtures.input(bytes, "pre148-card-$code-${name.length}") { path ->
                assertFailsWith<DesktopFullBackupSource.Refusal> { DesktopFullBackupSource.verify(path, null, Sink()) }
                assertFails { DesktopBackupReadOnlyInspector().inspect(path, null) }
            }
        }
    }

    @Test fun `actual published1238 output remains recoverable and reexports87 without losing Studio choices`() = runBlocking<Unit> {
        CensusWireFixtures.input(published1238StudioBackup(), "actual-published1238-v5") { path ->
            val sink = Sink(); val proof = DesktopFullBackupSource.verify(path, null, sink)
            assertEquals(86, proof.manifest.appVersionCode); assertTrue(proof.historicalDesktopV5Settings)
            assertTrue(DesktopBackupReadOnlyInspector().inspect(path, null).historicalDesktopV5Settings)
            DesktopFullBackupStaging.prepare(path, null, Files.createDirectory(path.parent.resolve("staging"))).use { staged ->
                assertTrue(staged.hasDesktopParticipation)
                val output = path.parent.resolve("migrated.cbbackup")
                DesktopFullBackupWriter.export(staged.files, output, null)
                val reread = Sink(); val after = DesktopFullBackupSource.verify(output, null, reread)
                assertEquals(87, after.manifest.appVersionCode); assertFalse(after.historicalDesktopV5Settings)
                val draft = backupJson.decodeFromJsonElement<NovelAiStudioDraft>(reread.entities.getValue("entities/novelai_studio_draft.json"))
                assertEquals(NovelAiEffort.MEDIUM, draft.v5Settings.effort); assertTrue(draft.v5Settings.transparentBackground)
                assertEquals(backupJson.decodeFromJsonElement<NovelAiStudioDraft>(sink.entities.values.single()), draft)
            }
        }
    }

    @Test fun `unknown nested fields still refuse and similarly named ordinary text is not a V5 field`() = runBlocking<Unit> {
        val corrupt = card.replace("\"effort\":\"MEDIUM\"", "\"effort\":\"MEDIUM\",\"futureField\":true")
        CensusWireFixtures.input(CensusWireFixtures.bytes(version = 2, code = 87, files = mapOf("entities/character_cards/c.json" to corrupt.toByteArray()), prefs = "{}", scope = cardScope), "unknown87-card") { path ->
            assertFails { DesktopFullBackupSource.verify(path, null, Sink()) }
        }
        val model = JsonObject(backupJson.parseToJsonElement(CensusWireFixtures.model()).jsonObject +
            ("displayName" to JsonPrimitive("effort transparentBackground"))).toString()
        CensusWireFixtures.input(CensusWireFixtures.bytes(version = 2, code = 86, files = mapOf("entities/model_configs/m.json" to model.toByteArray()), prefs = "{}"), "old86-unrelated-text") { path ->
            assertFalse(DesktopFullBackupSource.verify(path, null, Sink()).historicalDesktopV5Settings)
        }
    }
}
