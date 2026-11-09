package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*

/** Explicit card/person identity; never infer a player role from a matching name or avatar. */
object PlayerRolePolicy {
    fun person(session: ChatSession?, card: CharacterCard?): CharacterInfo? {
        val role = session?.desktopPlayerRole ?: return null
        if (card == null || card.id != session.characterCardId || card.id != role.cardId) return null
        return card.characters.singleOrNull { it.id == role.personId }
    }

    fun name(session: ChatSession?, card: CharacterCard?, global: String?): String? =
        person(session, card)?.name?.takeIf { session?.desktopPlayerRole?.followName == true && it.isNotBlank() }
            ?: session?.playerName?.takeIf(String::isNotBlank) ?: global?.takeIf(String::isNotBlank)

    fun avatar(session: ChatSession?, card: CharacterCard?, global: String?): String? =
        person(session, card)?.appearanceImage?.takeIf { session?.desktopPlayerRole?.followAvatar == true && it.isNotBlank() }
            ?: session?.desktopPlayerAvatar ?: global
}
