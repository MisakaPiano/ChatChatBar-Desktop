package com.example.chatbar.domain.chat

data class RoleplaySpeakerCandidate(val name: String, val avatarReference: String?)

data class RoleplaySpeakerIdentity(
    val displayName: String?,
    val avatarReference: String?,
    val avatarFallbackName: String,
)

/** Shared identity matching for Android and Desktop roleplay dialogue/thought headers. */
fun resolveRoleplaySpeakerIdentity(
    speakerName: String?,
    candidates: List<RoleplaySpeakerCandidate>,
    legacyAvatarReference: String? = null,
    legacyAvatarFallbackName: String = "",
): RoleplaySpeakerIdentity {
    if (speakerName == null) {
        return RoleplaySpeakerIdentity(
            displayName = null,
            avatarReference = legacyAvatarReference?.takeIf(String::isNotBlank),
            avatarFallbackName = legacyAvatarFallbackName,
        )
    }
    val normalized = speakerName.trim()
    if (normalized.isEmpty()) {
        return RoleplaySpeakerIdentity("未标注", null, "?")
    }
    val matches = candidates.filter { it.name.trim().equals(normalized, ignoreCase = true) }
    val matched = matches.singleOrNull()
    val displayName = matched?.name?.trim()?.takeIf(String::isNotEmpty) ?: normalized
    return RoleplaySpeakerIdentity(
        displayName = displayName,
        avatarReference = matched?.avatarReference?.takeIf(String::isNotBlank),
        avatarFallbackName = displayName,
    )
}

/** Header boundaries for a visible sequence of authoritative Roleplay segments. */
fun roleplaySpeakerHeaderIndexes(
    segments: List<RoleplayTextSegment>,
    visibleIndexes: Set<Int>? = null,
): Set<Int> {
    val headers = mutableSetOf<Int>()
    var groupId = 0
    var previousWasSpeakerSegment = false
    var previousSpeakerKey: String? = null
    var lastRenderedGroupId: Int? = null
    segments.forEachIndexed { index, segment ->
        val isSpeakerSegment = segment.kind == RoleplaySegmentKind.DIALOGUE ||
            segment.kind == RoleplaySegmentKind.THOUGHT
        val speakerKey = when (val name = segment.speakerName) {
            null -> "__legacy_unmarked__"
            else -> name.trim().takeIf(String::isNotEmpty)?.lowercase() ?: "__invalid_marker__"
        }
        val continuesGroup = isSpeakerSegment && previousWasSpeakerSegment &&
            speakerKey == previousSpeakerKey
        if (!continuesGroup) groupId++
        val currentGroupId = groupId.takeIf { isSpeakerSegment }
        if (visibleIndexes == null || index in visibleIndexes) {
            if (currentGroupId != null && currentGroupId != lastRenderedGroupId) headers += index
            lastRenderedGroupId = currentGroupId
        }
        previousWasSpeakerSegment = isSpeakerSegment
        previousSpeakerKey = speakerKey
    }
    return headers
}
