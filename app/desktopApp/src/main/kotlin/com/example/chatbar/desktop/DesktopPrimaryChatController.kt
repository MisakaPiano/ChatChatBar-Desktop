package com.example.chatbar.desktop

import kotlinx.coroutines.withContext

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChatScrollPosition
import com.example.chatbar.data.local.entity.ChatMessagePage
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
import com.example.chatbar.domain.chat.editRoleplayMessageSegment
import com.example.chatbar.domain.chat.parseRoleplayTextSegments
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
    val avatarReference: String? = null,
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
    val hasNewerMessages: Boolean = false,
    val readingPosition: ChatScrollPosition? = null,
    // A same-process return may restore an accepted snapshot whose write is still pending.
    val viewportRestorePosition: ChatScrollPosition? = null,
    val readingPositionError: Boolean = false,
    val messageWindowAnchorId: String? = null,
    val messageWindowRevision: Long = 0,
    val totalMessageCount: Int = 0,
    val composerDraft: String = "",
    val pendingImages: List<DesktopPendingImage> = emptyList(),
    val backgroundOpacity: Float = 0.35f,
    val modelUsable: Boolean = false,
    val modelDiagnostic: DesktopModelDiagnostic? = null,
    val configurationMessage: String? = "Select or create a session",
    val sessionSettingsDraft: ChatSession? = null,
    val sessionReplyLengthInput: String = "",
    val sessionSettingsDirty: Boolean = false,
    val sessionSettingsLeavePrompt: Boolean = false,
    val modelChoices: List<DesktopPrimaryChoice> = emptyList(),
    val effectiveModels: DesktopEffectiveModelPresentation = DesktopEffectiveModelPresentation(),
    val formatChoices: List<DesktopPrimaryChoice> = emptyList(),
    val worldBookChoices: List<DesktopPrimaryChoice> = emptyList(),
    val globalPlayerName: String? = null,
    val assistantSegmentedBubblesEnabled: Boolean = true,
    val chatBubbleFontScale: Float = 1.0f,
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
    readingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    readingWriter: suspend (ChatScrollPosition) -> Unit = chats::updateScrollPosition,
    private val afterWindowRead: suspend () -> Unit = {},
    val imageStore: DesktopChatImages? = null,
    val imagePicker: DesktopFilePicker = UnconfiguredDesktopFilePicker,
    val imageRegeneration: DesktopChatImageRegeneration? = null,
    val backgroundLibrary: DesktopCharacterBackgrounds? = null,
) {
    private val pendingBySession = mutableMapOf<String, List<DesktopPendingImage>>()
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
    private val readingLock = Any()
    private val readingPositions: DesktopChatReadingPositionWriter = DesktopChatReadingPositionWriter(chats::getScrollPosition, readingWriter,
        dispatcher = readingDispatcher, onResult = ::onReadingResult)

    fun updateMessageWindowAnchor(sessionId: String, anchor: String?) = synchronized(readingLock) {
        mutableState.update { current ->
            if (current.selectedSession?.id == sessionId && anchor != current.messageWindowAnchorId &&
                current.messages.any { it.id == anchor }) current.copy(messageWindowAnchorId = anchor,
                messageWindowRevision = current.messageWindowRevision + 1) else current
        }
    }

    fun submitReadingPosition(snapshot: ChatScrollPosition): ChatScrollPosition? = synchronized(readingLock) {
        updateMessageWindowAnchor(snapshot.sessionId, snapshot.anchorMessageId)
        readingPositions.submit(snapshot)?.also { accepted ->
            mutableState.update { if (it.selectedSession?.id == accepted.sessionId)
                it.copy(viewportRestorePosition = accepted) else it }
        }
    }

    private fun onReadingResult(saved: ChatScrollPosition, successful: Boolean) = synchronized(readingLock) {
        mutableState.update { current ->
            if (current.selectedSession?.id != saved.sessionId) current else current.copy(
                readingPosition = if (successful && saved.capturedAt > (current.readingPosition?.capturedAt ?: Long.MIN_VALUE))
                    saved else current.readingPosition,
                readingPositionError = if (!successful) true
                    else if (saved.capturedAt >= (readingPositions.latestPosition(saved.sessionId)?.capturedAt ?: 0)) false
                    else current.readingPositionError,
            )
        }
    }

    suspend fun persistReadingPosition(snapshot: ChatScrollPosition) {
        try {
            readingPositions.await(submitReadingPosition(snapshot))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DesktopReadingPositionPersistenceException) { /* onReadingResult owns the session-specific indicator. */ }
    }

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
        moveMessageWindow(older = true)
    }

    suspend fun loadNewer() = guarded {
        moveMessageWindow(older = false)
    }

    private suspend fun moveMessageWindow(older: Boolean) {
        stateLock.withLock {
            val current = state.value
            val id = current.selectedSession?.id ?: return@withLock
            if (if (older) !current.hasOlderMessages else !current.hasNewerMessages) return@withLock
            val boundary = (if (older) current.messages.firstOrNull() else current.messages.lastOrNull())?.id ?: return@withLock
            val adjacent = if (older) chats.getOlderMessagePage(id, boundary) else chats.getNewerMessagePage(id, boundary)
            val target = (if (older) adjacent.messages.lastOrNull() else adjacent.messages.firstOrNull())?.id ?: boundary
            val page = chats.getInitialMessagePage(id, target)
            synchronized(readingLock) {
                val visibleAnchor = state.value.messageWindowAnchorId?.takeIf { anchor -> page.messages.any { it.id == anchor } }
                applyMessageWindow(page, visibleAnchor ?: boundary.takeIf { b -> page.messages.any { it.id == b } } ?: target)
            }
        }
    }

    private fun applyMessageWindow(page: ChatMessagePage, anchor: String?, preserveViewport: Boolean = true) {
        mutableState.update { it.copy(messages = page.messages, hasOlderMessages = page.hasOlder,
            hasNewerMessages = page.hasNewer, totalMessageCount = page.totalMessageCount, error = null,
            viewportRestorePosition = anchor?.let { target -> ChatScrollPosition(it.selectedSession!!.id, target,
                page.messages.indexOfFirst { message -> message.id == target }.coerceAtLeast(0),
                if (preserveViewport && it.viewportRestorePosition?.anchorMessageId == target) it.viewportRestorePosition.scrollOffset else 0, 0) },
            messageWindowAnchorId = anchor, messageWindowRevision = it.messageWindowRevision + 1) }
    }

    suspend fun loadFirstMessageWindow(sessionId: String): String? = replaceReadingWindow(sessionId, first = true)
    suspend fun loadLatestMessageWindow(sessionId: String): String? = replaceReadingWindow(sessionId, first = false)

    private suspend fun replaceReadingWindow(sessionId: String, first: Boolean): String? {
        var target: String? = null
        guarded {
            stateLock.withLock {
                if (state.value.selectedSession?.id != sessionId) return@withLock
                val anchor = if (first) chats.getFirstMessageId(sessionId) else null
                val page = chats.getInitialMessagePage(sessionId, anchor)
                target = if (first) page.messages.firstOrNull()?.id else page.messages.lastOrNull()?.id
                synchronized(readingLock) { applyMessageWindow(page, target, preserveViewport = false) }
            }
        }
        return target
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

    suspend fun closeDraftPersistence(timeoutMillis: Long = 10_000L) {
        var readingFailure: DesktopReadingPositionPersistenceException? = null
        try { readingPositions.closeAndDrain(timeoutMillis) }
        catch (failure: DesktopReadingPositionPersistenceException) { readingFailure = failure }
        try { draftPersistence.closeAndDrain(timeoutMillis) }
        catch (failure: Throwable) { readingFailure?.let(failure::addSuppressed); throw failure }
        readingFailure?.let { throw it }
    }

    suspend fun pickImage() = guarded {
        val sessionId = state.value.selectedSession?.id ?: return@guarded
        val paths = imagePicker.pickOpenFiles(DesktopFileType("图片", listOf("png", "apng", "jpg", "jpeg", "webp", "gif")))
        if (paths.isNotEmpty()) receiveImagesForSession(DesktopImageIngress(paths), sessionId)
    }

    suspend fun receiveImages(input: DesktopImageIngress) = guarded {
        val sessionId = state.value.selectedSession?.id ?: return@guarded
        receiveImagesForSession(input, sessionId)
    }

    private suspend fun receiveImagesForSession(input: DesktopImageIngress, sessionId: String) {
        val store = requireNotNull(imageStore)
        val prepared = kotlinx.coroutines.withContext(Dispatchers.IO) {
            input.paths.map { store.prepare(it) } + listOfNotNull(input.raster?.let { store.prepareBytes(it) })
        }
        stateLock.withLock {
            val pending = pendingBySession[sessionId].orEmpty()
            pendingBySession[sessionId] = pending + prepared
            mutableState.update { if (it.selectedSession?.id == sessionId) it.copy(pendingImages = pending + prepared, error = null) else it }
        }
    }

    suspend fun reorderPendingImage(id: String, to: Int) = stateLock.withLock {
        val session = state.value.selectedSession ?: return@withLock
        val pending = pendingBySession[session.id].orEmpty().toMutableList()
        val from = pending.indexOfFirst { it.id == id }
        if (from < 0 || to !in pending.indices) return@withLock
        pending.add(to, pending.removeAt(from))
        pendingBySession[session.id] = pending
        mutableState.update { it.copy(pendingImages = pending, error = null) }
    }

    fun imageIngressFailure() { mutableState.update { it.copy(error = "图片附件无法读取；原有附件保留") } }

    suspend fun removePendingImage(id: String) = stateLock.withLock {
        val session = state.value.selectedSession ?: return@withLock
        val pending = pendingBySession[session.id].orEmpty().filterNot { it.id == id }
        pendingBySession[session.id] = pending
        mutableState.update { it.copy(pendingImages = pending, error = null) }
    }

    suspend fun chooseSessionBackground(clear: Boolean = false) = guarded {
        val session = state.value.selectedSession ?: return@guarded
        val bytes = if (clear) null else {
            val path = imagePicker.pickOpenFile(DesktopFileType("会话背景", listOf("png", "jpg", "jpeg", "webp"))) ?: return@guarded
            kotlinx.coroutines.withContext(Dispatchers.IO) { requireNotNull(imageStore).prepare(path).bytes }
        }
        val cleanupWarning = requireNotNull(imageStore).replaceBackground(session.id, bytes)
        val saved = requireNotNull(chats.getSession(session.id))
        stateLock.withLock {
            if (state.value.selectedSession?.id == session.id) {
                settingsBaseline = settingsBaseline?.copy(chatBackground = saved.chatBackground)
                mutableState.update { it.copy(selectedSession = saved, status = cleanupWarning,
                    sessionSettingsDraft = it.sessionSettingsDraft?.copy(chatBackground = saved.chatBackground)) }
            }
        }
    }

    suspend fun setBackgroundOpacity(value: Float) = guarded {
        val saved = settings.updateAppSettings { it.copy(chatBackgroundImageOpacity = value.coerceIn(0f, 1f)) }
        mutableState.update { it.copy(backgroundOpacity = saved.chatBackgroundImageOpacity) }
    }

    suspend fun characterSummary(id: String): DesktopCharacterManagementPresentation? = characters.getById(id)?.let { card ->
        withContext(Dispatchers.IO) { desktopCharacterManagementPresentation(card) { ref -> ref?.let { runCatching { characterResources.readBytes(it) }.getOrNull() } } }
    }

    suspend fun imageModelSummary(): String {
        val app = settings.getAppSettings()
        val session = state.value.selectedSession
        val target = com.example.chatbar.domain.image.NovelAiImageModelResolution.resolve(session?.novelAiImageModel,
            state.value.selectedCharacter?.defaultNovelAiImageModel, app.novelAiImageModel)
        return "${target.displayName} · 设计模型：${models.resolveImageModel(session?.imageModelId, app)?.displayName ?: "未配置"}"
    }

    suspend fun setAutomaticImages(enabled: Boolean) = guarded {
        val session = requireNotNull(state.value.selectedSession)
        chats.saveSessionSettingsDraft(session, session.copy(automaticImageGenerationEnabled = enabled))
        settingsBaseline = settingsBaseline?.copy(automaticImageGenerationEnabled = enabled)
        mutableState.update { it.copy(selectedSession = it.selectedSession?.copy(automaticImageGenerationEnabled = enabled),
            sessionSettingsDraft = it.sessionSettingsDraft?.copy(automaticImageGenerationEnabled = enabled)) }
    }

    suspend fun setImagePromptRequirement(value: String) = guarded {
        val session = requireNotNull(state.value.selectedSession)
        chats.saveSessionSettingsDraft(session, session.copy(imagePromptPreference = value))
        settingsBaseline = settingsBaseline?.copy(imagePromptPreference = value)
        mutableState.update { it.copy(selectedSession = it.selectedSession?.copy(imagePromptPreference = value),
            sessionSettingsDraft = it.sessionSettingsDraft?.copy(imagePromptPreference = value)) }
    }

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

    suspend fun pickMessageEditImage(): DesktopPendingImage? {
        var prepared: DesktopPendingImage? = null
        guarded {
            val path = imagePicker.pickOpenFile(DesktopFileType("图片", listOf("png", "apng", "jpg", "jpeg", "webp", "gif"))) ?: return@guarded
            prepared = withContext(Dispatchers.IO) { requireNotNull(imageStore).prepare(path) }
        }
        return prepared
    }

    suspend fun editMessage(messageId: String, content: String, retainedImages: List<String>? = null,
        additions: List<DesktopPendingImage> = emptyList(), expected: ChatMessage? = null): Boolean {
        var saved = false
        guarded {
            val session = state.value.selectedSession ?: return@guarded
            check(!hasActiveTask(session.id)) { "Message editing is unavailable during generation" }
            val message = chats.getMessage(messageId, session.id) ?: error("Message no longer exists")
            check(expected == null || message == expected) { "Message changed; reopen editing" }
            val retained = retainedImages ?: message.images
            require(content.isNotBlank() || retained.isNotEmpty() || additions.isNotEmpty()) { "Message cannot be empty" }
            val warning = if (retainedImages != null || additions.isNotEmpty()) requireNotNull(imageStore).editMessage(message, content, retained, additions)
            else { chats.updateMessage(
                MessageAlternativeVersionPolicy.collapseToEditedContent(message, content)
                    .copy(formatRepairNotice = null),
            ); null }
            refreshAfterTerminalTask(session.id)
            if (warning != null) mutableState.update { it.copy(status = warning) }
            saved = true
        }
        return saved
    }

    suspend fun deleteMessageImage(original: ChatMessage, reference: String): Boolean {
        var deleted = false
        guarded {
            check(!hasActiveTask(original.sessionId)) { "Image deletion is unavailable during generation" }
            val warning = requireNotNull(imageStore).deleteImage(original, reference)
            refreshAfterTerminalTask(original.sessionId)
            if (warning != null) mutableState.update { it.copy(status = warning) }
            deleted = true
        }
        return deleted
    }

    suspend fun editMessageSegment(messageId: String, start: Int, endExclusive: Int,
        replacement: String, expectedContent: String? = null): Boolean {
        var saved = false
        guarded {
            val session = state.value.selectedSession ?: return@guarded
            check(!hasActiveTask(session.id)) { "Message editing is unavailable during generation" }
            val message = chats.getMessage(messageId, session.id) ?: error("Message no longer exists")
            require(message.role == MessageRole.ASSISTANT) { "Only Assistant segments can be edited" }
            check(expectedContent == null || message.displayContent == expectedContent) { "Message changed; reopen segment actions" }
            require(parseRoleplayTextSegments(message.displayContent).any {
                it.start == start && it.endExclusive == endExclusive
            }) { "Message segment changed; reopen segment actions" }
            val outcome = editRoleplayMessageSegment(message, start, endExclusive, replacement)
            val updated = outcome.message
            if (updated == null) saved = deleteMessage(messageId)
            else {
                chats.updateMessage(updated.copy(formatRepairNotice = null))
                refreshAfterTerminalTask(session.id)
                saved = true
            }
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
            val cleanupWarning = imageStore?.cleanupRemoved(message.images)
            refreshAfterTerminalTask(session.id)
            if (cleanupWarning != null) mutableState.update { it.copy(status = cleanupWarning) }
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
                val attachments = if (continuation) emptyList() else current.pendingImages
                if (!continuation && text.isBlank() && attachments.isEmpty()) return@withLock
                try {
                    accepted = taskRuntime.launchChat(session.id, text, attachments)
                    if (!continuation) {
                        pendingBySession.remove(session.id)
                        mutableState.update { it.copy(pendingImages = emptyList()) }
                    }
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
                avatarReference = cards[session.characterCardId]?.avatar,
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
        selection@ while (true) {
            val session = chats.getSession(id) ?: error("Session no longer exists")
            val initial = synchronized(readingLock) { state.value }
            val sameSession = initial.selectedSession?.id == id
            val previous = initial.takeIf { sameSession && preserveWindow }
            val position = if (!sameSession) readingPositions.load(id) else initial.readingPosition
            val restorePosition = if (sameSession) initial.viewportRestorePosition else
                listOfNotNull(position, readingPositions.latestPosition(id)).maxByOrNull { it.capturedAt }
            val proposed = if (sameSession) initial.messageWindowAnchorId else restorePosition?.anchorMessageId
            val anchor = validWindowAnchor(id, proposed, initial.takeIf { sameSession })
            val page = chats.getInitialMessagePage(id, anchor)
            afterWindowRead()
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
            val effectiveModels = desktopEffectiveModels(models, appSettings)
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
                var obsoleteWindow = false
                val selected = synchronized(composerLock) { synchronized(readingLock) {
                    val current = state.value
                    val latest = current.selectedSession?.id?.takeIf { it != id }?.let(draftPersistence::latestRevision)
                    obsoleteWindow = if (sameSession) current.messageWindowRevision != initial.messageWindowRevision
                        else (readingPositions.latestPosition(id)?.capturedAt ?: Long.MIN_VALUE) > (restorePosition?.capturedAt ?: Long.MIN_VALUE)
                    if (latest != outgoing || obsoleteWindow) false else {
                        if (previous == null) settingsBaseline = session
                        mutableState.value = current.copy(
                            characters = characterItems,
                            sessions = sessions,
                            selectedSession = session,
                            selectedCharacter = selectedCharacter,
                            selectedCharacterMissing = selectedCharacter == null,
                            messages = page.messages,
                            hasOlderMessages = page.hasOlder,
                            hasNewerMessages = page.hasNewer,
                            readingPosition = if (sameSession) current.readingPosition else position,
                            viewportRestorePosition = if (sameSession) current.viewportRestorePosition else restorePosition,
                            readingPositionError = if (sameSession) current.readingPositionError else readingPositions.hasError(id),
                            messageWindowAnchorId = anchor?.takeIf { a -> page.messages.any { it.id == a } }
                                ?: page.messages.lastOrNull()?.id,
                            messageWindowRevision = current.messageWindowRevision + 1,
                            totalMessageCount = page.totalMessageCount,
                            pendingImages = pendingBySession[id].orEmpty(),
                            backgroundOpacity = appSettings.chatBackgroundImageOpacity,
                            composerDraft = if (current.selectedSession?.id == id) current.composerDraft else persistedDraft.orEmpty(),
                            modelUsable = modelStatus.isUsable,
                            modelDiagnostic = diagnostic,
                            configurationMessage = modelStatus.errors.firstOrNull(),
                            sessionSettingsDraft = if (previous == null) session else previous.sessionSettingsDraft,
                            sessionReplyLengthInput = if (previous == null) session.replyLength.toString()
                                else previous.sessionReplyLengthInput,
                            sessionSettingsDirty = if (previous == null) false else previous.sessionSettingsDirty,
                            modelChoices = modelChoices,
                            effectiveModels = effectiveModels,
                            formatChoices = formatChoices,
                            worldBookChoices = worldBookChoices,
                            globalPlayerName = session.playerName?.takeIf(String::isNotBlank)
                                ?: playerSetting.playerName.takeIf(String::isNotBlank),
                            assistantSegmentedBubblesEnabled = appSettings.assistantSegmentedBubblesEnabled,
                            chatBubbleFontScale = desktopSafeBubbleFontScale(appSettings.chatBubbleFontScale),
                            alternativeEligibleIds = alternativeEligibleIds,
                            error = null,
                        )
                        true
                    }
                } }
                if (obsoleteWindow) continue@selection
                if (selected) return
            }
        }
    }

    /** A missing live anchor uses its current-window neighbour, not the repository's latest-page fallback. */
    private suspend fun validWindowAnchor(id: String, anchor: String?, current: DesktopPrimaryChatState?): String? {
        if (anchor == null || chats.getMessage(anchor, id) != null) return anchor
        val messages = current?.messages.orEmpty()
        val at = messages.indexOfFirst { it.id == anchor }.takeIf { it >= 0 }
            ?: current?.readingPosition?.fallbackMessageIndex?.coerceIn(0, messages.lastIndex.coerceAtLeast(0)) ?: 0
        val candidates = messages.drop(at) + messages.take(at).asReversed()
        return candidates.firstOrNull { chats.getMessage(it.id, id) != null }?.id
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
