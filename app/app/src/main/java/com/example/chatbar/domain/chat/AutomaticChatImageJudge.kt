package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class AutomaticChatImageVerdict(
    val complete: Boolean,
    val continuesStory: Boolean,
    val refused: Boolean
)

class AutomaticChatImageJudge(private val streamingChatService: StreamingChatService) {
    suspend fun skipReason(model: ModelConfig, input: String): String? {
        val output = streamingChatService.completeTextStreaming(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.AUTOMATIC_CHAT_IMAGE_JUDGE_SYSTEM),
                ChatApiMessage.text("user", input)
            ),
            modelConfig = model,
            maxTokens = 256,
            disableThinking = true,
            isolatedTaskParameters = true
        )
        return parseSkipReason(output)
    }

    companion object {
        internal fun parseSkipReason(output: String): String? {
            // Missing fields, prose, malformed JSON, and refusal from the judge all fail closed.
            val verdict = Json.decodeFromString<AutomaticChatImageVerdict>(output.trim())
            return when {
                verdict.refused -> "回复拒绝继续生成故事"
                !verdict.complete -> "回复内容不完整或无法确认完整"
                !verdict.continuesStory -> "回复未延续当前故事"
                else -> null
            }
        }
    }
}
