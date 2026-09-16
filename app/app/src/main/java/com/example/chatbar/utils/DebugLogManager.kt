package com.example.chatbar.utils

import com.example.chatbar.domain.chat.PromptCacheUsage
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskFailureKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.aiTaskFailureKind
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class DebugLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val modelName: String = "",
    val apiUrl: String = "",
    val requestBodyJson: String = "",
    val systemPrompt: String = "",
    val ragChunks: List<String> = emptyList(),
    val rawSseOutput: StringBuilder = StringBuilder(),
    val rawAiOutput: StringBuilder = StringBuilder(),
    val rawReasoningOutput: StringBuilder = StringBuilder(),
    val estimatedPromptTokens: Int = 0,
    val estimatedCompletionTokens: Int = 0,
    val outputWeightedChars: Long = 0,
    val apiPromptTokens: Int? = null,
    val apiCompletionTokens: Int? = null,
    val cachedPromptTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val cacheMissTokens: Int? = null,
    val error: String? = null,
    val isCompleted: Boolean = false,
    val taskId: String? = null,
    val taskKind: String? = null,
    val taskName: String? = null,
    val taskStage: String? = null,
    val templateSymbols: List<String> = emptyList(),
    val templateFingerprint: String? = null,
    val confirmationEstimatedTokens: Int = 0,
    val finishReason: String? = null,
    val refused: Boolean = false,
    val failureKind: AiTaskFailureKind? = null,
    val completedAt: Long? = null,
    val logTruncated: Boolean = false
) {
    val rawSseOutputText: String get() = rawSseOutput.toString()
    val rawAiOutputText: String get() = rawAiOutput.toString()
    val rawReasoningOutputText: String get() = rawReasoningOutput.toString()
    val totalTokens: Int get() = estimatedPromptTokens + estimatedCompletionTokens
    val elapsedMillis: Long get() = (completedAt ?: System.currentTimeMillis()) - timestamp
    val resultLabel: String get() = when {
        !isCompleted -> "进行中"
        failureKind == AiTaskFailureKind.CANCELLED -> "已取消"
        error != null -> "失败"
        else -> "请求完成"
    }
    val cacheUsageSummary: String? get() = cachedPromptTokens?.let { cached ->
        val input = apiPromptTokens?.toString() ?: "未知"
        "缓存命中 $cached / $input" + cacheWriteTokens?.let { "，写入 $it" }.orEmpty()
    }
    val requestContainsArchive: Boolean get() = requestBodyJson.contains("【${PromptTemplates.SECTION_MEMORY_ARCHIVE}】")
    val requestContainsHead: Boolean get() = requestBodyJson.contains("【HEAD｜")
}

/** Process-local bounded diagnostics. Updates use request IDs; session IDs are a legacy compatibility path. */
object DebugLogManager {
    private const val MAX_ENTRIES = 100
    private const val MAX_TEXT_CHARS = 65_536
    private const val MAX_TOTAL_CHARS = 4 * 1024 * 1024
    private const val TRUNCATED = "\n[日志已截断；不影响实际请求与输出]"
    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()
    private val activeLogs = mutableMapOf<String, String>()
    private val requestSecrets = mutableMapOf<String, List<String>>()

    @Synchronized
    fun startRequest(
        sessionId: String,
        modelName: String,
        apiUrl: String,
        requestBodyJson: String,
        systemPrompt: String,
        ragChunks: List<String>,
        taskContext: AiTaskContext? = null,
        confirmationText: String = "",
        secrets: List<String> = emptyList()
    ): String {
        if (taskContext?.stage == AiTaskStage.REPAIR) {
            val previous = _logs.value.lastOrNull { it.taskId == taskContext.taskId && it.taskKind == taskContext.kind.name }
            if (previous?.isCompleted == true && previous.error == null) {
                logError(previous.id, "输出未通过场景校验，已进入修复阶段", AiTaskFailureKind.FORMAT)
            }
        }
        val id = taskContext?.requestId ?: UUID.randomUUID().toString()
        requestSecrets[id] = secrets.filter(String::isNotBlank)
        val sanitized = scrub(id, requestBodyJson)
        val entry = DebugLogEntry(
            id = id,
            sessionId = sessionId,
            modelName = scrub(id, modelName),
            apiUrl = scrub(id, apiUrl.substringBefore('?').replace(Regex("://[^/@]+@"), "://[redacted]@")),
            requestBodyJson = bounded(sanitized),
            systemPrompt = bounded(scrub(id, systemPrompt)),
            ragChunks = ragChunks.take(20).map { bounded(scrub(id, it)) },
            estimatedPromptTokens = estimateTokens(sanitized),
            taskId = taskContext?.taskId,
            taskKind = taskContext?.kind?.name,
            taskName = taskContext?.let { PromptTemplates.aiTaskProfile(it.kind, com.example.chatbar.domain.prompt.AiTaskStage.GENERATE).name },
            taskStage = taskContext?.stage?.name,
            templateSymbols = taskContext?.profile?.templateSymbols.orEmpty(),
            templateFingerprint = taskContext?.templateFingerprint,
            confirmationEstimatedTokens = estimateTokens(confirmationText),
            logTruncated = sanitized.length > MAX_TEXT_CHARS || systemPrompt.length > MAX_TEXT_CHARS
        )
        activeLogs[sessionId] = entry.id
        publish(_logs.value + entry)
        return entry.id
    }

    @Synchronized
    fun appendResponseChunk(sessionId: String, chunkData: String, deltaText: String? = null, reasoningText: String? = null) {
        update(sessionId) { entry ->
            val sse = scrub(entry.id, entry.rawSseOutputText + chunkData + "\n")
            val ai = scrub(entry.id, entry.rawAiOutputText + deltaText.orEmpty())
            val reasoning = scrub(entry.id, entry.rawReasoningOutputText + reasoningText.orEmpty())
            val outputWeight = entry.outputWeightedChars + tokenWeight(deltaText.orEmpty())
            entry.copy(
                rawSseOutput = StringBuilder(bounded(sse)),
                rawAiOutput = StringBuilder(bounded(ai)),
                rawReasoningOutput = StringBuilder(bounded(reasoning)),
                estimatedCompletionTokens = (outputWeight * 0.4).toInt(),
                outputWeightedChars = outputWeight,
                logTruncated = entry.logTruncated || maxOf(sse.length, ai.length, reasoning.length) > MAX_TEXT_CHARS
            )
        }
    }

    @Synchronized
    fun completeRequest(sessionId: String) {
        update(sessionId) { it.copy(isCompleted = true, completedAt = System.currentTimeMillis()) }
        removeActive(sessionId)
    }

    @Synchronized
    fun recordCompletion(sessionId: String, finishReason: String?, refused: Boolean = false) {
        update(sessionId) { it.copy(finishReason = finishReason ?: it.finishReason, refused = it.refused || refused) }
    }

    @Synchronized
    fun recordPromptCacheUsage(sessionId: String, usage: PromptCacheUsage) {
        update(sessionId) { it.copy(
            apiPromptTokens = usage.promptTokens ?: it.apiPromptTokens,
            apiCompletionTokens = usage.completionTokens ?: it.apiCompletionTokens,
            cachedPromptTokens = usage.cachedTokens ?: it.cachedPromptTokens,
            cacheWriteTokens = usage.cacheWriteTokens ?: it.cacheWriteTokens,
            cacheMissTokens = usage.cacheMissTokens ?: it.cacheMissTokens
        ) }
    }

    @Synchronized
    fun logError(sessionId: String, errorMsg: String, failureKind: AiTaskFailureKind? = null) {
        update(sessionId) { it.copy(
            error = bounded(scrub(it.id, errorMsg)),
            failureKind = failureKind,
            isCompleted = true,
            completedAt = System.currentTimeMillis()
        ) }
        removeActive(sessionId)
    }

    @Synchronized
    fun clearLogs(sessionId: String) {
        publish(_logs.value.filter { it.sessionId != sessionId })
    }

    @Synchronized
    fun clearCompletedLogs() { publish(_logs.value.filterNot { it.isCompleted }) }

    @Synchronized
    fun recordTaskFailure(taskId: String, error: Throwable) {
        val last = _logs.value.lastOrNull { it.taskId == taskId } ?: return
        if (last.error == null) {
            logError(last.id, "任务处理失败：${error.message ?: error::class.java.simpleName}", error.aiTaskFailureKind())
        }
    }

    fun recordCompleted(sessionId: String, modelName: String, apiUrl: String, requestBodyJson: String, rawAiOutput: String = "") {
        val id = startRequest(sessionId, modelName, apiUrl, requestBodyJson, "", emptyList())
        appendResponseChunk(id, "", rawAiOutput)
        completeRequest(id)
    }

    private fun update(key: String, transform: (DebugLogEntry) -> DebugLogEntry) {
        val id = if (_logs.value.any { it.id == key }) key else activeLogs[key] ?: return
        publish(_logs.value.map { if (it.id == id) transform(it) else it })
    }

    private fun removeActive(key: String) {
        val id = activeLogs[key] ?: key
        activeLogs.entries.removeAll { it.value == id }
    }

    private fun publish(entries: List<DebugLogEntry>) {
        val retainedEntries = entries.takeLast(MAX_ENTRIES).toMutableList()
        fun DebugLogEntry.charCount(): Int = requestBodyJson.length + systemPrompt.length +
            ragChunks.sumOf(String::length) + rawSseOutput.length + rawAiOutput.length + rawReasoningOutput.length
        var chars = retainedEntries.sumOf { it.charCount() }
        while (chars > MAX_TOTAL_CHARS && retainedEntries.size > 1) chars -= retainedEntries.removeAt(0).charCount()
        _logs.value = retainedEntries
        val retained = _logs.value.mapTo(mutableSetOf()) { it.id }
        activeLogs.entries.removeAll { it.value !in retained }
        requestSecrets.keys.retainAll(retained)
    }

    private fun scrub(id: String, text: String): String =
        sanitizeForDisplay(requestSecrets[id].orEmpty().fold(text) { current, secret -> current.replace(secret, "<redacted>") })

    internal fun estimateTokens(text: String): Int =
        (tokenWeight(text) * 0.4).toInt()

    private fun tokenWeight(text: String): Long = text.sumOf { if (it.code in 0x4E00..0x9FA5) 2L else 1L }

    private fun bounded(text: String): String = if (text.length <= MAX_TEXT_CHARS) text else
        text.take(MAX_TEXT_CHARS - TRUNCATED.length) + TRUNCATED

    internal fun sanitizeForDisplay(text: String): String = text
        .replace(Regex("data:image/[^;]+;base64,[A-Za-z0-9+/=]+"), "data:image/*;base64,<omitted>")
        .replace(Regex("(?i)Bearer\\s+[^\\s\\\"\\\\]+"), "Bearer <redacted>")
        .replace(Regex("(?i)(\\\"(?:api[_-]?key|authorization|access_token|secret)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"), "$1<redacted>$2")
}
