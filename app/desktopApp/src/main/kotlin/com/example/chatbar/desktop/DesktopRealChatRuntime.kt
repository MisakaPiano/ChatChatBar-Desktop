package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.chat.CharacterSessionService
import com.example.chatbar.domain.chat.ChatHistoryPromptPolicy
import com.example.chatbar.domain.chat.InterruptedReplyPolicy
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.ui.chat.resolveRegenerationTarget
import com.example.chatbar.domain.chat.OpenAiStreamingTransport
import com.example.chatbar.domain.chat.ProviderCompletionMetadata
import com.example.chatbar.domain.chat.ProviderStreamEvent
import com.example.chatbar.domain.chat.ProviderTransportDiagnostics
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

data class DesktopRealChatSession(
    val session: ChatSession,
    val messages: List<ChatMessage>,
)

data class DesktopRealChatStreamUpdate(
    val content: String,
    val reasoningContent: String,
    val targetMessageId: String? = null,
    val status: String? = null,
)

fun interface DesktopRealChatObserver {
    fun onUpdate(update: DesktopRealChatStreamUpdate)
}

data class DesktopRealChatResult(
    val currentUser: ChatMessage,
    val currentUserPersisted: Boolean,
    val assistant: ChatMessage,
    val completion: ProviderCompletionMetadata?,
    val failureMessage: String? = null,
    val inputNotice: String? = null,
)

class DesktopUserStoppedChatException(
    message: String = "用户停止回复生成",
) : CancellationException(message)

class DesktopChatConfigurationException(message: String) : IllegalStateException(message)

/** Single-operation stop handle; task ownership remains deferred to P5-S6. */
class DesktopChatGenerationControl {
    private val activeOperation = AtomicReference<Job?>(null)
    private val stopRequested = AtomicBoolean(false)

    fun requestUserStop(): Boolean {
        stopRequested.set(true)
        activeOperation.get()?.cancel(DesktopUserStoppedChatException())
        return true
    }

    internal fun attach(operation: Job) {
        check(activeOperation.compareAndSet(null, operation)) { "生成控制器已绑定其他请求" }
        if (stopRequested.get()) operation.cancel(DesktopUserStoppedChatException())
    }

    internal fun detach(operation: Job) {
        activeOperation.compareAndSet(operation, null)
    }
}

/** Coroutine-native Desktop real-chat operation layer. Task lifetime remains a future owner. */
class DesktopRealChatRuntime internal constructor(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val characterSessionService: CharacterSessionService,
    private val modelResolver: EffectiveModelResolver,
    private val requestPlanner: DesktopChatRequestPlanner,
    private val images: DesktopChatImages? = null,
    private val transportFactory: (Boolean) -> OpenAiStreamingTransport = { allowCleartext ->
        OpenAiStreamingTransport(allowCleartextHttp = { allowCleartext })
    },
) {
    suspend fun createSession(characterId: String): String =
        characterSessionService.createSessionForCharacter(characterId)

    suspend fun openSession(sessionId: String): DesktopRealChatSession {
        val session = requireNotNull(chatRepository.getSession(sessionId)) { "对话不存在" }
        return DesktopRealChatSession(session, chatRepository.getMessages(sessionId))
    }

    suspend fun sendText(
        sessionId: String,
        content: String,
        observer: DesktopRealChatObserver = DesktopRealChatObserver {},
        control: DesktopChatGenerationControl? = null,
        diagnosticsFactory: (ModelConfig) -> ProviderTransportDiagnostics = { ProviderTransportDiagnostics.NONE },
        attachments: List<DesktopPendingImage> = emptyList(),
    ): DesktopRealChatResult = coroutineScope {
        val operation = async(start = CoroutineStart.LAZY) {
            sendTextOperation(sessionId, content, observer, diagnosticsFactory, attachments)
        }
        control?.attach(operation)
        try {
            operation.start()
            operation.await()
        } finally {
            control?.detach(operation)
        }
    }

    private suspend fun sendTextOperation(
        sessionId: String,
        content: String,
        observer: DesktopRealChatObserver,
        diagnosticsFactory: (ModelConfig) -> ProviderTransportDiagnostics,
        attachments: List<DesktopPendingImage>,
    ): DesktopRealChatResult {
        val turn = resolveTurn(sessionId)
        var preparedContent = content
        var inputNotice: String? = null
        if (attachments.isNotEmpty() && !turn.model.isMultimodal) {
            val understanding = com.example.chatbar.domain.chat.ImageUnderstandingService(modelResolver) { encoded, model, onDelta ->
                DesktopAuxiliaryImageUnderstanding(transportFactory(turn.settings.allowCleartextModelApi))
                    .describe(encoded, model, onDelta)
            }.prepare(
                listOf(requireNotNull(images).jpegBase64(attachments.first().bytes)), turn.model,
                requireUnderstanding = false,
                onStatus = { observer.onUpdate(DesktopRealChatStreamUpdate("", "", status = it)) },
            )
            if (understanding.descriptions.isNotEmpty()) {
                preparedContent = com.example.chatbar.domain.prompt.AuxiliaryPromptAuthority
                    .appendUserImageDescriptions(content, understanding.descriptions)
            } else {
                inputNotice = "${understanding.unavailableReason}，将作为无图消息发送。"
                observer.onUpdate(DesktopRealChatStreamUpdate("", "", status = inputNotice))
            }
        }
        val plan = if (content.isBlank() && attachments.isEmpty()) {
            requestPlanner.planContinuation(
                sessionId = sessionId,
                inputs = turn.inputs,
                readOnlyRepositoryAccess = false,
            )
        } else {
            val persistedUser = if (attachments.isNotEmpty()) requireNotNull(images).persistUser(sessionId, preparedContent, attachments)
            else chatRepository.addMessage(
                ChatMessage.create(
                    sessionId = sessionId,
                    role = MessageRole.USER,
                    content = content,
                ),
            )
            requestPlanner.planPersistedUser(
                sessionId = sessionId,
                currentUser = persistedUser,
                inputs = turn.inputs,
                readOnlyRepositoryAccess = false,
            )
        }

        return generate(turn, plan, observer, diagnosticsFactory).copy(inputNotice = inputNotice)
    }

    suspend fun regenerate(
        sessionId: String,
        messageId: String,
        observer: DesktopRealChatObserver = DesktopRealChatObserver {},
        control: DesktopChatGenerationControl? = null,
        diagnosticsFactory: (ModelConfig) -> ProviderTransportDiagnostics = { ProviderTransportDiagnostics.NONE },
    ): DesktopRealChatResult = coroutineScope {
        val operation = async(start = CoroutineStart.LAZY) {
            val selected = requireNotNull(chatRepository.getMessage(messageId, sessionId)) { "回复不存在" }
            // Reject before any indexed read can repair/persist an old timeline index.
            val nearby = chatRepository.getMessagesReadOnly(sessionId)
            val target = requireNotNull(resolveRegenerationTarget(selected, nearby)) { "该消息不能重新生成" }
            val app = settingsRepository.readExistingAppSettings() ?: AppSettings()
            val size = app.defaultContextWindowSize.coerceAtLeast(0)
            val candidates = chatRepository.getContextCandidateMessagesReadOnly(sessionId, size + 4)
            check(ContextWindowManager().getRecentMessages(candidates, size).any { it.id == target.id }) {
                "Reply is outside the active context; regeneration is unavailable"
            }
            val turn = resolveTurn(sessionId)
            if (selected.id != target.id) chatRepository.deleteMessage(selected.id, sessionId)
            val plan = requestPlanner.planRegeneration(sessionId, target.id, turn.inputs)
            observer.onUpdate(DesktopRealChatStreamUpdate("", "", target.id))
            generate(turn, plan, observer, diagnosticsFactory, target)
        }
        control?.attach(operation)
        try {
            operation.start()
            operation.await()
        } finally {
            control?.detach(operation)
        }
    }

    private suspend fun generate(
        turn: ResolvedDesktopTurn,
        plan: DesktopChatRequestPlan,
        observer: DesktopRealChatObserver,
        diagnosticsFactory: (ModelConfig) -> ProviderTransportDiagnostics,
        regenerationTarget: ChatMessage? = null,
    ): DesktopRealChatResult {
        val sessionId = plan.session.id

        if (plan.worldBook.timedWorldInfo != plan.session.timedWorldInfo) {
            chatRepository.updateSession(
                plan.session.copy(timedWorldInfo = plan.worldBook.timedWorldInfo),
            )
        }

        val assistantBase = regenerationTarget?.copy(content = "", reasoningContent = null) ?: ChatMessage.create(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = "",
        )
        var accumulatedContent = ""
        var accumulatedReasoning = ""
        var persistedAssistant: ChatMessage? = null
        var completion: ProviderCompletionMetadata? = null

        suspend fun persist(message: ChatMessage): ChatMessage {
            if (regenerationTarget == null) return chatRepository.addMessage(message)
            val old = requireNotNull(chatRepository.getMessage(regenerationTarget.id, sessionId)) { "回复不存在" }
            val updated = MessageAlternativeVersionPolicy.append(
                old, message.content, MessageAlternativeVersionPolicy.newVersionId(old),
            ).copy(reasoningContent = message.reasoningContent, formatRepairNotice = null)
            chatRepository.updateMessage(updated)
            return updated
        }

        try {
            transportFactory(turn.settings.allowCleartextModelApi).streamMainChat(
                messages = plan.assembly.messages,
                modelConfig = turn.model,
                promptCacheKey = plan.assembly.promptCacheKey,
                diagnostics = diagnosticsFactory(turn.model),
            ).collect { event ->
                when (event) {
                    is ProviderStreamEvent.ContentDelta -> {
                        accumulatedContent += event.text
                        observer.onUpdate(DesktopRealChatStreamUpdate(accumulatedContent, accumulatedReasoning, regenerationTarget?.id))
                    }
                    is ProviderStreamEvent.ReasoningDelta -> {
                        accumulatedReasoning += event.text
                        observer.onUpdate(DesktopRealChatStreamUpdate(accumulatedContent, accumulatedReasoning, regenerationTarget?.id))
                    }
                    is ProviderStreamEvent.Usage -> Unit
                    is ProviderStreamEvent.Error -> throw DesktopProviderChatException(
                        event.message,
                        event.cause,
                    )
                    is ProviderStreamEvent.Completed -> {
                        check(completion == null) { "模型流返回了重复完成事件" }
                        ChatHistoryPromptPolicy.requirePersistableAssistantBody(accumulatedContent)
                        completion = event.metadata
                        val generated =
                            assistantBase.copy(
                                content = accumulatedContent,
                                reasoningContent = accumulatedReasoning.takeIf(String::isNotBlank),
                                updatedAt = System.currentTimeMillis(),
                            )
                        if (regenerationTarget == null) persistedAssistant = persist(generated)
                        else withContext(NonCancellable) { persistedAssistant = persist(generated) }
                    }
                }
            }
            val assistant = checkNotNull(persistedAssistant) { "模型流未返回完成事件" }
            return DesktopRealChatResult(
                currentUser = plan.currentUser,
                currentUserPersisted = plan.currentUserPersisted,
                assistant = assistant,
                completion = completion,
            )
        } catch (stopped: DesktopUserStoppedChatException) {
            val draft = InterruptedReplyPolicy.persistableDraft(
                assistantBase.copy(
                    content = accumulatedContent,
                    reasoningContent = accumulatedReasoning.takeIf(String::isNotBlank),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            if (persistedAssistant == null && draft != null) {
                withContext(NonCancellable) {
                    persistedAssistant = persist(draft)
                }
            }
            throw stopped
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val alreadyPersisted = persistedAssistant
                ?: if (regenerationTarget == null) chatRepository.getMessage(assistantBase.id, sessionId) else null
            if (alreadyPersisted != null) throw failure
            val errorText = failure.message ?: failure::class.simpleName ?: "模型请求失败"
            val errorAssistant = chatRepository.addMessage(
                ChatMessage.create(
                    sessionId = sessionId,
                    role = if (regenerationTarget == null) MessageRole.ASSISTANT else MessageRole.SYSTEM,
                    content = "错误: $errorText",
                ),
            )
            return DesktopRealChatResult(
                currentUser = plan.currentUser,
                currentUserPersisted = plan.currentUserPersisted,
                assistant = regenerationTarget ?: errorAssistant,
                completion = completion,
                failureMessage = errorText,
            )
        }
    }

    private suspend fun resolveTurn(sessionId: String): ResolvedDesktopTurn {
        val session = requireNotNull(chatRepository.getSession(sessionId)) { "对话不存在" }
        requireNotNull(characterRepository.getById(session.characterCardId)) { "角色卡不存在" }
        val settings = settingsRepository.getAppSettings()
        val player = settingsRepository.getPlayerSetting()
        requestPlanner.firstUserToolValidationError(session, settings.defaultFormatCardId)?.let { error ->
            throw DesktopChatConfigurationException("用户工具配置无效：$error")
        }
        val status = modelResolver.status(session.modelId, settings)
        val model = modelResolver.resolveChatModel(session.modelId, settings)
        if (!status.isUsable || model == null || !model.hasConfiguredAuthentication(settings)) {
            throw DesktopChatConfigurationException(
                status.errors.firstOrNull() ?: "未找到可用模型配置或 API Key 未配置",
            )
        }
        return ResolvedDesktopTurn(
            settings = settings,
            model = model,
            inputs = DesktopFakeChatInputs(
                globalPlayerName = player.playerName.takeIf(String::isNotBlank),
                globalPlayerSetting = player.globalPersona.takeIf(String::isNotBlank),
                effectiveContextWindowSize = settings.defaultContextWindowSize,
                defaultFormatCardId = settings.defaultFormatCardId,
                formatPromptPosition = model.formatPromptPosition,
                excludeAssistantStatusFromHistory = settings.excludeAssistantStatusFromHistory,
                ragInjectionMode = settings.ragInjectionMode,
                assistantSegmentedBubblesEnabled = settings.assistantSegmentedBubblesEnabled,
                supportsImages = model.isMultimodal,
            ),
        )
    }
}

private data class ResolvedDesktopTurn(
    val settings: AppSettings,
    val model: ModelConfig,
    val inputs: DesktopFakeChatInputs,
)

private class DesktopProviderChatException(
    message: String,
    cause: Throwable?,
) : IllegalStateException(message, cause)
