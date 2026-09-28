package com.example.chatbar.domain.chat

class ModelRequestException(
    message: String,
    val httpStatus: Int? = null,
    val traceId: String? = null,
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    val isAuthenticationFailure: Boolean get() = httpStatus == 401 || httpStatus == 403
    val isRetryable: Boolean get() = httpStatus == null || httpStatus in setOf(408, 425, 429) ||
        (httpStatus != null && httpStatus in 500..599)
}

const val MODEL_OUTPUT_TRUNCATED_MESSAGE =
    "模型服务返回输出截断（finish_reason=length），本次内容未完整生成"

class ModelResponseTruncatedException : RuntimeException(MODEL_OUTPUT_TRUNCATED_MESSAGE)
