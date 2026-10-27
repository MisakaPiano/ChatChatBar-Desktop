package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import com.sun.jna.platform.win32.Kernel32
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class SlotInspectFormat { UNKNOWN, LEGACY_JSON, ZIP_CONTAINER, ZIP_V8 }
internal enum class SlotInspectStatus { INVALID, UNSUPPORTED, STRUCTURE_VALID }
internal enum class SlotInspectCode { OK, FORMAT, BUDGET, SCHEMA, UNKNOWN_FIELD, ZIP_LAYOUT, ZIP64,
    CRC, COUNTS, IDENTITY, REFERENCE, UNSAFE_FILE, IO }
internal data class SlotInspectCounts(val messages: Int, val alternatives: Int, val sourceTurns: Int,
    val rag: Int, val voices: Int, val imageReferences: Int, val mediaFiles: Int, val memoryNodes: Int)
internal data class DesktopSaveSlotInspection(val format: SlotInspectFormat, val status: SlotInspectStatus,
    val code: SlotInspectCode, val schema: Int? = null, val counts: SlotInspectCounts? = null,
    val limitations: Set<String> = emptySet()) {
    val imported = false
    val desktopCanRestore = false // Never confused with successful format/structure inspection.
}

/** External file only. No repository/root, extraction, import, migration or provider dependency. */
internal class DesktopSaveSlotInspector(private val onRecord: suspend () -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = false }
    suspend fun inspect(path: Path): DesktopSaveSlotInspection = withContext(Dispatchers.IO) {
        var format = SlotInspectFormat.UNKNOWN
        try {
            validateFile(path)
            val size = Files.size(path)
            if (size > MAX_ARCHIVE_BYTES) fail(SlotInspectCode.BUDGET, unsupported = true)
            val prefix = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(4096) }
            if (prefix.size >= 4 && prefix.take(4) == listOf<Byte>(0x50, 0x4b, 3, 4)) {
                format = SlotInspectFormat.ZIP_CONTAINER // v8 identity requires a validated manifest.
                zip(path)
            } else {
                val probe = prefix.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                if (!probe.startsWith('{')) fail(SlotInspectCode.FORMAT, unsupported = true)
                format = SlotInspectFormat.LEGACY_JSON
                // Oversized legacy JSON is recognized but NOT decoded, including Base64 resources.
                if (size > MAX_JSON_BYTES) fail(SlotInspectCode.BUDGET, unsupported = true)
                val slot = decode(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_JSON_BYTES + 1) }, SaveSlot.serializer())
                if (slot.schemaVersion !in 1..7) fail(SlotInspectCode.SCHEMA, unsupported = true)
                if (slot.packageRef != null) fail(SlotInspectCode.SCHEMA)
                validate(slot, slot.messages, slot.vectorChunks, slot.voiceMessages, null)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: InspectionFailure) { DesktopSaveSlotInspection(format,
            if (error.unsupported) SlotInspectStatus.UNSUPPORTED else SlotInspectStatus.INVALID, error.code) }
        catch (_: Exception) { DesktopSaveSlotInspection(format, SlotInspectStatus.INVALID, SlotInspectCode.IO) }
    }

    private suspend fun zip(path: Path): DesktopSaveSlotInspection {
        val declaredEntries = checkDirectoryBudget(path) // Bound allocation BEFORE ZipFile construction.
        return ZipFile(path.toFile()).use { zip ->
            val entries = linkedMapOf<String, ZipEntry>()
            val aliases = mutableSetOf<String>()
            var size = 0L
            var metadataSize = 0L
            for (entry in zip.entries().asSequence()) {
                if (entries.size >= MAX_ENTRIES || !aliases.add(entry.name.lowercase())) fail(SlotInspectCode.ZIP_LAYOUT)
                if (entry.name.length > 300 || '\\' in entry.name || ':' in entry.name ||
                    entry.name.split('/').any { it == "." || it == ".." } || entry.name.startsWith('/')) fail(SlotInspectCode.ZIP_LAYOUT)
                val known = entry.name in setOf("manifest.json", "messages.jsonl", "rag.jsonl", "voices.jsonl", "media/", "media/images/", "media/audio/") ||
                    MEDIA_NAME.matches(entry.name)
                if (!known) fail(SlotInspectCode.UNKNOWN_FIELD, unsupported = true)
                if (entry.size < 0 || entry.compressedSize < 0 || entry.size > MAX_ENTRY_BYTES ||
                    entry.size / maxOf(1L, entry.compressedSize) > 1000L) fail(SlotInspectCode.BUDGET, unsupported = true)
                size += entry.size
                if (!MEDIA_NAME.matches(entry.name)) metadataSize += entry.size
                if (size > MAX_EXPANDED_BYTES) fail(SlotInspectCode.BUDGET, unsupported = true)
                if (metadataSize > MAX_METADATA_BYTES) fail(SlotInspectCode.BUDGET, unsupported = true)
                entries[entry.name] = entry
            }
            if (entries.size != declaredEntries) fail(SlotInspectCode.ZIP_LAYOUT)
            fun required(name: String) = entries[name] ?: fail(SlotInspectCode.ZIP_LAYOUT)
            val manifest = required("manifest.json")
            if (manifest.size > MAX_MANIFEST_BYTES) fail(SlotInspectCode.BUDGET, unsupported = true)
            val slot = decode(readEntry(zip, manifest, MAX_MANIFEST_BYTES), SaveSlot.serializer())
            if (slot.schemaVersion != 8 || slot.packageRef == null) fail(SlotInspectCode.SCHEMA, unsupported = true)
            if (slot.messages.isNotEmpty() || slot.vectorChunks.isNotEmpty() || slot.voiceMessages.isNotEmpty() ||
                slot.imageResources.isNotEmpty() || slot.audioResources.isNotEmpty()) fail(SlotInspectCode.SCHEMA)
            // Compact identities retained below; payload records are re-read one at a time.
            val records = Records(slot, entries)
            lines(zip, required("messages.jsonl"), ChatMessage.serializer(), records::message)
            records.checkMessageLinks()
            lines(zip, required("rag.jsonl"), VectorChunk.serializer(), records::rag)
            entries["voices.jsonl"]?.let { lines(zip, it, GeneratedVoiceMessage.serializer(), records::voice) }
            // Read every entry to EOF and independently compare CRC/length, including media/unused data.
            for (entry in entries.values) if (entry.name !in setOf("manifest.json", "messages.jsonl", "rag.jsonl", "voices.jsonl")) {
                consume(zip, entry) { input -> val buffer = ByteArray(32 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); if (input.read(buffer) < 0) break } }
            }
            records.finish()
        }
    }

    private suspend fun validate(slot: SaveSlot, messages: List<ChatMessage>, rag: List<VectorChunk>,
        voices: List<GeneratedVoiceMessage>, entries: Map<String, ZipEntry>?): DesktopSaveSlotInspection {
        val records = Records(slot, entries)
        for (message in messages) { onRecord(); currentCoroutineContext().ensureActive(); records.message(message) }
        records.checkMessageLinks()
        for (chunk in rag) { onRecord(); records.rag(chunk) }
        for (voice in voices) { onRecord(); records.voice(voice) }
        return records.finish()
    }

    private inner class Records(val slot: SaveSlot, val entries: Map<String, ZipEntry>?) {
        private val messages = linkedMapOf<String, Pair<Set<String>, String?>>()
        private val turns = mutableSetOf<String>()
        private val derivedLinks = mutableListOf<String>()
        private val ragIds = mutableSetOf<String>()
        private val voiceIds = mutableSetOf<String>()
        private val usedMedia = mutableSetOf<String>()
        private val media = mutableMapOf<String, String>()
        private val notes = mutableSetOf("DESKTOP_RESTORE_NOT_IMPLEMENTED", "RESOURCE_CODECS_NOT_VERIFIED")
        private var alternatives = 0
        private var images = 0
        init {
            identity(slot.id); identity(slot.sessionId)
            if (slot.name.isBlank()) fail(SlotInspectCode.SCHEMA)
            slot.packageRef?.let { if (it.fileName.length > 300 || '/' in it.fileName || '\\' in it.fileName ||
                ':' in it.fileName || it.fileName == "." || it.fileName == "..") fail(SlotInspectCode.REFERENCE) }
            entries?.keys?.filter { MEDIA_NAME.matches(it) }?.forEach { name ->
                val key = name.substringBeforeLast('/') + "/" + name.substringAfterLast('/').substringBeforeLast('.')
                if (media.put(key, name) != null) fail(SlotInspectCode.REFERENCE)
            }
        }
        fun message(message: ChatMessage) {
            identity(message.id)
            if (messages.size >= MAX_RECORDS) fail(SlotInspectCode.BUDGET, unsupported = true)
            if (message.sessionId != slot.sessionId || messages.containsKey(message.id)) fail(SlotInspectCode.IDENTITY)
            if (message.alternatives.size > 64) fail(SlotInspectCode.BUDGET, unsupported = true)
            val versions = message.alternativeVersionIds
            if (versions.isNotEmpty() && (versions.size != message.alternatives.size || versions.distinct().size != versions.size ||
                versions.any { it.isBlank() || it.length > 256 })) fail(SlotInspectCode.IDENTITY)
            if (message.alternatives.isNotEmpty() && message.currentAlternativeIndex !in message.alternatives.indices) fail(SlotInspectCode.IDENTITY)
            if (versions.isNotEmpty() && message.currentAlternativeVersionId != versions[message.currentAlternativeIndex]) fail(SlotInspectCode.IDENTITY)
            if (message.alternatives.isNotEmpty() && message.content != message.displayContent) fail(SlotInspectCode.IDENTITY)
            if (message.currentAlternativeVersionId == null || message.sourceTurnId == null) notes += "LEGACY_IDENTITY_PARTIAL"
            message.sourceTurnId?.let { identity(it); turns.add(it) }
            messages[message.id] = (versions.toSet() + listOfNotNull(message.currentAlternativeVersionId)) to message.sourceTurnId
            alternatives += message.alternatives.size
            message.images.forEach(::image)
            if (message.generatedImageMetadata.any { it.imagePath !in message.images }) fail(SlotInspectCode.REFERENCE)
            message.generatedFromMessageId?.let { derivedLinks.add(it) }
        }
        fun checkMessageLinks() { if (derivedLinks.any { it !in messages }) fail(SlotInspectCode.REFERENCE) }
        fun rag(chunk: VectorChunk) {
            notes += "RAG_RUNTIME_UNSUPPORTED"
            notes += "RAG_SOURCE_FINGERPRINT_NOT_VERIFIED"
            identity(chunk.id)
            if (ragIds.size >= MAX_RECORDS) fail(SlotInspectCode.BUDGET, unsupported = true)
            if (!ragIds.add(chunk.id)) fail(SlotInspectCode.IDENTITY)
            if (chunk.sourceType != ChunkSourceType.CHAT_MEMORY || chunk.sourceId != slot.sessionId)
                fail(SlotInspectCode.REFERENCE, unsupported = true)
            chunk.metadata["sessionId"]?.let { if (it != slot.sessionId) fail(SlotInspectCode.REFERENCE) }
            chunk.messageId?.let { if (it !in messages) fail(SlotInspectCode.REFERENCE) }
            chunk.metadata["messageIds"]?.split(',')?.filter(String::isNotBlank)?.forEach { if (it !in messages) fail(SlotInspectCode.REFERENCE) }
            chunk.metadata["sourceTurnId"]?.let { turn ->
                if (turn !in turns) fail(SlotInspectCode.REFERENCE)
                val linked = listOfNotNull(chunk.messageId) + chunk.metadata["messageIds"].orEmpty().split(',').filter(String::isNotBlank)
                for (id in linked) {
                    val actual = messages.getValue(id).second
                    if (actual == null) notes += "RAG_LEGACY_RELATION_PARTIAL"
                    else if (actual != turn) fail(SlotInspectCode.REFERENCE)
                }
            }
            if (chunk.embedding.size > 65536 || chunk.embedding.any { !it.isFinite() }) fail(SlotInspectCode.SCHEMA)
        }
        fun voice(voice: GeneratedVoiceMessage) {
            notes += "VOICE_RUNTIME_UNSUPPORTED"
            identity(voice.id)
            if (voiceIds.size >= MAX_RECORDS) fail(SlotInspectCode.BUDGET, unsupported = true)
            if (!voiceIds.add(voice.id) || voice.sessionId != slot.sessionId || voice.messageId !in messages) fail(SlotInspectCode.IDENTITY)
            voice.messageVersionId?.let { if (it !in messages.getValue(voice.messageId).first) fail(SlotInspectCode.REFERENCE) }
            if (entries != null) {
                if (!voice.audioPath.startsWith(AUDIO)) fail(SlotInspectCode.REFERENCE)
                val resource = resource("media/audio/", voice.audioPath.removePrefix(AUDIO))
                if (entries.getValue(resource).size != voice.byteLength || voice.durationMs < 0) fail(SlotInspectCode.COUNTS)
            } else if (legacyAudioResourceKey(slot, voice) == null) fail(SlotInspectCode.REFERENCE, unsupported = true)
        }
        fun image(reference: String) {
            images++
            if (reference.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) return
            if (entries != null) {
                if (!reference.startsWith(IMAGE)) fail(SlotInspectCode.REFERENCE)
                resource("media/images/", reference.removePrefix(IMAGE))
            } else if (reference !in slot.imageResources) fail(SlotInspectCode.REFERENCE, unsupported = true)
        }
        private fun resource(prefix: String, id: String): String {
            val name = media[prefix + id] ?: fail(SlotInspectCode.REFERENCE)
            usedMedia.add(name)
            return name
        }
        fun finish(): DesktopSaveSlotInspection {
            slot.chatBackground?.let(::image)
            slot.packageRef?.let { ref ->
                if (ref.messageCount != messages.size || ref.ragChunkCount != ragIds.size || ref.audioCount != voiceIds.size ||
                    ref.imageCount != images) fail(SlotInspectCode.COUNTS)
            }
            if (entries != null && usedMedia.size != media.size) fail(SlotInspectCode.REFERENCE, unsupported = true)
            val snapshot = slot.memorySnapshot
            if (slot.longTermMemory.isNotBlank()) notes += "LEGACY_MEMORY_RUNTIME_UNSUPPORTED"
            if (entries == null) {
                (slot.imageResources.values.map { it.data } + slot.audioResources.values.map { it.data }).forEach(::checkBase64)
                if (slot.imageResources.isNotEmpty() || slot.audioResources.isNotEmpty()) notes += "LEGACY_MEDIA_INTEGRITY_UNVERIFIED"
            }
            snapshot?.let {
                if (it.nodes.size > 2048) fail(SlotInspectCode.BUDGET, unsupported = true)
                val ids = it.nodes.map { node -> node.id }.toSet()
                it.nodes.forEach { node -> identity(node.id) }
                if (ids.size != it.nodes.size || it.nodes.any { node -> node.sessionId != slot.sessionId || node.childIds.any { child -> child !in ids } }) fail(SlotInspectCode.REFERENCE)
                it.state?.let { state -> if ((state.episodeNodeIds + state.arcNodeIds + state.eraNodeIds + state.legacyReferenceNodeIds).any { id -> id !in ids }) fail(SlotInspectCode.REFERENCE) }
                notes += "MEMORY_SOURCE_SEMANTICS_NOT_VERIFIED" // No journal replay/migration or proof invented.
            }
            return DesktopSaveSlotInspection(if (entries == null) SlotInspectFormat.LEGACY_JSON else SlotInspectFormat.ZIP_V8,
                SlotInspectStatus.STRUCTURE_VALID, SlotInspectCode.OK, slot.schemaVersion,
                SlotInspectCounts(messages.size, alternatives, turns.size, ragIds.size, voiceIds.size, images,
                    media.size + slot.imageResources.size + slot.audioResources.size, snapshot?.nodes?.size ?: 0), notes)
        }
    }

    private suspend fun <T> lines(zip: ZipFile, entry: ZipEntry, serializer: KSerializer<T>, action: (T) -> Unit) = consume(zip, entry) { input ->
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            currentCoroutineContext().ensureActive()
            val value = input.read()
            if (value < 0 || value == 10) {
                if (line.size() > 0) {
                    val bytes = line.toByteArray()
                    if (bytes.any { it.toInt() !in listOf(9, 13, 32) }) { onRecord(); action(decode(bytes, serializer)) }
                    line.reset()
                }
                if (value < 0) break
            } else { if (line.size() >= MAX_JSON_BYTES) fail(SlotInspectCode.BUDGET, unsupported = true); line.write(value) }
        }
    }
    private suspend fun consume(zip: ZipFile, entry: ZipEntry, action: suspend (InputStream) -> Unit) {
        val crc = CRC32(); var count = 0L
        zip.getInputStream(entry).buffered().use { raw ->
            val checked = object : java.io.FilterInputStream(raw) {
                override fun read(): Int { val value = `in`.read(); if (value >= 0) { crc.update(value); count++; checkCount() }; return value }
                override fun read(bytes: ByteArray, off: Int, len: Int): Int { val n = `in`.read(bytes, off, len)
                    if (n > 0) { crc.update(bytes, off, n); count += n; checkCount() }; return n }
                private fun checkCount() { if (count > entry.size || count > MAX_ENTRY_BYTES) fail(SlotInspectCode.BUDGET) }
            }
            action(checked)
            if (count != entry.size || crc.value != entry.crc) fail(SlotInspectCode.CRC)
        }
    }
    private suspend fun readEntry(zip: ZipFile, entry: ZipEntry, max: Int): ByteArray {
        var bytes = byteArrayOf()
        consume(zip, entry) { input -> bytes = input.readNBytes(max + 1); if (bytes.size > max) fail(SlotInspectCode.BUDGET, true) }
        return bytes
    }
    private fun <T> decode(bytes: ByteArray, serializer: KSerializer<T>): T {
        if (bytes.size > MAX_JSON_BYTES) fail(SlotInspectCode.BUDGET, true)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) { if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when(c) { '"' -> quoted = true; '{','[' -> if (++depth > 64) fail(SlotInspectCode.BUDGET, true); '}',']' -> depth-- } }
        return try { json.decodeFromString(serializer, text) } catch(error: SerializationException) {
            if (error.message?.contains("unknown key", ignoreCase = true) == true) fail(SlotInspectCode.UNKNOWN_FIELD, true)
            fail(SlotInspectCode.SCHEMA)
        }
    }
    private fun checkDirectoryBudget(path: Path) = RandomAccessFile(path.toFile(), "r").use { file ->
        val length = file.length(); val tailSize = minOf(length, 65557L).toInt(); val tail = ByteArray(tailSize)
        file.seek(length - tailSize); file.readFully(tail)
        fun u16(i: Int) = (tail[i].toInt() and 255) or ((tail[i+1].toInt() and 255) shl 8)
        fun u32(i: Int) = u16(i).toLong() or (u16(i+2).toLong() shl 16)
        val end = (tail.size-22 downTo 0).firstOrNull { i -> tail[i] == 0x50.toByte() && tail[i+1] == 0x4b.toByte() &&
            tail[i+2] == 5.toByte() && tail[i+3] == 6.toByte() && i + 22 + u16(i+20) == tail.size } ?: fail(SlotInspectCode.ZIP_LAYOUT)
        if (u16(end+4) != 0 || u16(end+6) != 0 || u16(end+8) != u16(end+10)) fail(SlotInspectCode.ZIP_LAYOUT)
        if (u16(end+10) == 65535 || u32(end+12) == 0xffffffffL || u32(end+16) == 0xffffffffL) fail(SlotInspectCode.ZIP64, true)
        if (u16(end+10) > MAX_ENTRIES || u32(end+12) > 4L*1024*1024 || u32(end+16)+u32(end+12) > length-tailSize+end) fail(SlotInspectCode.BUDGET, true)
        u16(end+10)
    }
    private fun validateFile(path: Path) {
        val absolute = path.toAbsolutePath().normalize(); var part = absolute.root
        for (segment in absolute) { part = part.resolve(segment)
            val attrs = Files.readAttributes(part, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attrs.isSymbolicLink || attrs.isOther || if (part == absolute) !attrs.isRegularFile else !attrs.isDirectory) fail(SlotInspectCode.UNSAFE_FILE)
            if (System.getProperty("os.name").startsWith("Windows")) {
                val attr = Kernel32.INSTANCE.GetFileAttributes(DesktopArchiveFiles.nativePath(part)); if (attr == -1 || attr and 0x400 != 0) fail(SlotInspectCode.UNSAFE_FILE)
            }
        }
    }
    private fun identity(id: String) { if (id.isBlank() || id.length > 256) fail(SlotInspectCode.IDENTITY) }
    private fun checkBase64(value: String) {
        var count = 0; var padding = 0
        for (c in value) {
            if (c.isWhitespace()) continue
            count++
            if (c == '=') { if (++padding > 2) fail(SlotInspectCode.SCHEMA) }
            else if (padding > 0 || !(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/')) fail(SlotInspectCode.SCHEMA)
        }
        if (count == 0 || count % 4 != 0) fail(SlotInspectCode.SCHEMA)
    }
    private class InspectionFailure(val code: SlotInspectCode, val unsupported: Boolean) : Exception()
    private fun fail(code: SlotInspectCode, unsupported: Boolean = false): Nothing = throw InspectionFailure(code, unsupported)
    companion object {
        // Android 1.4.1 materializeSaveSlotAudio: path first, then voice ID. The newly
        // accepted fallback is limited to schemas 5–7; earlier inspector rules stay intact.
        internal fun legacyAudioResourceKey(slot: SaveSlot, voice: GeneratedVoiceMessage): String? = when {
            voice.audioPath in slot.audioResources -> voice.audioPath
            slot.schemaVersion in 5..7 && voice.id in slot.audioResources -> voice.id
            else -> null
        }

        private const val IMAGE = "chatbar-save-slot-image:"
        private const val AUDIO = "chatbar-save-slot-audio:"
        private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        // Media is consumed with a fixed buffer; its aggregate is not a JSON-memory budget.
        // Keep both total work and structured data bounded independently.
        private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 64L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_JSON_BYTES = 1024 * 1024
        private const val MAX_ENTRIES = 4096
        private const val MAX_RECORDS = 20000
        private val MEDIA_NAME = Regex("media/(images|audio)/[A-Za-z0-9_-]{1,256}\\.[A-Za-z0-9]{1,12}")
    }
}

/** Uses only the existing native OPEN picker. No import delegate or data-root path is accepted. */
internal class DesktopSaveSlotInspectionPicker(private val picker: DesktopFilePicker,
    private val inspector: DesktopSaveSlotInspector = DesktopSaveSlotInspector()) {
    suspend fun inspectSelected(): DesktopSaveSlotInspection? {
        val file = withContext(Dispatchers.IO) { picker.pickOpenFile(DesktopFileType("检查单会话存档（只读）· 尚未导入", listOf("cbsave", "json"))) } ?: return null
        return inspector.inspect(file)
    }
}
