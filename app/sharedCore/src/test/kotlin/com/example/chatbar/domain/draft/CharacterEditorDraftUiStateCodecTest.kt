package com.example.chatbar.domain.draft

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.WorldBook
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CharacterEditorDraftUiStateCodecTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `missing and legacy raw Character modal remain readable`() {
        val modal = CharacterOpenModalState(CharacterOpenModalKind.EDIT_DOCUMENT,
            documentId = "doc", documentName = "notes.txt", documentContent = "draft")
        assertNull(CharacterEditorDraftUiStateCodec.decode(json, null))
        assertEquals(modal, CharacterEditorDraftUiStateCodec.decode(json,
            json.encodeToString(CharacterOpenModalState.serializer(), modal))?.openModalState)
    }

    @Test fun `versioned envelope preserves modal and durable post-commit intent`() {
        val state = CharacterEditorDraftUiState(
            openModalState = CharacterOpenModalState(CharacterOpenModalKind.CHARACTER,
                character = CharacterInfo.create("Person")),
            postCommit = CharacterEditorPostCommitState("card", "sha256", "Old", "New"))
        val encoded = CharacterEditorDraftUiStateCodec.encode(json, state)
        assertTrue(encoded.contains("\"characterEditorDraftUiVersion\":1"))
        assertEquals(state, CharacterEditorDraftUiStateCodec.decode(json, encoded))
    }

    @Test fun `malformed markerless and unknown version envelopes fail closed`() {
        listOf("{}", """{"postCommit":null}""", """{"openModalState":null}""",
            """{"unrelated":true}""").forEach { raw ->
            assertFailsWith<IllegalArgumentException> { CharacterEditorDraftUiStateCodec.decode(json, raw) }
        }
        assertFailsWith<IllegalArgumentException> {
            CharacterEditorDraftUiStateCodec.decode(json, """{"characterEditorDraftUiVersion":2}""")
        }
        assertFailsWith<IllegalArgumentException> {
            CharacterEditorDraftUiStateCodec.decode(json,
                """{"characterEditorDraftUiVersion":1,"postCommit":{"characterId":"card","expectedFingerprint":"hash","oldCardName":"Old"}}""")
        }
    }

    @Test fun `fingerprints include Character durable resource refs but ignore WorldBook repository timestamp`() {
        val card = CharacterCard.create("Card", "Hi")
        assertNotEquals(EditorPostCommitFingerprint.character(json, card.copy(avatar = "draft.png")),
            EditorPostCommitFingerprint.character(json, card.copy(avatar = "owned.png")))
        val book = WorldBook.create("Book")
        assertEquals(EditorPostCommitFingerprint.worldBook(json, book),
            EditorPostCommitFingerprint.worldBook(json, book.copy(updatedAt = book.updatedAt + 100)))
        assertNotEquals(EditorPostCommitFingerprint.worldBook(json, book),
            EditorPostCommitFingerprint.worldBook(json, book.copy(description = "Changed")))
    }
}
