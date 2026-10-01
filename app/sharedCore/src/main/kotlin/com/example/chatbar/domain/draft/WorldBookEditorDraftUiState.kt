package com.example.chatbar.domain.draft

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Editor-only presentation state in EditorDraft.openModalState; never part of a WorldBook. */
@Serializable
data class WorldBookEditorDraftUiState(
    val worldBookEditorDraftUiVersion: Int = 1,
    val entryModalState: WorldBookEntryModalState? = null,
    val scanDepthInput: String? = null,
    val tokenBudgetInput: String? = null,
    val postCommit: WorldBookEditorPostCommitState? = null,
)

@Serializable
data class WorldBookEditorPostCommitState(
    val worldBookId: String,
    val expectedSemanticFingerprint: String,
    val recoveryTargetId: String? = null,
)

object WorldBookEditorDraftUiStateCodec {
    private val envelopeKeys = setOf("entryModalState", "scanDepthInput", "tokenBudgetInput", "postCommit")
    private val legacyModalKeys = setOf("editingIndex", "originalEntryId", "name", "keys", "secondary",
        "content", "order", "position", "enabled", "constant", "useRegex", "wholeWords",
        "caseSensitive", "matchCharacterDescription", "matchCharacterPersonality", "matchScenario",
        "matchCreatorNotes", "matchPersonaDescription", "ignoreBudget", "excludeRecursion",
        "preventRecursion", "delayUntilRecursion", "logic", "probability", "group", "groupWeight",
        "scanDepth", "sticky", "cooldown", "delay", "outlet")

    fun encode(json: Json, state: WorldBookEditorDraftUiState): String = json.encodeToString(state)

    /** Earlier Android/Desktop drafts serialized WorldBookEntryModalState directly. */
    fun decode(json: Json, raw: String?): WorldBookEditorDraftUiState? {
        if (raw == null) return null
        val element = json.parseToJsonElement(raw).jsonObject
        val marker = element["worldBookEditorDraftUiVersion"]
        if (marker == null) {
            require(element.keys.none(envelopeKeys::contains) && element.keys.any(legacyModalKeys::contains)) {
                "Malformed or unrecognized WorldBook editor draft UI state"
            }
            return WorldBookEditorDraftUiState(
                entryModalState = json.decodeFromString(WorldBookEntryModalState.serializer(), raw)
            )
        }
        val version = marker.jsonPrimitive.intOrNull
            ?: error("Malformed WorldBook editor draft UI state version")
        require(version == 1) { "Unsupported WorldBook editor draft UI state version: $version" }
        return json.decodeFromString(WorldBookEditorDraftUiState.serializer(), raw).also { state ->
            state.postCommit?.let { commit ->
                require(commit.worldBookId.isNotBlank() && commit.expectedSemanticFingerprint.isNotBlank()) {
                    "Malformed WorldBook post-commit recovery marker"
                }
            }
        }
    }
}
