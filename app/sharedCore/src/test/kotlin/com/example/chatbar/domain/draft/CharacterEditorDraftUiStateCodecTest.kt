package com.example.chatbar.domain.draft

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.data.local.entity.SpeakerTagRenameTask
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

    @Test fun `Character fingerprint ignores only existing editor conflict exclusions`() {
        val person = CharacterInfo.create("Person").copy(appearanceImage = "images/person.png")
        val document = DocumentInfo.create("notes.txt", "documents/notes.txt", "txt")
        val card = CharacterCard.create("Card", "Hi").copy(
            avatar = "images/avatar.png", characters = listOf(person), customDocuments = listOf(document))
        val fingerprint = EditorPostCommitFingerprint.character(json, card)
        val maintenance = card.copy(ragIndexStatus = "COMPLETE", ragIndexDone = 1,
            ragIndexTotal = 1, ragIndexMessage = "done", ragIndexedAt = 5,
            customDocuments = listOf(document.copy(contentHash = "content", indexedHash = "indexed",
                ragStatus = "COMPLETE", ragChunkCount = 3, ragIndexedAt = 6, ragError = "old error")),
            pendingSpeakerRenameTasks = listOf(SpeakerTagRenameTask("task", card.id,
                card.updatedAt, listOf(SpeakerTagRename("person", "Old", "New")), 7)),
            updatedAt = card.updatedAt + 1)
        assertEquals(CharacterEditorSemanticProjection.normalize(card),
            CharacterEditorSemanticProjection.normalize(maintenance))
        assertEquals(fingerprint, EditorPostCommitFingerprint.character(json, maintenance))
        listOf(
            card.copy(name = "Other"),
            card.copy(greeting = "Other"),
            card.copy(characters = listOf(person.copy(profile = "Other"))),
            card.copy(avatar = "images/other.png"),
            card.copy(characters = listOf(person.copy(appearanceImage = "images/other.png"))),
            card.copy(customDocuments = listOf(document.copy(filePath = "documents/other.txt"))),
            card.copy(customDocuments = listOf(document.copy(fileName = "other.txt"))),
        ).forEach { changed ->
            assertNotEquals(fingerprint, EditorPostCommitFingerprint.character(json, changed))
        }
    }
}
