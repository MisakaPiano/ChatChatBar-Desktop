package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.*
import java.nio.file.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopBackupSelectionTest {
    private val json = backupJson
    private class Store : DesktopSecretStore {
        val values = mutableMapOf<DesktopCredentialKey, String>()
        override fun load(key: DesktopCredentialKey) = values[key]
        override fun save(key: DesktopCredentialKey, secret: String) { values[key] = secret }
        override fun delete(key: DesktopCredentialKey) { values.remove(key) }
    }
    private suspend fun fixture(work: suspend (Path, Map<String, Path>) -> Unit) {
        val base = Files.createTempDirectory("selected-backup-")
        val root = Files.createDirectory(base.resolve("root"))
        try {
            fun write(path: String, value: JsonObject) { val file = root.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, value.toString()) }
            for (id in listOf("a", "b")) {
                write("entities/character_cards/$id.json", json.encodeToJsonElement(CharacterCard.serializer(), CharacterCard(id, "Card $id", createdAt = 1, updatedAt = 1)).jsonObject)
                write("entities/chat_sessions/s$id.json", json.encodeToJsonElement(ChatSession.serializer(), ChatSession("s$id", id, "Session $id", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1)).jsonObject)
                val image = root.resolve("images/$id.png"); Files.createDirectories(image.parent)
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
                val message = ChatMessage("m$id", "s$id", MessageRole.USER, "Preserved text $id", images = listOf(image.toString()), createdAt = 1, updatedAt = 1)
                write("entities/chat_messages/s${id}_m$id.json", json.encodeToJsonElement(ChatMessage.serializer(), message).jsonObject)
            }
            write("entities/app_settings.json", json.encodeToJsonElement(AppSettings.serializer(), AppSettings(defaultModelId = "model", themeMode = ThemeMode.DARK)).jsonObject)
            write("entities/model_configs/model.json", json.encodeToJsonElement(ModelConfig.serializer(),
                ModelConfig("model", "Synthetic model", "https://synthetic.invalid", "", "synthetic", createdAt = 1)).jsonObject)
            val files = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associateBy { root.relativize(it).joinToString("/") } }
            work(base, files)
        } finally { base.toFile().deleteRecursively() }
    }
    @Test fun `official selection removes a card's sessions and bulk media preserves independent choices`() = runBlocking {
        fixture { base, files ->
            val catalog = BackupExportCatalog.read(base.resolve("root").toFile(), files) {}
            val all = catalog.allSelection()
            assertEquals(setOf("a", "b"), all.characterIds)
            val removed = all.withCharacter("a", false, catalog)
            assertEquals(setOf("sb"), removed.sessions.keys)
            val light = catalog.lightSelection()
            assertEquals(all.characterIds, light.characterIds); assertEquals(all.sessions.keys, light.sessions.keys)
            assertTrue(light.sessions.values.all { !it.images && !it.voice && !it.archives })
            val images = light.withAllSessionImages(true)
            assertTrue(images.sessions.values.all { it.images && !it.voice && !it.archives })
            assertFails { catalog.plan(all.copy(characterIds = setOf("unknown"))) }
        }
    }
    @Test fun `selected export retains associated model but omits unselected card media and global settings`() = runBlocking {
        fixture { base, _ ->
            val workspace = Files.createDirectory(base.resolve("work"))
            val brokenFile = base.resolve("root/entities/character_cards/b.json")
            val broken = json.decodeFromString(CharacterCard.serializer(), Files.readString(brokenFile)).copy(customDocuments = listOf(
                DocumentInfo("doc", "Missing.txt", "documents/missing.txt", "txt", 1)))
            Files.writeString(brokenFile, json.encodeToString(CharacterCard.serializer(), broken))
            val mapping = DesktopFullBackupMapping(base.resolve("root"))
            assertFailsWith<DesktopBackupMissingResource> { mapping.export(workspace, 86, StrictBackupZipReader.Limits().expanded) }
            val selection = AppBackupSelection(characterIds = setOf("a"), sessions = mapOf("sa" to BackupSessionChoice(images = false, voice = false, archives = false)),
                sessionImagesDefault = false, sessionVoiceDefault = false, sessionArchivesDefault = false)
            mapping.export(workspace, 86, StrictBackupZipReader.Limits().expanded, selection = selection).use { selected ->
                    assertFalse(selected.files.keys.any { it.startsWith("images/") })
                    assertTrue("entities/model_configs/model.json" in selected.files)
                    assertFalse("entities/character_cards/b.json" in selected.files)
                    val message = json.parseToJsonElement(Files.readString(selected.files.getValue("entities/chat_messages/sa_ma.json"))).jsonObject
                    assertEquals("Preserved text a", message.getValue("content").jsonPrimitive.content)
                    assertTrue(message.getValue("images").jsonArray.isEmpty())
                    val settings = json.parseToJsonElement(Files.readString(selected.files.getValue("entities/app_settings.json"))).jsonObject
                    assertEquals(json.encodeToJsonElement(AppSettings.serializer(), AppSettings()).jsonObject["themeMode"], settings["themeMode"])
                    val output = base.resolve("selected.cbbackup")
                    DesktopFullBackupWriter.export(selected.files, output, null, selection = selection)
                    val result = verify(output)
                    assertEquals(1, result.manifest.scope!!.sessions)
                    assertEquals(0, result.manifest.scope!!.sessionsWithImages)
                    assertEquals(setOf("sa"), result.manifest.scope!!.sessionChoices.keys)
                    assertFalse(result.manifest.scope!!.sessionChoices.getValue("sa").archives)
            }
            assertEquals(broken, json.decodeFromString(CharacterCard.serializer(), Files.readString(brokenFile)))
        }
    }
    @Test fun `optional credentials round trip exact official fields encrypted or plain without changing source files`() = runBlocking {
        fixture { base, files ->
            val model = files.getValue("entities/model_configs/model.json")
            val before = Files.readAllBytes(model)
            val store = Store().apply {
                save(DesktopCredentialKey.ModelApiKey("model"), "SYNTHETIC_MODEL_SECRET")
                save(DesktopCredentialKey.NovelAiToken, "SYNTHETIC_NAI_SECRET")
                save(DesktopCredentialKey.FishAudioApiKey, "SYNTHETIC_FISH_SECRET")
                save(DesktopCredentialKey.SiliconFlowApiKey, "SYNTHETIC_SF_SECRET")
            }
            val entities = files.filterKeys { it.startsWith("entities/") }.mapValues { json.parseToJsonElement(Files.readString(it.value)).jsonObject }
            for (encrypted in listOf(false, true)) DesktopBackupExportCredentials.capture(store, entities).use { credentials ->
                val output = base.resolve("credentials-$encrypted.cbbackup")
                val password = if (encrypted) "synthetic-password".toCharArray() else null
                DesktopFullBackupWriter.export(files, output, password, credentials = credentials)
                if (password != null) assertTrue(password.all { it == '\u0000' })
                DesktopImportSecrets().use { imported ->
                    val result = DesktopFullBackupSource.verify(output, if (encrypted) "synthetic-password".toCharArray() else null,
                        object : DesktopFullBackupSource.TentativeSink {
                            override fun entity(path: String, original: JsonObject, raw: ByteArray) {
                                val clean = imported.entity(path, original)
                                if (DesktopBackupPolicy.entityType(path) == "model_configs") assertEquals("", clean["apiKey"]!!.jsonPrimitive.content)
                            }
                            override fun preferences(original: JsonObject) { imported.preferences(original) }
                            override fun media(path: String, input: java.io.InputStream) {}
                        })
                    assertEquals(encrypted, result.encrypted)
                    val modelSecret = imported.metadata.single { it.key == DesktopCredentialKey.ModelApiKey("model") }
                    assertEquals("SYNTHETIC_MODEL_SECRET", modelSecret.withValue { it!!.concatToString() })
                    assertEquals("SYNTHETIC_NAI_SECRET", imported.metadata.single { it.key == DesktopCredentialKey.NovelAiToken }.withValue { it!!.concatToString() })
                }
            }
            assertContentEquals(before, Files.readAllBytes(model))
            val output = base.resolve("without-credentials.cbbackup")
            DesktopFullBackupWriter.export(files, output, null)
            assertFalse(verify(output).manifest.scope!!.globalOptions.contains("novelAiCredentials"))
        }
    }
    @Test fun `unselected global credentials stay absent while dependency model key follows its model`() = runBlocking {
        fixture { base, files ->
            val store = Store().apply { save(DesktopCredentialKey.ModelApiKey("model"), "SYNTHETIC_MODEL_SECRET"); save(DesktopCredentialKey.NovelAiToken, "SYNTHETIC_NAI_SECRET") }
            val entities = files.filterKeys { it.startsWith("entities/") }.mapValues { json.parseToJsonElement(Files.readString(it.value)).jsonObject }
            DesktopBackupExportCredentials.capture(store, entities, emptySet()).use { credentials ->
                assertNull(credentials.preferences(BackupPreferences()).novelAiToken)
                assertEquals("SYNTHETIC_MODEL_SECRET", credentials.entity("entities/model_configs/model.json", entities.getValue("entities/model_configs/model.json"))["apiKey"]!!.jsonPrimitive.content)
                assertEquals("", credentials.entity("entities/app_settings.json", entities.getValue("entities/app_settings.json"))["siliconFlowApiKey"]!!.jsonPrimitive.content)
                val output = base.resolve("changed-secret.cbbackup")
                assertFailsWith<DesktopFullBackupWriter.Refusal> {
                    DesktopFullBackupWriter.export(files, output, null, credentials = credentials) { store.save(DesktopCredentialKey.ModelApiKey("model"), "SYNTHETIC_CHANGED") }
                }
                assertFalse(Files.exists(output))
            }
        }
    }
    @Test fun `recognized rebuildable Studio auxiliary caches do not reject export but unknown files remain visible failures`() = runBlocking {
        fixture { base, _ ->
            val root = base.resolve("root"); val cache = Files.createDirectories(root.resolve("auxiliary/novelai"))
            val bytes = "SQLite format 3\u0000SYNTHETIC_REBUILDABLE_CACHE".toByteArray()
            val file = Files.write(cache.resolve(DesktopBackupPolicy.digest(bytes) + ".sqlite"), bytes)
            assertTrue(DesktopFullBackupMapping(root).inventory().keys.none { it.startsWith("auxiliary/") })
            Files.write(file, byteArrayOf(1, 2, 3))
            assertFails { DesktopFullBackupMapping(root).inventory() }
            Files.write(file, bytes)
            val unknown = Files.writeString(cache.resolve("my-notes.txt"), "SYNTHETIC_USER_CONTENT")
            assertFails { DesktopFullBackupMapping(root).inventory() }
            assertEquals("SYNTHETIC_USER_CONTENT", Files.readString(unknown))
        }
    }
    private fun verify(output: Path) = DesktopFullBackupSource.verify(output, null, object : DesktopFullBackupSource.TentativeSink {
        override fun entity(path: String, original: JsonObject, raw: ByteArray) {}
        override fun preferences(original: JsonObject) {}
        override fun media(path: String, input: java.io.InputStream) {}
    })
}
