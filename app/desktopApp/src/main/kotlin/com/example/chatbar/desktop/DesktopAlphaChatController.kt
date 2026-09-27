package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.model.EffectiveModelResolver
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DesktopAlphaCharacterItem(val id: String, val name: String)
data class DesktopAlphaSessionItem(val id: String, val title: String)

data class DesktopAlphaChatState(
    val characters: List<DesktopAlphaCharacterItem> = emptyList(),
    val sessions: List<DesktopAlphaSessionItem> = emptyList(),
    val selectedSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val modelUsable: Boolean = false,
    val configurationMessage: String? = null,
    val error: String? = null,
    val lastLaunchedTaskId: String? = null,
)

/** Disposable view state over container-owned tasks and authoritative persisted chat. */
internal class DesktopAlphaChatController(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val modelResolver: EffectiveModelResolver,
    private val realChat: DesktopRealChatRuntime,
    val taskRuntime: DesktopTaskRuntime,
) {
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(DesktopAlphaChatState())
    val state: StateFlow<DesktopAlphaChatState> = mutableState.asStateFlow()

    suspend fun refresh() = refreshMutex.withLock {
        try {
            val characters = characterRepository.getAll().map { DesktopAlphaCharacterItem(it.id, it.name) }
            val sessions = chatRepository.getAllSessions().map { session ->
                DesktopAlphaSessionItem(
                    id = session.id,
                    title = session.displayTitleOverride?.takeIf(String::isNotBlank)
                        ?: session.title.takeIf(String::isNotBlank)
                        ?: session.id,
                )
            }
            val selected = mutableState.value.selectedSessionId
                ?.takeIf { id -> sessions.any { it.id == id } }
                ?: sessions.firstOrNull()?.id
            val messages = selected?.let { realChat.openSession(it).messages }.orEmpty()
            val settings = settingsRepository.getAppSettings()
            val session = selected?.let { chatRepository.getSession(it) }
            val modelStatus = session?.let { modelResolver.status(it.modelId, settings) }
            mutableState.value = mutableState.value.copy(
                characters = characters,
                sessions = sessions,
                selectedSessionId = selected,
                messages = messages,
                modelUsable = modelStatus?.isUsable == true,
                configurationMessage = if (selected == null) {
                    "Select or create a session"
                } else {
                    modelStatus?.errors?.firstOrNull()
                },
                error = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(
                modelUsable = false,
                error = failure.message ?: failure::class.simpleName,
            )
        }
    }

    suspend fun selectSession(sessionId: String) {
        require(state.value.sessions.any { it.id == sessionId }) { "会话不存在" }
        mutableState.value = mutableState.value.copy(selectedSessionId = sessionId)
        refresh()
    }

    suspend fun createSession(characterId: String) {
        try {
            val sessionId = realChat.createSession(characterId)
            mutableState.value = mutableState.value.copy(selectedSessionId = sessionId)
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(error = failure.message ?: failure::class.simpleName)
        }
    }

    fun send(content: String): String? {
        val selected = state.value.selectedSessionId ?: return null
        if (!state.value.modelUsable) {
            mutableState.value = state.value.copy(error = state.value.configurationMessage ?: "No usable model")
            return null
        }
        return try {
            taskRuntime.launchChat(selected, content).also { id ->
                mutableState.value = state.value.copy(lastLaunchedTaskId = id, error = null)
            }
        } catch (failure: DesktopTaskAdmissionException) {
            mutableState.value = state.value.copy(error = failure.message)
            null
        }
    }

    fun stop(taskId: String): Boolean = taskRuntime.requestUserStop(taskId)

    fun diagnostic(taskId: String): DesktopTransportDiagnosticEntry? =
        taskRuntime.diagnostics.entries.value.firstOrNull { it.taskId == taskId }
}
