package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.MainChatPromptAuthority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class DesktopRepairStatus {
    RUNNING, APPLIED, UNCHANGED, STOPPED, RESTORED, ERROR, CANCELLED,
    REJECTED, CONFLICT, MISSING, CORRUPT, STORAGE_FAILURE, COMMIT_UNCERTAIN,
}

internal enum class DesktopRepairSafety { VERIFIED_SAFE, UNKNOWN, UNSAFE }

/** A proof boundary, not a settings-toggle shortcut. Production stays closed pending derived coordination. */
internal fun interface DesktopRepairSafetyGate {
    suspend fun check(session: ChatSession, message: ChatMessage): DesktopRepairSafety
}

internal class DesktopRepairRequest(
    val formatCard: String?, val segmentedFormat: String?, val original: String,
    val model: ModelConfig, val allowCleartext: Boolean,
)

/** Private in-memory evidence; deliberately has no data-class toString exposing message/exception bodies. */
internal class DesktopRepairState(
    val status: DesktopRepairStatus,
    val preview: String? = null,
    val code: String? = null,
    val commit: ConditionalMessageUpdateResult? = null,
    val identity: DesktopRepairTaskIdentity? = null,
    val source: ChatMessage? = null,
)

internal enum class DesktopRepairTerminalKind { DONE, ERROR, STOP, CANCELLED }
internal data class DesktopRepairTerminal(val kind: DesktopRepairTerminalKind, val code: String? = null)

/** Stop only cancels the network child. Closing cancels the owning runtime job separately. */
internal class DesktopRepairControl {
    private val lock = Any()
    private var terminal: DesktopRepairTerminal? = null
    private var network: Job? = null
    fun requestStop(): Boolean = synchronized(lock) {
        if (terminal != null) return@synchronized false
        terminal = DesktopRepairTerminal(DesktopRepairTerminalKind.STOP)
        network?.cancel()
        true
    }
    fun bind(job: Job) = synchronized(lock) {
        network = job
        if (terminal?.kind == DesktopRepairTerminalKind.STOP) job.cancel()
    }
    fun acceptDelta(update: () -> Unit) = synchronized(lock) { if (terminal == null) update() }
    /** Linearization point shared by terminal events, thrown failures, EOF, cancellation and Stop. */
    fun complete(kind: DesktopRepairTerminalKind, code: String? = null): DesktopRepairTerminal = synchronized(lock) {
        terminal ?: DesktopRepairTerminal(kind, code).also { terminal = it }
    }
}

internal class DesktopMessageFormatRepairController(
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val characters: CharacterRepository,
    private val formats: FormatCardRepository,
    private val models: EffectiveModelResolver,
    private val runtime: DesktopTaskRuntime,
    private val safety: DesktopRepairSafetyGate = DesktopRepairSafetyGate { _, _ -> DesktopRepairSafety.UNKNOWN },
    private val stream: (DesktopRepairRequest) -> Flow<StreamEvent> = { request ->
        OpenAiStreamingTransport { request.allowCleartext }.streamFormatRepair(
            request.formatCard, request.segmentedFormat, request.original, request.model,
        )
    },
) {
    private val stateLock = Any()
    private val mutableStates = MutableStateFlow<Map<String, DesktopRepairState>>(emptyMap())
    private val sources = mutableMapOf<String, ChatMessage>()
    val states: StateFlow<Map<String, DesktopRepairState>> = mutableStates.asStateFlow()

    fun repair(sessionId: String, messageId: String): String = launch(sessionId, messageId, restore = false)
    fun restore(sessionId: String, messageId: String): String = launch(sessionId, messageId, restore = true)

    private fun launch(sessionId: String, messageId: String, restore: Boolean): String =
        runtime.launchFormatRepair(sessionId, messageId) { id, control ->
            publish(id, DesktopRepairState(DesktopRepairStatus.RUNNING))
            try {
                execute(id, control, sessionId, messageId, restore).also { publish(id, it) }.status
            } catch (cancelled: CancellationException) {
                control.complete(DesktopRepairTerminalKind.CANCELLED)
                publish(id, DesktopRepairState(DesktopRepairStatus.CANCELLED, code = "LIFECYCLE_CANCELLED"))
                throw cancelled
            } catch (_: Exception) {
                control.complete(DesktopRepairTerminalKind.ERROR, "PREPARATION_FAILED")
                // No provider body, full prompt or credential enters diagnostics/notices.
                publish(id, DesktopRepairState(DesktopRepairStatus.ERROR, code = "PREPARATION_FAILED"))
                DesktopRepairStatus.ERROR
            }
        }

    private fun publish(id: String, state: DesktopRepairState) = synchronized(stateLock) {
        val identity = runtime.repairIdentities.value[id]
        val identified = DesktopRepairState(state.status, state.preview, state.code, state.commit, identity, sources[id])
        val values = mutableStates.value + (id to identified)
        val completed = values.filterValues { it.status != DesktopRepairStatus.RUNNING }.keys.toList().takeLast(40).toSet()
        mutableStates.value = values.filter { it.value.status == DesktopRepairStatus.RUNNING || it.key in completed }
        sources.keys.retainAll(mutableStates.value.keys)
    }

    private suspend fun execute(id: String, control: DesktopRepairControl, sessionId: String, messageId: String,
        restore: Boolean): DesktopRepairState {
        val snapshot = when (val read = chats.readMessageDurable(messageId, sessionId)) {
            is JsonFileStorage.EntityReadResult.Valid -> read.value
            JsonFileStorage.EntityReadResult.Missing -> return DesktopRepairState(DesktopRepairStatus.MISSING)
            is JsonFileStorage.EntityReadResult.Corrupt -> return DesktopRepairState(DesktopRepairStatus.CORRUPT)
            is JsonFileStorage.EntityReadResult.ReadError -> return DesktopRepairState(DesktopRepairStatus.STORAGE_FAILURE)
        }
        if (snapshot.sessionId != sessionId || snapshot.id != messageId || snapshot.role != MessageRole.ASSISTANT)
            return rejected("INVALID_TARGET")
        synchronized(stateLock) { sources[id] = snapshot }
        val session = when (val read = chats.readSessionDurable(sessionId)) {
            is JsonFileStorage.EntityReadResult.Valid -> read.value
            JsonFileStorage.EntityReadResult.Missing -> return DesktopRepairState(DesktopRepairStatus.MISSING)
            is JsonFileStorage.EntityReadResult.Corrupt -> return DesktopRepairState(DesktopRepairStatus.CORRUPT)
            is JsonFileStorage.EntityReadResult.ReadError -> return DesktopRepairState(DesktopRepairStatus.STORAGE_FAILURE)
        }
        if (session.id != sessionId) return rejected("INVALID_SESSION")
        if (safety.check(session, snapshot) != DesktopRepairSafety.VERIFIED_SAFE) return rejected("DERIVED_DATA_UNPROVEN")
        if (restore) {
            val restored = MessageFormatRepairPolicy.restoreOriginal(snapshot) ?: return rejected("NO_RECOVERY_POINT")
            if (control.complete(DesktopRepairTerminalKind.DONE).kind == DesktopRepairTerminalKind.STOP)
                return DesktopRepairState(DesktopRepairStatus.CANCELLED, code = "RESTORE_NOT_STARTED")
            return commit(session, snapshot, restored.content, null, DesktopRepairStatus.RESTORED)
        }
        val appSettings = settings.getAppSettings()
        val character = characters.getById(session.characterCardId)
        val player = session.playerName?.takeIf(String::isNotBlank) ?: settings.getPlayerSetting().playerName
        val formatId = session.formatCardId ?: appSettings.defaultFormatCardId
        val format = formatId?.let { formats.getById(it)?.content }?.let {
            PlaceholderRenderer.render(it, player, character?.effectiveBotName ?: session.title)
        }?.takeIf(String::isNotBlank)
        val segmented = if (appSettings.assistantSegmentedBubblesEnabled)
            MainChatPromptAuthority.roleplaySpeakerFormatSystemPrompt(character?.characters?.map { it.name }.orEmpty()) else null
        if (format == null && segmented.isNullOrBlank()) return rejected("NO_RULES")
        // Reuse the repository's complete candidate/role validation without writing any fields.
        when (val validated = chats.updateMessageFormatRepairNotice(sessionId, messageId, snapshot, snapshot.formatRepairNotice)) {
            is ConditionalMessageUpdateResult.Success -> Unit
            else -> return classify(validated, DesktopRepairStatus.UNCHANGED)
        }
        val model = models.resolveFormatRepairModel(appSettings.formatRepairModelId, appSettings)
            ?: return configurationFailure(control, session, snapshot, "INVALID_MODEL")
        if (model.apiKey.isBlank()) return configurationFailure(control, session, snapshot, "AUTHENTICATION_REQUIRED")
        val original = snapshot.displayContent
        var prefix = ""
        val terminal = supervisorScope {
            val network = async(start = CoroutineStart.LAZY) {
                try {
                    // Stop collecting at the first trusted transport terminal; do not await an
                    // upstream Flow's post-terminal suspension or accept its late events.
                    stream(DesktopRepairRequest(format, segmented, original, model, appSettings.allowCleartextModelApi)).firstOrNull { event ->
                        when (event) {
                            is StreamEvent.Delta -> {
                                var preview: String? = null
                                control.acceptDelta {
                                    prefix += event.text
                                    preview = MessageFormatRepairPolicy.progressiveOverlay(original, prefix)
                                }
                                // Do not resume StateFlow observers while holding the terminal lock.
                                preview?.let { publish(id, DesktopRepairState(DesktopRepairStatus.RUNNING, it)) }
                                false
                            }
                            is StreamEvent.Error -> {
                                val category = event.failureKind?.name
                                val protocol = (event.cause as? AuxiliaryProtocolException)?.failure?.name
                                val code = listOfNotNull(category, protocol).distinct().joinToString(":")
                                    .ifEmpty { failureCode(event.cause) }
                                control.complete(DesktopRepairTerminalKind.ERROR, code)
                                true
                            }
                            StreamEvent.Done -> { control.complete(DesktopRepairTerminalKind.DONE); true }
                            is StreamEvent.ReasoningDelta, is StreamEvent.Usage -> false
                        }
                    }
                    // A natural EOF without a terminal is an error. Existing Done/Error wins.
                    control.complete(DesktopRepairTerminalKind.ERROR, "UNCONFIRMED_TERMINAL")
                } catch (cancelled: CancellationException) {
                    val winner = control.complete(DesktopRepairTerminalKind.CANCELLED)
                    if (winner.kind == DesktopRepairTerminalKind.CANCELLED) throw cancelled
                    winner
                } catch (error: Exception) {
                    // Seal in the collecting child, before async completion/parent dispatch.
                    control.complete(DesktopRepairTerminalKind.ERROR, failureCode(error))
                }
            }
            control.bind(network)
            network.start()
            try { network.await() }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive() // Parent cancellation is never explicit Stop.
                val winner = control.complete(DesktopRepairTerminalKind.CANCELLED)
                if (winner.kind == DesktopRepairTerminalKind.CANCELLED) throw cancelled
                winner
            }
        }
        currentCoroutineContext().ensureActive()
        if (terminal.kind == DesktopRepairTerminalKind.STOP) {
            val mixed = MessageFormatRepairPolicy.progressiveOverlay(original, prefix)
            return commit(session, snapshot, mixed,
                MessageFormatRepairNotice(MessageFormatRepairNoticeKind.STOPPED, mixed, original), DesktopRepairStatus.STOPPED)
        }
        if (terminal.kind == DesktopRepairTerminalKind.CANCELLED) throw CancellationException("Repair collection cancelled")
        if (terminal.kind == DesktopRepairTerminalKind.ERROR || prefix.isBlank())
            return recordFailure(session, snapshot, terminal.code ?: "EMPTY_OUTPUT")
        if (MessageFormatRepairPolicy.isUnchangedResult(prefix) || prefix == original)
            return commit(session, snapshot, null, MessageFormatRepairPolicy.recoverableNotice(snapshot), DesktopRepairStatus.UNCHANGED)
        return commit(session, snapshot, prefix, MessageFormatRepairPolicy.completedRepairNotice(original, prefix), DesktopRepairStatus.APPLIED)
    }

    private suspend fun configurationFailure(control: DesktopRepairControl, session: ChatSession,
        snapshot: ChatMessage, code: String): DesktopRepairState {
        if (control.complete(DesktopRepairTerminalKind.ERROR, code).kind == DesktopRepairTerminalKind.STOP)
            return DesktopRepairState(DesktopRepairStatus.CANCELLED, code = "REQUEST_NOT_STARTED")
        return recordFailure(session, snapshot, code)
    }

    private suspend fun recordFailure(session: ChatSession, snapshot: ChatMessage, code: String): DesktopRepairState {
        val old = MessageFormatRepairPolicy.recoverableNotice(snapshot)?.takeIf {
            it.kind == MessageFormatRepairNoticeKind.APPLIED || it.kind == MessageFormatRepairNoticeKind.STOPPED
        }
        val notice = old ?: MessageFormatRepairNotice(MessageFormatRepairNoticeKind.ERROR, snapshot.displayContent, errorMessage = code)
        return commit(session, snapshot, null, notice, DesktopRepairStatus.ERROR, code)
    }

    private fun failureCode(error: Throwable?): String = when (error) {
        is AuxiliaryProtocolException -> error.failure.name
        null -> "STREAM_ERROR"
        else -> error.javaClass.simpleName
    }

    private suspend fun commit(session: ChatSession, snapshot: ChatMessage, replacement: String?,
        notice: MessageFormatRepairNotice?, success: DesktopRepairStatus, code: String? = null): DesktopRepairState {
        currentCoroutineContext().ensureActive()
        if (safety.check(session, snapshot) != DesktopRepairSafety.VERIFIED_SAFE) return rejected("DERIVED_DATA_CHANGED")
        val result = if (replacement == null) chats.updateMessageFormatRepairNotice(session.id, snapshot.id, snapshot, notice)
            else chats.updateMessageForFormatRepair(session.id, snapshot.id, snapshot, replacement, notice)
        // No suspension after the repository returns: retain uncertain evidence even during cancellation.
        return classify(result, success, code)
    }

    private fun classify(result: ConditionalMessageUpdateResult, success: DesktopRepairStatus,
        code: String? = null): DesktopRepairState {
        val status = when (result) {
            is ConditionalMessageUpdateResult.Success -> success
            is ConditionalMessageUpdateResult.Conflict -> DesktopRepairStatus.CONFLICT
            is ConditionalMessageUpdateResult.Missing -> DesktopRepairStatus.MISSING
            is ConditionalMessageUpdateResult.Corrupt -> DesktopRepairStatus.CORRUPT
            is ConditionalMessageUpdateResult.StorageFailure -> DesktopRepairStatus.STORAGE_FAILURE
            is ConditionalMessageUpdateResult.CommitUncertain -> DesktopRepairStatus.COMMIT_UNCERTAIN
        }
        return DesktopRepairState(status, code = code, commit = result)
    }

    private fun rejected(code: String) = DesktopRepairState(DesktopRepairStatus.REJECTED, code = code)
}
