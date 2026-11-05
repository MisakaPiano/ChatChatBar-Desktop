package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class VoiceCommitPersistence { REFERENCED, NOT_REFERENCED, UNKNOWN }

/** Only the local commit is serialized. Network/tag/TTS work must finish before entering here. */
internal class VoiceGenerationCommitter(
    private val chats: ChatRepository,
    private val voices: VoiceMessageRepository,
    private val deleteUncommittedAudio: (String) -> Unit,
    private val save: suspend (GeneratedVoiceMessage, GeneratedVoiceMessage?, () -> Unit) -> Boolean = voices::saveIfUnchanged,
    private val inspect: suspend (GeneratedVoiceMessage) -> VoiceCommitPersistence = voices::inspectGeneratedCommit
) {
    suspend fun commit(
        source: VoiceGenerationSourceSnapshot,
        voice: GeneratedVoiceMessage,
        previous: GeneratedVoiceMessage? = null
    ): GeneratedVoiceMessage {
        val caller = currentCoroutineContext()
        var committed = false
        try {
            caller.ensureActive()
            return chats.withMessageCommit(source.sessionId, source.messageId) { current ->
                check(source.matches(current)) { "语音来源已改写、删除或版本已退役，未保存过期结果" }
                check(voice.sessionId == source.sessionId && voice.messageId == source.messageId &&
                    voice.messageVersionId == source.versionId) { "语音保存目标与生成快照不一致" }
                // Finish one atomic record write once admitted, even if cancellation arrives during disk I/O.
                withContext(NonCancellable) {
                    if (voice.anchorId != null) {
                        val anchors = voices.ensureAnchorsForVersion(current, source.versionId, source.content)
                        check(anchors.any { it.anchor.id == voice.anchorId }) { "语音目标片段已不存在" }
                    }
                    check(save(voice, previous) { caller.ensureActive() }) {
                        "原语音已删除或被其他任务替换，未覆盖当前结果"
                    }
                    committed = true
                    voice
                }
            }
        } catch (error: Throwable) {
            if (!committed && voice.audioPath != previous?.audioPath) {
                withContext(NonCancellable) {
                    val persisted = try { inspect(voice) } catch (_: Exception) { VoiceCommitPersistence.UNKNOWN }
                    if (persisted == VoiceCommitPersistence.NOT_REFERENCED) {
                        try { deleteUncommittedAudio(voice.audioPath) } catch (cleanupError: Exception) {
                            error.addSuppressed(cleanupError)
                        }
                    } else if (persisted == VoiceCommitPersistence.UNKNOWN) {
                        error.addSuppressed(IllegalStateException("语音提交结果无法确认，保留新音频等待核对"))
                    }
                }
            }
            throw error
        }
    }
}
