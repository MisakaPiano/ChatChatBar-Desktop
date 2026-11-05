package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.prompt.CharacterCardAiPromptAuthority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*

enum class CharacterCardAiOperation { FILL, REWRITE }
enum class CharacterCardAiStage { GENERATE, REPAIR }

class CharacterCardJsonRepairTruncatedException(cause: ModelResponseTruncatedException) :
    IllegalStateException("角色卡 JSON 格式修复达到模型输出上限；请调高模型输出上限后重试", cause)

fun interface CharacterCardAiGateway {
    fun stream(operation: CharacterCardAiOperation, stage: CharacterCardAiStage,
        messages: List<ChatApiMessage>, model: ModelConfig): Flow<StreamEvent>
}

/** Shared final generation/repair; platforms retain source preparation and resumable context ownership. */
class CharacterCardCandidateGenerator(private val gateway: CharacterCardAiGateway) {
    suspend fun fill(card: CharacterCard, payload: String, model: ModelConfig,
        directImages: List<String> = emptyList(), completedRaw: String = "",
        json: Json = CharacterAutoFillPolicy.defaultJson,
        onCheckpoint: (String) -> Unit = {}, onStatus: (String) -> Unit = {},
        onRaw: (String) -> Unit = {}, onRepair: (String) -> Unit = {},
        onReasoning: (CharacterCardAiStage, String) -> Unit = { _, _ -> }): CharacterAutoFillDraft {
        val raw = generate(CharacterCardAiOperation.FILL, payload, model, directImages, completedRaw,
            onCheckpoint, onRaw, onReasoning)
        val draft = CharacterAutoFillPolicy.parseDraft(raw, json) ?: run {
            onStatus("生成已完成，正在修复 JSON 格式")
            val repaired = request(CharacterCardAiOperation.FILL, CharacterCardAiStage.REPAIR, listOf(
                ChatApiMessage.text("system", CharacterCardAiPromptAuthority.CHARACTER_AUTO_FILL_REPAIR_PROMPT),
                ChatApiMessage.text("user", raw)), model, onRepair, onReasoning)
            CharacterAutoFillPolicy.parseDraft(repaired, json)?.also { onStatus("JSON 格式修复完成") }
                ?: error("AI 自动填充结果不是可解析 JSON")
        }
        return CharacterAutoFillPolicy.constrainToTargets(draft, card)
    }

    suspend fun rewrite(card: CharacterCard, payload: String, model: ModelConfig, completedRaw: String = "",
        json: Json = CharacterRewritePolicy.defaultJson,
        onCheckpoint: (String) -> Unit = {}, onRaw: (String) -> Unit = {}, onRepair: (String) -> Unit = {},
        onReasoning: (CharacterCardAiStage, String) -> Unit = { _, _ -> }): CharacterRewriteDraft {
        val raw = generate(CharacterCardAiOperation.REWRITE, payload, model, emptyList(), completedRaw,
            onCheckpoint, onRaw, onReasoning)
        val draft = CharacterRewritePolicy.parseDraft(raw, json) ?: run {
            val repaired = request(CharacterCardAiOperation.REWRITE, CharacterCardAiStage.REPAIR, listOf(
                ChatApiMessage.text("system", CharacterCardAiPromptAuthority.CHARACTER_REWRITE_REPAIR_PROMPT),
                ChatApiMessage.text("user", buildJsonObject {
                    put("outputSchema", CharacterCardAiPayload.rewriteSchema(card)); put("text", raw)
                }.toString())), model, onRepair, onReasoning)
            CharacterRewritePolicy.parseDraft(repaired, json) ?: error("AI 自动改写结果不是可解析 JSON")
        }
        return CharacterRewritePolicy.materializeDraft(card, draft)
    }

    private suspend fun generate(operation: CharacterCardAiOperation, payload: String, model: ModelConfig,
        images: List<String>, completedRaw: String, checkpoint: (String) -> Unit, raw: (String) -> Unit,
        reasoning: (CharacterCardAiStage, String) -> Unit): String {
        if (completedRaw.isNotBlank()) { raw(completedRaw); return completedRaw }
        val system = when (operation) {
            CharacterCardAiOperation.FILL -> CharacterCardAiPromptAuthority.CHARACTER_AUTO_FILL_SYSTEM_PROMPT
            CharacterCardAiOperation.REWRITE -> CharacterCardAiPromptAuthority.CHARACTER_REWRITE_SYSTEM_PROMPT
        }
        val value = request(operation, CharacterCardAiStage.GENERATE, listOf(ChatApiMessage.text("system", system),
            if (images.isEmpty()) ChatApiMessage.text("user", payload) else ChatApiMessage.withImages("user", payload, images)),
            model, raw, reasoning)
        checkpoint(value)
        return value
    }

    private suspend fun request(operation: CharacterCardAiOperation, stage: CharacterCardAiStage,
        messages: List<ChatApiMessage>, model: ModelConfig, publish: (String) -> Unit,
        reasoning: (CharacterCardAiStage, String) -> Unit): String {
        val raw = StringBuilder(); val thought = StringBuilder()
        var completed = false; var published = 0L
        try {
            gateway.stream(operation, stage, messages, model).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        raw.append(event.text)
                        val now = System.nanoTime()
                        if (now - published >= 80_000_000L) { publish(raw.toString()); published = now }
                    }
                    is StreamEvent.ReasoningDelta -> { thought.append(event.text); reasoning(stage, thought.toString()) }
                    is StreamEvent.Error -> throw event.asException()
                    StreamEvent.Done -> completed = true
                    is StreamEvent.Usage -> Unit
                }
            }
        } catch (error: ModelResponseTruncatedException) {
            if (stage == CharacterCardAiStage.REPAIR) throw CharacterCardJsonRepairTruncatedException(error)
            throw error
        } finally { publish(raw.toString()) }
        check(completed) { "生成连接提前结束，未收到完成信号" }
        check(raw.isNotBlank()) { "AI 返回空内容" }
        return raw.toString()
    }
}
