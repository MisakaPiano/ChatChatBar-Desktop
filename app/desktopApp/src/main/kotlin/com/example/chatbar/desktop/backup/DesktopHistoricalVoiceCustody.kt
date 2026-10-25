package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.desktop.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Original historical live recording/owner/audio custody. No inference, task or playback activation. */
internal object DesktopHistoricalVoiceCustody {
    const val ROOT = "desktop_historical_voice_sources"
    private val refs = setOf("id", "sessionId", "messageId", "characterId", "anchorId")
    private val hashes = Regex("[a-f0-9]{64}")
    @Serializable private data class FileProof(val path: String, val size: Long, val sha256: String)
    @Serializable private data class Record(val sourcePath: String, val ownerPath: String,
        val voice: String, val owner: String, val audio: String, val bindings: JsonObject)
    @Serializable private data class Index(val version: Int = 1, val kind: String = "historical-live-voice-source",
        val sourceSha256: String, val sourceCode: Int, val sourceFormat: Int, val sourceScope: InspectionScope?,
        val files: List<FileProof>, val records: List<Record>)
    internal class Retained(val voiceId: String, val bindingSha256: String, val audioSha256: String)
    internal class Evidence internal constructor(private val records: List<Retained>) {
        private val byId = records.groupBy { it.voiceId }
        internal fun audioProof(voice: GeneratedVoiceMessage): String? {
            val candidates = byId[voice.id] ?: return null
            val expected = bindingHash(voice)
            return candidates.firstOrNull { it.bindingSha256 == expected }?.audioSha256
        }
        internal fun plus(other: Evidence): Evidence {
            check(records.size + other.records.size <= 40000) { "HISTORICAL_VOICE_RECORD_BUDGET" }
            return Evidence(records + other.records)
        }
        override fun toString() = "HistoricalVoiceEvidence(records=${records.size})"
    }

    suspend fun preserve(input: DesktopFullBackupStaging, plan: DesktopMergeDestinationPlan, root: Path,
        authority: DesktopFreshRootAuthority, pending: DesktopFreshRootAuthority.Pending,
        beforePublish: suspend () -> Unit = {}): Evidence = try {
        preserveOwned(input, plan, root, authority, pending, beforePublish)
    } catch (failure: Throwable) { pending.invalidate(); throw failure }

    private suspend fun preserveOwned(input: DesktopFullBackupStaging, plan: DesktopMergeDestinationPlan, root: Path,
        authority: DesktopFreshRootAuthority, pending: DesktopFreshRootAuthority.Pending,
        beforePublish: suspend () -> Unit): Evidence {
        authority.requirePreparing(pending, root)
        val owners = linkedMapOf<String, Pair<String, ChatMessage>>()
        for ((path, value) in input.entities) if (DesktopBackupPolicy.entityType(path) == "chat_messages") {
            val message = backupJson.decodeFromJsonElement<ChatMessage>(value)
            check(owners.put(message.id, path to message) == null) { "HISTORICAL_VOICE_DUPLICATE_OWNER" }
        }
        val selected = plan.incomingRecords.filter { it.type == "generated_voice_messages" }
        val candidates = mutableListOf<Triple<DesktopMergeRecord, Pair<String, ChatMessage>, GeneratedVoiceMessage>>()
        for (record in selected) {
            val voice = backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(record.value)
            val owner = requireNotNull(owners[voice.messageId]) { "RECORDING_MESSAGE_MISSING" }
            check(owner.second.sessionId == voice.sessionId) { "RECORDING_SESSION_MISMATCH" }
            val version = voice.messageVersionId ?: continue
            check(version.isNotBlank() && version.length <= 256) { "RECORDING_VERSION_INVALID" }
            if (version !in MessageAlternativeVersionPolicy.versions(owner.second).map { it.id }) candidates += Triple(record, owner, voice)
        }
        if (candidates.isEmpty()) return Evidence(emptyList())
        check(candidates.size <= 10000) { "HISTORICAL_VOICE_RECORD_BUDGET" }
        val files = linkedMapOf<String, FileProof>(); val sourceFiles = linkedMapOf<String, String>(); val records = mutableListOf<Record>()
        fun file(source: String, category: String, suffix: String): String {
            val proof = input.source.inventory.getValue(source)
            val path = "$category/${proof.sha256}.$suffix"
            val next = FileProof(path, proof.size, proof.sha256)
            check(files.putIfAbsent(path, next)?.let { it == next } != false)
            sourceFiles.putIfAbsent(path, source)
            return path
        }
        for ((record, owner, original) in candidates) {
            val id = plan.identity.value("generated_voice_messages", original.id)
            val mapped = plan.entities.entries.single { (path, value) -> DesktopBackupPolicy.entityType(path) == "generated_voice_messages" && value["id"]?.jsonPrimitive?.content == id }.value
            val mappedVoice = backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(mapped)
            val bindings = JsonObject(backupJson.encodeToJsonElement(GeneratedVoiceMessage.serializer(), mappedVoice).jsonObject.filterKeys { it in refs })
            check((mappedVoice.anchorId == null) == (original.anchorId == null)) { "HISTORICAL_VOICE_ANCHOR_REBOUND" }
            check(mappedVoice.messageVersionId == original.messageVersionId) { "HISTORICAL_VOICE_VERSION_CHANGED" }
            check(stable(mappedVoice).filterKeys { it !in refs } == stable(original).filterKeys { it !in refs }) { "HISTORICAL_VOICE_CONTENT_CHANGED" }
            check(original.audioPath.startsWith(DesktopBackupPolicy.PREFIX)) { "HISTORICAL_VOICE_AUDIO_REFERENCE" }
            val audioPath = original.audioPath.removePrefix(DesktopBackupPolicy.PREFIX)
            val audio = input.source.inventory.getValue(audioPath)
            check(audio.size == original.byteLength && audio.size in 1..(16L shl 20) && original.durationMs >= 0) { "HISTORICAL_VOICE_AUDIO_LENGTH" }
            records += Record(record.relative, owner.first, file(record.relative, "records", "json"), file(owner.first, "records", "json"),
                file(audioPath, "media", "mp3"), bindings)
        }
        val index = Index(sourceSha256 = input.source.sourceHash, sourceCode = input.source.manifest.appVersionCode,
            sourceFormat = input.source.manifest.formatVersion, sourceScope = input.source.manifest.scope,
            files = files.values.toList(), records = records)
        val parent = root.resolve(ROOT); Files.createDirectories(parent); DesktopArchiveFiles.safe(parent, true)
        val home = parent.resolve(sealName(index))
        if (Files.exists(home, LinkOption.NOFOLLOW_LINKS)) { check(validateSeal(home).first == index) { "HISTORICAL_VOICE_EXISTING_CONFLICT" }; return validateSeal(home).second }
        Files.createDirectory(home); Files.createDirectory(home.resolve("records")); Files.createDirectory(home.resolve("media"))
        for ((path, proof) in files) {
            currentCoroutineContext().ensureActive(); authority.requirePreparing(pending, root)
            check(DesktopArchiveFiles.copyNew(input.files.getValue(sourceFiles.getValue(path)), home.resolve(path), DesktopFullBackupSource.MAX_FILE) == proof.sha256) { "HISTORICAL_VOICE_SOURCE_CHANGED" }
        }
        val part = home.resolve("index.part")
        write(part, backupJson.encodeToString(Index.serializer(), index).toByteArray(Charsets.UTF_8))
        beforePublish(); currentCoroutineContext().ensureActive(); authority.requirePreparing(pending, root)
        Files.move(part, home.resolve("index.json"), StandardCopyOption.ATOMIC_MOVE)
        return validateSeal(home).second
    }

    suspend fun validate(root: Path): Evidence {
        val parent = root.resolve(ROOT)
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) return Evidence(emptyList())
        DesktopArchiveFiles.safe(parent, true)
        var result = Evidence(emptyList()); var count = 0
        Files.newDirectoryStream(parent).use { homes -> for (home in homes) {
            currentCoroutineContext().ensureActive(); check(++count <= 10000) { "HISTORICAL_VOICE_SEAL_BUDGET" }
            result = result.plus(validateSeal(home).second)
        } }
        return result
    }
    private suspend fun validateSeal(home: Path): Pair<Index, Evidence> {
        DesktopArchiveFiles.safe(home, true)
        val path = home.resolve("index.json"); DesktopArchiveFiles.safe(path, false)
        check(Files.size(path) <= DesktopBackupPolicy.MAX_JSON)
        val index = backupJson.decodeFromJsonElement<Index>(strict(Files.readAllBytes(path)))
        check(index.version == 1 && index.kind == "historical-live-voice-source" && hashes.matches(index.sourceSha256) &&
            index.sourceCode in 84..86 && index.sourceFormat in 1..2 && (index.sourceFormat == 2) == (index.sourceScope != null) &&
            (index.sourceFormat != 2 || index.sourceCode >= 85)) { "HISTORICAL_VOICE_INDEX_SCHEMA" }
        check(index.records.size in 1..10000 && index.files.size <= DesktopBackupPolicy.MAX_FILES && home.fileName.toString() == sealName(index))
        val files = index.files.associateBy { it.path }; check(files.size == index.files.size)
        for (proof in files.values) {
            check(proof.path.matches(Regex("(?:records/[a-f0-9]{64}\\.json|media/[a-f0-9]{64}\\.mp3)")) && hashes.matches(proof.sha256) && proof.path.substringAfterLast('/').substringBeforeLast('.') == proof.sha256 && proof.size in 1..DesktopFullBackupSource.MAX_FILE)
            val file = home.resolve(proof.path); DesktopArchiveFiles.safe(file, false)
            check(Files.size(file) == proof.size && DesktopArchiveFiles.hash(file, DesktopFullBackupSource.MAX_FILE) == proof.sha256) { "HISTORICAL_VOICE_FILE_CHANGED" }
        }
        val used = hashSetOf<String>(); val voices = hashSetOf<String>(); val result = mutableListOf<Retained>()
        for (record in index.records) {
            currentCoroutineContext().ensureActive()
            DesktopBackupPolicy.path(record.sourcePath); DesktopBackupPolicy.path(record.ownerPath)
            check(DesktopBackupPolicy.entityType(record.sourcePath) == "generated_voice_messages" && DesktopBackupPolicy.entityType(record.ownerPath) == "chat_messages")
            DesktopBackupPolicy.path(record.sourcePath); DesktopBackupPolicy.path(record.ownerPath)
            check(record.bindings.keys == refs && listOf(record.voice, record.owner).all { it.startsWith("records/") } && record.audio.startsWith("media/"))
            for (name in listOf(record.voice, record.owner, record.audio)) { check(name in files); used += name }
            fun raw(name: String): JsonObject { check(files.getValue(name).size <= DesktopBackupPolicy.MAX_JSON); return strict(Files.readAllBytes(home.resolve(name))) }
            val original = backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(raw(record.voice))
            val owner = backupJson.decodeFromJsonElement<ChatMessage>(raw(record.owner))
            val version = original.messageVersionId
            check(original.messageId == owner.id && original.sessionId == owner.sessionId && version != null && version.isNotBlank() && version.length <= 256 &&
                version !in MessageAlternativeVersionPolicy.versions(owner).map { it.id }) { "HISTORICAL_VOICE_SOURCE_RELATION" }
            check(original.byteLength == files.getValue(record.audio).size && original.durationMs >= 0 && original.audioPath.startsWith(DesktopBackupPolicy.PREFIX))
            DesktopBackupPolicy.path(original.audioPath.removePrefix(DesktopBackupPolicy.PREFIX))
            DesktopMp3Validation.validate(home.resolve(record.audio), 16L shl 20, 1_800_000)
            val mapped = backupJson.decodeFromJsonElement<GeneratedVoiceMessage>(JsonObject(backupJson.encodeToJsonElement(GeneratedVoiceMessage.serializer(), original).jsonObject + record.bindings))
            check(listOf(mapped.id, mapped.sessionId, mapped.messageId, mapped.characterId).all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) })
            check((mapped.anchorId == null) == (original.anchorId == null)) { "HISTORICAL_VOICE_ANCHOR_REBOUND" }
            check(voices.add(mapped.id)) { "HISTORICAL_VOICE_DUPLICATE_BINDING" }
            result += Retained(mapped.id, bindingHash(mapped), files.getValue(record.audio).sha256)
        }
        check(used == files.keys)
        val observed = hashSetOf<String>()
        Files.walk(home).use { paths -> paths.forEach { file ->
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(file, true)
            else { DesktopArchiveFiles.safe(file, false); check(observed.size <= DesktopBackupPolicy.MAX_FILES); observed += home.relativize(file).joinToString("/") }
        } }
        check(observed == files.keys + "index.json") { "HISTORICAL_VOICE_FILE_SET_CHANGED" }
        return index to Evidence(result)
    }
    private fun sealName(index: Index) = DesktopBackupPolicy.digest((index.sourceSha256 + "\u0000" + index.records.sortedBy { it.sourcePath }
        .joinToString("\n") { it.sourcePath + "\u0000" + sorted(it.bindings) }).toByteArray(Charsets.UTF_8))
    private fun sorted(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + sorted(it.value) }
        is JsonArray -> value.joinToString(",", "[", "]") { sorted(it) }
        else -> value.toString()
    }
    private fun bindingHash(voice: GeneratedVoiceMessage) = DesktopBackupPolicy.digest(sorted(stable(voice)).toByteArray(Charsets.UTF_8))
    private fun stable(voice: GeneratedVoiceMessage) = JsonObject(backupJson.encodeToJsonElement(GeneratedVoiceMessage.serializer(), voice).jsonObject - setOf("audioPath", "durationMs", "byteLength"))
    private fun strict(bytes: ByteArray): JsonObject {
        val out = java.io.StringWriter()
        bytes.inputStream().strictUtf8Reader().use { BackupJsonStream(it, out) { value, _ -> value }.copy() }
        return backupJson.parseToJsonElement(out.toString()).jsonObject
    }
    private fun write(path: Path, bytes: ByteArray) {
        check(bytes.size <= DesktopBackupPolicy.MAX_JSON)
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val data = ByteBuffer.wrap(bytes); while(data.hasRemaining()) channel.write(data); channel.force(true)
        }
    }
}
