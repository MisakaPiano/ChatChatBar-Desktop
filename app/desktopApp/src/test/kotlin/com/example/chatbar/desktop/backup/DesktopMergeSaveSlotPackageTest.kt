package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.*
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopMergeSaveSlotPackageTest {
    private fun home() = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-package").toAbsolutePath()), "case-")
    private fun slot(session: String = "s", audio: Int = 0) = DesktopMergeRecord("entities/save_slots/slot.json", backupJson.encodeToJsonElement(SaveSlot.serializer(),
        SyntheticSaveSlotCorpus.slot().copy(sessionId = session, packageRef = SaveSlotPackageRef("synthetic.cbsave", messageCount = 1, audioCount = audio))).jsonObject)
    private fun ids() = DesktopMergeIdentity().apply {
        bind("save_slots", "slot", "fork-slot"); bind("chat_sessions", "s", "fork-s")
        bind("chat_messages", "m", "fork-m"); bind("generated_voice_messages", "voice", "fork-voice")
    }
    private fun voice(version: String?) = GeneratedVoiceMessage(id = "voice", sessionId = "s", messageId = "m", messageVersionId = version,
        anchorId = "old-segment-anchor", sourceOrder = 1, sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic",
        sourceText = "Synthetic original source", taggedText = "Synthetic original source", characterId = "c", characterName = "Synthetic",
        voice = FishAudioVoiceBinding("fake-reference", "Synthetic"), fishModelId = "synthetic", audioPath = "chatbar-save-slot-audio:audio",
        durationMs = 100, byteLength = fishPreviewMp3().size.toLong(), createdAt = 1, updatedAt = 1)
    private fun json(zip: ZipFile, name: String) = backupJson.parseToJsonElement(zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()).jsonObject

    @Test fun `forked v8 keeps original custody media historical voice and coherent message identities`() = runBlocking<Unit> {
        val home = home(); val source = SyntheticSaveSlotCorpus.archive(home.resolve("source.cbsave"), voices = listOf(voice("pruned-version")),
            media = mapOf("media/audio/audio.mp3" to fishPreviewMp3()))
        val before = Files.readAllBytes(source)
        DesktopMergeSaveSlotPackage.prepare(source, slot(audio = 1), ids(), emptyList(), home).use { result ->
            assertEquals("fork-slot.cbsave", result.fileName); assertEquals(Files.size(result.rewritten), result.bytes)
            assertContentEquals(before, Files.readAllBytes(result.original))
            ZipFile(result.rewritten.toFile()).use { zip ->
                val manifest = json(zip, "manifest.json"); val message = json(zip, "messages.jsonl"); val recording = json(zip, "voices.jsonl")
                assertEquals(JsonPrimitive("fork-slot"), manifest["id"]); assertEquals(JsonPrimitive("fork-s"), manifest["sessionId"])
                assertEquals(JsonPrimitive("fork-slot.cbsave"), manifest.getValue("packageRef").jsonObject["fileName"])
                assertEquals(JsonPrimitive("fork-m"), message["id"]); assertEquals(JsonPrimitive("v"), message["currentAlternativeVersionId"])
                assertEquals(JsonPrimitive("turn"), message["sourceTurnId"])
                assertEquals(JsonPrimitive("fork-m"), recording["messageId"]); assertEquals(JsonPrimitive("pruned-version"), recording["messageVersionId"])
                assertEquals(JsonPrimitive("old-segment-anchor"), recording["anchorId"])
                assertEquals(JsonPrimitive("Synthetic original source"), recording["sourceText"])
                assertContentEquals(fishPreviewMp3(), zip.getInputStream(zip.getEntry("media/audio/audio.mp3")).use { it.readBytes() })
            }
            assertEquals(1, DesktopSaveSlotInspector(true).inspect(result.rewritten).counts?.historicalVoices)
        }
        assertContentEquals(before, Files.readAllBytes(source))
        assertEquals(listOf("source.cbsave"), Files.list(home).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test fun `legacy derived versions are pinned before changing message ID`() = runBlocking<Unit> {
        val home = home(); val message = SyntheticSaveSlotCorpus.message(text = "Current").copy(alternatives = listOf("Earlier", "Current"), currentAlternativeIndex = 1,
            currentAlternativeVersionId = null, alternativeVersionIds = emptyList())
        val versions = MessageAlternativeVersionPolicy.versions(message)
        val source = SyntheticSaveSlotCorpus.archive(home.resolve("source.cbsave"), messages = listOf(message), voices = listOf(voice(versions[0].id)),
            media = mapOf("media/audio/audio.mp3" to fishPreviewMp3()))
        DesktopMergeSaveSlotPackage.prepare(source, slot(audio = 1), ids(), emptyList(), home).use { result ->
            ZipFile(result.rewritten.toFile()).use { zip ->
                val mapped = backupJson.decodeFromJsonElement(ChatMessage.serializer(), json(zip, "messages.jsonl"))
                assertEquals(versions, MessageAlternativeVersionPolicy.versions(mapped))
                assertEquals(JsonPrimitive(versions[0].id), json(zip, "voices.jsonl")["messageVersionId"])
            }
            assertEquals(0, DesktopSaveSlotInspector(true).inspect(result.rewritten).counts?.historicalVoices)
        }
    }

    @Test fun `single legacy current version remains original message ID after fork`() = runBlocking<Unit> {
        val home = home(); val message = SyntheticSaveSlotCorpus.message().copy(currentAlternativeVersionId = null)
        val source = SyntheticSaveSlotCorpus.archive(home.resolve("source.cbsave"), messages = listOf(message), voices = listOf(voice("m")),
            media = mapOf("media/audio/audio.mp3" to fishPreviewMp3()))
        DesktopMergeSaveSlotPackage.prepare(source, slot(audio = 1), ids(), emptyList(), home).use { result ->
            ZipFile(result.rewritten.toFile()).use { zip -> assertEquals(JsonPrimitive("m"), json(zip, "messages.jsonl")["currentAlternativeVersionId"]) }
            assertEquals(0, DesktopSaveSlotInspector(true).inspect(result.rewritten).counts?.historicalVoices)
        }
    }

    @Test fun `owner mismatch invalid package and cancellation cannot publish output`() = runBlocking<Unit> {
        val home = home(); val source = SyntheticSaveSlotCorpus.archive(home.resolve("source.cbsave")); val before = Files.readAllBytes(source)
        assertEquals("MERGE_PACKAGE_OWNER_IDENTITY", assertFails { DesktopMergeSaveSlotPackage.prepare(source, slot("other"), ids(), emptyList(), home) }.message)
        assertFailsWith<CancellationException> { DesktopMergeSaveSlotPackage.prepare(source, slot(), ids(), emptyList(), home) { throw CancellationException("Synthetic cancel") } }
        assertContentEquals(before, Files.readAllBytes(source))
        val invalid = home.resolve("invalid.cbsave"); Files.write(invalid, byteArrayOf(1, 2, 3))
        assertEquals("MERGE_SOURCE_PACKAGE_INVALID", assertFails { DesktopMergeSaveSlotPackage.prepare(invalid, slot(), ids(), emptyList(), home) }.message)
        assertEquals(setOf("source.cbsave", "invalid.cbsave"), Files.list(home).use { it.map { p -> p.fileName.toString() }.toList().toSet() })
    }

    @Test fun `shared imported library envelope catalogs forks and preserves original package without rebinding history`() = runBlocking<Unit> {
        val home = home()
        val source = SyntheticSaveSlotCorpus.archive(home.resolve("source.cbsave"), voices = listOf(voice("pruned-version")),
            media = mapOf("media/audio/audio.mp3" to fishPreviewMp3()))
        val original = Files.readAllBytes(source)
        val library = home.resolve("library")
        val codec = SaveSlotPackageStorage(library.toFile(), { _, _ -> error("No re-encoding") },
            maximumImportBytes = DesktopSaveSlotArchive.MAX_ARCHIVE)
        val imported = Files.newInputStream(source).use { codec.importPackage(it, "library-session") { "Renamed library entry" }.slot }
        val packagePath = library.resolve("save_slot_packages").resolve(requireNotNull(imported.packageRef).fileName)
        val record = DesktopMergeRecord("entities/save_slots/${imported.id}.json", backupJson.encodeToJsonElement(SaveSlot.serializer(), imported).jsonObject)
        val catalog = DesktopMergeSaveSlotPackage.catalog(packagePath, record, home)
        assertEquals(imported.id, catalog.manifest.text("id"))
        assertTrue("save_slots" to imported.id in catalog.declarations)
        assertFalse("save_slots" to "slot" in catalog.declarations)
        val identity = ids().apply {
            bind("save_slots", imported.id, "fork-library-slot")
            bind("chat_sessions", "library-session", "fork-library-session")
        }
        DesktopMergeSaveSlotPackage.prepare(packagePath, record, identity, emptyList(), home).use { prepared ->
            assertEquals("fork-s", identity.value("chat_sessions", "s"))
            assertEquals("fork-slot", identity.value("save_slots", "slot"))
            assertContentEquals(original, Files.readAllBytes(prepared.original))
            ZipFile(prepared.rewritten.toFile()).use { zip ->
                val manifest = json(zip, "manifest.json")
                assertEquals(JsonPrimitive("fork-library-slot"), manifest["id"])
                assertEquals(JsonPrimitive("fork-library-session"), manifest["sessionId"])
                assertEquals(JsonPrimitive("Renamed library entry"), manifest["name"])
                assertEquals(JsonPrimitive("fork-library-session"), json(zip, "messages.jsonl")["sessionId"])
                val recording = json(zip, "voices.jsonl")
                assertEquals(JsonPrimitive("fork-library-session"), recording["sessionId"])
                assertEquals(JsonPrimitive("fork-m"), recording["messageId"])
                assertEquals(JsonPrimitive("pruned-version"), recording["messageVersionId"])
                assertEquals(JsonPrimitive("Synthetic original source"), recording["sourceText"])
                assertContentEquals(fishPreviewMp3(), zip.getInputStream(zip.getEntry("media/audio/audio.mp3")).use { it.readBytes() })
            }
            val custody = Files.createDirectory(home.resolve("custody"))
            val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
                DesktopDataRootResolution.Resolved(custody, DesktopDataRootProvenance.CLI_OVERRIDE, home.resolve("unused-bootstrap")))).ownership
            owner.use {
                DesktopSaveSlotSourceCustody.preserve(custody, record.relative, record.value.toString().toByteArray(), prepared.original,
                    "1".repeat(64), 84, 1, null)
                DesktopSaveSlotSourceCustody.validate(custody)
            }
        }
        assertContentEquals(original, Files.readAllBytes(source))
        assertContentEquals(original, Files.readAllBytes(packagePath))
    }

    @Test fun `library envelope cannot conceal changed snapshot counts or nonimport owner substitution`() = runBlocking<Unit> {
        val home = home()
        val source = SyntheticSaveSlotCorpus.archive(home.resolve("source.cbsave"))
        val codec = SaveSlotPackageStorage(home.resolve("library").toFile(), { _, _ -> error("No re-encoding") },
            maximumImportBytes = DesktopSaveSlotArchive.MAX_ARCHIVE)
        val imported = Files.newInputStream(source).use { codec.importPackage(it, "library-session") { it }.slot }
        val packagePath = home.resolve("library/save_slot_packages").resolve(requireNotNull(imported.packageRef).fileName)
        val before = Files.readAllBytes(packagePath)
        val ref = requireNotNull(imported.packageRef)
        for ((bad, stage) in listOf(imported.copy(longTermMemory = "Changed snapshot") to "MERGE_PACKAGE_ENVELOPE_CONTENT",
            imported.copy(packageRef = ref.copy(messageCount = ref.messageCount + 1)) to "MERGE_PACKAGE_ENVELOPE_CONTENT",
            imported.copy(packageRef = ref.copy(byteLength = ref.byteLength + 1)) to "MERGE_PACKAGE_ENVELOPE_LENGTH",
            imported.copy(packageRef = ref.copy(fileName = "unrelated.cbsave")) to "MERGE_PACKAGE_OWNER_IDENTITY",
            imported.copy(id = "slot") to "MERGE_PACKAGE_OWNER_IDENTITY")) {
            val record = DesktopMergeRecord("entities/save_slots/${bad.id}.json", backupJson.encodeToJsonElement(SaveSlot.serializer(), bad).jsonObject)
            assertEquals(stage, assertFails { DesktopMergeSaveSlotPackage.catalog(packagePath, record, home) }.message)
            assertEquals(stage, assertFails { DesktopMergeSaveSlotPackage.prepare(packagePath, record, ids(), emptyList(), home) }.message)
            val custody = Files.createTempDirectory(home, "bad-custody-")
            val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
                DesktopDataRootResolution.Resolved(custody, DesktopDataRootProvenance.CLI_OVERRIDE, home.resolve("unused-bootstrap")))).ownership
            owner.use {
                val failure = assertFails { DesktopSaveSlotSourceCustody.preserve(custody, record.relative, record.value.toString().toByteArray(), packagePath,
                    "1".repeat(64), 84, 1, null) }
                assertEquals(if (stage == "MERGE_PACKAGE_ENVELOPE_LENGTH") "SLOT_CUSTODY_PACKAGE_LENGTH" else stage, failure.message)
            }
            assertContentEquals(before, Files.readAllBytes(packagePath))
        }
    }
}
