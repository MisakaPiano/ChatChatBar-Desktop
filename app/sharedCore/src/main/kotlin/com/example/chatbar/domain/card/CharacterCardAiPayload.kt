package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.domain.prompt.CharacterCardAiPromptAuthority
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class CharacterAutoFillImageContext(
    val hasSourceImages: Boolean = false,
    val descriptions: List<String> = emptyList()
) {
    fun hasContent(): Boolean = hasSourceImages || descriptions.any(String::isNotBlank)
}

/** Platform preparation supplies only a completed research brief; selected sources may not be discarded. */
object CharacterCardAiPayload {
    val promptJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    fun fill(userInput: String, currentCard: CharacterCard,
        externalResearch: JsonElement? = null, imageContext: CharacterAutoFillImageContext? = null,
        json: Json = promptJson): String = buildJsonObject {
        put("request", userInput.trim())
        imageContext?.takeIf(CharacterAutoFillImageContext::hasContent)?.let { context ->
            put("sourceImageInstructions", CharacterCardAiPromptAuthority.CHARACTER_AUTO_FILL_SOURCE_IMAGE_INSTRUCTIONS)
            if (context.descriptions.isNotEmpty()) put("sourceImageDescription", context.descriptions.joinToString("\n"))
        }
        put("fillTargets", CharacterAutoFillPolicy.fillTargets(currentCard))
        put("lockedContext", json.parseToJsonElement(json.encodeToString(CharacterAutoFillPolicy.lockedDraft(currentCard))))
        put("defaultNaiStyle", CharacterCardAiPromptAuthority.DEFAULT_CHARACTER_NAI_STYLE_PROMPT.trim())
        put("characterImageGuide", CharacterCardAiPromptAuthority.CHARACTER_IMAGE_NAI_PROMPT_GUIDE.trim())
        externalResearch?.let {
            put("externalResearchUsage", CharacterCardAiPromptAuthority.CHARACTER_EXTERNAL_RESEARCH_USAGE_PROMPT)
            put("externalResearch", it)
        }
    }.toString()

    fun rewrite(userInput: String, currentCard: CharacterCard, externalResearch: JsonElement? = null): String =
        buildJsonObject {
            put("request", userInput.trim())
            put("current", currentCard.toRewriteCurrent())
            put("outputSchema", rewriteSchema(currentCard))
            if (currentCard.editMode == CharacterEditMode.STRUCTURED)
                put("characterImageGuide", CharacterCardAiPromptAuthority.CHARACTER_IMAGE_NAI_PROMPT_GUIDE.trim())
            externalResearch?.let {
                put("externalResearchUsage", CharacterCardAiPromptAuthority.CHARACTER_EXTERNAL_RESEARCH_USAGE_PROMPT)
                put("externalResearch", it)
            }
        }.toString()

    fun rewriteSchema(card: CharacterCard): JsonObject = CharacterCardAiPromptAuthority.characterRewriteOutputSchema(
        card.editMode, listOf("name", "greeting", "basicSetting", "defaultImagePrompt"), personFields)

    fun mergeFill(current: CharacterCard, draft: CharacterAutoFillDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }): CharacterCard {
        require(current.editMode == CharacterEditMode.STRUCTURED) { "AI 自动填充仅支持分段模式" }
        val normalized = CharacterAutoFillPolicy.normalize(draft)
        return current.copy(
            name = CharacterAutoFillPolicy.fillBlank(current.name, normalized.name),
            greeting = CharacterAutoFillPolicy.fillBlank(current.greeting, normalized.greeting),
            basicSetting = CharacterAutoFillPolicy.fillBlank(current.basicSetting, normalized.basicSetting),
            defaultImagePrompt = CharacterAutoFillPolicy.fillBlank(current.defaultImagePrompt,
                normalized.defaultImagePrompt.ifBlank { CharacterCardAiPromptAuthority.DEFAULT_CHARACTER_NAI_STYLE_PROMPT }),
            defaultImageNegativePrompt = CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(current.defaultImageNegativePrompt),
            characters = CharacterAutoFillPolicy.mergeCharacters(current.characters, normalized.characters, idFactory))
    }

    private val personFields = listOf("name", "profile", "appearance", "clothing", "abilities", "habits",
        "background", "relationships", "speakingStyle", "imagePrompt")
}

private fun CharacterCard.toRewriteCurrent(): JsonObject = buildJsonObject {
    putNonBlank("name", name)
    putNonBlank("greeting", greeting)
    putNonBlank("basicSetting", basicSetting)
    putNonBlank("defaultImagePrompt", defaultImagePrompt)
    when (editMode) {
        CharacterEditMode.STRUCTURED -> {
            val visibleCharacters = characters.mapNotNull(CharacterInfo::toRewriteCurrentCharacter)
            if (visibleCharacters.isNotEmpty()) put("characters", buildJsonArray { visibleCharacters.forEach { add(it) } })
        }
        CharacterEditMode.FREEFORM -> putNonBlank("freeformCharacterText", freeformCharacterText)
    }
}

private fun CharacterInfo.toRewriteCurrentCharacter(): JsonObject? {
    val fields = listOf("name" to name, "profile" to profile, "appearance" to appearance, "clothing" to clothing,
        "abilities" to abilities, "habits" to habits, "background" to background, "relationships" to relationships,
        "speakingStyle" to speakingStyle, "imagePrompt" to imagePrompt)
    if (fields.none { it.second.isNotBlank() }) return null
    return buildJsonObject { put("id", id); fields.forEach { (key, value) -> putNonBlank(key, value) } }
}

private fun JsonObjectBuilder.putNonBlank(key: String, value: String) { if (value.isNotBlank()) put(key, value) }
