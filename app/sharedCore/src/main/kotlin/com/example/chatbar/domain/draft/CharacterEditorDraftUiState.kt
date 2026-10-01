package com.example.chatbar.domain.draft

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Editor-only recovery metadata in EditorDraft.openModalState, not a Character entity field. */
@Serializable
data class CharacterEditorDraftUiState(
    val characterEditorDraftUiVersion: Int = 1,
    val openModalState: CharacterOpenModalState? = null,
    val postCommit: CharacterEditorPostCommitState? = null,
)

@Serializable
data class CharacterEditorPostCommitState(
    val characterId: String,
    val expectedFingerprint: String,
    val oldCardName: String? = null,
    val newCardName: String? = null,
    val recoveryTargetId: String? = null,
    val recoverySessionId: String? = null,
)

object CharacterEditorDraftUiStateCodec {
    private val envelopeKeys = setOf("openModalState", "postCommit")
    private val legacyModalKeys = setOf("kind", "character", "documentId", "documentName", "documentContent")

    fun encode(json: Json, state: CharacterEditorDraftUiState): String = json.encodeToString(state)

    /** Android drafts written before the envelope serialized CharacterOpenModalState directly. */
    fun decode(json: Json, raw: String?): CharacterEditorDraftUiState? {
        if (raw == null) return null
        val element = json.parseToJsonElement(raw).jsonObject
        val marker = element["characterEditorDraftUiVersion"]
        if (marker == null) {
            require(element.keys.none(envelopeKeys::contains) && element.keys.any(legacyModalKeys::contains)) {
                "Malformed or unrecognized Character editor draft UI state"
            }
            return CharacterEditorDraftUiState(
                openModalState = json.decodeFromString(CharacterOpenModalState.serializer(), raw)
            )
        }
        val version = marker.jsonPrimitive.intOrNull
            ?: error("Malformed Character editor draft UI state version")
        require(version == 1) { "Unsupported Character editor draft UI state version: $version" }
        return json.decodeFromString(CharacterEditorDraftUiState.serializer(), raw).also { state ->
            state.postCommit?.let { commit ->
                require(commit.characterId.isNotBlank() && commit.expectedFingerprint.isNotBlank() &&
                    (commit.oldCardName == null) == (commit.newCardName == null)) {
                    "Malformed Character post-commit recovery marker"
                }
            }
        }
    }
}
