package com.example.chatbar.domain.card

import kotlinx.serialization.Serializable

@Serializable
data class CharacterRewriteDraft(
    val name: String? = null,
    val greeting: String? = null,
    val basicSetting: String? = null,
    val defaultImagePrompt: String? = null,
    val freeformCharacterText: String? = null,
    val deleteCharacterIds: List<String> = emptyList(),
    val characters: List<CharacterRewriteCharacterDraft> = emptyList()
)

@Serializable
data class CharacterRewriteCharacterDraft(
    val id: String? = null,
    val name: String? = null,
    val profile: String? = null,
    val appearance: String? = null,
    val clothing: String? = null,
    val abilities: String? = null,
    val habits: String? = null,
    val background: String? = null,
    val relationships: String? = null,
    val speakingStyle: String? = null,
    val imagePrompt: String? = null
)

