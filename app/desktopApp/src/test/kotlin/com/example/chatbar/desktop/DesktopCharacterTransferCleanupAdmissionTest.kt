package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterTransferCleanupAdmissionTest {
    @Test fun `committed deletion releases normal admission before cleanup and unreadable authority remains a committed management warning`() = runBlocking {
        for (unreadable in listOf(false, true)) {
            val parent = Files.createTempDirectory("ccb-transfer-cleanup-admission-")
            val root = parent.resolve("data")
            val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
            val release = CompletableDeferred<Unit>(); var blocker: Job? = null
            try {
                val image = root.resolve("images/retired.png"); Files.createDirectories(image.parent); Files.writeString(image, "original-retired-image")
                app.characterRepository.save(CharacterCard("source", "Synthetic", greeting = "Greeting", avatar = "images/retired.png", createdAt = 1, updatedAt = 1))
                if (unreadable) {
                    val bad = root.resolve("entities/unknown_authority/broken.json"); Files.createDirectories(bad.parent); Files.writeString(bad, "{")
                    assertFails { kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(bad)) }
                    val management = app.createManagementController(app.createTypedTransferController())
                    management.requestDelete(DesktopTransferKind.CHARACTER, "source", "Synthetic")
                    management.confirmDeletion()
                    assertNull(app.characterRepository.getById("source"))
                    assertEquals(DesktopUiText.MANAGE_DELETE_COMMITTED, management.state.value.warning)
                    assertNull(management.state.value.error); assertNull(management.state.value.pendingDeletion)
                    assertEquals("original-retired-image", Files.readString(image)); assertEquals("{", Files.readString(bad))
                } else {
                    val entered = CompletableDeferred<Unit>()
                    blocker = launch(Dispatchers.Default) { app.dataOperationCoordinator.withNormalOperation { entered.complete(Unit); release.await() } }
                    entered.await()
                    val deleted = async(Dispatchers.Default) { app.characterTransfers.deleteCard("source") }
                    withTimeout(8000) { while (app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) delay(10) }
                    // Do not enqueue a normal repository read behind our own held blocker.
                    assertFalse(Files.exists(root.resolve("entities/character_cards/source.json")))
                    assertFalse(deleted.isCompleted); assertTrue(Files.exists(image))
                    release.complete(Unit); blocker.join(); withTimeout(8000) { deleted.await() }
                    assertFalse(Files.exists(image)); assertNull(app.characterRepository.getById("source"))
                    assertEquals(DesktopDataOperationCoordinatorState.OPEN, app.dataOperationCoordinator.state)
                }
            } finally { release.complete(Unit); blocker?.join(); app.close(); check(parent.toFile().deleteRecursively()) }
        }
    }
}
