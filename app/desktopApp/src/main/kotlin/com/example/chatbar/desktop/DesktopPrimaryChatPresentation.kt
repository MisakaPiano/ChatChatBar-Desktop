package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.RoleplaySpeakerCandidate
import com.example.chatbar.domain.chat.RoleplaySpeakerIdentity
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
)

internal fun desktopPresentMessage(
    message: ChatMessage,
    card: CharacterCard?,
    playerName: String?,
    segmentedAssistant: Boolean,
): DesktopPresentedMessage {
    val botName = card?.effectiveBotName ?: "Assistant"
    val roleLabel = when (message.role) {
        MessageRole.USER -> playerName?.takeIf(String::isNotBlank) ?: "You"
        MessageRole.ASSISTANT -> botName
        MessageRole.SYSTEM -> "System"
    }
    val reasoning = message.reasoningContent?.takeIf(String::isNotBlank)
        ?.let { PlaceholderRenderer.render(it, playerName, botName) }
    if (message.role != MessageRole.ASSISTANT) {
        val text = PlaceholderRenderer.render(message.displayContent, playerName, botName)
        return DesktopPresentedMessage(
            speakerLabel = roleLabel,
            segments = listOf(DesktopPresentedSegment(RoleplaySegmentKind.NARRATION, text, null, false)),
            segmented = false,
            reasoning = reasoning,
            copyText = text,
        )
    }

    val candidates = card?.characters.orEmpty().map {
        RoleplaySpeakerCandidate(it.name, it.appearanceImage)
    }
    val parsed = parseRoleplayTextSegments(message.displayContent).map { segment ->
        val speaker = if (segment.kind == RoleplaySegmentKind.DIALOGUE ||
            segment.kind == RoleplaySegmentKind.THOUGHT
        ) {
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
            statusDefaultExpanded = segment.statusDefaultExpanded,
        )
    }
    val visibleText = parsed.joinToString("") { it.text }.trim()
    return DesktopPresentedMessage(
        speakerLabel = roleLabel,
        segments = if (segmentedAssistant) parsed else listOf(
            DesktopPresentedSegment(RoleplaySegmentKind.NARRATION, visibleText, null, false),
        ),
        segmented = segmentedAssistant,
        reasoning = reasoning,
        copyText = visibleText,
    )
}

internal fun desktopPresentedAvatarReference(
    speaker: RoleplaySpeakerIdentity?,
    card: CharacterCard?,
): String? = speaker?.avatarReference
    ?: card?.avatar?.takeIf { speaker == null || speaker.displayName == card.effectiveBotName }

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
