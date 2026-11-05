package com.example.chatbar.domain.card

import kotlinx.serialization.Serializable

@Serializable
data class CharacterAutoFillDraft(
    val name: String = "",
    val greeting: String = "",
    val basicSetting: String = "",
    val defaultImagePrompt: String = "",
    val characters: List<CharacterAutoFillCharacterDraft> = emptyList()
)

@Serializable
data class CharacterAutoFillCharacterDraft(
    val targetIndex: Int? = null,
    val name: String = "",
    val profile: String = "",
    val appearance: String = "",
    val clothing: String = "",
    val abilities: String = "",
    val habits: String = "",
    val background: String = "",
    val relationships: String = "",
    val speakingStyle: String = "",
    val imagePrompt: String = ""
)
