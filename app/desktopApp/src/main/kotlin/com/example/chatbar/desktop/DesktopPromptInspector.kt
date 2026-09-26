package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.TimedEffectState
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.MainChatLogicalMessageTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DesktopPromptInspectionResult(
    val sessionId: String,
    val userMessageId: String,
    val logicalMessages: List<MainChatLogicalMessageTrace>,
    val stablePrefixCacheable: Boolean,
    val stablePrefixMessages: List<ChatApiMessage>,
    val promptCacheKey: String?,
    val worldBookEvidence: List<String>,
    val worldBookPrompt: String?,
    val worldBookOutlets: Map<String, String>,
    val persistedTimedWorldInfo: Map<String, TimedEffectState>,
    val proposedTimedWorldInfo: Map<String, TimedEffectState>,
)

/** Read-only projection over [DesktopChatRequestPlanner]. */
internal class DesktopPromptInspector(
    private val requestPlanner: DesktopChatRequestPlanner,
) {
    suspend fun inspect(
        sessionId: String,
        userMessageId: String,
        inputs: DesktopFakeChatInputs,
    ): DesktopPromptInspectionResult {
        val plan = requestPlanner.planPersistedUser(
            sessionId = sessionId,
            userMessageId = userMessageId,
            inputs = inputs,
            readOnlyRepositoryAccess = true,
        )
        return DesktopPromptInspectionResult(
            sessionId = plan.session.id,
            userMessageId = plan.currentUser.id,
            logicalMessages = plan.assembly.messageTrace,
            stablePrefixCacheable = plan.assembly.stablePrefixCacheable,
            stablePrefixMessages = plan.assembly.stablePrefixMessages,
            promptCacheKey = plan.assembly.promptCacheKey,
            worldBookEvidence = plan.worldBookDebugLog,
            worldBookPrompt = plan.worldBook.prompt,
            worldBookOutlets = plan.worldBook.outlets,
            persistedTimedWorldInfo = plan.session.timedWorldInfo,
            proposedTimedWorldInfo = plan.worldBook.timedWorldInfo,
        )
    }
}

internal data class DesktopPromptInspectorSessionItem(
    val id: String,
    val title: String,
)

internal data class DesktopPromptInspectorUserItem(
    val id: String,
    val content: String,
)

internal data class DesktopPromptInspectorInputs(
    val effectiveContextWindowSize: String = "",
    val globalPlayerName: String = "",
    val globalPlayerSetting: String = "",
    val defaultFormatCardId: String = "",
    val formatPromptPosition: FormatPromptPosition = FormatPromptPosition.END,
    val excludeAssistantStatusFromHistory: Boolean = false,
    val ragInjectionMode: String = "OFF",
    val assistantSegmentedBubblesEnabled: Boolean = false,
)

internal sealed interface DesktopPromptInspectorStatus {
    data object Idle : DesktopPromptInspectorStatus
    data object Loading : DesktopPromptInspectorStatus
    data class Ready(val result: DesktopPromptInspectionResult) : DesktopPromptInspectorStatus
    data class Error(val message: String) : DesktopPromptInspectorStatus
}

internal data class DesktopPromptInspectorState(
    val sessions: List<DesktopPromptInspectorSessionItem> = emptyList(),
    val selectedSessionId: String? = null,
    val userMessages: List<DesktopPromptInspectorUserItem> = emptyList(),
    val selectedUserMessageId: String? = null,
    val inputs: DesktopPromptInspectorInputs = DesktopPromptInspectorInputs(),
    val status: DesktopPromptInspectorStatus = DesktopPromptInspectorStatus.Idle,
)

internal class DesktopPromptInspectorController(
    private val chatRepository: ChatRepository,
    private val inspector: DesktopPromptInspector,
) {
    private val mutableState = MutableStateFlow(DesktopPromptInspectorState())
    val state: StateFlow<DesktopPromptInspectorState> = mutableState.asStateFlow()

    suspend fun refresh() {
        mutableState.value = mutableState.value.copy(status = DesktopPromptInspectorStatus.Loading)
        try {
            val sessions = chatRepository.getAllSessions().map { session ->
                DesktopPromptInspectorSessionItem(session.id, session.displayTitle())
            }
            val selectedSessionId = mutableState.value.selectedSessionId
                ?.takeIf { current -> sessions.any { it.id == current } }
                ?: sessions.firstOrNull()?.id
            val users = if (selectedSessionId == null) {
                emptyList()
            } else {
                readUsers(selectedSessionId)
            }
            val selectedUserId = mutableState.value.selectedUserMessageId
                ?.takeIf { current -> users.any { it.id == current } }
                ?: users.lastOrNull()?.id
            mutableState.value = mutableState.value.copy(
                sessions = sessions,
                selectedSessionId = selectedSessionId,
                userMessages = users,
                selectedUserMessageId = selectedUserId,
                status = DesktopPromptInspectorStatus.Idle,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(
                status = DesktopPromptInspectorStatus.Error(failure.message ?: failure::class.simpleName.orEmpty()),
            )
        }
    }

    suspend fun selectSession(sessionId: String) {
        require(mutableState.value.sessions.any { it.id == sessionId }) { "会话不存在" }
        mutableState.value = mutableState.value.copy(status = DesktopPromptInspectorStatus.Loading)
        try {
            val users = readUsers(sessionId)
            mutableState.value = mutableState.value.copy(
                selectedSessionId = sessionId,
                userMessages = users,
                selectedUserMessageId = users.lastOrNull()?.id,
                status = DesktopPromptInspectorStatus.Idle,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(
                status = DesktopPromptInspectorStatus.Error(failure.message ?: failure::class.simpleName.orEmpty()),
            )
        }
    }

    fun selectUserMessage(messageId: String) {
        require(mutableState.value.userMessages.any { it.id == messageId }) { "用户消息不存在" }
        mutableState.value = mutableState.value.copy(
            selectedUserMessageId = messageId,
            status = DesktopPromptInspectorStatus.Idle,
        )
    }

    fun updateInputs(transform: (DesktopPromptInspectorInputs) -> DesktopPromptInspectorInputs) {
        mutableState.value = mutableState.value.copy(
            inputs = transform(mutableState.value.inputs),
            status = DesktopPromptInspectorStatus.Idle,
        )
    }

    suspend fun inspect() {
        val snapshot = mutableState.value
        val sessionId = snapshot.selectedSessionId
        val messageId = snapshot.selectedUserMessageId
        val effectiveContextWindowSize = snapshot.inputs.effectiveContextWindowSize.toIntOrNull()
        if (sessionId == null || messageId == null || effectiveContextWindowSize == null) {
            mutableState.value = snapshot.copy(
                status = DesktopPromptInspectorStatus.Error(
                    when {
                        sessionId == null -> "没有可检查的会话"
                        messageId == null -> "所选会话没有已持久化的 USER 消息"
                        else -> "请输入有效的上下文窗口大小"
                    },
                ),
            )
            return
        }

        mutableState.value = snapshot.copy(status = DesktopPromptInspectorStatus.Loading)
        try {
            val inputs = snapshot.inputs
            val result = inspector.inspect(
                sessionId = sessionId,
                userMessageId = messageId,
                inputs = DesktopFakeChatInputs(
                    globalPlayerName = inputs.globalPlayerName.takeIf(String::isNotBlank),
                    globalPlayerSetting = inputs.globalPlayerSetting.takeIf(String::isNotBlank),
                    effectiveContextWindowSize = effectiveContextWindowSize,
                    defaultFormatCardId = inputs.defaultFormatCardId.takeIf(String::isNotBlank),
                    formatPromptPosition = inputs.formatPromptPosition,
                    excludeAssistantStatusFromHistory = inputs.excludeAssistantStatusFromHistory,
                    ragInjectionMode = inputs.ragInjectionMode,
                    assistantSegmentedBubblesEnabled = inputs.assistantSegmentedBubblesEnabled,
                ),
            )
            mutableState.value = mutableState.value.copy(status = DesktopPromptInspectorStatus.Ready(result))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(
                status = DesktopPromptInspectorStatus.Error(failure.message ?: failure::class.simpleName.orEmpty()),
            )
        }
    }

    private suspend fun readUsers(sessionId: String): List<DesktopPromptInspectorUserItem> =
        chatRepository.getMessagesReadOnly(sessionId)
            .filter { it.role == MessageRole.USER }
            .map { DesktopPromptInspectorUserItem(it.id, it.displayContent) }

    private fun ChatSession.displayTitle(): String = displayTitleOverride
        ?.takeIf(String::isNotBlank)
        ?: title.takeIf(String::isNotBlank)
        ?: id
}
