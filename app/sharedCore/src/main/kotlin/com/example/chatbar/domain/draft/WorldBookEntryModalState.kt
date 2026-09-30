package com.example.chatbar.domain.draft

import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class WorldBookEntryModalState(
    val editingIndex: Int? = null,
    val originalEntryId: String? = null,
    val name: String = "",
    val keys: String = "",
    val secondary: String = "",
    val content: String = "",
    val order: String = "100",
    val position: WorldBookPosition = WorldBookPosition.BEFORE_CHAR,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWords: Boolean? = null,
    val caseSensitive: Boolean? = null,
    val matchCharacterDescription: Boolean = false,
    val matchCharacterPersonality: Boolean = false,
    val matchScenario: Boolean = false,
    val matchCreatorNotes: Boolean = false,
    val matchPersonaDescription: Boolean = false,
    val ignoreBudget: Boolean = false,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val delayUntilRecursion: Boolean = false,
    val logic: Int = WorldBookSelectiveLogic.AND_ANY.value,
    val probability: String = "100",
    val group: String = "",
    val groupWeight: String = "100",
    val scanDepth: String = "",
    val sticky: String = "0",
    val cooldown: String = "0",
    val delay: String = "0",
    val outlet: String = ""
) {
    companion object {
        fun from(index: Int?, entry: WorldBookEntry?): WorldBookEntryModalState =
            WorldBookEntryModalState(
                editingIndex = index,
                originalEntryId = entry?.id,
                name = entry?.name ?: "",
                keys = entry?.keys?.joinToString(", ") ?: "",
                secondary = entry?.secondaryKeys?.joinToString(", ") ?: "",
                content = entry?.content ?: "",
                order = (entry?.insertionOrder ?: 100).toString(),
                position = entry?.position ?: WorldBookPosition.BEFORE_CHAR,
                enabled = entry?.enabled ?: true,
                constant = entry?.constant ?: false,
                useRegex = entry?.useRegex ?: false,
                wholeWords = entry?.matchWholeWords,
                caseSensitive = entry?.caseSensitive,
                matchCharacterDescription = entry?.matchCharacterDescription ?: false,
                matchCharacterPersonality = entry?.matchCharacterPersonality ?: false,
                matchScenario = entry?.matchScenario ?: false,
                matchCreatorNotes = entry?.matchCreatorNotes ?: false,
                matchPersonaDescription = entry?.matchPersonaDescription ?: false,
                ignoreBudget = entry?.ignoreBudget ?: false,
                excludeRecursion = entry?.excludeRecursion ?: false,
                preventRecursion = entry?.preventRecursion ?: false,
                delayUntilRecursion = entry?.delayUntilRecursion ?: false,
                logic = entry?.selectiveLogic ?: WorldBookSelectiveLogic.AND_ANY.value,
                probability = (entry?.probability ?: 100).toString(),
                group = entry?.group ?: "",
                groupWeight = (entry?.groupWeight ?: 100).toString(),
                scanDepth = entry?.scanDepth?.toString() ?: "",
                sticky = (entry?.sticky ?: 0).toString(),
                cooldown = (entry?.cooldown ?: 0).toString(),
                delay = (entry?.delay ?: 0).toString(),
                outlet = entry?.outletName ?: ""
            )
    }
}

fun WorldBookEntryModalState.hasMeaningfulEntryData(): Boolean =
    name.isNotBlank() || keys.isNotBlank() || content.isNotBlank()

fun WorldBookEntryModalState.materialize(
    existing: WorldBookEntry?,
    idFactory: () -> String = { UUID.randomUUID().toString() }
): WorldBookEntry = (existing ?: WorldBookEntry(id = originalEntryId ?: idFactory())).copy(
    name = name,
    keys = keys.split(",").map(String::trim).filter(String::isNotBlank),
    secondaryKeys = secondary.split(",").map(String::trim).filter(String::isNotBlank),
    selective = secondary.isNotBlank(),
    selectiveLogic = logic,
    content = content,
    insertionOrder = order.toIntOrNull() ?: 100,
    position = position,
    enabled = enabled,
    constant = constant,
    useRegex = useRegex,
    matchWholeWords = wholeWords,
    caseSensitive = caseSensitive,
    matchCharacterDescription = matchCharacterDescription,
    matchCharacterPersonality = matchCharacterPersonality,
    matchScenario = matchScenario,
    matchCreatorNotes = matchCreatorNotes,
    matchPersonaDescription = matchPersonaDescription,
    ignoreBudget = ignoreBudget,
    excludeRecursion = excludeRecursion,
    preventRecursion = preventRecursion,
    delayUntilRecursion = delayUntilRecursion,
    probability = probability.toIntOrNull()?.coerceIn(0, 100) ?: 100,
    group = group,
    groupWeight = groupWeight.toIntOrNull()?.coerceAtLeast(0) ?: 100,
    scanDepth = scanDepth.toIntOrNull()?.coerceAtLeast(0),
    sticky = sticky.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    cooldown = cooldown.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    delay = delay.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    outletName = outlet
)
