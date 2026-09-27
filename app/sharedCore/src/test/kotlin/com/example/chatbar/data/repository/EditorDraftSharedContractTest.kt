package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftMode
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorDraftSharedContractTest {
    private lateinit var root: Path
    private lateinit var storage: JsonFileStorage
    private lateinit var repository: EditorDraftRepository

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("shared-editor-draft-")
        storage = JsonFileStorage(root)
        repository = EditorDraftRepository(storage)
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `character create and edit IDs bookkeeping and same-root restart retain the stored contract`() = runTest {
        val payload = CharacterCard(id = "card", name = "Draft character", createdAt = 1, updatedAt = 2)
        val create = repository.characterDraft(
            targetId = null,
            draftSessionId = "first-session",
            payload = payload,
            base = null,
            draftAssetPaths = listOf("staged/a.png", "staged/a.png"),
            pendingDeletedAssets = listOf("old/b.png"),
            pendingDeletedDocumentIds = listOf("document-id"),
            openModalState = """{"tab":"portrait"}""",
        )
        assertEquals("character_card_new", create.id)
        assertEquals(EditorDraftMode.CREATE, create.mode)
        assertNull(create.targetId)
        assertNull(create.baseUpdatedAt)
        assertNull(create.baseHash)
        assertEquals(listOf("staged/a.png"), create.draftAssetPaths)

        repository.save(create)
        val replaced = repository.save(create.copy(draftSessionId = "second-session"))
        assertEquals(1, repository.getAll().size)
        assertEquals("second-session", repository.getLatestNew(EditorDraftType.CHARACTER_CARD)?.draftSessionId)

        val base = payload.copy(name = "Formal character")
        val edit = repository.save(repository.characterDraft(
            targetId = base.id,
            draftSessionId = "edit-session",
            payload = payload,
            base = base,
            draftAssetPaths = listOf("staged/edit.png"),
            pendingDeletedAssets = listOf("old/edit.png"),
            pendingDeletedDocumentIds = listOf("old-document"),
            openModalState = """{"modal":"asset"}""",
        ))
        assertEquals("character_card_card", edit.id)
        assertEquals(EditorDraftMode.EDIT, edit.mode)
        assertEquals(base.id, edit.targetId)
        assertEquals(base.updatedAt, edit.baseUpdatedAt)
        assertNotNull(edit.baseHash)

        val restarted = EditorDraftRepository(JsonFileStorage(root))
        assertEquals(replaced, restarted.getLatestNew(EditorDraftType.CHARACTER_CARD))
        assertEquals(edit, restarted.getForTarget(EditorDraftType.CHARACTER_CARD, base.id))
        assertEquals(listOf("staged/edit.png"), restarted.getById(edit.id)?.draftAssetPaths)
        assertEquals(listOf("old/edit.png"), restarted.getById(edit.id)?.pendingDeletedAssets)
        assertEquals(listOf("old-document"), restarted.getById(edit.id)?.pendingDeletedDocumentIds)
        assertEquals("""{"modal":"asset"}""", restarted.getById(edit.id)?.openModalState)
    }

    @Test
    fun `delete and deleteForTarget remove only their logical draft IDs`() = runTest {
        val base = FormatCard(id = "format", name = "Formal", content = "original", createdAt = 1)
        val newDraft = repository.save(repository.formatDraft(null, "new-session", base, null))
        val editDraft = repository.save(repository.formatDraft(base.id, "edit-session", base, base))

        repository.delete(newDraft.id)
        assertNull(repository.getLatestNew(EditorDraftType.FORMAT_CARD))
        assertNotNull(repository.getById(editDraft.id))
        repository.deleteForTarget(EditorDraftType.FORMAT_CARD, base.id)
        assertNull(repository.getForTarget(EditorDraftType.FORMAT_CARD, base.id))
        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun `character hash excludes index maintenance but detects document and character edits`() {
        val document = DocumentInfo(
            id = "document", fileName = "notes.txt", filePath = "documents/notes.txt",
            fileType = "txt", addedAt = 1,
        )
        val base = CharacterCard(
            id = "card", name = "Formal character", customDocuments = listOf(document),
            createdAt = 1, updatedAt = 2,
        )
        val draft = repository.characterDraft(
            targetId = base.id,
            draftSessionId = "edit-session",
            payload = base,
            base = base,
            draftAssetPaths = emptyList(),
            pendingDeletedAssets = emptyList(),
            pendingDeletedDocumentIds = emptyList(),
        )
        val indexMaintenance = base.copy(
            ragIndexStatus = "COMPLETE",
            ragIndexDone = 5,
            ragIndexTotal = 5,
            ragIndexMessage = "finished",
            ragIndexedAt = 10,
            updatedAt = 11,
            customDocuments = listOf(document.copy(
                contentHash = "source-hash", indexedHash = "index-hash",
                ragStatus = "INDEXED", ragChunkCount = 3, ragIndexedAt = 10, ragError = "old-error",
            )),
        )

        assertFalse(repository.isChanged(indexMaintenance, draft))
        assertTrue(repository.isChanged(indexMaintenance.copy(name = "Renamed character"), draft))
        assertTrue(repository.isChanged(indexMaintenance.copy(
            customDocuments = listOf(indexMaintenance.customDocuments.single().copy(fileName = "changed.txt")),
        ), draft))
    }

    @Test
    fun `format and worldbook hashes detect meaningful external content changes`() {
        val format = FormatCard(id = "format", name = "Format", content = "original", createdAt = 1)
        val formatDraft = repository.formatDraft(format.id, "format-session", format, format)
        assertEquals(format.createdAt, formatDraft.baseUpdatedAt)
        assertFalse(repository.isChanged(format, formatDraft))
        assertTrue(repository.isChanged(format.copy(content = "externally changed"), formatDraft))

        val book = WorldBook(id = "book", name = "World", description = "original", createdAt = 1, updatedAt = 2)
        val bookDraft = repository.worldBookDraft(book.id, "book-session", book, book)
        assertEquals(book.updatedAt, bookDraft.baseUpdatedAt)
        assertFalse(repository.isChanged(book, bookDraft))
        assertTrue(repository.isChanged(book.copy(description = "externally changed"), bookDraft))
    }

    @Test
    fun `initialized cache retains descending updatedAt order`() = runTest {
        val older = repository.formatDraft(
            null, "older", FormatCard("format", "Format", "text", createdAt = 1), null,
        ).copy(updatedAt = 10)
        val newer = repository.worldBookDraft(
            null, "newer", WorldBook(id = "book", name = "World"), null,
        ).copy(updatedAt = 20)
        storage.saveEntity("edit_drafts", older.id, older, EditorDraft.serializer())
        storage.saveEntity("edit_drafts", newer.id, newer, EditorDraft.serializer())

        assertEquals(listOf(newer.id, older.id), repository.getAll().map { it.id })
    }
}
