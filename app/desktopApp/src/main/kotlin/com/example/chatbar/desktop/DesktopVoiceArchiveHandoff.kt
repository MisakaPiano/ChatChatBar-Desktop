package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.voice.VoiceMessageVersionPolicy
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/** Audio-only P11 port. It does not create/import a .cbsave or mutate chat/session entities.
 * P11 owns package validation, message mapping and atomic entity replacement.
 */
internal class DesktopVoiceArchiveHandoff(private val store: DesktopVoiceAudioStore,
    private val repository: VoiceMessageRepository,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    internal data class Export(val voices: List<GeneratedVoiceMessage>, val sources: Map<String, Path>)

    suspend fun planExport(voices: List<GeneratedVoiceMessage>, includeAudio: Boolean): Export {
        if (!includeAudio) return Export(emptyList(), emptyMap()) // Must not read omitted audio.
        require(voices.map { it.id }.distinct().size == voices.size)
        val sources = linkedMapOf<String, Path>()
        val packaged = voices.map { voice ->
            val source = store.verify(voice.audioPath)
            val resourceId = "audio-${UUID.randomUUID()}"
            sources[resourceId] = source
            voice.copy(audioPath = PREFIX + resourceId)
        }
        return Export(packaged, sources)
    }

    suspend fun writeVoices(plan: Export, output: OutputStream) {
        plan.voices.forEach { voice ->
            currentCoroutineContext().ensureActive()
            output.write(json.encodeToString(GeneratedVoiceMessage.serializer(), voice).toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
        }
    }

    suspend fun writeAudio(plan: Export, resourceId: String, output: OutputStream) {
        val source = checkNotNull(plan.sources[resourceId]); store.verify(source.toString())
        Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(16384); var size = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                check(n <= CLIP_BYTES - size); output.write(buffer, 0, n); size += n
            }
        }
        store.verify(source.toString())
    }

    /** targetMessages must be normalized BEFORE changing message IDs (official copy semantics).
     * No source path is opened: resources are supplied as bounded streams by the validated P11 reader.
     * Reattachment is performed by shared restoreForSession(session, staged.voices, targetMessages).
     */
    suspend fun stage(voices: List<GeneratedVoiceMessage>, targetSession: String,
        messageMap: Map<String, ChatMessage>, openResource: (String) -> InputStream): Staged {
        require(voices.size <= 10_000 && voices.map { it.id }.distinct().size == voices.size)
        val resources = voices.map { voice ->
            require(voice.id.isNotBlank() && voice.audioPath.startsWith(PREFIX))
            val resource = voice.audioPath.removePrefix(PREFIX)
            require(resource.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val target = requireNotNull(messageMap[voice.messageId]) { "语音消息缺少目标映射" }
            require(target.sessionId == targetSession && target.role == MessageRole.ASSISTANT)
            val versions = MessageAlternativeVersionPolicy.versions(target)
            require(voice.messageVersionId == null || versions.any { it.id == voice.messageVersionId }) {
                "目标消息版本必须在改 ID 前归一化并保留原版本身份"
            }
            resource
        }
        val staged = Staged()
        try {
            var totalBytes = 0L
            voices.zip(resources).forEach { (voice, resource) ->
                currentCoroutineContext().ensureActive()
                val id = UUID.randomUUID().toString()
                val target = messageMap.getValue(voice.messageId)
                val audio = openResource(resource).use { input ->
                    store.persist(targetSession, UUID.randomUUID().toString(), input) { received ->
                        check(received <= SESSION_BYTES - totalBytes) { "本轮暂存音频超过 64 MiB；请由 P11 分批处理" }
                    }
                }
                staged.paths += audio.path; totalBytes += audio.byteLength
                staged.voices += voice.copy(id = id, sessionId = targetSession, messageId = target.id,
                    messageVersionId = voice.messageVersionId ?: VoiceMessageVersionPolicy.inferLegacyVersionId(target, voice, null),
                    anchorId = null, audioPath = audio.path, durationMs = audio.durationMs, byteLength = audio.byteLength,
                    updatedAt = System.currentTimeMillis())
            }
            return staged
        } catch (error: Throwable) {
            try { staged.rollbackUnreferenced() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    suspend fun stageLegacy(slot: SaveSlot, targetSession: String, messageMap: Map<String, ChatMessage>): Staged {
        require(slot.schemaVersion in 1..7)
        return stage(slot.voiceMessages, targetSession, messageMap) { resource ->
            val entry = requireNotNull(slot.audioResources[resource]) { "旧存档缺少音频" }
            require(entry.data.length <= 24 * 1024 * 1024)
            Base64.getDecoder().wrap(entry.data.byteInputStream(Charsets.US_ASCII))
        }
    }

    inner class Staged internal constructor() {
        val voices = mutableListOf<GeneratedVoiceMessage>()
        internal val paths = mutableListOf<String>()
        /** P11 invokes after failure/cancel, including uncertain commits. Strict read failures
         * retain every remaining file; committed references are never removed.
         */
        suspend fun rollbackUnreferenced() = withContext(NonCancellable) {
            paths.toList().forEach { path ->
                if (!repository.isAudioReferenced(path) && store.deleteIfOwned(path)) paths.remove(path)
            }
        }
    }

    companion object {
        const val PREFIX = "chatbar-save-slot-audio:"
        const val VOICES_ENTRY = "voices.jsonl"
        const val AUDIO_DIRECTORY = "media/audio/"
        private const val CLIP_BYTES = 16L * 1024 * 1024
        private const val SESSION_BYTES = 64L * 1024 * 1024
    }
}
