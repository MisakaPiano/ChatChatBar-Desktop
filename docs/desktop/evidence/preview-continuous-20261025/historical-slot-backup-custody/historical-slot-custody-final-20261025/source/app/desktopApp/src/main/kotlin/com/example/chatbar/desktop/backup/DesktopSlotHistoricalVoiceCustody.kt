package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.*
import com.example.chatbar.domain.chat.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Session-journal participant: an independent original package, never a live media owner. */
internal object DesktopSlotHistoricalVoiceCustody {
    const val KIND = "historical-save-slot-voice-source"
    private val bindings = setOf("id", "sessionId", "messageId", "characterId", "anchorId", "updatedAt")
    @Serializable private data class Binding(val sourceVoiceId: String, val target: JsonObject)
    @Serializable private data class Index(val version: Int = 1, val kind: String = KIND,
        val sourceSha256: String, val sourceBytes: Long, val sourceSchema: Int, val records: List<Binding>)

    suspend fun preserve(prepared: DesktopSaveSlotArchive.Prepared, directory: Path,
        mapped: Map<String, GeneratedVoiceMessage>, beforePublish: suspend () -> Unit = {}) {
        check(directory.parent == prepared.directory && directory.fileName.toString().startsWith("restore-"))
        if (mapped.isEmpty()) return
        check(DesktopArchiveFiles.hash(prepared.file, DesktopSaveSlotArchive.MAX_ARCHIVE) == prepared.sha256)
        val original = source(prepared.file, mapped.keys)
        val records = original.voices.values.mapNotNull { voice ->
            val owner = requireNotNull(original.messages[voice.messageId])
            val version = voice.messageVersionId ?: return@mapNotNull null
            check(owner.sessionId == voice.sessionId)
            if (version in MessageAlternativeVersionPolicy.versions(owner).map { it.id }) return@mapNotNull null
            check(version.isNotBlank() && version.length <= 256)
            val target = requireNotNull(mapped[voice.id])
            // Only the actual restore's identities and update timestamp may change. Text/version stay exact.
            val targetJson = backupJson.encodeToJsonElement(GeneratedVoiceMessage.serializer(), target).jsonObject
            check(stable(target).filterKeys { it !in bindings } == stable(voice).filterKeys { it !in bindings })
            check(target.anchorId == null || target.anchorId == voice.anchorId)
            Binding(voice.id, JsonObject(targetJson.filterKeys { it in bindings }))
        }
        if (records.isEmpty()) return
        check(records.size <= 10000)
        val index = Index(sourceSha256 = prepared.sha256, sourceBytes = Files.size(prepared.file),
            sourceSchema = prepared.slot.schemaVersion, records = records)
        val parent = directory.resolve(DesktopHistoricalVoiceCustody.ROOT)
        Files.createDirectories(parent); DesktopArchiveFiles.safe(parent, true)
        val home = Files.createDirectory(parent.resolve(name(index)))
        check(DesktopArchiveFiles.copyNew(prepared.file, home.resolve("source.cbsave"), DesktopSaveSlotArchive.MAX_ARCHIVE) == prepared.sha256)
        val bytes = backupJson.encodeToString(Index.serializer(), index).toByteArray(Charsets.UTF_8)
        check(bytes.size <= DesktopBackupPolicy.MAX_JSON)
        FileChannel.open(home.resolve("index.part"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
        }
        beforePublish(); currentCoroutineContext().ensureActive()
        Files.move(home.resolve("index.part"), home.resolve("index.json"), StandardCopyOption.ATOMIC_MOVE)
        validateSeal(home)
        check(DesktopArchiveFiles.hash(prepared.file, DesktopSaveSlotArchive.MAX_ARCHIVE) == prepared.sha256)
    }

    suspend fun validateSeal(home: Path): DesktopHistoricalVoiceCustody.Evidence {
        DesktopArchiveFiles.safe(home, true)
        check(Files.newDirectoryStream(home).use { it.map { p -> p.fileName.toString() }.toSet() } == setOf("source.cbsave", "index.json"))
        val file = home.resolve("index.json"); DesktopArchiveFiles.safe(file, false)
        check(Files.size(file) <= DesktopBackupPolicy.MAX_JSON)
        val index = backupJson.decodeFromJsonElement<Index>(strict(Files.readAllBytes(file)))
        check(index.version == 1 && index.kind == KIND && index.sourceSchema in 1..8 &&
            index.sourceSha256.matches(Regex("[a-f0-9]{64}")) && index.sourceBytes in 1..DesktopSaveSlotArchive.MAX_ARCHIVE &&
            index.records.size in 1..10000 && home.fileName.toString() == name(index))
        val archive = home.resolve("source.cbsave"); DesktopArchiveFiles.safe(archive, false)
        check(Files.size(archive) == index.sourceBytes && DesktopArchiveFiles.hash(archive, DesktopSaveSlotArchive.MAX_ARCHIVE) == index.sourceSha256)
        val sourceIds = index.records.map { it.sourceVoiceId }.toSet()
        check(sourceIds.size == index.records.size)
        val source = source(archive, sourceIds)
        check(source.schema == index.sourceSchema)
        val targetIds = hashSetOf<String>()
        val retained = index.records.map { record ->
            currentCoroutineContext().ensureActive()
            check(record.target.keys == bindings)
            val original = source.voices.getValue(record.sourceVoiceId)
            val owner = source.messages.getValue(original.messageId)
            val version = original.messageVersionId
            check(owner.sessionId == original.sessionId && version != null && version.isNotBlank() && version.length <= 256 &&
                version !in MessageAlternativeVersionPolicy.versions(owner).map { it.id })
            val mapped = backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(JsonObject(
                backupJson.encodeToJsonElement(GeneratedVoiceMessage.serializer(), original).jsonObject + record.target))
            check(listOf(mapped.id, mapped.sessionId, mapped.messageId, mapped.characterId).all {
                it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)
            } && targetIds.add(mapped.id))
            check(mapped.anchorId == null || mapped.anchorId == original.anchorId)
            DesktopHistoricalVoiceCustody.Retained(mapped.id, DesktopHistoricalVoiceCustody.bindingHash(mapped),
                source.audio.getValue(record.sourceVoiceId))
        }
        return DesktopHistoricalVoiceCustody.Evidence(retained)
    }

    private data class Source(val schema: Int, val voices: Map<String, GeneratedVoiceMessage>,
        val messages: Map<String, ChatMessage>, val audio: Map<String, String>)
    private suspend fun source(path: Path, selected: Set<String>): Source {
        val inspected = DesktopSaveSlotInspector(true).inspect(path)
        check(inspected.status == SlotInspectStatus.STRUCTURE_VALID)
        val voices = linkedMapOf<String, GeneratedVoiceMessage>(); val messages = linkedMapOf<String, ChatMessage>()
        val audio = linkedMapOf<String, String>()
        var schema = 0
        if (inspected.format == SlotInspectFormat.ZIP_V8) ZipFile(path.toFile()).use { zip ->
            schema = 8
            lines(zip.getInputStream(zip.getEntry("voices.jsonl"))) { value ->
                val voice = backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(value)
                if (voice.id in selected) check(voices.put(voice.id, voice) == null)
            }
            val owners = voices.values.map { it.messageId }.toSet()
            lines(zip.getInputStream(zip.getEntry("messages.jsonl"))) { value ->
                val message = backupJson.decodeFromJsonElement<ChatMessage>(value)
                if (message.id in owners) check(messages.put(message.id, message) == null)
            }
            for (voice in voices.values) {
                check(voice.audioPath.startsWith(SAVE_SLOT_AUDIO_PREFIX))
                val key = voice.audioPath.removePrefix(SAVE_SLOT_AUDIO_PREFIX)
                check(key.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                audio[voice.id] = zip.getInputStream(requireNotNull(zip.getEntry("media/audio/$key.mp3"))).use { hash(it, voice.byteLength) }
            }
        } else {
            check(Files.size(path) <= 1024 * 1024)
            val slot = backupJson.decodeFromJsonElement<SaveSlot>(strict(Files.readAllBytes(path)))
            schema = slot.schemaVersion
            slot.voiceMessages.filter { it.id in selected }.forEach { check(voices.put(it.id, it) == null) }
            val owners = voices.values.map { it.messageId }.toSet()
            slot.messages.filter { it.id in owners }.forEach { check(messages.put(it.id, it) == null) }
            for (voice in voices.values) {
                val key = requireNotNull(DesktopSaveSlotInspector.legacyAudioResourceKey(slot, voice))
                audio[voice.id] = java.util.Base64.getDecoder().wrap(slot.audioResources.getValue(key).data.byteInputStream(Charsets.US_ASCII)).use { hash(it, voice.byteLength) }
            }
        }
        check(voices.keys == selected && messages.keys.containsAll(voices.values.map { it.messageId }))
        return Source(schema, voices, messages, audio)
    }
    private suspend fun hash(input: java.io.InputStream, expected: Long): String {
        check(expected in 1..(16L shl 20)); var total = 0L; val buffer = ByteArray(65536); val digest = MessageDigest.getInstance("SHA-256")
        while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
            total += count; check(total <= expected); digest.update(buffer, 0, count) }
        check(total == expected); return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun lines(input: java.io.InputStream, consume: (JsonObject) -> Unit) = input.buffered().use { stream ->
        val line = java.io.ByteArrayOutputStream(); var count = 0
        while (true) { if (++count % 8192 == 0) currentCoroutineContext().ensureActive(); val next = stream.read()
            if (next < 0 || next == 10) { val bytes = line.toByteArray(); line.reset()
                if (bytes.any { it !in byteArrayOf(9,13,32) }) consume(strict(bytes)); if (next < 0) break
            } else { check(line.size() < 1024 * 1024); line.write(next) }
        }
    }
    private fun strict(bytes: ByteArray): JsonObject {
        val out = java.io.StringWriter(); bytes.inputStream().strictUtf8Reader().use { BackupJsonStream(it,out) { value,_ -> value }.copy() }
        return backupJson.parseToJsonElement(out.toString()).jsonObject
    }
    private fun stable(voice: GeneratedVoiceMessage) = JsonObject(backupJson.encodeToJsonElement(GeneratedVoiceMessage.serializer(), voice).jsonObject - setOf("audioPath","durationMs","byteLength"))
    private fun name(index: Index) = DesktopBackupPolicy.digest(backupJson.encodeToString(Index.serializer(), index).toByteArray(Charsets.UTF_8))
}
