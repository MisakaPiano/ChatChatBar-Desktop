package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.chat.CharacterSessionService
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.ui.chat.isRetryableGenerationError
import com.example.chatbar.ui.chat.regenerationTargetAssistantMessageId
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopPrimaryChoice(val id: String, val label: String)

internal data class DesktopPrimarySessionItem(
    val id: String,
    val title: String,
    val displayTitleOverride: String?,
    val pinned: Boolean,
    val characterName: String?,
    val lastMessagePreview: String?,
) {
    val characterMissing: Boolean get() = characterName == null
}

internal data class DesktopPrimaryChatState(
    val characters: List<DesktopPrimaryChoice> = emptyList(),
    val sessions: List<DesktopPrimarySessionItem> = emptyList(),
    val sessionQuery: String = "",
    val selectedSession: ChatSession? = null,
    val selectedCharacter: CharacterCard? = null,
    val selectedCharacterMissing: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val totalMessageCount: Int = 0,
    val composerDraft: String = "",
    val modelUsable: Boolean = false,
    val modelDiagnostic: DesktopModelDiagnostic? = null,
    val configurationMessage: String? = "Select or create a session",
    val sessionSettingsDraft: ChatSession? = null,
    val sessionReplyLengthInput: String = "",
    val sessionSettingsDirty: Boolean = false,
    val sessionSettingsLeavePrompt: Boolean = false,
    val modelChoices: List<DesktopPrimaryChoice> = emptyList(),
    val formatChoices: List<DesktopPrimaryChoice> = emptyList(),
    val worldBookChoices: List<DesktopPrimaryChoice> = emptyList(),
    val globalPlayerName: String? = null,
    val assistantSegmentedBubblesEnabled: Boolean = true,
    val alternativeEligibleIds: Set<String> = emptySet(),
    val error: String? = null,
    val status: String? = null,
)

internal enum class DesktopRegenerationAction { REGENERATE, RETRY }

internal fun desktopRegenerationAction(
    messages: List<ChatMessage>, selected: ChatMessage, running: DesktopTaskEntry?,
): DesktopRegenerationAction? = when {
    running != null || regenerationTargetAssistantMessageId(messages, selected.id) == null -> null
    selected.isRetryableGenerationError() -> DesktopRegenerationAction.RETRY
    else -> DesktopRegenerationAction.REGENERATE
}

internal fun desktopVisibleMessages(messages: List<ChatMessage>, running: DesktopTaskEntry?): List<ChatMessage> =
    messages.filterNot { it.id == running?.targetMessageId }

internal fun desktopMessageMutationActionsAvailable(running: DesktopTaskEntry?): Boolean = running == null

/** Container-lifetime view state and draft persistence; DesktopTaskRuntime owns generation. */
internal class DesktopPrimaryChatController(
    private val characters: CharacterRepository,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val models: EffectiveModelResolver,
    private val formats: FormatCardRepository,
    private val worldBooks: WorldBookRepository,
    private val sessionService: CharacterSessionService,
    val characterResources: DesktopCharacterResourceStore,
    private val modelRepository: ModelRepository? = null,
    private val catalogProvider: (String?) -> String? = { null },
    val taskRuntime: DesktopTaskRuntime,
    draftDispatcher: CoroutineDispatcher = Dispatchers.IO,
    draftWriter: suspend (String, String) -> Unit = chats::updateSessionDraft,
    private val sessionSearch: suspend (String) -> List<ChatSession> = chats::searchSessions,
) {
    private val stateLock = Mutex()
    private val draftLock = Mutex()
    private val mutableState = MutableStateFlow(DesktopPrimaryChatState())
    val state: StateFlow<DesktopPrimaryChatState> = mutableState.asStateFlow()
    private var settingsBaseline: ChatSession? = null
    private var pendingSessionSettingsLeave: (suspend () -> Unit)? = null
    private val searchGeneration = AtomicLong()
    private val composerLock = Any()
    private var pendingComposerClear: DesktopDraftRevision? = null
    private val draftPersistence = DesktopChatDraftPersistence(draftWriter, draftDispatcher, ::onDraftResult)

    suspend fun refresh() = guarded {
        stateLock.withLock {
            val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
            val allSessions = sessionItems(characterItems, "")
            val sessions = if (state.value.sessionQuery.isBlank()) allSessions else
                sessionItems(characterItems, state.value.sessionQuery)
            val selectedId = state.value.selectedSession?.id
                ?.takeIf { id -> allSessions.any { it.id == id } }
                ?: allSessions.firstOrNull()?.id
            if (selectedId == null) {
                settingsBaseline = null
                mutableState.value = DesktopPrimaryChatState(
                    characters = characterItems, sessions = sessions, sessionQuery = state.value.sessionQuery,
                )
            } else {
                loadSelection(selectedId, characterItems, sessions, preserveWindow = true)
            }
        }
    }

    suspend fun searchSessions(query: String) = guarded {
        val generation = searchGeneration.incrementAndGet()
        stateLock.withLock {
            val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
            val sessions = sessionItems(characterItems, query)
            if (generation == searchGeneration.get()) {
                mutableState.update { it.copy(characters = characterItems, sessions = sessions, sessionQuery = query) }
            }
        }
    }

    suspend fun selectSession(id: String): Unit = guarded {
        if (state.value.sessionSettingsDirty && state.value.selectedSession?.id != id) {
            requestSessionSettingsLeave { selectSession(id) }
            return@guarded
        }
        draftLock.withLock {
            stateLock.withLock {
                val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
                val allSessions = sessionItems(characterItems, "")
                val sessions = sessionItems(characterItems, state.value.sessionQuery)
                require(allSessions.any { it.id == id }) { "Session no longer exists" }
                loadSelection(id, characterItems, sessions, preserveWindow = false)
            }
        }
    }

    suspend fun createSession(characterId: String) = guarded {
        require(characters.getById(characterId) != null) { "Character no longer exists" }
        val modelStatus = models.status(settings.getAppSettings())
        if (!modelStatus.isUsable) {
            val message = modelStatus.errors.firstOrNull() ?: "Configure a usable chat model"
            mutableState.update {
                it.copy(
                    configurationMessage = if (it.selectedSession == null) message else it.configurationMessage,
                    error = message,
                )
            }
            return@guarded
        }
        val id = sessionService.createSessionForCharacter(characterId)
        searchGeneration.incrementAndGet()
        stateLock.withLock {
            val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
            val sessions = sessionItems(characterItems, "")
            mutableState.update {
                it.copy(sessionQuery = "", sessions = sessions)
            }
        }
        selectSession(id)
    }

    suspend fun loadOlder() = guarded {
        stateLock.withLock {
            val current = state.value
            val id = current.selectedSession?.id ?: return@withLock
            val oldestId = current.messages.firstOrNull()?.id ?: return@withLock
            if (!current.hasOlderMessages) return@withLock
            val page = chats.getOlderMessagePage(id, oldestId)
            mutableState.update {
                it.copy(
                    messages = (page.messages + current.messages).distinctBy(ChatMessage::id)
                        .sortedWith(ChatMessage.TimelineComparator),
                    hasOlderMessages = page.hasOlder,
                    totalMessageCount = page.totalMessageCount,
                    error = null,
                )
            }
        }
    }

    suspend fun refreshAfterTerminalTask(sessionId: String) = guarded {
        stateLock.withLock {
            val current = state.value
            val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
            val sessions = sessionItems(characterItems, state.value.sessionQuery)
            if (current.selectedSession?.id == sessionId) {
                loadSelection(sessionId, characterItems, sessions, preserveWindow = true)
            } else {
                mutableState.update { it.copy(characters = characterItems, sessions = sessions) }
            }
        }
    }

    suspend fun togglePin(id: String) = guarded {
        val pinned = state.value.sessions.firstOrNull { it.id == id }?.pinned ?: return@guarded
        if (pinned) chats.unpinSession(id) else chats.pinSession(id)
        refresh()
        mutableState.update { it.copy(status = "Session pin saved") }
    }

    suspend fun setDisplayTitle(id: String, title: String) = guarded {
        chats.updateSessionDisplayTitle(id, title)
        refresh()
    }

    fun editSessionSettings(change: (ChatSession) -> ChatSession) {
        mutableState.update {
            val changed = it.sessionSettingsDraft?.let(change)
            it.copy(sessionSettingsDraft = changed,
                sessionSettingsDirty = changed != null && (changed != settingsBaseline ||
                    it.sessionReplyLengthInput != settingsBaseline?.replyLength?.toString()), error = null)
        }
    }

    fun editSessionReplyLengthInput(value: String) {
        mutableState.update { current ->
            val valid = value.toIntOrNull()?.takeIf { it > 0 }
            val draft = current.sessionSettingsDraft?.let { if (valid == null) it else it.copy(replyLength = valid) }
            current.copy(sessionReplyLengthInput = value, sessionSettingsDraft = draft,
                sessionSettingsDirty = draft != null && (draft != settingsBaseline ||
                    value != settingsBaseline?.replyLength?.toString()), error = null)
        }
    }

    fun discardSessionSettings() {
        mutableState.update { it.copy(sessionSettingsDraft = settingsBaseline,
            sessionReplyLengthInput = settingsBaseline?.replyLength?.toString().orEmpty(),
            sessionSettingsDirty = false) }
    }

    suspend fun requestSessionSettingsLeave(action: suspend () -> Unit) {
        if (state.value.sessionSettingsDirty) {
            pendingSessionSettingsLeave = action
            mutableState.update { it.copy(sessionSettingsLeavePrompt = true) }
        } else action()
    }

    fun continueSessionSettingsEditing() {
        pendingSessionSettingsLeave = null
        mutableState.update { it.copy(sessionSettingsLeavePrompt = false) }
    }

    suspend fun resolveSessionSettingsLeave(save: Boolean) {
        val action = pendingSessionSettingsLeave ?: return
        if (save) {
            saveSessionSettings()
            if (state.value.error != null || state.value.sessionSettingsDirty) return
        } else discardSessionSettings()
        pendingSessionSettingsLeave = null
        mutableState.update { it.copy(sessionSettingsLeavePrompt = false) }
        action()
    }

    suspend fun saveSessionSettings() = guarded {
        if (state.value.sessionReplyLengthInput.toIntOrNull()?.let { it > 0 } != true) {
            mutableState.update { it.copy(error = "Reply length must be positive") }
            return@guarded
        }
        val baseline = settingsBaseline ?: return@guarded
        val draft = state.value.sessionSettingsDraft ?: return@guarded
        val saved = chats.saveSessionSettingsDraft(baseline, draft)
        settingsBaseline = saved
        val modelStatus = models.status(saved.modelId, settings.getAppSettings())
        mutableState.update {
            it.copy(
                selectedSession = saved,
                sessionSettingsDraft = saved,
                sessionReplyLengthInput = saved.replyLength.toString(),
                sessionSettingsDirty = false,
                modelUsable = modelStatus.isUsable,
                configurationMessage = modelStatus.errors.firstOrNull(),
                status = "Session settings saved",
            )
        }
        refresh()
    }

    /** Capture the session and latest edit before returning; the application writer owns persistence. */
    fun editComposer(text: String) {
        synchronized(composerLock) {
            val id = state.value.selectedSession?.id ?: return
            pendingComposerClear = null
            try {
                draftPersistence.submit(id, text)
                mutableState.update { it.copy(composerDraft = text, error = null) }
            } catch (_: IllegalStateException) {
                mutableState.update { it.copy(composerDraft = text, error = "Chat draft persistence is closing") }
            }
        }
    }

    /** Optional flush for tests/callers; normal UI edits already schedule their own persistence. */
    suspend fun persistComposer() = guarded {
        state.value.selectedSession?.id?.let { draftPersistence.flush(it) }
    }

    suspend fun closeDraftPersistence(timeoutMillis: Long = 10_000L) = draftPersistence.closeAndDrain(timeoutMillis)

    suspend fun send(): String? = launch(continuation = false)

    suspend fun continueReply(): String? = launch(continuation = true)

    fun stop(taskId: String): Boolean = taskRuntime.requestUserStop(taskId)

    suspend fun regenerate(messageId: String): String? {
        var taskId: String? = null
        guarded {
            val session = state.value.selectedSession ?: return@guarded
            taskId = taskRuntime.launchRegeneration(session.id, messageId)
        }
        return taskId
    }

    suspend fun selectAssistantAlternative(messageId: String, direction: Int) = guarded {
        val session = state.value.selectedSession ?: return@guarded
        val message = chats.getMessage(messageId, session.id) ?: return@guarded
        if (message.role != MessageRole.ASSISTANT || message.alternatives.size <= 1) return@guarded
        val size = settings.getAppSettings().defaultContextWindowSize.coerceAtLeast(0)
        if (messageId !in eligibleAlternativeIds(session.id, size)) return@guarded
        val next = (message.currentAlternativeIndex + direction).coerceIn(0, message.alternatives.lastIndex)
        if (next == message.currentAlternativeIndex) return@guarded
        chats.updateMessage(MessageAlternativeVersionPolicy.select(message, next))
        refreshAfterTerminalTask(session.id)
    }

    suspend fun editMessage(messageId: String, content: String): Boolean {
        var saved = false
        guarded {
            val session = state.value.selectedSession ?: return@guarded
            check(!hasActiveTask(session.id)) { "Message editing is unavailable during generation" }
            val message = chats.getMessage(messageId, session.id) ?: error("Message no longer exists")
            require(content.isNotBlank() || message.images.isNotEmpty()) { "Message cannot be empty" }
            chats.updateMessage(
                MessageAlternativeVersionPolicy.collapseToEditedContent(message, content)
                    .copy(formatRepairNotice = null),
            )
            refreshAfterTerminalTask(session.id)
            saved = true
        }
        return saved
    }

    suspend fun deleteMessage(messageId: String): Boolean {
        var deleted = false
        guarded {
            val session = state.value.selectedSession ?: return@guarded
            check(!hasActiveTask(session.id)) { "Message deletion is unavailable during generation" }
            val message = chats.getMessage(messageId, session.id) ?: error("Message no longer exists")
            chats.deleteMessage(messageId, session.id)
            refreshAfterTerminalTask(session.id)
            deleted = true
        }
        return deleted
    }

    suspend fun relinkArchivedSession(characterId: String): Boolean {
        var relinked = false
        guarded {
            val session = state.value.selectedSession ?: return@guarded
            chats.relinkArchivedSession(session.id, characterId, characters)
            stateLock.withLock {
                val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
                val sessions = sessionItems(characterItems, state.value.sessionQuery)
                loadSelection(session.id, characterItems, sessions, preserveWindow = true)
                val saved = state.value.selectedSession ?: error("Session no longer exists")
                settingsBaseline = saved
                mutableState.update { current ->
                    current.copy(sessionSettingsDraft = if (current.sessionSettingsDirty) {
                        current.sessionSettingsDraft?.copy(characterCardId = characterId)
                    } else saved)
                }
            }
            relinked = true
        }
        return relinked
    }

    private suspend fun launch(continuation: Boolean): String? {
        var accepted: String? = null
        guarded {
            draftLock.withLock {
                val (current, revision) = synchronized(composerLock) {
                    val snapshot = state.value
                    snapshot to snapshot.selectedSession?.id?.let(draftPersistence::latestRevision)
                }
                val session = current.selectedSession ?: return@withLock
                if (current.selectedCharacterMissing || characters.getById(session.characterCardId) == null) {
                    mutableState.update {
                        it.copy(selectedCharacterMissing = true, error = "Archived session: character is missing")
                    }
                    return@withLock
                }
                val modelStatus = models.status(session.modelId, settings.getAppSettings())
                if (!modelStatus.isUsable) {
                    mutableState.update {
                        it.copy(
                            modelUsable = false,
                            configurationMessage = modelStatus.errors.firstOrNull(),
                            error = modelStatus.errors.firstOrNull() ?: "No usable chat model",
                        )
                    }
                    return@withLock
                }
                val text = if (continuation) "" else current.composerDraft
                if (!continuation && text.isBlank()) return@withLock
                try {
                    accepted = taskRuntime.launchChat(session.id, text)
                } catch (rejected: DesktopTaskAdmissionException) {
                    mutableState.update { it.copy(error = rejected.message) }
                    return@withLock
                }
                if (!continuation) {
                    val clear = synchronized(composerLock) {
                        draftPersistence.clearIfCurrent(session.id, revision)?.also {
                            pendingComposerClear = it
                        }
                    }
                    // Cancelling this waiter cannot cancel the write or its UI-state completion callback.
                    draftPersistence.await(clear)
                }
            }
        }
        return accepted
    }

    private fun onDraftResult(revision: DesktopDraftRevision, successful: Boolean) {
        synchronized(composerLock) {
            if (!successful) {
                mutableState.update { it.copy(error = "Unable to save chat draft") }
            } else if (pendingComposerClear == revision &&
                draftPersistence.latestRevision(revision.sessionId) == revision
            ) {
                pendingComposerClear = null
                mutableState.update {
                    if (it.selectedSession?.id == revision.sessionId) it.copy(composerDraft = "", error = null) else it
                }
            }
        }
    }

    private suspend fun sessionItems(characterItems: List<DesktopPrimaryChoice>, query: String): List<DesktopPrimarySessionItem> {
        val names = characterItems.associate { it.id to it.label }
        val found = if (query.isBlank()) chats.getAllSessions() else sessionSearch(query.trim())
        if (found.isEmpty()) return emptyList()
        val cards = characters.getAll().associateBy(CharacterCard::id)
        val globalPlayerName = settings.getPlayerSetting().playerName
        return found.map { session ->
            DesktopPrimarySessionItem(
                id = session.id,
                title = desktopRenderedSessionTitle(session, cards[session.characterCardId], globalPlayerName)
                    .takeIf(String::isNotBlank) ?: session.id,
                displayTitleOverride = session.displayTitleOverride,
                pinned = session.isPinned,
                characterName = names[session.characterCardId],
                lastMessagePreview = desktopRenderSessionText(
                    session, session.lastMessagePreview ?: "开始全新对话…",
                    cards[session.characterCardId], globalPlayerName,
                ),
            )
        }
    }

    private suspend fun loadSelection(
        id: String,
        characterItems: List<DesktopPrimaryChoice>,
        sessions: List<DesktopPrimarySessionItem>,
        preserveWindow: Boolean,
    ) {
        val session = chats.getSession(id) ?: error("Session no longer exists")
        val previous = state.value.takeIf { it.selectedSession?.id == id && preserveWindow }
        val page = chats.getInitialMessagePage(id)
        // Regeneration updates the same row and may delete a mapped retry-error row. Keep the
        // loaded older window, but never let its stale snapshots override durable replacements.
        val pageIds = page.messages.mapTo(mutableSetOf(), ChatMessage::id)
        val retained = previous?.messages.orEmpty().filterNot { it.id in pageIds }
            .mapNotNull { chats.getMessage(it.id, id) }
        val refreshedMessages = (retained + page.messages).sortedWith(ChatMessage.TimelineComparator)
        val appSettings = settings.getAppSettings()
        val alternativeEligibleIds = eligibleAlternativeIds(
            id, appSettings.defaultContextWindowSize.coerceAtLeast(0),
        )
        val playerSetting = settings.getPlayerSetting()
        val selectedCharacter = characters.getById(session.characterCardId)
        val modelStatus = models.status(session.modelId, appSettings)
        val effective = models.resolveChatModel(session.modelId, appSettings)
        val diagnostic = desktopModelDiagnostic(
            session.modelId, effective, appSettings,
            effective?.id?.let { modelRepository?.getModel(it) }, catalogProvider(effective?.sourcePresetKey),
        )
        val modelChoices = models.availableChatModels(appSettings)
            .map { DesktopPrimaryChoice(it.id, it.displayName) }
        val formatChoices = formats.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
        val worldBookChoices = worldBooks.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
        val persistedDraft = if (state.value.selectedSession?.id == id) null else {
            draftPersistence.flush(id)
            chats.getSessionDraft(id)
        }
        // Edits may arrive while selection reads suspend. Commit a switch only after the outgoing
        // revision is durable, checking it again under the same lock used to capture edits.
        while (true) {
            val outgoing = synchronized(composerLock) {
                state.value.selectedSession?.id?.takeIf { it != id }?.let(draftPersistence::latestRevision)
            }
            draftPersistence.await(outgoing)
            val selected = synchronized(composerLock) {
                val current = state.value
                val latest = current.selectedSession?.id?.takeIf { it != id }?.let(draftPersistence::latestRevision)
                if (latest != outgoing) false else {
                    if (previous == null) settingsBaseline = session
                    mutableState.value = current.copy(
                        characters = characterItems,
                        sessions = sessions,
                        selectedSession = session,
                        selectedCharacter = selectedCharacter,
                        selectedCharacterMissing = selectedCharacter == null,
                        messages = refreshedMessages,
                        hasOlderMessages = if (previous == null) page.hasOlder else previous.hasOlderMessages,
                        totalMessageCount = page.totalMessageCount,
                        composerDraft = if (current.selectedSession?.id == id) current.composerDraft else persistedDraft.orEmpty(),
                        modelUsable = modelStatus.isUsable,
                        modelDiagnostic = diagnostic,
                        configurationMessage = modelStatus.errors.firstOrNull(),
                        sessionSettingsDraft = if (previous == null) session else previous.sessionSettingsDraft,
                        sessionReplyLengthInput = if (previous == null) session.replyLength.toString()
                            else previous.sessionReplyLengthInput,
                        sessionSettingsDirty = if (previous == null) false else previous.sessionSettingsDirty,
                        modelChoices = modelChoices,
                        formatChoices = formatChoices,
                        worldBookChoices = worldBookChoices,
                        globalPlayerName = session.playerName?.takeIf(String::isNotBlank)
                            ?: playerSetting.playerName.takeIf(String::isNotBlank),
                        assistantSegmentedBubblesEnabled = appSettings.assistantSegmentedBubblesEnabled,
                        alternativeEligibleIds = alternativeEligibleIds,
                        error = null,
                    )
                    true
                }
            }
            if (selected) break
        }
    }

    private fun hasActiveTask(sessionId: String): Boolean = taskRuntime.tasks.value.any {
        it.sessionId == sessionId && it.status == DesktopTaskStatus.RUNNING
    }

    private suspend fun eligibleAlternativeIds(sessionId: String, contextSize: Int): Set<String> {
        val candidates = chats.getContextCandidateMessagesReadOnly(sessionId, contextSize + 4)
        return ContextWindowManager().getRecentMessages(candidates, contextSize)
            .filter { it.role == MessageRole.ASSISTANT && it.alternatives.size > 1 }
            .mapTo(mutableSetOf(), ChatMessage::id)
    }

    private suspend inline fun guarded(crossinline action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.update { it.copy(error = failure.message ?: failure::class.simpleName) }
        }
    }
}
