package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.Dispatchers

class FormatCardAutoFillService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val settingsProvider: suspend () -> AppSettings
) {
    suspend fun generateStreaming(
        character: CharacterCard,
        request: String,
        requestedName: String,
        modelId: String?,
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {},
        onRepairText: (String) -> Unit = {},
        onValidationIssue: (String) -> Unit = {}
    ): FormatCardAutoFillDraft = withAiTaskRun(Dispatchers.IO) {
        val settings = settingsProvider()
        val model = if (modelId == null) {
            modelResolver.defaultChatModel(settings) ?: error("未配置可用的默认对话模型")
        } else {
            modelResolver.availableChatModels(settings).firstOrNull { it.id == modelId }
                ?: error("所选模型不可用，请重新选择")
        }
        require(model.hasConfiguredAuthentication(settings)) { "所选模型的 API 地址或认证配置不可用" }

        FormatCardAutoFillGenerator { stage, messages, resolved ->
            chatService.streamText(
                taskContext = AiTaskContext(AiTaskKind.FORMAT_CARD, AiTaskStage.valueOf(stage.name)),
                messages = messages, modelConfig = resolved, readTimeoutSeconds = 600L
            )
        }.generateStreaming(character, request, requestedName, model,
            onStatus, onRawText, onRepairText, onValidationIssue)
    }
}
