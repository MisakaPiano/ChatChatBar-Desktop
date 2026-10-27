package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import java.nio.file.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class DesktopFullBackupMappingTest {
    @Test fun `bad existing ownership cannot fall back to a newly derived clip and unowned originals survive`() = runBlocking {
        for (kind in listOf("unowned", "bad-hash", "bad-length")) {
            val base = Files.createTempDirectory("synthetic-retained-audio-")
            try {
                val source = Files.createDirectory(base.resolve("source"))
                val target = Files.createDirectory(base.resolve("target"))
                val work = Files.createDirectory(base.resolve("work"))
                val stored = DesktopVoiceAudioStore(source).persist("s", "clip", fishPreviewMp3().inputStream())
                val path = Path.of(stored.path)
                val relative = if (kind == "unowned") "audio/from-phone.mp3" else "audio/generated/s/clip.mp3"
                val voice = GeneratedVoiceMessage("voice", "s", "m", "v", sourceOrder = 0,
                    sourceSegmentKind = "NARRATION", sourceSpeakerName = "", sourceText = "Synthetic",
                    taggedText = "Synthetic", characterId = "card", characterName = "Synthetic",
                    voice = FishAudioVoiceBinding("ref", "Synthetic"), fishModelId = "synthetic",
                    audioPath = DesktopBackupPolicy.PREFIX + relative, durationMs = stored.durationMs,
                    byteLength = stored.byteLength, createdAt = 1, updatedAt = 1)
                val record = Files.writeString(base.resolve("voice.json"), backupJson.encodeToString(GeneratedVoiceMessage.serializer(), voice))
                val inputs = linkedMapOf("entities/generated_voice_messages/voice.json" to record, relative to path)
                if (kind != "unowned") {
                    val receipt = path.resolveSibling("${path.fileName}.owner")
                    val hash = if (kind == "bad-hash") "0".repeat(64) else DesktopArchiveFiles.hash(path, 16L shl 20)
                    val length = stored.byteLength + if (kind == "bad-length") 1 else 0
                    Files.writeString(receipt, "$length\n$hash\n")
                    inputs["$relative.owner"] = receipt
                }
                val before = inputs.mapValues { Files.readAllBytes(it.value) }
                if (kind == "unowned") {
                    DesktopFullBackupMapping(target).restoreMerged(inputs, work, BackupPreferences()).use { plan ->
                        assertContentEquals(fishPreviewMp3(), Files.readAllBytes(plan.files.getValue(relative)))
                        assertEquals(2, plan.files.keys.count { it.startsWith("audio/") && it.endsWith(".mp3") })
                        val restored = backupJson.decodeFromString(GeneratedVoiceMessage.serializer(),
                            Files.readString(plan.files.getValue("entities/generated_voice_messages/voice.json")))
                        assertEquals(voice.messageVersionId, restored.messageVersionId)
                        val owned = target.relativize(Path.of(restored.audioPath)).joinToString("/")
                        assertNotEquals(relative, owned)
                        assertTrue("$owned.owner" in plan.files)
                        DesktopVoiceAudioStore(plan.directory).verify(plan.directory.resolve(owned).toString())
                    }
                } else {
                    assertFailsWith<IllegalStateException> {
                        DesktopFullBackupMapping(target).restoreMerged(inputs, work, BackupPreferences()).use { error("Corrupt receipt was admitted") }
                    }
                }
                before.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(inputs.getValue(name))) }
                assertTrue(Files.list(target).use { it.findAny().isEmpty })
            } finally { base.toFile().deleteRecursively() }
        }
    }
    @Test fun `recorded audio byte length mismatch cannot be normalized into successful restore`() = runBlocking {
        val actual = fishPreviewMp3().size.toLong()
        for (declared in listOf(-1L, 0L, actual - 1, actual + 1)) {
            assertInvalidRecording(declared, 1, "RECORDING_AUDIO_LENGTH_MISMATCH")
        }
    }

    @Test fun `negative source duration cannot be hidden by reparsing the clip`() = runBlocking {
        assertInvalidRecording(fishPreviewMp3().size.toLong(), -1, "RECORDING_DURATION_INVALID")
    }

    private suspend fun assertInvalidRecording(bytes: Long, duration: Long, reason: String) {
        val base = Files.createTempDirectory("synthetic-recording-metadata-")
        try {
            val target = Files.createDirectory(base.resolve("target"))
            val work = Files.createDirectory(base.resolve("work"))
            val audio = Files.write(base.resolve("fixture.mp3"), fishPreviewMp3())
            val voice = GeneratedVoiceMessage("voice", "s", "m", "v", sourceOrder = 0,
                sourceSegmentKind = "NARRATION", sourceSpeakerName = "", sourceText = "Synthetic text",
                taggedText = "Synthetic text", characterId = "card", characterName = "Synthetic",
                voice = FishAudioVoiceBinding("ref", "Synthetic"), fishModelId = "synthetic",
                audioPath = DesktopBackupPolicy.PREFIX + "audio/source.mp3", durationMs = duration,
                byteLength = bytes, createdAt = 1, updatedAt = 1)
            val record = Files.writeString(base.resolve("voice.json"), backupJson.encodeToString(GeneratedVoiceMessage.serializer(), voice))
            val archive = DesktopFullBackupArchive(work)
            val original = base.resolve("synthetic.cbbackup")
            archive.export(mapOf("entities/generated_voice_messages/voice.json" to record,
                "audio/source.mp3" to audio), original, target.toString(), null)
            val before = Files.readAllBytes(original)
            archive.inspect(original, null).use { prepared ->
                var unexpected: DesktopFullBackupMapping.Restore? = null
                try {
                    val failure = assertFailsWith<IllegalStateException> {
                        unexpected = DesktopFullBackupMapping(target).restore(prepared)
                    }
                    assertEquals(reason, failure.message)
                } finally { unexpected?.close() }
                archive.verify(prepared)
            }
            assertContentEquals(before, Files.readAllBytes(original))
            assertTrue(Files.list(target).use { it.findAny().isEmpty })
        } finally { base.toFile().deleteRecursively() }
    }

    @Test fun `whole app mapping preserves identities versions and bytes while removing exported credentials`() = runBlocking {
        val base = Files.createTempDirectory("full-backup-map-")
        try {
            val source = Files.createDirectory(base.resolve("source"))
            val target = Files.createDirectory(base.resolve("target"))
            val work = Files.createDirectory(base.resolve("work"))
            val storage = JsonFileStorage(source)
            val message = SyntheticSaveSlotCorpus.message().copy(images = listOf("images/original.png"))
            val session = ChatSession.create("card", "Synthetic").copy(id = "s", modelId = "vendor:model")
            storage.saveEntity("chat_sessions", "s", session, ChatSession.serializer())
            storage.saveEntity("chat_messages", "s_m", message, ChatMessage.serializer())
            val model = ModelConfig.create("vendor:model", "https://synthetic.invalid", "synthetic-key-not-real", "Synthetic")
                .copy(id = "vendor:model")
            storage.saveEntity("model_configs", WindowsSafeModelStorageKeyPolicy.storageKey(model.id), model, ModelConfig.serializer())
            val image = java.awt.image.BufferedImage(12, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val imageBytes = java.io.ByteArrayOutputStream().use { output -> javax.imageio.ImageIO.write(image, "png", output); output.toByteArray() }
            image.flush()
            Files.createDirectories(source.resolve("images")); Files.write(source.resolve("images/original.png"), imageBytes)
            val preferences = BackupPreferences(brushSize = 21f, brushType = "Brush")
            Files.writeString(source.resolve("entities/desktop_backup_preferences.json"), backupJson.encodeToString(BackupPreferences.serializer(), preferences))
            val audio = DesktopVoiceAudioStore(source).persist("s", "first", fishPreviewMp3().inputStream())
            val voice = GeneratedVoiceMessage("voice", "s", "m", "v", sourceOrder = 0,
                sourceSegmentKind = "NARRATION", sourceSpeakerName = "", sourceText = message.content,
                taggedText = message.content, characterId = "card", characterName = "Synthetic", voice = FishAudioVoiceBinding("ref", "Synthetic"),
                fishModelId = "synthetic", audioPath = audio.path, durationMs = audio.durationMs, byteLength = audio.byteLength, createdAt = 1, updatedAt = 1)
            storage.saveEntity("generated_voice_messages", voice.id, voice, GeneratedVoiceMessage.serializer())
            val exporter = DesktopFullBackupMapping(source)
            val before = exporter.inventory()
            val exported = exporter.export(work)
            assertTrue("entities/model_configs/vendor:model.json" in exported.files)
            val exportedModel = backupJson.decodeFromString(ModelConfig.serializer(), Files.readString(exported.files.getValue("entities/model_configs/vendor:model.json")))
            assertEquals("", exportedModel.apiKey)
            val archive = DesktopFullBackupArchive(work)
            val output = base.resolve("export.cbbackup")
            assertEquals(preferences, exported.preferences)
            assertFalse("entities/desktop_backup_preferences.json" in exported.files)
            archive.export(exported.files, output, source.toString(), null, exported.preferences)
            val prepared = archive.inspect(output, null)
            val importer = DesktopFullBackupMapping(target)
            val plan = importer.restore(prepared)
            importer.verify(plan)
            DesktopFullBackupGraphValidation.validate(plan.directory)
            assertEquals(preferences, backupJson.decodeFromString(BackupPreferences.serializer(), Files.readString(plan.files.getValue("entities/desktop_backup_preferences.json"))))
            val restored = backupJson.decodeFromString(ChatMessage.serializer(), Files.readString(plan.files.getValue("entities/chat_messages/s_m.json")))
            assertEquals(message.id, restored.id); assertEquals(message.sourceTurnId, restored.sourceTurnId)
            assertEquals(message.alternativeVersionIds, restored.alternativeVersionIds)
            assertEquals(2, Path.of(restored.images.single()).nameCount)
            assertContentEquals(imageBytes, Files.readAllBytes(plan.files.getValue(restored.images.single())))
            val recorded = backupJson.decodeFromString(GeneratedVoiceMessage.serializer(), Files.readString(plan.files.getValue("entities/generated_voice_messages/voice.json")))
            assertEquals(voice.id, recorded.id); assertEquals(voice.messageVersionId, recorded.messageVersionId)
            val audioRelative = target.relativize(Path.of(recorded.audioPath)).joinToString("/")
            assertContentEquals(fishPreviewMp3(), Files.readAllBytes(plan.files.getValue(audioRelative)))
            assertTrue("$audioRelative.owner" in plan.files)
            assertEquals(exported.files.keys.count { it.startsWith("audio/") && it.endsWith(".mp3") },
                plan.files.keys.count { it.startsWith("audio/") && it.endsWith(".mp3") },
                "A verified existing Desktop clip must not gain an extra MP3 on every restoration")
            assertEquals(source.relativize(Path.of(audio.path)).toString(), target.relativize(Path.of(recorded.audioPath)).toString())
            assertTrue("entities/model_configs/${WindowsSafeModelStorageKeyPolicy.storageKey(model.id)}.json" in plan.files)
            assertEquals(before, exporter.inventory())
            plan.close(); prepared.close(); exported.close()
            assertFalse(Files.exists(plan.directory))
            assertFalse(Files.exists(prepared.directory))
            assertFalse(Files.exists(exported.directory))
            assertTrue(Files.exists(output))
            assertEquals(before, exporter.inventory())
        } finally { base.toFile().deleteRecursively() }
    }

    @Test fun `lightweight legacy catalog skips inline media and requires a complete JSON object`() {
        val body = """{"id":"slot","sessionId":"s","name":"Synthetic","createdAt":1,"messages":[{"largeBody":"unused"}],"imageResources":{"image":{"data":"not decoded by listing"}},"audioResources":{}}"""
        val result = DesktopSaveSlotSummaryReader.read("slot", body.byteInputStream())
        assertEquals(1, result.messageCount); assertEquals(1, result.imageCount)
        assertFails { DesktopSaveSlotSummaryReader.read("slot", (body + "{}").byteInputStream()) }
        assertFails { DesktopSaveSlotSummaryReader.read("wrong-id", body.byteInputStream()) }
    }
}
