package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.aiTaskRunContext
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
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
            taskContext = AiTaskContext(AiTaskKind.IMAGE_JUDGE, AiTaskStage.JUDGE),
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
        private val jsonCodeWrapper = Regex(
            """^`{1,3}(?:json)?\s*(\{[\s\S]*\})\s*(?:`{1,3})?$""",
            RegexOption.IGNORE_CASE
        )

        internal fun parseSkipReason(output: String): String? {
            // Strip only a code wrapper; missing fields, prose and malformed JSON still fail closed.
            val trimmed = output.trim()
            val payload = jsonCodeWrapper.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
            val verdict = Json.decodeFromString<AutomaticChatImageVerdict>(payload)
            return when {
                verdict.refused -> "回复拒绝继续生成故事"
                !verdict.complete -> "回复内容不完整或无法确认完整"
                !verdict.continuesStory -> "回复未延续当前故事"
                else -> null
            }
        }
    }
}
