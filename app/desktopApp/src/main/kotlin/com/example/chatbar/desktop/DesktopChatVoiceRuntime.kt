package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.voice.*
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.operation.NoOpAppDataOperationGate
import java.nio.file.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-owned formal voice feature. No automatic synthesis on assistant completion.
 * All entry points re-read ownership before network work or destructive mutation.
 */
internal class DesktopChatVoiceRuntime(
    private val chats: ChatRepository, private val characters: CharacterRepository,
    private val settings: SettingsRepository, private val models: EffectiveModelResolver,
    val repository: VoiceMessageRepository, val audio: DesktopVoiceAudioStore,
    credentials: DesktopFishAudioCredentials, output: DesktopAudioOutput,
    private val picker: DesktopFilePicker = UnconfiguredDesktopFilePicker,
    private val gate: AppDataOperationGate = NoOpAppDataOperationGate,
    private val textBusy: (String) -> Boolean = { false },
    transportOverride: DesktopFishVoiceTransport? = null,
    textGateway: FishVoiceTextGateway? = null,
) {
    private val textTransport = lazy { OpenAiStreamingTransport { settings.currentAppSettings.allowCleartextModelApi } }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val mutation = Mutex()
    private val transport = transportOverride ?: DesktopFishVoiceTransport(credentials, audio)
    val playback = DesktopVoicePlayback(audio, output, gate)
    val coordinator = DesktopFishGenerationCoordinator(scope, chats, characters, settings, repository, credentials,
        transport, FishAudioTagService(textGateway ?: FishVoiceTextGateway { _, messages, config, _ ->
            textTransport.value.streamFishVoice(messages, config)
        }), models, audio, playback, gate)
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private var closed = false

    fun foreground(sessionId: String?) {
        coordinator.setForegroundSession(sessionId)
        scope.launch { playback.stop() }
    }

    suspend fun generate(expected: ChatMessage, narrator: String?, segment: Int? = null,
        auxiliaryAuthorized: Boolean = false, replace: GeneratedVoiceMessage? = null,
        editedTaggedText: String? = null, additional: GeneratedVoiceMessage? = null): String? = guarded {
        gate.withNormalOperation { mutation.withLock {
            check(!closed && !textBusy(expected.sessionId)) { "当前会话仍有文本任务，请稍后操作" }
            val current = chats.getMessage(expected.id, expected.sessionId) ?: error("消息已不存在")
            check(current == expected) { "消息已变化；请重新打开语音操作" }
            val session = chats.getSession(current.sessionId) ?: error("会话已不存在")
            val configured = settings.getAppSettings()
            val needsAuxiliary = !VoiceGenerationPolicy.audiobookEnabled(session, configured) || !session.voiceLanguage.isNullOrBlank()
            check(replace != null || !needsAuxiliary || auxiliaryAuthorized) { "翻译或标签需要明确授权所选辅助模型" }
            if (replace != null) {
                check(repository.get(replace.id) == replace && replace.messageId == current.id && replace.sessionId == current.sessionId)
                coordinator.regenerate(replace.id, checkNotNull(editedTaggedText))
            } else if (additional != null) {
                check(repository.get(additional.id) == additional && additional.messageId == current.id && additional.sessionId == current.sessionId)
                coordinator.generateAdditional(current.sessionId, current.id, additional.anchorId, narrator)
            } else if (segment == null) coordinator.generateWhole(current.sessionId, current.id, narrator)
            else coordinator.generateSingle(current.sessionId, current.id, segment, narrator)
        } }
    }

    suspend fun <T> mutateMessage(sessionId: String, messageId: String, block: suspend () -> T): T =
        mutation.withLock {
            check(!closed)
            coordinator.cancelAndJoinForMessage(sessionId, messageId)
            playback.stop()
            // Existing message/image owners acquire their own normal/exclusive scopes.
            // Never wrap their cleanup in an outer normal lease (no upgrade deadlock).
            block()
        }

    /** Called only after the owning chat message was explicitly deleted. */
    suspend fun removeDeletedMessage(sessionId: String, messageId: String) = gate.withNormalOperation {
        check(chats.readMessageDurable(messageId, sessionId) is com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Missing) { "消息删除状态未知；录音已保留" }
        repository.initialize() // Strict Desktop inventory must be complete before metadata deletion.
        val removed = repository.deleteForMessage(messageId)
        removed.forEach { releaseIfUnreferenced(it.audioPath) }
    }

    /** P11/deletion handoff: caller owns deletion of the session; no recursive directory cleanup. */
    suspend fun deleteSession(sessionId: String, deleteChat: suspend () -> Unit) = mutation.withLock {
            coordinator.cancelAndJoinForSession(sessionId); playback.stop(); repository.initialize()
            deleteChat()
            check(chats.readSessionDurable(sessionId) is com.example.chatbar.data.local.JsonFileStorage.EntityReadResult.Missing) { "会话删除状态未知；录音已保留" }
            gate.withNormalOperation { repository.deleteForSession(sessionId).forEach { releaseIfUnreferenced(it.audioPath) } }
    }

    suspend fun deleteVoice(expected: GeneratedVoiceMessage) = guarded {
        mutateMessage(expected.sessionId, expected.messageId) {
            check(repository.get(expected.id) == expected) { "录音已变化；请重新确认" }
            repository.delete(expected.id)?.let { releaseIfUnreferenced(it.audioPath) }
            mutableNotice.value = "已删除所选录音"
        }
    }
    private suspend fun releaseIfUnreferenced(path: String) = withContext(NonCancellable) {
        gate.withNormalOperation { if (!repository.isAudioReferenced(path)) audio.deleteIfOwned(path) else false }
    }
    suspend fun play(voice: GeneratedVoiceMessage, message: ChatMessage) = guarded {
        gate.withNormalOperation {
            val current = chats.getMessage(message.id, message.sessionId) ?: error("消息已不存在")
            check(current == message)
            val visible = repository.placementsForMessage(current).map { it.voice }
            check(visible.any { it.id == voice.id })
            coordinator.playFrom(voice, visible)
        }
    }
    fun stop() { coordinator.stopPlayback() }
    fun generationSegments(message: ChatMessage, session: ChatSession?, segmented: Boolean): List<CurrentVoiceSegment> =
        if (session == null) emptyList() else VoiceGenerationPolicy.generationSegments(message.displayContent,
            VoiceGenerationPolicy.audiobookEnabled(session, settings.currentAppSettings), segmented)

    suspend fun save(voice: GeneratedVoiceMessage, chatTitle: String) = guarded {
        val destination = withContext(Dispatchers.IO) {
            picker.pickSaveFile(DesktopFileType("MP3 音频", listOf("mp3")), buildVoiceDownloadFileName(chatTitle, voice.characterName))
        } ?: return@guarded
        gate.withNormalOperation { withContext(Dispatchers.IO) {
            val source = audio.verify(voice.audioPath)
            val target = destination.toAbsolutePath().normalize()
            check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "目标文件已存在；请选择新文件名" }
            check(target.parent.toRealPath() == target.parent)
            val part = target.resolveSibling(".${UUID.randomUUID()}.part")
            try {
                Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
                    Files.newOutputStream(part, StandardOpenOption.CREATE_NEW).use { output ->
                        val bytes = ByteArray(16384); var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(bytes); if (n < 0) break
                            check(n <= voice.byteLength - copied); output.write(bytes, 0, n); copied += n
                        }
                        check(copied == voice.byteLength)
                    }
                }
                audio.verify(voice.audioPath); currentCoroutineContext().ensureActive()
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
                mutableNotice.value = "MP3 已保存；未向 QQ 或其他联系人发送"
            } finally { Files.deleteIfExists(part) }
        } }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableNotice.value = e.message ?: "语音操作失败；已有音频已保留"; null }

    suspend fun closeAndDrain() = withContext(NonCancellable) {
        closed = true
        coordinator.closeAndDrain(); job.cancelAndJoin()
        transport.closeAndDrain()
        if (textTransport.isInitialized()) textTransport.value.closeAndDrain()
        playback.closeAndDrain()
    }
}
