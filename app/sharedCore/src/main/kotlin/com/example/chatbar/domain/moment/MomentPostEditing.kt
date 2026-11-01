package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.CharacterCard

data class MomentSender(val characterId: String?, val name: String, val avatar: String?)

data class MomentSenderOption(val key: String, val sender: MomentSender)

object MomentPostEditing {
    fun senderOptions(card: CharacterCard?): List<MomentSenderOption> {
        if (card == null) return emptyList()
        return listOf(MomentSenderOption("card", MomentSender(null, card.name, card.avatar))) +
            card.characters.filter { it.name.isNotBlank() }.map {
                MomentSenderOption("character:${it.id}", MomentSender(
                    it.id, it.name, it.appearanceImage?.takeIf(String::isNotBlank)
                        ?: card.avatar?.takeIf(String::isNotBlank)
                ))
            }
    }
}
