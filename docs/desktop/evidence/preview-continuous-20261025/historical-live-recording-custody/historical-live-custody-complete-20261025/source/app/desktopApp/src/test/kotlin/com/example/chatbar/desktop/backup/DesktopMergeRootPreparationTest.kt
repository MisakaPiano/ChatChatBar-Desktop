package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.*
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopMergeRootPreparationTest {
    private val fake = MergeRootSyntheticProtector
    private fun own(root: Path, base: Path) = (DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired).ownership
    private fun <T> bytes(serializer: KSerializer<T>, value: T) = backupJson.encodeToJsonElement(serializer, value).toString().toByteArray(Charsets.UTF_8)
    private fun records(label: String, unique: String): Map<String, ByteArray> = linkedMapOf(
        "entities/character_cards/c.json" to bytes(CharacterCard.serializer(), CharacterCard("c", label, createdAt = 1, updatedAt = 1)),
        "entities/chat_sessions/s.json" to bytes(ChatSession.serializer(), ChatSession("s", "c", "$label branch", createdAt = 1, updatedAt = 1)),
        "entities/chat_messages/s_m.json" to bytes(ChatMessage.serializer(), ChatMessage("m", "s", MessageRole.USER, "$label content", currentAlternativeVersionId = "v", createdAt = 1, updatedAt = 1)),
        "entities/chat_sessions/$unique.json" to bytes(ChatSession.serializer(), ChatSession(unique, "c", "$label unique", createdAt = 1, updatedAt = 1)),
        "entities/chat_messages/${unique}_${unique}-message.json" to bytes(ChatMessage.serializer(), ChatMessage("$unique-message", unique, MessageRole.USER, "$label unique content", createdAt = 1, updatedAt = 1)))
    private suspend fun fixture(withPackage: Boolean = false, withEmbedding: Boolean = false, withPlanning: Boolean = false, withHistoricalLive: Boolean = false,
        action: suspend (Path, Path, DesktopFullBackupMapping.Export, DesktopFullBackupStaging, DesktopMergePreview.Selection) -> Unit) {
        val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-root").toAbsolutePath()), "case-")
        val pcRoot = Files.createDirectory(base.resolve("pc")); val original = records("Computer", "pc-only").toMutableMap()
        if (withEmbedding) original["entities/embedding_configs/default.json"] = bytes(EmbeddingConfig.serializer(),
            EmbeddingConfig("default", "Computer legacy", "https://pc-legacy.invalid/v1", "", "remote"))
        if (withPlanning) original["entities/model_configs/m.json"] = bytes(ModelConfig.serializer(),
            ModelConfig("m", "Computer model", "https://pc.invalid/v1", "", "remote", createdAt = 1))
        for ((name, value) in original) { val target = pcRoot.resolve(name); Files.createDirectories(target.parent); Files.write(target, value) }
        val pcWork = Files.createDirectory(base.resolve("pc-work")); val work = Files.createDirectory(base.resolve("work"))
        own(pcRoot, base).use {
            DesktopFullBackupMapping(pcRoot).export(pcWork, 86, StrictBackupZipReader.Limits().expanded, forMerge = true).use { pc ->
                val incoming = records("Phone", "phone-only").toMutableMap()
                if (withEmbedding) {
                    incoming["entities/embedding_configs/default.json"] = bytes(EmbeddingConfig.serializer(),
                        EmbeddingConfig("default", "Phone legacy", "https://phone-legacy.invalid/v1", "", "remote"))
                    incoming["entities/embedding_model_config/default.json"] = bytes(EmbeddingConfig.serializer(),
                        EmbeddingConfig("default", "Phone active", "https://phone-singleton.invalid/v1", "", "remote"))
                }
                if (withPlanning) {
                    incoming["entities/model_configs/m.json"] = bytes(ModelConfig.serializer(),
                        ModelConfig("m", "Phone model", "https://phone.invalid/v1", "", "remote", createdAt = 1))
                    incoming["entities/retrieval_model_config/default.json"] = bytes(ModelConfig.serializer(),
                        ModelConfig("m", "Phone planning", "https://phone.invalid/v1", "", "remote", createdAt = 1))
                }
                if (withHistoricalLive) {
                    val audio = fishPreviewMp3()
                    val voice = GeneratedVoiceMessage(id = "historical-live", sessionId = "s", messageId = "m", messageVersionId = "pruned-live-version",
                        anchorId = null, sourceOrder = 1, sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic", sourceText = "Earlier synthetic text",
                        taggedText = "Earlier synthetic text", characterId = "c", characterName = "Synthetic", voice = FishAudioVoiceBinding("fake", "Synthetic"),
                        fishModelId = "synthetic", audioPath = "chatbar-backup-file:audio/original.mp3", durationMs = 100, byteLength = audio.size.toLong(), createdAt = 1, updatedAt = 1)
                    val raw = (" \n" + bytes(GeneratedVoiceMessage.serializer(), voice).toString(Charsets.UTF_8) + "\n").toByteArray()
                    incoming["entities/generated_voice_messages/historical-live.json"] = raw
                    incoming["audio/original.mp3"] = audio
                    Files.write(base.resolve("expected-original-voice.json"), raw)
                }
                if (withPackage) {
                    val voice = GeneratedVoiceMessage(id = "old-voice", sessionId = "s", messageId = "m", messageVersionId = "pruned-version", anchorId = null,
                        sourceOrder = 1, sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic", sourceText = "Old synthetic", taggedText = "Old synthetic",
                        characterId = "c", characterName = "Synthetic", voice = FishAudioVoiceBinding("fake", "Synthetic"), fishModelId = "synthetic",
                        audioPath = "chatbar-save-slot-audio:audio", durationMs = 100, byteLength = fishPreviewMp3().size.toLong(), createdAt = 1, updatedAt = 1)
                    val nested = SyntheticSaveSlotCorpus.archive(base.resolve("nested.cbsave"), voices = listOf(voice), media = mapOf("media/audio/audio.mp3" to fishPreviewMp3()))
                    incoming["save_slot_packages/synthetic.cbsave"] = Files.readAllBytes(nested)
                    incoming["entities/save_slots/slot.json"] = bytes(SaveSlot.serializer(), SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("synthetic.cbsave", 1,
                        audioCount = 1, byteLength = Files.size(nested))))
                    val png = java.io.ByteArrayOutputStream().also { out ->
                        val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        try { javax.imageio.ImageIO.write(image, "png", out) } finally { image.flush() }
                    }.toByteArray()
                    incoming["images/source.png"] = png
                    incoming["entities/character_cards/c.json"] = bytes(CharacterCard.serializer(), CharacterCard("c", "Phone", avatar = "chatbar-backup-file:images/source.png", createdAt = 1, updatedAt = 1))
                }
                val source = Files.write(base.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(prefs = "{}", files = incoming))
                val before = Files.readAllBytes(source)
                DesktopFullBackupStaging.prepare(source, null, work).use { phone ->
                    val pcEntities = DesktopMergeRootPreparation.computerEntities(pc)
                    val media = phone.source.inventory.filterKeys { !it.startsWith("entities/") }.mapValues { it.value.sha256 }
                    val preview = DesktopMergePreview.create(pcEntities, phone.entities, emptyMap(), media)
                    val selected = preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.BOTH })
                    action(base, pcRoot, pc, phone, selected)
                }
                assertContentEquals(before, Files.readAllBytes(source))
            }
        }
        for ((path, value) in original) assertContentEquals(value, Files.readAllBytes(pcRoot.resolve(path)))
    }

    @Test fun `authenticated historical live voice keeps old ID with original custody and current playback exclusion`() = runBlocking<Unit> {
        fixture(withHistoricalLive = true) { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin()
                val result = DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work"))
                authority.complete(pending, result.files); authority.verifyAndResolve()
                DesktopFullBackupGraphValidation.validate(root)
                val storage = JsonFileStorage(root)
                val session = result.plan.identity.value("chat_sessions", "s")
                val historical = com.example.chatbar.data.repository.VoiceMessageRepository(storage, strictReads = true).listForSession(session).single()
                assertEquals("pruned-live-version", historical.messageVersionId)
                val message = ChatRepository(storage).getMessagesReadOnly(session).single()
                assertTrue(com.example.chatbar.data.repository.VoiceMessageRepository(storage, strictReads = true).listForCurrentVersion(message).isEmpty())
                val custody = root.resolve("desktop_historical_voice_sources")
                assertTrue(Files.isDirectory(custody))
                val expected = Files.readAllBytes(base.resolve("expected-original-voice.json"))
                assertTrue(Files.walk(custody).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .anyMatch { Files.readAllBytes(it).contentEquals(expected) } })
                val snapshot = com.example.chatbar.data.snapshot.AppDataSnapshotService(root).createSnapshot()
                assertTrue(com.example.chatbar.data.snapshot.AppDataSnapshotService(root).validateSnapshot(snapshot.directory).valid)
                assertTrue(Files.walk(snapshot.directory.resolve("payload/desktop_historical_voice_sources")).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .anyMatch { Files.readAllBytes(it).contentEquals(expected) } })
                val exportWork = Files.createDirectory(base.resolve("history-export-work"))
                DesktopFullBackupMapping(root).export(exportWork, 86, StrictBackupZipReader.Limits().expanded).use { exported ->
                    val archive = base.resolve("history-return.cbbackup")
                    DesktopFullBackupWriter.export(exported.files, archive, null, exported.preferences)
                    DesktopFullBackupStaging.prepare(archive, null, Files.createDirectory(base.resolve("history-return-work"))).use { returned ->
                        val other = Files.createDirectory(base.resolve("history-return-root"))
                        DesktopFullBackupMapping(other).restoreMerged(returned.files, base.resolve("history-return-work"), BackupPreferences()).use { mapped ->
                            DesktopFullBackupGraphValidation.validate(mapped.directory, futureRoot = other)
                            assertTrue(Files.walk(mapped.directory.resolve("desktop_historical_voice_sources")).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                                .anyMatch { Files.readAllBytes(it).contentEquals(expected) } })
                        }
                    }
                }
                assertTrue(DesktopVoiceAudioStore(root).deleteIfOwned(historical.audioPath))
                storage.deleteEntity("generated_voice_messages", historical.id)
                DesktopFullBackupGraphValidation.validate(root)
                assertTrue(Files.walk(custody).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".mp3") }
                    .allMatch { Files.readAllBytes(it).contentEquals(fishPreviewMp3()) } })

            }
        }
    }

    @Test fun `historical admission refuses missing custody changed binding audio and unknown evidence`() = runBlocking<Unit> {
        fixture(withHistoricalLive = true) { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin()
                val result = DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work"))
                authority.complete(pending, result.files); authority.verifyAndResolve()
                val storage = JsonFileStorage(root)
                val session = result.plan.identity.value("chat_sessions", "s")
                val voice = com.example.chatbar.data.repository.VoiceMessageRepository(storage, strictReads = true).listForSession(session).single()
                val custody = root.resolve("desktop_historical_voice_sources"); val held = base.resolve("held-custody")
                check(custody.toAbsolutePath().startsWith(base) && held.toAbsolutePath().startsWith(base))
                Files.move(custody, held)
                assertFails { DesktopFullBackupGraphValidation.validate(root) }
                Files.move(held, custody)
                storage.saveEntity("generated_voice_messages", voice.id, voice.copy(messageVersionId = "unproven"), GeneratedVoiceMessage.serializer())
                assertFails { DesktopFullBackupGraphValidation.validate(root) }
                storage.saveEntity("generated_voice_messages", voice.id, voice.copy(sourceText = "Changed synthetic source"), GeneratedVoiceMessage.serializer())
                assertFails { DesktopFullBackupGraphValidation.validate(root) }
                storage.saveEntity("generated_voice_messages", voice.id, voice, GeneratedVoiceMessage.serializer())
                val audio = Path.of(voice.audioPath); val original = Files.readAllBytes(audio)
                Files.write(audio, original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                assertFails { DesktopFullBackupGraphValidation.validate(root) }; Files.write(audio, original)
                val extra = custody.resolve("unlisted.bin"); Files.write(extra, byteArrayOf(1))
                assertFails { DesktopFullBackupGraphValidation.validate(root) }; Files.delete(extra)
                val index = Files.walk(custody).use { paths -> paths.filter { it.fileName.toString() == "index.json" }.findFirst().get() }
                val raw = Files.readAllBytes(index); val obj = backupJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
                Files.writeString(index, JsonObject(obj + ("future" to JsonPrimitive(true))).toString())
                assertFails { DesktopFullBackupGraphValidation.validate(root) }; Files.write(index, raw)
                DesktopFullBackupGraphValidation.validate(root)
            }
        }
    }

    @Test fun `cancelled historical source publication retains inert files and poisons pending capability`() = runBlocking<Unit> {
        fixture(withHistoricalLive = true) { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin()
                val pcMedia = pc.files.filterKeys { !it.startsWith("entities/") }.mapValues { DesktopArchiveFiles.hash(it.value, DesktopFullBackupSource.MAX_FILE) }
                val media = phone.source.inventory.filterKeys { !it.startsWith("entities/") && !it.startsWith("save_slot_packages/") }.mapValues { it.value.sha256 }
                val plan = DesktopMergeDestinationPlan.create(selected, pcMedia, media)
                assertFailsWith<CancellationException> { DesktopHistoricalVoiceCustody.preserve(phone, plan, root, authority, pending) { throw CancellationException("Synthetic stop") } }
                assertFails { DesktopHistoricalVoiceCustody.validate(root) }
                assertEquals("FRESH_IMPORT_PREPARATION_INVALIDATED", assertFails { authority.complete(pending, emptyMap()) }.message)
                assertTrue(Files.walk(root.resolve("desktop_historical_voice_sources")).use { paths -> paths.anyMatch { it.fileName.toString() == "index.part" } })
            }
        }
    }

    @Test fun `forking legacy embedding default cannot rename or hide active singleton role`() = runBlocking<Unit> {
        fixture(withEmbedding = true) { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin()
                val result = DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work"))
                authority.complete(pending, result.files); authority.verifyAndResolve()
                val models = com.example.chatbar.data.repository.ModelRepository(JsonFileStorage(root),
                    modelStorageKeyPolicy = com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy)
                assertEquals("https://phone-singleton.invalid/v1", models.getEmbeddingModel()?.baseUrl)
                assertEquals(2, models.getAllEmbeddings().size)
                assertTrue(Files.isRegularFile(root.resolve("entities/embedding_model_config/default.json")))
            }
        }
    }

    @Test fun `forked retired planning role is discoverable by shared migration and preserved`() = runBlocking<Unit> {
        fixture(withPlanning = true) { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin()
                val result = DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work"))
                authority.complete(pending, result.files); authority.verifyAndResolve()
                val models = com.example.chatbar.data.repository.ModelRepository(JsonFileStorage(root),
                    modelStorageKeyPolicy = com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy)
                val restored = models.getAllModels()
                assertEquals(setOf("Computer model", "Phone model", "Phone planning"), restored.map { it.displayName }.toSet())
                assertEquals(3, restored.map { it.id }.toSet().size)
            }
        }
    }

    @Test fun `actual root retains image plus original and forked historical package through modern export`() = runBlocking<Unit> {
        fixture(withPackage = true) { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin()
                val result = DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work"))
                val slot = result.plan.packages.single()
                val packagePath = root.resolve(slot.destination)
                assertEquals(1, DesktopSaveSlotInspector(true).inspect(packagePath).counts?.historicalVoices)
                DesktopSaveSlotSourceCustody.validate(root)
                assertTrue(result.files.keys.any { it.startsWith("${DesktopSaveSlotSourceCustody.ROOT}/") })
                val image = result.files.keys.single { it.startsWith("images/") }
                assertContentEquals(Files.readAllBytes(phone.files.getValue("images/source.png")), Files.readAllBytes(root.resolve(image)))
                authority.complete(pending, result.files); authority.verifyAndResolve()
                val exportWork = Files.createDirectory(base.resolve("export-work"))
                DesktopFullBackupMapping(root).export(exportWork, 86, StrictBackupZipReader.Limits().expanded).use { exported ->
                    val output = base.resolve("merged.cbbackup")
                    DesktopFullBackupWriter.export(exported.files, output, null, exported.preferences)
                    DesktopFullBackupStaging.prepare(output, null, Files.createDirectory(base.resolve("roundtrip"))).use { returned ->
                        assertTrue(returned.files.keys.any { it.startsWith("${DesktopSaveSlotSourceCustody.ROOT}/") })
                        assertTrue(returned.entities.values.any { it["content"] == JsonPrimitive("Computer content") })
                        assertTrue(returned.entities.values.any { it["content"] == JsonPrimitive("Phone content") })
                    }
                }
            }
        }
    }

    @Test fun `actual prepared root contains both branches and can complete reopen and continue`() = runBlocking<Unit> {
        fixture { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val credentials = Files.createDirectory(base.resolve("credentials"))
            val authority = DesktopFreshRootAuthority(root, credentials, fake)
            var namespace: Path? = null
            var incoming = ""
            own(root, base).use {
                val pending = authority.begin()
                val result = DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work"))
                assertFails { authority.verifyAndResolve() }
                // No runtime repositories before the genesis proof is complete.
                incoming = result.plan.identity.value("chat_sessions", "s")
                assertTrue(Files.isRegularFile(root.resolve("entities/chat_sessions/$incoming.json")))
                pending.store.save(DesktopCredentialKey.NovelAiToken, "FAKE_NEW_ROOT_ONLY")
                assertEquals("FAKE_NEW_ROOT_ONLY", pending.store.load(DesktopCredentialKey.NovelAiToken))
                authority.complete(pending, result.files); namespace = pending.credentialRoot
            }
            val log = base.resolve("child-reopen.log")
            val command = listOf(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
                "-cp", requireNotNull(System.getProperty("ccb.readonly.classpath")), MergeRootReopenProcess::class.java.name, root.toString(), credentials.toString())
            val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
            listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").forEach(builder.environment()::remove)
            val child = builder.start(); child.outputStream.close()
            try {
                assertTrue(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "MERGE_CHILD_TIMEOUT")
                assertEquals(0, child.exitValue(), "MERGE_CHILD_FAILED")
                assertEquals("MERGE_REOPEN_PASS", Files.readString(log).trim())
            } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
            own(root, base).use {
                assertEquals(namespace, DesktopFreshRootAuthority(root, credentials, fake).verifyAndResolve())
                DesktopFullBackupGraphValidation.validate(root)
                val chats = ChatRepository(JsonFileStorage(root))
                assertEquals(4, chats.getAllSessions().size)
                assertEquals("Computer content", chats.getMessages("s").single().content)
                assertEquals("Phone content", chats.getMessages(incoming).single().content)
                assertEquals("Computer unique content", chats.getMessages("pc-only").single().content)
                assertEquals(listOf("Phone unique content", "Synthetic child continuation"), chats.getMessages("phone-only").map { it.content })
                chats.addMessage(ChatMessage("continued", "pc-only", MessageRole.USER, "Synthetic continuation", createdAt = 2, updatedAt = 2))
                assertEquals(2, chats.getMessages("pc-only").size)
            }
        }
    }

    @Test fun `cancellation after real file writes leaves non runnable evidence and original sides intact`() = runBlocking<Unit> {
        fixture { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("cancelled")); val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin(); var wrote = false
                assertFailsWith<CancellationException> { DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work")) {
                    wrote = true; throw CancellationException("Synthetic cancel")
                } }
                assertTrue(wrote); assertTrue(Files.isDirectory(root.resolve("entities")))
                assertEquals("FRESH_IMPORT_PREPARATION_INVALIDATED", assertFails { authority.complete(pending, emptyMap()) }.message)
                assertFails { authority.verifyAndResolve() }
            }
        }
    }

    @Test fun `changed preview snapshot and wrong target root refuse before publication`() = runBlocking<Unit> {
        fixture { base, _, pc, phone, selected ->
            val root = Files.createDirectory(base.resolve("merged")); val other = Files.createDirectory(base.resolve("other"))
            val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), fake)
            own(root, base).use {
                val pending = authority.begin()
                assertEquals("FRESH_IMPORT_TARGET_ROOT_MISMATCH", assertFails { DesktopMergeRootPreparation.prepare(other, authority, pending, pc, phone, selected, base.resolve("work")) }.message)
                assertTrue(Files.list(other).use { it.findAny().isEmpty })
                val changed = pc.files.getValue("entities/chat_messages/s_m.json")
                Files.write(changed, bytes(ChatMessage.serializer(), ChatMessage("m", "s", MessageRole.USER, "Changed after preview", createdAt = 1, updatedAt = 1)))
                assertEquals("MERGE_COMPUTER_PREVIEW_CHANGED", assertFails { DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected, base.resolve("work")) }.message)
                assertFalse(Files.exists(root.resolve("entities"))); assertFails { authority.verifyAndResolve() }
            }
        }
    }
}

internal object MergeRootSyntheticProtector : SecretProtector {
    override fun protect(plaintext: ByteArray) = byteArrayOf(61) + plaintext.map { (it.toInt() xor 93).toByte() }
    override fun unprotect(ciphertext: ByteArray) = ciphertext.also { check(it.first() == 61.toByte()) }.drop(1).map { (it.toInt() xor 93).toByte() }.toByteArray()
}

internal object MergeRootReopenProcess {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        require(args.size == 2)
        val root = Path.of(args[0]); val credentials = Path.of(args[1])
        require(root.toString().contains("preview-synthetic") && credentials.parent == root.parent)
        val owner = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.parent.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired
        owner.ownership.use {
            val namespace = DesktopFreshRootAuthority(root, credentials, MergeRootSyntheticProtector).verifyAndResolve()
            assertEquals("FAKE_NEW_ROOT_ONLY", WindowsSecretStore(namespace, MergeRootSyntheticProtector).load(DesktopCredentialKey.NovelAiToken))
            DesktopFullBackupGraphValidation.validate(root)
            val chats = ChatRepository(JsonFileStorage(root))
            assertEquals(4, chats.getAllSessions().size)
            chats.addMessage(ChatMessage("child-continued", "phone-only", MessageRole.USER, "Synthetic child continuation", createdAt = 2, updatedAt = 2))
            println("MERGE_REOPEN_PASS")
        }
    }
}
