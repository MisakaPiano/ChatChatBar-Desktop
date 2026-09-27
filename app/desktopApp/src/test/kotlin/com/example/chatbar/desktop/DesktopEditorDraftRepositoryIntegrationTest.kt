package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopEditorDraftRepositoryIntegrationTest {
    @Test
    fun `container repository access is zero-write and explicit save survives restart`() = runBlocking {
        val parent = Files.createTempDirectory("desktop-editor-draft-")
        val root = parent.resolve("app-data")
        val resolved = DesktopDataRootResolution.Resolved(
            appDataRoot = root,
            provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
            bootstrapPath = parent.resolve("bootstrap.json"),
        )
        val secrets = InMemoryDesktopSecretStore()
        try {
            val first = DesktopAppContainer(resolved, secretStoreFactory = { secrets })
            val saved = try {
                val drafts = first.editorDraftRepository
                assertFalse(Files.exists(root))
                val draft = drafts.characterDraft(
                    targetId = null,
                    draftSessionId = "desktop-session",
                    payload = CharacterCard(id = "card", name = "Desktop draft", createdAt = 1, updatedAt = 2),
                    base = null,
                    draftAssetPaths = emptyList(),
                    pendingDeletedAssets = emptyList(),
                    pendingDeletedDocumentIds = emptyList(),
                )
                drafts.save(draft)
            } finally {
                first.close()
            }
            assertTrue(Files.isRegularFile(root.resolve("entities/edit_drafts/character_card_new.json")))

            val restarted = DesktopAppContainer(resolved, secretStoreFactory = { secrets })
            try {
                assertEquals(saved, restarted.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertEquals(saved, restarted.editorDraftRepository.getLatestNew(EditorDraftType.CHARACTER_CARD))
            } finally {
                restarted.close()
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
