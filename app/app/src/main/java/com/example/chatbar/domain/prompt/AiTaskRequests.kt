package com.example.chatbar.domain.prompt

import com.example.chatbar.BuildConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import com.example.chatbar.utils.DebugLogManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

enum class AiTaskKind {
    CHARACTER_FILL, CHARACTER_REWRITE, CHARACTER_APPEARANCE, FORMAT_CARD,
    WORLD_BOOK_CREATE, WORLD_BOOK_FILL, IMAGE_DESCRIPTION, IMAGE_DESIGN, IMAGE_RESEARCH,
    MOMENT_JUDGE, MOMENT_GENERATION, MEMORY_EPISODE, MEMORY_COMPRESSION_PLAN,
    MEMORY_COMPRESSION, MEMORY_HEAD, RETRIEVAL_PLAN, CHARACTER_RESEARCH, CHARACTER_BRIEF,
    WORLD_BOOK_RESEARCH, WORLD_BOOK_BRIEF, VOICE_TRANSLATION, VOICE_TAGS, FORMAT_REPAIR, IMAGE_JUDGE
}

enum class AiTaskStage { GENERATE, REPAIR, PLAN, JUDGE, TRANSLATE, TAG, SUMMARIZE }

data class AiTaskPromptProfile(val name: String, val boundary: String, val templateSymbols: List<String>)

/** A logical operation owns its requests, including nested research and repair calls. */
class AiTaskRun(val taskId: String = UUID.randomUUID().toString()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiTaskRun>
}

suspend fun aiTaskRunContext(): AiTaskRun = currentCoroutineContext()[AiTaskRun] ?: AiTaskRun()

suspend fun <T> withAiTaskRun(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T
): T {
    val run = aiTaskRunContext()
    return withContext(context + run) {
        try {
            block()
        } catch (error: Throwable) {
            DebugLogManager.recordTaskFailure(run.taskId, error)
            throw error
        }
    }
}

data class AiTaskContext(
    val kind: AiTaskKind,
    val stage: AiTaskStage = AiTaskStage.GENERATE,
    val taskId: String = UUID.randomUUID().toString(),
    val requestId: String = UUID.randomUUID().toString()
) {
    val profile: AiTaskPromptProfile get() = PromptTemplates.aiTaskProfile(kind, stage)
    val preservesInputRoles: Boolean get() = kind == AiTaskKind.IMAGE_DESIGN && stage == AiTaskStage.GENERATE
    val templateFingerprint: String get() = templateFingerprint(BuildConfig.AI_PROMPT_SOURCE_SHA256, kind, stage)

    // Resolve at collection time: collecting a cold Flow twice represents two billable requests.
    suspend fun forRequest(): AiTaskContext = copy(
        taskId = currentCoroutineContext()[AiTaskRun]?.taskId ?: taskId,
        requestId = UUID.randomUUID().toString()
    )

    companion object {
        fun templateFingerprint(source: String, kind: AiTaskKind, stage: AiTaskStage): String =
            MessageDigest.getInstance("SHA-256").digest("$source:${kind.name}:${stage.name}".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
    }
}

object AiTaskMessageAssembler {
    fun assemble(messages: List<ChatApiMessage>, context: AiTaskContext): List<ChatApiMessage> =
        AuxiliaryMessageAssembler.assemble(messages, context.preservesInputRoles)
    fun addedText(messages: List<ChatApiMessage>, context: AiTaskContext? = null): String =
        AuxiliaryMessageAssembler.addedText(messages, context?.preservesInputRoles == true)
}
