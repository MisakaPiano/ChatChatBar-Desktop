package com.example.chatbar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.RoleplaySpeakerCandidate
import com.example.chatbar.domain.chat.RoleplaySpeakerIdentity
import com.example.chatbar.domain.chat.roleplaySpeakerHeaderIndexes
import com.example.chatbar.domain.chat.SessionDisplayTitlePolicy
import com.example.chatbar.domain.chat.parseRoleplayTextSegments
import com.example.chatbar.domain.chat.resolveRoleplaySpeakerIdentity
import com.example.chatbar.domain.chat.stripRoleplaySpeakerMarkers

internal data class DesktopPresentedSegment(
    val kind: RoleplaySegmentKind,
    val text: String,
    val speaker: RoleplaySpeakerIdentity?,
    val statusDefaultExpanded: Boolean,
)

internal data class DesktopPresentedMessage(
    val speakerLabel: String,
    val segments: List<DesktopPresentedSegment>,
    val segmented: Boolean,
    val reasoning: String?,
    val copyText: String,
    val defaultReasoningExpanded: Boolean = false,
    val speakerHeaderIndexes: Set<Int> = emptySet(),
)

internal data class DesktopRoleLabels(
    val assistant: String = "Assistant",
    val user: String = "You",
    val system: String = "System",
)

/** Ephemeral UI-only expansion, never attached to ChatMessage or its repository. */
internal class DesktopPresentationExpansion(initiallyExpanded: Boolean) {
    var expanded by mutableStateOf(initiallyExpanded)
        private set

    fun toggle() { expanded = !expanded }
}

internal fun desktopPresentMessage(
    message: ChatMessage,
    card: CharacterCard?,
    playerName: String?,
    segmentedAssistant: Boolean,
    roleLabels: DesktopRoleLabels = DesktopRoleLabels(),
): DesktopPresentedMessage {
    val botName = card?.effectiveBotName ?: "Assistant"
    val roleLabel = when (message.role) {
        MessageRole.USER -> playerName?.takeIf(String::isNotBlank) ?: roleLabels.user
        MessageRole.ASSISTANT -> card?.effectiveBotName ?: roleLabels.assistant
        MessageRole.SYSTEM -> roleLabels.system
    }
    val reasoning = message.reasoningContent?.takeIf(String::isNotBlank)
        ?.let { PlaceholderRenderer.render(it, playerName, botName) }
    val candidates = card?.characters.orEmpty().map {
        RoleplaySpeakerCandidate(it.name, it.appearanceImage)
    }
    val roleplaySegments = parseRoleplayTextSegments(message.displayContent)
    val parsed = roleplaySegments.map { segment ->
        val speaker = if (message.role == MessageRole.ASSISTANT && (segment.kind == RoleplaySegmentKind.DIALOGUE ||
            segment.kind == RoleplaySegmentKind.THOUGHT
        )) {
            resolveRoleplaySpeakerIdentity(
                segment.speakerName, candidates, card?.avatar, botName,
            )
        } else null
        DesktopPresentedSegment(
            kind = segment.kind,
            text = PlaceholderRenderer.render(
                stripRoleplaySpeakerMarkers(segment.displayText), playerName, botName,
            ),
            speaker = speaker,
            statusDefaultExpanded = segment.kind == RoleplaySegmentKind.STATUS || segment.statusDefaultExpanded,
        )
    }
    val visibleText = parsed.joinToString("") { it.text }.trim()
    val segmented = message.role == MessageRole.ASSISTANT && segmentedAssistant
    val presentedSegments = if (segmented) parsed else buildList {
        val body = StringBuilder()
        fun flushBody() {
            if (body.isNotEmpty()) {
                add(DesktopPresentedSegment(RoleplaySegmentKind.NARRATION, body.toString(), null, false))
                body.clear()
            }
        }
        parsed.forEach { segment ->
            if (segment.kind == RoleplaySegmentKind.STATUS) {
                flushBody()
                add(segment.copy(speaker = null))
            } else body.append(segment.text)
        }
        flushBody()
    }
    return DesktopPresentedMessage(
        speakerLabel = roleLabel,
        segments = presentedSegments,
        segmented = segmented,
        reasoning = reasoning,
        copyText = visibleText,
        speakerHeaderIndexes = if (segmented) roleplaySpeakerHeaderIndexes(roleplaySegments) else emptySet(),
    )
}

internal fun desktopPresentedAvatarReference(
    speaker: RoleplaySpeakerIdentity?,
    card: CharacterCard?,
): String? = speaker?.avatarReference
    ?: card?.avatar?.takeIf { speaker == null || speaker.displayName == card.effectiveBotName }

internal fun desktopStreamingMessage(
    sessionId: String,
    taskId: String,
    content: String,
    reasoning: String,
): ChatMessage = ChatMessage.create(
    sessionId, MessageRole.ASSISTANT, content,
    reasoningContent = reasoning.takeIf(String::isNotBlank),
).copy(id = "stream:$taskId")

internal fun desktopVisibleAssistantText(
    content: String,
    playerName: String?,
    botName: String,
): String = parseRoleplayTextSegments(content).joinToString("") { segment ->
    PlaceholderRenderer.render(stripRoleplaySpeakerMarkers(segment.displayText), playerName, botName)
}.trim()

internal fun desktopRenderSessionText(
    session: ChatSession,
    text: String,
    card: CharacterCard?,
    globalPlayerName: String?,
): String = PlaceholderRenderer.render(
    text = text,
    playerName = session.playerName?.takeIf(String::isNotBlank)
        ?: globalPlayerName?.takeIf(String::isNotBlank),
    botName = card?.effectiveBotName ?: session.title,
)

internal fun desktopRenderedSessionTitle(
    session: ChatSession,
    card: CharacterCard?,
    globalPlayerName: String?,
): String = desktopRenderSessionText(
    session, SessionDisplayTitlePolicy.resolve(session), card, globalPlayerName,
)
