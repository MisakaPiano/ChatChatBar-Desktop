package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopManagementDraftAdmissionTest {
    @Test fun `actual management draft cleanup keeps snapshot behind the complete transaction while ordinary card cleanup still releases normal admission`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("ccb-management-draft-admission-")
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val card = CharacterCard("source", "Synthetic", greeting = "Greeting", createdAt = 1, updatedAt = 1)
            app.characterRepository.save(card)
            val assets = DesktopCharacterDraftResources(root, app.characterResourceStore)
            val png = DesktopImageEditing.png(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB))
            val reference = assets.stageImage("draft-session", png)
            val draft = app.editorDraftRepository.save(app.editorDraftRepository.characterDraft(card.id, "draft-session",
                card.copy(avatar = reference), card, listOf(reference), emptyList(), emptyList()))
            val management = app.createManagementController(app.createTypedTransferController(), discardDraftAssets = { session ->
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); assets.discardSession(session)
            })
            management.requestDiscard(draft)
            val deletion = async(Dispatchers.Default) { management.confirmDeletion() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertFalse(Files.exists(root.resolve("entities/edit_drafts/${draft.id}.json")))
            assertTrue(Files.isRegularFile(root.resolve(reference)))
            val snapshot = async(Dispatchers.Default) { app.coordinatedSnapshotService.createSnapshot() }
            withTimeout(5000) { while (!snapshot.isCompleted && app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) delay(10) }
            assertFalse(snapshot.isCompleted, "snapshot must not copy between draft deletion and its resource cleanup")
            release.countDown(); withTimeout(10000) { deletion.await() }
            val saved = withTimeout(10000) { snapshot.await() }
            assertTrue(saved.validation.valid); assertTrue(app.coordinatedSnapshotService.validateSnapshot(saved.directory).valid)
            assertFalse(Files.exists(root.resolve(reference)))
            assertNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, card.id))
            assertNull(management.state.value.error); assertNull(management.state.value.warning)
            // The adjacent Entity branch must not inherit this normal scope: Core needs exclusive cleanup.
            val oldImage = root.resolve("images/only.png"); Files.createDirectories(oldImage.parent); Files.write(oldImage, png)
            app.characterRepository.save(card.copy(avatar = "images/only.png"))
            management.requestDelete(DesktopTransferKind.CHARACTER, card.id, card.name)
            withTimeout(10000) { management.confirmDeletion() }
            assertNull(app.characterRepository.getById(card.id)); assertFalse(Files.exists(oldImage))
            assertNull(management.state.value.error); assertNull(management.state.value.warning)
        } finally { release.countDown(); app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
