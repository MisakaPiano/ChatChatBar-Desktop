package com.example.chatbar.domain.chat

import com.example.chatbar.domain.prompt.AiTaskFailureKind

sealed class StreamEvent {
    /** 增量文本片段 */
    data class Delta(val text: String) : StreamEvent()

    /** 增量思维链片段 */
    data class ReasoningDelta(val text: String) : StreamEvent()

    /** 供应商在流结束前返回的真实输入与提示词缓存计量。 */
    data class Usage(val usage: PromptCacheUsage) : StreamEvent()

    /** 错误 */
    data class Error(
        val message: String,
        val failureKind: AiTaskFailureKind? = null,
        val cause: Throwable? = null
    ) : StreamEvent() {
        fun asException(): Throwable = cause ?: IllegalStateException(message)
    }

    /** 流结束 */
    data object Done : StreamEvent()
}
