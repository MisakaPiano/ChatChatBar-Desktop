package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

/** All files are SYNTHETIC, generated offline from the single shared official Entity schema.
 * This is an extensible generator, NOT evidence of running the Android exporter or round-trip. */
internal object SyntheticSaveSlotCorpus {
    private val json = Json { encodeDefaults = true }
    fun message(id: String = "m", text: String = "Synthetic", images: List<String> = emptyList()) =
        ChatMessage(id, "s", MessageRole.ASSISTANT, text, images = images,
            sourceTurnId = "turn", sourceTurnOrder = 1, currentAlternativeVersionId = "v", createdAt = 1, updatedAt = 1)
    fun slot(schema: Int = 8) = SaveSlot(schemaVersion = schema, id = "slot", sessionId = "s", name = "Synthetic", createdAt = 1)
    fun legacy(path: Path, slot: SaveSlot) = path.also { Files.writeString(it, json.encodeToString(SaveSlot.serializer(), slot)) }
    fun archive(path: Path, messages: List<ChatMessage> = listOf(message()), chunks: List<VectorChunk> = emptyList(),
        voices: List<GeneratedVoiceMessage> = emptyList(), media: Map<String, ByteArray> = emptyMap(),
        manifest: SaveSlot? = null, overrides: Map<String, ByteArray> = emptyMap(), stored: Boolean = false): Path {
        val base = manifest ?: slot().copy(packageRef = SaveSlotPackageRef("synthetic.cbsave", messages.size,
            messages.sumOf { it.images.size }, voices.size, chunks.size))
        val entries = linkedMapOf(
            "manifest.json" to json.encodeToString(SaveSlot.serializer(), base).toByteArray(),
            "messages.jsonl" to messages.joinToString("\n", postfix = if(messages.isEmpty()) "" else "\n") { json.encodeToString(ChatMessage.serializer(), it) }.toByteArray(),
            "rag.jsonl" to chunks.joinToString("\n", postfix = if(chunks.isEmpty()) "" else "\n") { json.encodeToString(VectorChunk.serializer(), it) }.toByteArray(),
            "voices.jsonl" to voices.joinToString("\n", postfix = if(voices.isEmpty()) "" else "\n") { json.encodeToString(GeneratedVoiceMessage.serializer(), it) }.toByteArray(),
        )
        entries.putAll(media); entries.putAll(overrides)
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> for((name, bytes) in entries) {
            val entry = ZipEntry(name)
            if(stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        } }
        return path
    }
}

class DesktopSaveSlotInspectorTest {
    private suspend fun fixture(block: suspend (Path) -> Unit) {
        val root = Files.createTempDirectory("slot-inspector-")
        try { withTimeout(30_000) { block(root) } } finally { root.toFile().deleteRecursively() }
    }
    private fun hash(file: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).toList()
    private suspend fun inspect(file: Path): DesktopSaveSlotInspection {
        val before = hash(file); val result = DesktopSaveSlotInspector().inspect(file)
        assertEquals(before, hash(file)); assertFalse(result.imported); assertFalse(result.desktopCanRestore)
        assertFalse(result.toString().contains("Synthetic")); assertFalse(result.toString().contains(file.toString()))
        return result
    }
    @Test fun `content detection ignores extension and valid ZIP archive stays read only`() = runBlocking { fixture { root ->
        val file = SyntheticSaveSlotCorpus.archive(root.resolve("not-json.txt"))
        val result = inspect(file)
        assertEquals(SlotInspectFormat.ZIP_V8, result.format); assertEquals(SlotInspectStatus.STRUCTURE_VALID, result.status)
        assertEquals(1, result.counts!!.messages); assertEquals(1, result.counts.sourceTurns)
        assertFalse(Files.exists(root.resolve("entities"))); assertFalse(Files.exists(root.resolve("save_slot_packages")))
    } }
    @Test fun `legacy schemas one to seven use shared defaults and bounded payload inspection`() = runBlocking { fixture { root ->
        for(schema in 1..7) {
            val file = SyntheticSaveSlotCorpus.legacy(root.resolve("legacy-$schema.cbsave"),
                SyntheticSaveSlotCorpus.slot(schema).copy(messages = listOf(SyntheticSaveSlotCorpus.message())))
            val result = inspect(file)
            assertEquals(SlotInspectFormat.LEGACY_JSON, result.format); assertEquals(schema, result.schema)
            assertEquals(SlotInspectStatus.STRUCTURE_VALID, result.status)
            // Minimal historical shape as well as modern schema-tagged generated records.
            Files.writeString(file, """{"schemaVersion":$schema,"id":"slot","sessionId":"s","name":"Synthetic","createdAt":1}""")
            assertEquals(SlotInspectStatus.STRUCTURE_VALID, inspect(file).status)
        }
    } }
    @Test fun `candidates source turns RAG and memory remain inspect only`() = runBlocking { fixture { root ->
        val message = SyntheticSaveSlotCorpus.message(text = "selected").copy(alternatives = listOf("old", "selected"),
            alternativeVersionIds = listOf("a", "v"), currentAlternativeIndex = 1)
        val chunk = VectorChunk("rag", ChunkSourceType.CHAT_MEMORY, "s", "m", "Synthetic knowledge", listOf(.1f, .2f),
            mapOf("messageIds" to "m", "sourceTurnId" to "turn"), 1)
        val slot = SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("test.cbsave", 1, ragChunkCount = 1),
            memorySnapshot = MemorySnapshot(nodes = listOf(MemoryNode("node", "s", MemoryTier.EPISODE, sourceTurnIds = listOf("turn")))))
        val result = inspect(SyntheticSaveSlotCorpus.archive(root.resolve("memory.cbsave"), listOf(message), listOf(chunk), manifest = slot))
        assertEquals(SlotInspectStatus.STRUCTURE_VALID, result.status)
        assertEquals(2, result.counts!!.alternatives); assertEquals(1, result.counts.rag); assertEquals(1, result.counts.memoryNodes)
        assertTrue("MEMORY_SOURCE_SEMANTICS_NOT_VERIFIED" in result.limitations)
    } }
    @Test fun `images omitted and included have distinct reference and media counts`() = runBlocking { fixture { root ->
        val omitted = SyntheticSaveSlotCorpus.message(images = listOf("chatbar-save-slot-omitted-image:image"))
        val a = inspect(SyntheticSaveSlotCorpus.archive(root.resolve("none.cbsave"), listOf(omitted)))
        assertEquals(SlotInspectStatus.STRUCTURE_VALID, a.status); assertEquals(0, a.counts!!.mediaFiles)
        val included = SyntheticSaveSlotCorpus.message(images = listOf("chatbar-save-slot-image:image"))
        val b = inspect(SyntheticSaveSlotCorpus.archive(root.resolve("image.cbsave"), listOf(included), media = mapOf("media/images/image.png" to byteArrayOf(1, 2, 3))))
        assertEquals(SlotInspectStatus.STRUCTURE_VALID, b.status); assertEquals(1, b.counts!!.mediaFiles)
        assertTrue("RESOURCE_CODECS_NOT_VERIFIED" in b.limitations)
    } }
    private fun voice() = GeneratedVoiceMessage("voice", "s", "m", "v", sourceOrder = 1, sourceSegmentKind = "DIALOGUE",
        sourceSpeakerName = "Synthetic", sourceText = "Synthetic", taggedText = "Synthetic", characterId = "c", characterName = "Synthetic",
        voice = FishAudioVoiceBinding("ref", "Synthetic"), fishModelId = "synthetic", audioPath = "chatbar-save-slot-audio:audio",
        durationMs = 1, byteLength = 3, createdAt = 1, updatedAt = 1)
    private fun legacyVoiceSlot(schema: Int, resources: Map<String, SaveSlotAudioResource>,
        record: GeneratedVoiceMessage = voice()) = SyntheticSaveSlotCorpus.slot(schema).copy(
        messages = listOf(SyntheticSaveSlotCorpus.message()), voiceMessages = listOf(record), audioResources = resources)

    @Test fun `legacy five to seven accept voice ID fallback without exposing audio identity`() = runBlocking { fixture { root ->
        for (schema in 5..7) {
            val slot = legacyVoiceSlot(schema, mapOf(voice().id to SaveSlotAudioResource("synthetic.mp3", "AQID")))
            assertEquals(voice().id, DesktopSaveSlotInspector.legacyAudioResourceKey(slot, voice()))
            val result = inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("id-$schema.cbsave"), slot))
            assertEquals(SlotInspectStatus.STRUCTURE_VALID, result.status)
            assertEquals(1, result.counts!!.voices)
            assertTrue("VOICE_CODECS_AND_PLAYBACK_NOT_VERIFIED" in result.limitations)
            assertFalse(result.toString().contains(voice().audioPath))
        }
    } }
    @Test fun `legacy five to seven accept original audio path resource keys`() = runBlocking { fixture { root ->
        for (schema in 5..7) {
            val slot = legacyVoiceSlot(schema, mapOf(voice().audioPath to SaveSlotAudioResource("path.mp3", "BAUG")))
            assertEquals(voice().audioPath, DesktopSaveSlotInspector.legacyAudioResourceKey(slot, voice()))
            assertEquals(SlotInspectStatus.STRUCTURE_VALID,
                inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("path-$schema.cbsave"), slot)).status)
        }
    } }
    @Test fun `legacy audio path takes precedence when both resource keys exist`() = runBlocking { fixture { root ->
        for (schema in 5..7) {
            val slot = legacyVoiceSlot(schema, mapOf(
                voice().id to SaveSlotAudioResource("id.mp3", "AQID"),
                voice().audioPath to SaveSlotAudioResource("path.mp3", "BAUG")))
            // Assert the exact key used by the real inspector, not merely that either key exists.
            assertEquals(voice().audioPath, DesktopSaveSlotInspector.legacyAudioResourceKey(slot, voice()))
            assertEquals(SlotInspectStatus.STRUCTURE_VALID,
                inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("both-$schema.cbsave"), slot)).status)
        }
    } }
    @Test fun `legacy missing both audio associations fails closed`() = runBlocking { fixture { root ->
        for (schema in 5..7) {
            val slot = legacyVoiceSlot(schema, mapOf("unrelated" to SaveSlotAudioResource("other.mp3", "AQID")))
            assertNull(DesktopSaveSlotInspector.legacyAudioResourceKey(slot, voice()))
            val result = inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("missing-$schema.cbsave"), slot))
            assertEquals(SlotInspectCode.REFERENCE, result.code)
            assertEquals(SlotInspectStatus.UNSUPPORTED, result.status)
        }
    } }
    @Test fun `legacy audio association never bypasses bounded Base64 validation`() = runBlocking { fixture { root ->
        for (schema in 5..7) for (key in listOf(voice().id, voice().audioPath)) {
            val slot = legacyVoiceSlot(schema, mapOf(key to SaveSlotAudioResource("broken.mp3", "not-base64!")))
            assertEquals(SlotInspectCode.SCHEMA,
                inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("base64-$schema.cbsave"), slot)).code)
        }
    } }
    @Test fun `legacy voice ID fallback preserves message session and version guards`() = runBlocking { fixture { root ->
        for (schema in 5..7) for ((record, expected) in listOf(
            voice().copy(messageId = "missing") to SlotInspectCode.IDENTITY,
            voice().copy(sessionId = "other-session") to SlotInspectCode.IDENTITY,
            voice().copy(messageVersionId = "other-version") to SlotInspectCode.REFERENCE)) {
            val slot = legacyVoiceSlot(schema, mapOf(record.id to SaveSlotAudioResource("id.mp3", "AQID")), record)
            assertEquals(expected, inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("identity-$schema.cbsave"), slot)).code)
        }
    } }
    @Test fun `earlier legacy and v8 audio reference contracts are unchanged`() = runBlocking { fixture { root ->
        for (schema in 1..4) {
            val slot = legacyVoiceSlot(schema, mapOf(voice().audioPath to SaveSlotAudioResource("path.mp3", "AQID")))
            assertEquals(SlotInspectStatus.STRUCTURE_VALID,
                inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("earlier-$schema.cbsave"), slot)).status)
            assertEquals(SlotInspectCode.REFERENCE, inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("earlier-id-$schema.cbsave"),
                slot.copy(audioResources = mapOf(voice().id to SaveSlotAudioResource("id.mp3", "AQID"))))).code)
        }
        assertEquals(SlotInspectCode.REFERENCE, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("v8-id.cbsave"),
            voices = listOf(voice()), media = mapOf("media/audio/voice.mp3" to byteArrayOf(1, 2, 3)))).code)
    } }
    @Test fun `legacy fallback cancellation leaves source unchanged and safely readable`() = runBlocking { fixture { root ->
        val slot = legacyVoiceSlot(7, mapOf(voice().id to SaveSlotAudioResource("id.mp3", "AQID")))
        val file = SyntheticSaveSlotCorpus.legacy(root.resolve("cancel.cbsave"), slot)
        val before = hash(file); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var records = 0
        val job = async { DesktopSaveSlotInspector {
            if (++records == 2) { entered.complete(Unit); release.await() }
        }.inspect(file) }
        entered.await(); job.cancelAndJoin()
        assertEquals(before, hash(file))
        assertEquals(SlotInspectStatus.STRUCTURE_VALID, inspect(file).status)
        assertEquals(listOf(file), Files.list(root).use { it.toList() })
    } }
    @Test fun `legacy oversized audio is rejected before inline decoding`() = runBlocking { fixture { root ->
        val slot = legacyVoiceSlot(7, mapOf(voice().id to SaveSlotAudioResource("large.mp3", "A".repeat(1_100_000))))
        assertEquals(SlotInspectCode.BUDGET,
            inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("large-audio.cbsave"), slot)).code)
    } }
    @Test fun `voice requires archived audio and matching message version`() = runBlocking { fixture { root ->
        val a = inspect(SyntheticSaveSlotCorpus.archive(root.resolve("voice.cbsave"), voices = listOf(voice()), media = mapOf("media/audio/audio.mp3" to byteArrayOf(1, 2, 3))))
        assertEquals(SlotInspectStatus.STRUCTURE_VALID, a.status); assertEquals(1, a.counts!!.voices)
        val b = inspect(SyntheticSaveSlotCorpus.archive(root.resolve("wrong-version.cbsave"), voices = listOf(voice().copy(messageVersionId = "missing")), media = mapOf("media/audio/audio.mp3" to byteArrayOf(1, 2, 3))))
        assertEquals(SlotInspectCode.REFERENCE, b.code)
        assertEquals(SlotInspectCode.COUNTS, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("wrong-audio-length.cbsave"), voices = listOf(voice().copy(byteLength = 99)), media = mapOf("media/audio/audio.mp3" to byteArrayOf(1, 2, 3)))).code)
    } }
    @Test fun `duplicate message and rag IDs and wrong source references fail before restore`() = runBlocking { fixture { root ->
        assertEquals(SlotInspectCode.IDENTITY, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("dup.cbsave"), messages = List(2) { SyntheticSaveSlotCorpus.message() })).code)
        val rag = VectorChunk("r", ChunkSourceType.CHAT_MEMORY, "s", "missing", "Synthetic", listOf(1f), createdAt = 1)
        assertEquals(SlotInspectCode.REFERENCE, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("rag.cbsave"), chunks = listOf(rag))).code)
        val valid = rag.copy(messageId = "m")
        assertEquals(SlotInspectCode.IDENTITY, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("dup-rag.cbsave"), chunks = listOf(valid, valid))).code)
    } }
    @Test fun `missing image media count mismatch and ambiguous media IDs are invalid`() = runBlocking { fixture { root ->
        assertEquals(SlotInspectCode.REFERENCE, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("missing.cbsave"), messages = listOf(SyntheticSaveSlotCorpus.message(images = listOf("chatbar-save-slot-image:image"))))).code)
        val manifest = SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("slot.cbsave", messageCount = 99))
        assertEquals(SlotInspectCode.COUNTS, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("counts.cbsave"), manifest = manifest)).code)
        assertEquals(SlotInspectCode.REFERENCE, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("ambiguous.cbsave"), media = mapOf("media/images/x.png" to byteArrayOf(), "media/images/x.jpg" to byteArrayOf()))).code)
    } }
    @Test fun `unknown manifest fields unsupported versions and illegal ZIP names are not silently ignored`() = runBlocking { fixture { root ->
        val json = Json { encodeDefaults = true }
        val slot = SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("slot.cbsave", 1))
        val raw = json.encodeToString(SaveSlot.serializer(), slot).replaceFirst("{", "{\"future\":1,")
        assertEquals(SlotInspectCode.UNKNOWN_FIELD, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("future.cbsave"), overrides = mapOf("manifest.json" to raw.toByteArray()))).code)
        assertEquals(SlotInspectStatus.UNSUPPORTED, inspect(SyntheticSaveSlotCorpus.legacy(root.resolve("new.json"), slot.copy(schemaVersion = 99, packageRef = null))).status)
        for(name in listOf("../outside", "/absolute", "media/images/a\\b.png", "media/images/../x.png")) {
            assertEquals(SlotInspectCode.ZIP_LAYOUT, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("unsafe.cbsave"), overrides = mapOf(name to byteArrayOf(1)))).code)
        }
    } }
    @Test fun `truncated archives broken JSON CRC and duplicate ZIP names retain original bytes`() = runBlocking { fixture { root ->
        val file = SyntheticSaveSlotCorpus.archive(root.resolve("broken.cbsave"), stored = true)
        val original = Files.readAllBytes(file)
        Files.write(file, original.copyOf(original.size - 5))
        assertEquals(SlotInspectCode.ZIP_LAYOUT, inspect(file).code)
        SyntheticSaveSlotCorpus.archive(file, overrides = mapOf("manifest.json" to "{".toByteArray()))
        assertEquals(SlotInspectCode.SCHEMA, inspect(file).code)
        Files.write(file, original)
        val central = (0 until original.size-4).first { original[it] == 0x50.toByte() && original[it+1] == 0x4b.toByte() && original[it+2] == 1.toByte() && original[it+3] == 2.toByte() }
        val changed = original.copyOf(); changed[central+16] = (changed[central+16].toInt() xor 1).toByte(); Files.write(file, changed)
        assertEquals(SlotInspectCode.CRC, inspect(file).code)
        SyntheticSaveSlotCorpus.archive(file, overrides = mapOf("zag.jsonl" to byteArrayOf()))
        val text = Files.readAllBytes(file).toString(Charsets.ISO_8859_1).replace("zag.jsonl", "rag.jsonl")
        Files.write(file, text.toByteArray(Charsets.ISO_8859_1))
        assertEquals(SlotInspectCode.ZIP_LAYOUT, inspect(file).code)
    } }
    @Test fun `legacy Base64 and JSONL size budgets avoid whole unbounded decoding`() = runBlocking { fixture { root ->
        val json = root.resolve("large.json")
        Files.writeString(json, "{\"schemaVersion\":7,\"imageResources\":{\"x\":{\"data\":\"" + "A".repeat(1_100_000) + "\"}}}")
        assertEquals(SlotInspectCode.BUDGET, inspect(json).code)
        val record = SyntheticSaveSlotCorpus.message(text = "x".repeat(1_050_000))
        assertEquals(SlotInspectCode.BUDGET, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("line.cbsave"), listOf(record), stored = true)).code)
        assertEquals(SlotInspectCode.BUDGET, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("ratio.cbsave"), media = mapOf("media/images/x.png" to ByteArray(1_100_000)))).code)
    } }
    @Test fun `large bounded archive streams and cancellation closes source without data root mutation`() = runBlocking { fixture { root ->
        val file = SyntheticSaveSlotCorpus.archive(root.resolve("large.cbsave"), List(300) { SyntheticSaveSlotCorpus.message("m$it", "x".repeat(2000)) })
        assertEquals(300, inspect(file).counts!!.messages)
        val before = hash(file); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val job = async { DesktopSaveSlotInspector { entered.complete(Unit); release.await() }.inspect(file) }
        entered.await(); job.cancelAndJoin()
        assertEquals(before, hash(file)); assertEquals(300, inspect(file).counts!!.messages)
        assertEquals(listOf(file), Files.list(root).use { it.toList() })
    } }
    @Test fun `container OPEN picker exposes inspection only and cancel has no import or save action`() = runBlocking { fixture { root ->
        val file = SyntheticSaveSlotCorpus.archive(root.resolve("synthetic.cbsave"))
        var opens = 0; var selected: Path? = file
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? { opens++; assertTrue(type.description.contains("尚未导入")); return selected }
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("Inspection cannot save/import")
        }
        val data = root.resolve("app-data")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(data, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() }, filePicker = picker)
        try {
            assertFalse(container.saveSlotInspectionPicker.inspectSelected()!!.imported)
            selected = null; assertNull(container.saveSlotInspectionPicker.inspectSelected()); assertEquals(2, opens)
            assertFalse(Files.exists(data.resolve("entities"))); assertFalse(Files.exists(data.resolve("save_slot_packages")))
        } finally { container.close() }
    } }

    @Test fun `entry count unsupported container and unknown message fields cannot pass as v8`() = runBlocking { fixture { root ->
        val large = root.resolve("many.cbsave")
        ZipOutputStream(Files.newOutputStream(large)).use { zip ->
            repeat(4097) { zip.putNextEntry(ZipEntry("media/images/i$it.png")); zip.closeEntry() }
        }
        assertEquals(SlotInspectCode.BUDGET, inspect(large).code)
        SyntheticSaveSlotCorpus.archive(large, overrides = mapOf("manifest.json" to "{\"notASaveSlot\":true}".toByteArray()))
        val unsupported = inspect(large)
        assertEquals(SlotInspectFormat.ZIP_CONTAINER, unsupported.format)
        assertEquals(SlotInspectStatus.UNSUPPORTED, unsupported.status)
        val json = Json { encodeDefaults = true }
        val raw = json.encodeToString(ChatMessage.serializer(), SyntheticSaveSlotCorpus.message()).replaceFirst("{", "{\"futureMessage\":1,")
        SyntheticSaveSlotCorpus.archive(large, overrides = mapOf("messages.jsonl" to raw.toByteArray()))
        assertEquals(SlotInspectCode.UNKNOWN_FIELD, inspect(large).code)
    } }
    @Test fun `known RAG source contradictions and missing memory roots are rejected`() = runBlocking { fixture { root ->
        val first = SyntheticSaveSlotCorpus.message()
        val second = SyntheticSaveSlotCorpus.message("m2").copy(sourceTurnId = "other-turn", sourceTurnOrder = 2)
        val rag = VectorChunk("r", ChunkSourceType.CHAT_MEMORY, "s", "m", "Synthetic", listOf(1f),
            mapOf("sourceTurnId" to "other-turn"), 1)
        assertEquals(SlotInspectCode.REFERENCE, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("wrong-turn.cbsave"), listOf(first, second), listOf(rag))).code)
        assertEquals(SlotInspectCode.REFERENCE, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("wrong-session.cbsave"), chunks = listOf(rag.copy(metadata = mapOf("sessionId" to "other"))))).code)
        val manifest = SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("slot.cbsave", 1),
            memorySnapshot = MemorySnapshot(state = MemorySessionSnapshot(episodeNodeIds = listOf("missing"))))
        assertEquals(SlotInspectCode.REFERENCE, inspect(SyntheticSaveSlotCorpus.archive(root.resolve("memory-root.cbsave"), manifest = manifest)).code)
    } }
}
