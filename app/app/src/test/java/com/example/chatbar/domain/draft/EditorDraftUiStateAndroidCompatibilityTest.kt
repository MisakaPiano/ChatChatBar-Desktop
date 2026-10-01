package com.example.chatbar.domain.draft

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorDraftUiStateAndroidCompatibilityTest {
    private val draftJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `Android Character legacy modal and current envelope restore the same modal`() {
        val modal = CharacterOpenModalState(CharacterOpenModalKind.EDIT_DOCUMENT,
            documentId = "document", documentName = "notes.txt", documentContent = "unfinished")
        val legacy = draftJson.encodeToString(CharacterOpenModalState.serializer(), modal)
        val current = CharacterEditorDraftUiStateCodec.encode(draftJson,
            CharacterEditorDraftUiState(openModalState = modal))
        assertEquals(modal, CharacterEditorDraftUiStateCodec.decode(draftJson, legacy)?.openModalState)
        assertEquals(modal, CharacterEditorDraftUiStateCodec.decode(draftJson, current)?.openModalState)
    }

    @Test fun `Android WorldBook legacy modal and version one envelope remain readable`() {
        val modal = WorldBookEntryModalState(name = "Unfinished", keys = "key")
        val legacy = draftJson.encodeToString(WorldBookEntryModalState.serializer(), modal)
        val current = WorldBookEditorDraftUiStateCodec.encode(draftJson,
            WorldBookEditorDraftUiState(entryModalState = modal))
        assertEquals(modal, WorldBookEditorDraftUiStateCodec.decode(draftJson, legacy)?.entryModalState)
        assertEquals(modal, WorldBookEditorDraftUiStateCodec.decode(draftJson, current)?.entryModalState)
    }
}
