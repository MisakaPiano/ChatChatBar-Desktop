package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.withAiTaskRun
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

class CharacterAppearanceImageService(
    modelResolver: EffectiveModelResolver,
    chatService: StreamingChatService,
    settingsProvider: suspend () -> AppSettings,
    json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }
) {
    private val core = CharacterAppearanceImageCore(modelResolver,
        CharacterAppearanceImageGateway { messages, model ->
            chatService.completeTextStreaming(
                taskContext = AiTaskContext(AiTaskKind.CHARACTER_APPEARANCE, AiTaskStage.GENERATE),
                messages = messages, modelConfig = model, disableThinking = true,
                isolatedTaskParameters = true, responseFormatJson = true)
        }, settingsProvider, json)

    suspend fun generate(imageBase64: String, characterName: String,
        onModelResolved: (CharacterAppearanceImageModelRoute) -> Unit = {}): CharacterAppearanceImageResult =
        withAiTaskRun(Dispatchers.IO) { core.generate(imageBase64, characterName, onModelResolved) }

    companion object {
        fun parseDraft(raw: String, json: Json = CharacterAppearanceImageCore.defaultJson): CharacterAppearanceImageDraft? =
            CharacterAppearanceImageCore.parseDraft(raw, json)
    }
}
