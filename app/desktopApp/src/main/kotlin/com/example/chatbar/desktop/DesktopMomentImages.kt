package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.domain.moment.MomentGenerationProgress
import com.example.chatbar.domain.moment.MomentDebugGenerationResult

internal data class DesktopMomentImageResult(val path: String, val metadata: GeneratedImageMetadata,
    val imagePrompt: String, val imageBrief: String)

internal fun interface DesktopMomentImageGenerator {
    suspend fun generate(post: MomentPost, card: CharacterCard?, session: ChatSession?, settings: AppSettings,
        draft: NovelAiImageRegenerationDraft?, registerImageCleanup: (String) -> Unit,
        progress: (MomentGenerationProgress) -> Unit): DesktopMomentImageResult
}

internal fun interface DesktopMomentDebugGenerator {
    suspend fun generate(card: CharacterCard, session: ChatSession, messages: List<ChatMessage>, latest: MomentPost?,
        settings: AppSettings, task: MomentTask, registerImageCleanup: (String) -> Unit): MomentDebugGenerationResult
}
