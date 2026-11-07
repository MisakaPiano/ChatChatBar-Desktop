package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/** Inert PC snapshot originals whose ownership cannot be proved against replacement text. */
@OptIn(ExperimentalSerializationApi::class)
internal object DesktopComputerVoiceCustody {
    const val KIND = "unselected-computer-voice-source"
    @Serializable private data class Proof(val path: String, val bytes: Long, val sha256: String)
    @Serializable private data class Entry(val type: String, val sourcePath: String, val ownerPath: String,
        val targetPath: String, val record: String, val owner: String, val target: String, val audio: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val targetIncoming: Boolean = false)
    @Serializable private data class SessionAlias(val source: String, val target: String)
    @Serializable private data class Index(val version: Int = 1, val kind: String = KIND,
        val computerFingerprint: String, val incomingSha256: String, val scope: InspectionScope,
        val files: List<Proof>, val entries: List<Entry>,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val aliases: List<SessionAlias> = emptyList())
    private val hash = Regex("[a-f0-9]{64}")

    suspend fun preserve(computer: DesktopFullBackupMapping.Export, incoming: DesktopFullBackupStaging,
        selection: DesktopMergePreview.Selection, root: Path, authority: DesktopFreshRootAuthority,
        pending: DesktopFreshRootAuthority.Pending) {
        val selected = selection.computerVoiceSources
        if (selected.isEmpty()) return
        authority.requirePreparing(pending, root)
        check(computer.credentialFreeSource && selected.size <= DesktopBackupPolicy.MAX_FILES)
        val files = linkedMapOf<String, Proof>()
        val sources = linkedMapOf<String, Path>()
        val aliases = linkedMapOf<String, SessionAlias>()
        suspend fun file(source: Path, media: Boolean = false): String {
            val sha = DesktopArchiveFiles.hash(source, DesktopFullBackupSource.MAX_FILE)
            val path = if (media) "media/$sha.mp3" else "records/$sha.json"
            val proof = Proof(path, Files.size(source), sha)
            check(files.putIfAbsent(path, proof)?.let { it == proof } != false)
            sources.putIfAbsent(path, source)
            return path
        }
        val entries = selected.sortedBy { it.record.relative }.map { source ->
            currentCoroutineContext().ensureActive()
            val record = computer.files.getValue(source.record.relative)
            val owner = computer.files.getValue(source.owner.relative)
            val target = (if (source.targetSide == DesktopMergeDestinationPlan.Side.COMPUTER) computer.files else incoming.files)
                .getValue(source.target.relative)
            val value = strict(record)
            val ownerValue = strict(owner); val targetValue = strict(target)
            val sourceSession = requireNotNull(targetValue["sessionId"]?.jsonPrimitive?.contentOrNull)
            val targetSession = requireNotNull(ownerValue["sessionId"]?.jsonPrimitive?.contentOrNull)
            val incomingAlias = sourceSession != targetSession
            if (incomingAlias) {
                check(source.targetSide == DesktopMergeDestinationPlan.Side.PHONE && selection.aliases.any {
                    it.type == "chat_sessions" && it.sourceId == sourceSession && it.targetId == targetSession
                }) { "COMPUTER_VOICE_TARGET_OWNER_CHANGED" }
                aliases[sourceSession] = SessionAlias(sourceSession, targetSession)
            }
            val comparison = if (incomingAlias) JsonObject(targetValue + ("sessionId" to JsonPrimitive(targetSession))) else targetValue
            sameOwner(ownerValue, comparison)
            check(!DesktopMergeVoiceSourcePolicy.canRetainLive(source.record.type, value, ownerValue, comparison)) {
                "MERGE_COMPUTER_VOICE_CLASSIFICATION_CHANGED"
            }
            val audio = if (source.record.type == "generated_voice_messages") {
                val voice = backupJson.decodeFromJsonElement(GeneratedVoiceMessage.serializer(), value)
                check(voice.audioPath.startsWith(DesktopBackupPolicy.PREFIX)) { "COMPUTER_VOICE_SOURCE_REFERENCE" }
                file(computer.files.getValue(voice.audioPath.removePrefix(DesktopBackupPolicy.PREFIX)), media = true)
            } else null
            Entry(source.record.type, source.record.relative, source.owner.relative, source.target.relative,
                file(record), file(owner), file(target), audio, incomingAlias)
        }
        val index = Index(version = if (aliases.isEmpty()) 1 else 2, computerFingerprint = selection.pcFingerprint, incomingSha256 = incoming.source.sourceHash,
            scope = requireNotNull(selection.incomingScope), files = files.values.toList(), entries = entries, aliases = aliases.values.toList())
        val bytes = backupJson.encodeToString(Index.serializer(), index).toByteArray(Charsets.UTF_8)
        check(bytes.size <= DesktopBackupPolicy.MAX_JSON)
        val parent = root.resolve(DesktopHistoricalVoiceCustody.ROOT)
        Files.createDirectories(parent); DesktopArchiveFiles.safe(parent, true)
        val home = parent.resolve(name(index))
        if (Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
            check(validate(home) == index) { "COMPUTER_VOICE_EXISTING_CONFLICT" }; return
        }
        Files.createDirectory(home); Files.createDirectory(home.resolve("records")); Files.createDirectory(home.resolve("media"))
        for (proof in files.values) {
            currentCoroutineContext().ensureActive(); authority.requirePreparing(pending, root)
            check(DesktopArchiveFiles.copyNew(sources.getValue(proof.path), home.resolve(proof.path), DesktopFullBackupSource.MAX_FILE) == proof.sha256) {
                "COMPUTER_VOICE_SNAPSHOT_CHANGED"
            }
        }
        FileChannel.open(home.resolve("index.part"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
        }
        currentCoroutineContext().ensureActive(); authority.requirePreparing(pending, root)
        Files.move(home.resolve("index.part"), home.resolve("index.json"), StandardCopyOption.ATOMIC_MOVE)
        check(validate(home) == index)
    }

    suspend fun validateSeal(home: Path): DesktopHistoricalVoiceCustody.Evidence {
        validate(home)
        // These originals never grant admission to a live/current-version recording.
        return DesktopHistoricalVoiceCustody.Evidence(emptyList())
    }

    private suspend fun validate(home: Path): Index {
        DesktopArchiveFiles.safe(home, true)
        val index = backupJson.decodeFromJsonElement(Index.serializer(), strict(home.resolve("index.json")))
        check(index.version in 1..2 && index.kind == KIND && hash.matches(index.computerFingerprint) && hash.matches(index.incomingSha256) &&
            index.entries.size in 1..DesktopBackupPolicy.MAX_FILES && index.files.size <= DesktopBackupPolicy.MAX_FILES && home.fileName.toString() == name(index)) {
            "COMPUTER_VOICE_INDEX_SCHEMA"
        }
        val aliases = index.aliases.associateBy { it.source }
        check(aliases.size == index.aliases.size && aliases.size <= DesktopBackupPolicy.MAX_FILES &&
            index.aliases.map { it.target }.distinct().size == aliases.size &&
            (index.version == 1) == (aliases.isEmpty() && index.entries.none { it.targetIncoming })) { "COMPUTER_VOICE_ALIAS_SCHEMA" }
        index.aliases.forEach { alias ->
            check(alias.source != alias.target && listOf(alias.source, alias.target).all { id ->
                id.isNotBlank() && id.length <= 1024 && id.none(Char::isISOControl)
            }) { "COMPUTER_VOICE_ALIAS_SCHEMA" }
        }
        val files = index.files.associateBy { it.path }; check(files.size == index.files.size)
        for (proof in files.values) {
            currentCoroutineContext().ensureActive()
            check(proof.path.matches(Regex("(?:records/[a-f0-9]{64}\\.json|media/[a-f0-9]{64}\\.mp3)")) && hash.matches(proof.sha256) &&
                proof.path.substringAfterLast('/').substringBeforeLast('.') == proof.sha256 && proof.bytes in 1..DesktopFullBackupSource.MAX_FILE)
            val file = home.resolve(proof.path); DesktopArchiveFiles.safe(file, false)
            check(Files.size(file) == proof.bytes && DesktopArchiveFiles.hash(file, DesktopFullBackupSource.MAX_FILE) == proof.sha256) {
                "COMPUTER_VOICE_FILE_CHANGED"
            }
        }
        val used = hashSetOf<String>(); val records = hashSetOf<String>(); val usedAliases = hashSetOf<String>()
        for (entry in index.entries) {
            currentCoroutineContext().ensureActive()
            listOf(entry.sourcePath, entry.ownerPath, entry.targetPath).forEach(DesktopBackupPolicy::path)
            check(entry.type in setOf("generated_voice_messages", "voice_anchor_states") &&
                DesktopBackupPolicy.entityType(entry.sourcePath) == entry.type &&
                DesktopBackupPolicy.entityType(entry.ownerPath) == "chat_messages" && DesktopBackupPolicy.entityType(entry.targetPath) == "chat_messages" &&
                records.add(entry.sourcePath))
            for (file in listOfNotNull(entry.record, entry.owner, entry.target, entry.audio)) { check(file in files); used += file }
            check(listOf(entry.record, entry.owner, entry.target).all { it.startsWith("records/") })
            val original = strict(home.resolve(entry.record))
            val ownerValue = strict(home.resolve(entry.owner)); val targetValue = strict(home.resolve(entry.target))
            val comparison = if (entry.targetIncoming) {
                val source = requireNotNull(targetValue["sessionId"]?.jsonPrimitive?.contentOrNull)
                val alias = aliases[source]
                check(alias != null && alias.target == ownerValue["sessionId"]?.jsonPrimitive?.contentOrNull) { "COMPUTER_VOICE_TARGET_OWNER_CHANGED" }
                usedAliases += source
                JsonObject(targetValue + ("sessionId" to JsonPrimitive(alias.target)))
            } else targetValue
            sameOwner(ownerValue, comparison)
            check(!DesktopMergeVoiceSourcePolicy.canRetainLive(entry.type, original, ownerValue, comparison)) {
                "COMPUTER_VOICE_SOURCE_CLASSIFICATION"
            }
            if (entry.type == "generated_voice_messages") {
                val voice = backupJson.decodeFromJsonElement(GeneratedVoiceMessage.serializer(), original)
                val audio = requireNotNull(entry.audio); check(audio.startsWith("media/"))
                check(voice.audioPath.startsWith(DesktopBackupPolicy.PREFIX) && voice.durationMs >= 0 &&
                    (voice.byteLength == 0L || voice.byteLength == files.getValue(audio).bytes)) { "COMPUTER_VOICE_AUDIO_LENGTH" }
                DesktopBackupPolicy.path(voice.audioPath.removePrefix(DesktopBackupPolicy.PREFIX))
                DesktopMp3Validation.validate(home.resolve(audio), 16L shl 20, 1_800_000)
            } else check(entry.audio == null)
        }
        check(used == files.keys && usedAliases == aliases.keys)
        val observed = hashSetOf<String>()
        Files.walk(home).use { paths -> paths.forEach { file ->
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(file, true)
            else { DesktopArchiveFiles.safe(file, false); check(observed.size <= DesktopBackupPolicy.MAX_FILES); observed += home.relativize(file).joinToString("/") }
        } }
        check(observed == files.keys + "index.json") { "COMPUTER_VOICE_FILE_SET_CHANGED" }
        return index
    }
    private fun name(index: Index) = DesktopBackupPolicy.digest((KIND + "\u0000" +
        backupJson.encodeToString(Index.serializer(), index)).toByteArray(Charsets.UTF_8))
    private fun sameOwner(before: JsonObject, after: JsonObject) {
        val source = backupJson.decodeFromJsonElement(ChatMessage.serializer(), before)
        val target = backupJson.decodeFromJsonElement(ChatMessage.serializer(), after)
        check(source.id == target.id && source.sessionId == target.sessionId) { "COMPUTER_VOICE_TARGET_OWNER_CHANGED" }
    }
    private suspend fun strict(path: Path): JsonObject {
        DesktopArchiveFiles.safe(path, false); check(Files.size(path) <= DesktopBackupPolicy.MAX_JSON)
        val out = java.io.StringWriter()
        val context = currentCoroutineContext()
        Files.newInputStream(path).use { input -> input.strictUtf8Reader().use {
            BackupJsonStream(it, out, checkActive = { context.ensureActive() }) { value, _ -> value }.copy()
        } }
        return backupJson.parseToJsonElement(out.toString()).jsonObject
    }
}
