package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.chat.PlaceholderRenderer

/** Request-only matching sources; never appended to the world-book prompt itself. */
data class WorldBookScanContext(
    val characterDescription: String = "",
    val characterPersonality: String = "",
    val scenario: String = "",
    val creatorNotes: String = "",
    val personaDescription: String = ""
) {
    fun selectedText(entry: WorldBookEntry): List<String> = buildList {
        if (entry.matchCharacterDescription) add(characterDescription)
        if (entry.matchCharacterPersonality) add(characterPersonality)
        if (entry.matchScenario) add(scenario)
        if (entry.matchCreatorNotes) add(creatorNotes)
        if (entry.matchPersonaDescription) add(personaDescription)
    }

    companion object {
        fun fromCard(card: CharacterCard, playerSetting: String?, playerName: String?): WorldBookScanContext {
            val description = mutableListOf<String>()
            val personality = mutableListOf<String>()
            val scenario = mutableListOf(card.basicSetting)
            if (card.editMode == CharacterEditMode.FREEFORM) {
                // The importer preserves these sections in the editable freeform body.
                var destination = description
                for (line in card.freeformCharacterText.lines()) {
                    when (line.trim()) {
                        "【人物描述】", "【角色名称】" -> destination = description
                        "【性格特点】" -> destination = personality
                        "【背景场景】" -> destination = scenario
                        "【对话示例】" -> destination = mutableListOf()
                        else -> destination.add(line)
                    }
                }
            } else {
                card.characters.forEach { character ->
                    description.addAll(listOf(character.name, character.profile, character.appearance,
                        character.clothing, character.abilities, character.background, character.relationships))
                    personality.addAll(listOf(character.habits, character.speakingStyle))
                }
            }
            fun render(parts: List<String>) = PlaceholderRenderer.render(
                parts.filter(String::isNotBlank).joinToString("\n"), playerName, card.effectiveBotName
            )
            return WorldBookScanContext(
                characterDescription = render(description),
                characterPersonality = render(personality),
                scenario = render(scenario),
                creatorNotes = render(listOf(card.creatorNotes)),
                personaDescription = render(listOf(playerSetting.orEmpty()))
            )
        }
    }
}
