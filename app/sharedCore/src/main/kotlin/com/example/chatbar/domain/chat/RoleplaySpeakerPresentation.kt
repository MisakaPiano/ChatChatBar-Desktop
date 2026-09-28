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
