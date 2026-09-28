package com.example.chatbar.domain.prompt

import com.example.chatbar.domain.chat.ModelRequestException
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

enum class AiTaskFailureKind { REFUSAL, CONTENT_FILTER, FORMAT, TRUNCATED, NETWORK, AUTHENTICATION, REQUEST, CANCELLED, EMPTY }

class AiTaskEmptyResponseException(reasoningOnly: Boolean) :
    RuntimeException(if (reasoningOnly) "AI 仅返回思考内容，没有任务结果" else "AI 返回空内容")

class AiTaskRefusalException(val kind: AiTaskFailureKind, val finishReason: String? = null) :
    RuntimeException(if (kind == AiTaskFailureKind.CONTENT_FILTER) "模型服务过滤了本次输出，已停止后续修复和重试" else "模型拒绝了本次任务，已停止后续修复和重试")

/** Terminal evidence must survive caller wrappers and cannot enter a success-looking fallback. */
fun Throwable.rethrowIfAiTaskTerminalFailure() {
    var error: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (error != null && visited.add(error)) {
        if (error is CancellationException || error is AiTaskRefusalException) throw error
        error = error.cause
    }
}

fun Throwable.aiTaskFailureKind(): AiTaskFailureKind = when (this) {
    is AiTaskRefusalException -> kind
    is CancellationException -> AiTaskFailureKind.CANCELLED
    is ModelResponseTruncatedException -> AiTaskFailureKind.TRUNCATED
    is AiTaskEmptyResponseException -> AiTaskFailureKind.EMPTY
    is ModelRequestException -> when {
        isAuthenticationFailure -> AiTaskFailureKind.AUTHENTICATION
        isRetryable -> AiTaskFailureKind.NETWORK
        else -> AiTaskFailureKind.REQUEST
    }
    is java.io.IOException -> AiTaskFailureKind.NETWORK
    else -> AiTaskFailureKind.FORMAT
}

object AiTaskRefusalPolicy {
    fun failure(content: String, finishReason: String?, refused: Boolean): AiTaskRefusalException? = when {
        finishReason == "content_filter" -> AiTaskRefusalException(AiTaskFailureKind.CONTENT_FILTER, finishReason)
        refused -> AiTaskRefusalException(AiTaskFailureKind.REFUSAL, finishReason)
        isStandaloneRefusal(content) -> AiTaskRefusalException(AiTaskFailureKind.REFUSAL, finishReason)
        else -> null
    }

    /** Deliberately conservative: JSON, quotes, dialogue and mixed payloads are not keyword-scanned. */
    fun isStandaloneRefusal(content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty() || text.length > 500 || text.contains('\n') || text.contains('"') ||
            text.contains('“') || text.contains('「') || text.contains('：') || text.contains('`') ||
            text.contains('{') || text.contains('[')
        ) return false
        if (runCatching { Json.parseToJsonElement(text) }.getOrNull() is JsonObject) return false
        return Regex("^(?:抱歉[，,。 ]*|对不起[，,。 ]*)?我(?:无法|不能)(?:帮助|协助|提供|生成|完成|处理|满足|继续).{0,180}(?:请求|内容|任务|要求|生成|创作)[。！.! ]*$")
            .matches(text) || Regex("^(?:I(?:'m| am) sorry[, .]*|Sorry[, .]*)?I (?:cannot|can't|am unable to) (?:help|assist|provide|generate|fulfill|comply with).{0,180}(?:request|content|task)[.! ]*$", RegexOption.IGNORE_CASE)
            .matches(text)
    }

    fun responseRefused(body: String): Boolean = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject
        val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val hasRefusal = !(message?.get("refusal") as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        hasRefusal ||
            (message?.get("content") as? JsonArray)?.any {
                ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "refusal"
            } == true
    }.getOrDefault(false)
}
