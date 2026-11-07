package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterManualDraftAdmissionTest {
    private class HeldPicker(private val image: Path) : DesktopFilePicker {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun pickOpenFile(type: DesktopFileType): Path {
            entered.countDown(); check(release.await(8, TimeUnit.SECONDS)); return image
        }
        override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
    }

    @Test fun `an already open picker returning during maintenance cannot stage or assign character draft media and ordinary retry remains usable`() = runBlocking {
        val parent = Files.createTempDirectory("ccb-manual-draft-admission-")
        val root = parent.resolve("data")
        val png = DesktopImageEditing.png(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB))
        val external = parent.resolve("selected.png"); Files.write(external, png)
        val picker = HeldPicker(external)
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker,
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        val maintenanceRelease = CompletableDeferred<Unit>()
        var maintenance: Job? = null
        try {
            val card = CharacterCard("source", "Synthetic", greeting = "Greeting", createdAt = 1, updatedAt = 1)
            app.characterRepository.save(card)
            val editor = app.characterEditorController; editor.openExisting(card.id)
            val before = assertNotNull(editor.state.value.card)
            val selected = async(Dispatchers.Default) { editor.chooseAvatar() }
            assertTrue(withContext(Dispatchers.IO) { picker.entered.await(5, TimeUnit.SECONDS) })
            val entered = CompletableDeferred<Unit>()
            maintenance = launch(Dispatchers.Default) { app.dataOperationCoordinator.withExclusiveMaintenance {
                entered.complete(Unit); maintenanceRelease.await()
            } }
            withTimeout(5000) { entered.await() }
            picker.release.countDown(); withTimeout(5000) { selected.await() }
            assertEquals(before.avatar, editor.state.value.card!!.avatar, "picker completion must not assign across maintenance")
            assertFalse(Files.exists(root.resolve("draft_assets")), "no manual draft file may be staged during exclusive maintenance")
            assertEquals(CharacterEditorProblem.RESOURCE_FAILED, editor.state.value.problem)
            assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, app.dataOperationCoordinator.state)
            maintenanceRelease.complete(Unit); maintenance.join()
            editor.chooseAvatar()
            val reference = assertNotNull(editor.state.value.card!!.avatar)
            assertTrue(reference.startsWith("draft_assets/")); assertContentEquals(png, Files.readAllBytes(root.resolve(reference)))
            assertContentEquals(png, Files.readAllBytes(external)); assertNull(editor.state.value.problem)
        } finally {
            picker.release.countDown(); maintenanceRelease.complete(Unit); maintenance?.join(); app.close()
            check(parent.toFile().deleteRecursively())
        }
    }
}
