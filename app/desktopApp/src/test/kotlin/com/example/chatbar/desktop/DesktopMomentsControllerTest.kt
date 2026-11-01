package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.PackagedImage
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopMomentsControllerTest {
    @Test fun `loaded enabled and pending never send AI and stopped manual generation retains a resumable checkpoint`() = runBlocking {
        val parent = Files.createTempDirectory("moments-controller-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        var controller: DesktopMomentsController? = null
        try {
            val storage = container.jsonFileStorage
            val card = CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1)
            val session = ChatSession("session", card.id, "Synthetic", createdAt = 1, updatedAt = 1)
            storage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
            storage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
            container.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            val resources = DesktopCharacterResourceStore(root)
            val cleanup = DesktopMomentImageCleanup(root, storage, container.dataOperationCoordinator,
                DesktopOwnedImageCleanup(root, resources, container.dataOperationCoordinator))
            val store = DesktopMomentStore(storage, container.dataOperationCoordinator, cleanup)
            val imported = MomentTask.create(card.id, session.id, 1)
            store.saveTask(imported)
            val calls = AtomicInteger(); val entered = CompletableDeferred<Unit>()
            val image = CompletableDeferred<String>()
            val generator = DesktopMomentGenerator { _, _, _, _, _, _, _, checkpoint, _, register ->
                calls.incrementAndGet()
                val candidate = resources.materializeImageTracked(PackagedImage("fixture.png", "AQ=="), 42, "fixture") {
                    register(cleanup.prepareCandidate(it))
                }
                image.complete(candidate)
                checkpoint("synthetic-completed-stage"); entered.complete(Unit); awaitCancellation()
            }
            fun create() = DesktopMomentsController(store, container.settingsRepository, container.characterRepository,
                container.chatRepository, container.dataOperationCoordinator, container.taskRuntime, generator, cleanup::finish)
            val first = create(); controller = first
            withTimeout(10_000) { first.state.first { it.enabled && it.tasks.size == 1 } }
            assertEquals(0, calls.get()); assertFalse(first.state.value.scheduling)
            assertEquals(imported, store.tasks().single())
            first.generate(card.id)
            withTimeout(10_000) { entered.await() }
            first.closeAndDrain(); controller = null
            assertFalse(Files.exists(root.resolve(image.await())))
            val interrupted = store.tasks().single { it.id != imported.id }
            assertEquals(MomentTaskStatus.FAILED, interrupted.status)
            assertEquals("synthetic-completed-stage", store.posts().single().generationCheckpoint)
            assertEquals(imported, store.tasks().first { it.id == imported.id })
            val second = create(); controller = second
            withTimeout(10_000) { second.state.first { it.tasks.size == 2 } }
            assertEquals(1, calls.get()); assertFalse(second.state.value.scheduling)
        } finally {
            controller?.closeAndDrain(); container.close(); parent.toFile().deleteRecursively()
        }
    }
}
