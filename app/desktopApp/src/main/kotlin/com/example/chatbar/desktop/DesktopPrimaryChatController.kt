package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.chat.CharacterSessionService
import com.example.chatbar.domain.model.EffectiveModelResolver
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopPrimaryChoice(val id: String, val label: String)

internal data class DesktopPrimarySessionItem(
    val id: String,
    val title: String,
    val pinned: Boolean,
    val characterName: String?,
    val lastMessagePreview: String?,
) {
    val characterMissing: Boolean get() = characterName == null
}

internal data class DesktopPrimaryChatState(
    val characters: List<DesktopPrimaryChoice> = emptyList(),
    val sessions: List<DesktopPrimarySessionItem> = emptyList(),
    val selectedSession: ChatSession? = null,
    val selectedCharacterMissing: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val totalMessageCount: Int = 0,
    val composerDraft: String = "",
    val modelUsable: Boolean = false,
    val configurationMessage: String? = "Select or create a session",
    val sessionSettingsDraft: ChatSession? = null,
    val modelChoices: List<DesktopPrimaryChoice> = emptyList(),
    val formatChoices: List<DesktopPrimaryChoice> = emptyList(),
    val error: String? = null,
    val status: String? = null,
)

/** View state only: repositories own entities and DesktopTaskRuntime owns generation. */
internal class DesktopPrimaryChatController(
    private val characters: CharacterRepository,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val models: EffectiveModelResolver,
    private val formats: FormatCardRepository,
    private val sessionService: CharacterSessionService,
    val taskRuntime: DesktopTaskRuntime,
) {
    private val stateLock = Mutex()
    private val draftLock = Mutex()
    private val mutableState = MutableStateFlow(DesktopPrimaryChatState())
    val state: StateFlow<DesktopPrimaryChatState> = mutableState.asStateFlow()
    private var settingsBaseline: ChatSession? = null

    suspend fun refresh() = guarded {
        stateLock.withLock {
            val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
            val sessions = sessionItems(characterItems)
            val selectedId = state.value.selectedSession?.id
                ?.takeIf { id -> sessions.any { it.id == id } }
                ?: sessions.firstOrNull()?.id
            if (selectedId == null) {
                settingsBaseline = null
                mutableState.value = DesktopPrimaryChatState(characters = characterItems, sessions = sessions)
            } else {
                loadSelection(selectedId, characterItems, sessions, preserveWindow = true)
            }
        }
    }

    suspend fun selectSession(id: String) = guarded {
        draftLock.withLock {
            persistCurrentDraft()
            stateLock.withLock {
                val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
                val sessions = sessionItems(characterItems)
                require(sessions.any { it.id == id }) { "Session no longer exists" }
                loadSelection(id, characterItems, sessions, preserveWindow = false)
            }
        }
    }

    suspend fun createSession(characterId: String) = guarded {
        require(characters.getById(characterId) != null) { "Character no longer exists" }
        val modelStatus = models.status(settings.getAppSettings())
        if (!modelStatus.isUsable) {
            val message = modelStatus.errors.firstOrNull() ?: "Configure a usable chat model"
            mutableState.value = state.value.copy(
                configurationMessage = if (state.value.selectedSession == null) message else state.value.configurationMessage,
                error = message,
            )
            return@guarded
        }
        val id = sessionService.createSessionForCharacter(characterId)
        selectSession(id)
    }

    suspend fun loadOlder() = guarded {
        stateLock.withLock {
            val current = state.value
            val id = current.selectedSession?.id ?: return@withLock
            val oldestId = current.messages.firstOrNull()?.id ?: return@withLock
            if (!current.hasOlderMessages) return@withLock
            val page = chats.getOlderMessagePage(id, oldestId)
            mutableState.value = current.copy(
                messages = (page.messages + current.messages).distinctBy(ChatMessage::id)
                    .sortedWith(ChatMessage.TimelineComparator),
                hasOlderMessages = page.hasOlder,
                totalMessageCount = page.totalMessageCount,
                error = null,
            )
        }
    }

    suspend fun refreshAfterTerminalTask(sessionId: String) = guarded {
        stateLock.withLock {
            val current = state.value
            val characterItems = characters.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
            val sessions = sessionItems(characterItems)
            if (current.selectedSession?.id == sessionId) {
                loadSelection(sessionId, characterItems, sessions, preserveWindow = true)
            } else {
                mutableState.value = current.copy(characters = characterItems, sessions = sessions)
            }
        }
    }

    suspend fun togglePin(id: String) = guarded {
        val pinned = state.value.sessions.firstOrNull { it.id == id }?.pinned ?: return@guarded
        if (pinned) chats.unpinSession(id) else chats.pinSession(id)
        refresh()
    }

    suspend fun setDisplayTitle(id: String, title: String) = guarded {
        chats.updateSessionDisplayTitle(id, title)
        refresh()
    }

    fun editSessionSettings(change: (ChatSession) -> ChatSession) {
        val current = state.value
        mutableState.value = current.copy(
            sessionSettingsDraft = current.sessionSettingsDraft?.let(change),
            error = null,
        )
    }

    suspend fun saveSessionSettings() = guarded {
        val baseline = settingsBaseline ?: return@guarded
        val draft = state.value.sessionSettingsDraft ?: return@guarded
        val saved = chats.saveSessionSettingsDraft(baseline, draft)
        settingsBaseline = saved
        val modelStatus = models.status(saved.modelId, settings.getAppSettings())
        mutableState.value = state.value.copy(
            selectedSession = saved,
            sessionSettingsDraft = saved,
            modelUsable = modelStatus.isUsable,
            configurationMessage = modelStatus.errors.firstOrNull(),
            status = "Session settings saved",
        )
        refresh()
    }

    /** Update visible text synchronously; queued persistence always reads the latest value. */
    fun editComposer(text: String) {
        mutableState.value = state.value.copy(composerDraft = text, error = null)
    }

    suspend fun persistComposer() = guarded {
        draftLock.withLock { persistCurrentDraft() }
    }

    suspend fun send(): String? = launch(continuation = false)

    suspend fun continueReply(): String? = launch(continuation = true)

    fun stop(taskId: String): Boolean = taskRuntime.requestUserStop(taskId)

    private suspend fun launch(continuation: Boolean): String? {
        var accepted: String? = null
        guarded {
            draftLock.withLock {
                val current = state.value
                val session = current.selectedSession ?: return@withLock
                if (current.selectedCharacterMissing || characters.getById(session.characterCardId) == null) {
                    mutableState.value = current.copy(
                        selectedCharacterMissing = true,
                        error = "Archived session: character is missing",
                    )
                    return@withLock
                }
                val modelStatus = models.status(session.modelId, settings.getAppSettings())
                if (!modelStatus.isUsable) {
                    mutableState.value = current.copy(
                        modelUsable = false,
                        configurationMessage = modelStatus.errors.firstOrNull(),
                        error = modelStatus.errors.firstOrNull() ?: "No usable chat model",
                    )
                    return@withLock
                }
                val text = if (continuation) "" else current.composerDraft
                if (!continuation && text.isBlank()) return@withLock
                try {
                    accepted = taskRuntime.launchChat(session.id, text)
                } catch (rejected: DesktopTaskAdmissionException) {
                    mutableState.value = state.value.copy(error = rejected.message)
                    return@withLock
                }
                if (!continuation) {
                    chats.updateSessionDraft(session.id, "")
                    if (state.value.selectedSession?.id == session.id && state.value.composerDraft == text) {
                        mutableState.value = state.value.copy(composerDraft = "", error = null)
                    }
                }
            }
        }
        return accepted
    }

    private suspend fun persistCurrentDraft() {
        val current = state.value
        current.selectedSession?.id?.let { chats.updateSessionDraft(it, current.composerDraft) }
    }

    private suspend fun sessionItems(characterItems: List<DesktopPrimaryChoice>): List<DesktopPrimarySessionItem> {
        val names = characterItems.associate { it.id to it.label }
        return chats.getAllSessions().map { session ->
            DesktopPrimarySessionItem(
                id = session.id,
                title = session.displayTitleOverride?.takeIf(String::isNotBlank)
                    ?: session.title.takeIf(String::isNotBlank) ?: session.id,
                pinned = session.isPinned,
                characterName = names[session.characterCardId],
                lastMessagePreview = session.lastMessagePreview,
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
        val appSettings = settings.getAppSettings()
        val modelStatus = models.status(session.modelId, appSettings)
        val modelChoices = models.availableChatModels(appSettings)
            .map { DesktopPrimaryChoice(it.id, it.displayName) }
        val formatChoices = formats.getAll().map { DesktopPrimaryChoice(it.id, it.name) }
        if (previous == null) settingsBaseline = session
        mutableState.value = state.value.copy(
            characters = characterItems,
            sessions = sessions,
            selectedSession = session,
            selectedCharacterMissing = sessions.firstOrNull { it.id == id }?.characterMissing == true,
            messages = if (previous == null) page.messages else
                (previous.messages + page.messages).distinctBy(ChatMessage::id)
                    .sortedWith(ChatMessage.TimelineComparator),
            hasOlderMessages = if (previous == null) page.hasOlder else previous.hasOlderMessages,
            totalMessageCount = page.totalMessageCount,
            composerDraft = previous?.composerDraft ?: chats.getSessionDraft(id),
            modelUsable = modelStatus.isUsable,
            configurationMessage = modelStatus.errors.firstOrNull(),
            sessionSettingsDraft = if (previous == null) session else previous.sessionSettingsDraft,
            modelChoices = modelChoices,
            formatChoices = formatChoices,
            error = null,
        )
    }

    private suspend inline fun guarded(crossinline action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = state.value.copy(error = failure.message ?: failure::class.simpleName)
        }
    }
}
