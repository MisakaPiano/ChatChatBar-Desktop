package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.repository.EditorDraftRepository
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopEditorDraftCleanupTest {
    @Test fun `physical removal remains committed when delete reports post-delete cache failure`() = runBlocking {
        val root = Files.createTempDirectory("draft-cleanup-")
        try {
            val drafts = EditorDraftRepository(JsonFileStorage(root))
            drafts.save(drafts.formatDraft(null, "session", FormatCard.create("Draft", "Body"), null))
            val result = deleteEditorDraftConfirmed(drafts, EditorDraftType.FORMAT_CARD, null, delete = { type, target ->
                drafts.deleteForTarget(type, target)
                error("post-delete cache refresh")
            })
            assertEquals(DraftPhysicalState.REMOVED, result.physical)
            assertTrue(result.cacheReconciled)
            assertFalse(result.blocking)
            assertTrue(result.errors.isNotEmpty())
            assertFalse(drafts.existsForTarget(EditorDraftType.FORMAT_CARD, null))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `no-op delete remains blocking while draft physically exists`() = runBlocking {
        val root = Files.createTempDirectory("draft-cleanup-")
        try {
            val drafts = EditorDraftRepository(JsonFileStorage(root))
            drafts.save(drafts.formatDraft(null, "session", FormatCard.create("Draft", "Body"), null))
            val result = deleteEditorDraftConfirmed(drafts, EditorDraftType.FORMAT_CARD, null,
                delete = { _, _ -> Unit })
            assertEquals(DraftPhysicalState.EXISTS, result.physical)
            assertTrue(result.blocking)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `physical deletion and failed cache refresh remain separate results`() = runBlocking {
        val root = Files.createTempDirectory("draft-cleanup-")
        try {
            val drafts = EditorDraftRepository(JsonFileStorage(root))
            drafts.save(drafts.formatDraft(null, "session", FormatCard.create("Draft", "Body"), null))
            val result = deleteEditorDraftConfirmed(drafts, EditorDraftType.FORMAT_CARD, null,
                drafts::deleteForTarget) { error("cache refresh failed") }
            assertEquals(DraftPhysicalState.REMOVED, result.physical)
            assertFalse(result.cacheReconciled)
            assertTrue(result.blocking)
            assertFalse(drafts.existsForTarget(EditorDraftType.FORMAT_CARD, null))
        } finally { root.toFile().deleteRecursively() }
    }
}
