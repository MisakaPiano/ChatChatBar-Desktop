package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.image.NovelAiPromptPlan
import kotlinx.serialization.Serializable

@Serializable
data class MomentPostDecision(
    val shouldPost: Boolean = false,
    val reason: String = ""
)

@Serializable
data class MomentDraft(
    val shouldPost: Boolean = true,
    val senderName: String = "",
    val text: String = "",
    val imageBrief: String = "",
    val isPrivate: Boolean = false,
    val likeTier: String = "normal",
    val reason: String = ""
)

sealed class MomentGenerationResult {
    data class Posted(val post: MomentPost) : MomentGenerationResult()
    data class Skipped(val reason: String) : MomentGenerationResult()
}

data class MomentDebugExchange(
    val title: String,
    val input: String,
    val output: String
)

@Serializable
data class MomentGenerationCheckpoint(
    val decision: MomentPostDecision? = null,
    val draft: MomentDraft? = null,
    val imagePrompt: NovelAiPromptPlan? = null
)

data class MomentDebugGenerationResult(
    val post: MomentPost? = null,
    val errorMessage: String? = null,
    val exchanges: List<MomentDebugExchange> = emptyList()
)

enum class MomentGenerationProgressPhase {
    JUDGING,
    WRITING,
    DESIGNING_IMAGE,
    GENERATING_IMAGE,
    SAVING,
    DONE
}

data class MomentGenerationProgress(
    val phase: MomentGenerationProgressPhase,
    val message: String,
    val streamedText: String = "",
    val progress: Float = 0f
)
