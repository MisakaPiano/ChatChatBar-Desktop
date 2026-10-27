package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.CharacterCard
import java.io.InputStream
import java.nio.file.*
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopFullBackupWriterTest {
    @Test fun `plain modern transport preserves new card fields with independent official manifest decode`() = runBlocking {
        fixture { base, files ->
            val output = base.resolve("plain.cbbackup")
            val source = Files.readAllBytes(files.values.single())
            DesktopFullBackupWriter.export(files, output, null)
            val raw = entries(output, null)
            val manifest = officialWireJson.decodeFromString(OfficialModernManifest.serializer(), raw.getValue("manifest.json").toString(Charsets.UTF_8))
            val prefs = officialWireJson.decodeFromString(OfficialModernPreferences.serializer(), raw.getValue("preferences.json").toString(Charsets.UTF_8))
            assertEquals(2, manifest.formatVersion); assertEquals(86, manifest.appVersionCode)
            assertEquals(1, manifest.scope!!.characters); assertEquals(0, manifest.scope!!.sessions)
            assertEquals(setOf("novelai_credentials", "fish_audio_credentials", "community_session"), prefs.retainedPreferences)
            assertNull(prefs.novelAiToken); assertNull(prefs.fishAudioKey); assertNull(prefs.communitySession)
            assertContentEquals(source, raw.getValue("files/entities/character_cards/card.json"))
            assertContentEquals(source, Files.readAllBytes(files.values.single()))
            val scope = backupJson.parseToJsonElement(raw.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject.getValue("scope").jsonObject
            assertFalse("sessionChoices" in scope)
            assertFalse("momentsImages" in scope) // Official true default; not a false/omitted-media assertion.
            assertEquals(JsonObject(emptyMap()), scope["sessionDefaultChoice"])
            assertEquals(2, inspect(output, null).manifest.formatVersion)
            assertFails { DesktopFullBackupArchive(Files.createDirectory(base.resolve("legacy"))).inspect(output, null) }
        }
    }

    @Test fun `password modern output authenticates and the supplied password buffer is cleared`() = runBlocking {
        fixture { base, files ->
            val output = base.resolve("encrypted.cbbackup")
            val password = "synthetic-writer-password".toCharArray()
            DesktopFullBackupWriter.export(files, output, password)
            assertTrue(password.all { it == '\u0000' })
            assertTrue(inspect(output, "synthetic-writer-password".toCharArray()).encrypted)
            assertFails { inspect(output, "incorrect-synthetic-password".toCharArray()) }
            val damaged = Files.readAllBytes(output).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val corrupt = Files.write(base.resolve("damaged.cbbackup"), damaged)
            assertFails { inspect(corrupt, "synthetic-writer-password".toCharArray()) }
        }
    }

    @Test fun `known incoming preferences credentials are rejected without output or secret exception text`() = runBlocking {
        fixture { base, files ->
            val password = "synthetic-writer-password".toCharArray()
            val destination = base.resolve("refused.cbbackup")
            val error = assertFailsWith<DesktopFullBackupWriter.Refusal> {
                DesktopFullBackupWriter.export(files, destination, password,
                    BackupPreferences(novelAiToken = "FAKE_WRITER_SECRET_NOT_REAL"))
            }
            assertEquals("INPUT", error.stage); assertFalse(error.toString().contains("FAKE_WRITER_SECRET"))
            assertFalse(Files.exists(destination)); assertTrue(password.all { it == '\u0000' })
        }
    }

    @Test fun `known model key cannot escape inside an ordinary exported entity`() = runBlocking {
        fixture { base, _ ->
            val value = """{"id":"synthetic-model","displayName":"Synthetic","baseUrl":"https://synthetic.invalid","apiKey":"FAKE_WRITER_MODEL_SECRET","modelName":"synthetic"}"""
            val file = Files.writeString(base.resolve("model.json"), value)
            val error = assertFailsWith<DesktopFullBackupWriter.Refusal> {
                DesktopFullBackupWriter.export(mapOf("entities/model_configs/synthetic-model.json" to file), base.resolve("refused.cbbackup"), null)
            }
            assertFalse(error.toString().contains("FAKE_WRITER_MODEL_SECRET"))
            assertFalse(Files.exists(base.resolve("refused.cbbackup")))
        }
    }

    @Test fun `changed snapshot source refuses publication and retains the changed file`() = runBlocking {
        fixture { base, files ->
            val file = files.values.single(); val output = base.resolve("changed.cbbackup")
            val error = assertFailsWith<DesktopFullBackupWriter.Refusal> {
                DesktopFullBackupWriter.export(files, output, null) { Files.writeString(file, "SYNTHETIC_EXTERNAL_CHANGE") }
            }
            assertEquals("SOURCE_RECHECK", error.stage); assertFalse(Files.exists(output))
            assertEquals("SYNTHETIC_EXTERNAL_CHANGE", Files.readString(file))
            assertFalse(Files.list(base).use { it.anyMatch { it.fileName.toString().startsWith("backup-modern-output-") } })
        }
    }

    @Test fun `cancellation and late destination conflict never overwrite existing files`() = runBlocking {
        fixture { base, files ->
            val output = base.resolve("kept.cbbackup")
            assertFailsWith<CancellationException> {
                DesktopFullBackupWriter.export(files, output, null) { throw CancellationException("Synthetic cancellation") }
            }
            assertFalse(Files.exists(output))
            assertFailsWith<DesktopFullBackupWriter.Refusal> {
                DesktopFullBackupWriter.export(files, output, null) { Files.writeString(output, "SYNTHETIC_UNMANAGED_DESTINATION") }
            }
            assertEquals("SYNTHETIC_UNMANAGED_DESTINATION", Files.readString(output))
            assertFalse(Files.list(base).use { it.anyMatch { it.fileName.toString().startsWith("backup-modern-output-") } })
        }
    }

    @Test fun `unknown entities and logical path aliases refuse before output`() = runBlocking {
        fixture { base, files ->
            assertFailsWith<DesktopFullBackupWriter.Refusal> {
                DesktopFullBackupWriter.export(mapOf("entities/unknown/item.json" to files.values.single()), base.resolve("unknown.cbbackup"), null)
            }
            assertFailsWith<DesktopFullBackupWriter.Refusal> {
                DesktopFullBackupWriter.export(files + ("ENTITIES/character_cards/card.json" to files.values.single()), base.resolve("alias.cbbackup"), null)
            }
            assertFalse(Files.exists(base.resolve("unknown.cbbackup"))); assertFalse(Files.exists(base.resolve("alias.cbbackup")))
        }
    }

    private suspend fun fixture(action: suspend (Path, Map<String, Path>) -> Unit) {
        val root = Files.createTempDirectory("synthetic-modern-backup-writer-")
        try {
            val file = Files.writeString(root.resolve("card.json"), backupJson.encodeToString(CharacterCard.serializer(),
                CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1)))
            action(root, mapOf("entities/character_cards/card.json" to file))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun inspect(file: Path, password: CharArray?) = DesktopFullBackupSource.verify(file, password,
        object : DesktopFullBackupSource.TentativeSink {
            override fun entity(path: String, original: JsonObject, raw: ByteArray) {}
            override fun preferences(original: JsonObject) {}
            override fun media(path: String, input: InputStream) {}
        })

    private fun entries(file: Path, password: CharArray?): Map<String, ByteArray> = Files.newInputStream(file).use { input ->
        AppBackupCodec.decryptingForInspection(input, password) { version, _ -> assertEquals(2, version) }.use { clear ->
            ZipInputStream(clear).use { zip -> buildMap {
                while (true) { val item = zip.nextEntry ?: break; put(item.name, zip.readNBytes(1024 * 1024)); zip.closeEntry() }
            } }
        }
    }
}
