package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.moment.MomentGenerationResult
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopMomentsControllerTest {
    @Test fun `moment cleanup uncertainty survives skipped generation without false completion or replay`() = runBlocking {
        val parent = Files.createTempDirectory("moment-task-cleanup-")
        val root = Files.createDirectory(parent.resolve("data"))
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        var controller: DesktopMomentsController? = null
        try {
            val card = CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1)
            val session = ChatSession("session", card.id, "Synthetic", createdAt = 1, updatedAt = 1)
            container.jsonFileStorage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
            container.jsonFileStorage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
            container.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            val calls = AtomicInteger()
            val owner = DesktopMomentsController(DesktopMomentStore(container.jsonFileStorage, container.dataOperationCoordinator),
                container.settingsRepository, container.characterRepository, container.chatRepository,
                container.dataOperationCoordinator, container.taskRuntime,
                DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, register ->
                    calls.incrementAndGet(); register("SYNTHETIC_PRIVATE_RECEIPT"); MomentGenerationResult.Skipped("SYNTHETIC_PRIVATE_REASON")
                }, { error("SYNTHETIC_PRIVATE_ERROR_AND_PATH") })
            controller = owner
            withTimeout(5000) { owner.state.first { it.enabled } }
            owner.generate(card.id)
            val row = withTimeout(5000) { owner.workRows.first { it.isNotEmpty() && it.none { row -> row.active } } }.single()
            assertEquals(DesktopWorkKind.MOMENT, row.kind); assertEquals(DesktopWorkPhase.UNCONFIRMED, row.phase)
            assertFalse(row.canStop); assertFalse(row.canRetry); assertFalse(row.toString().contains("SYNTHETIC_PRIVATE"))
            assertEquals(1, calls.get()); assertFalse(owner.state.value.scheduling)
            assertTrue(container.taskRuntime.ownedWork.value.none { it.draftSessionId == "moments:${session.id}" })
        } finally { controller?.closeAndDrain(); container.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `local approved plans survive reopen while new imported plans and same-root restore remain unapproved`() = runBlocking {
        val parent = Files.createTempDirectory("moments-local-resume-")
        val root = Files.createDirectory(parent.resolve("data"))
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val container = DesktopAppContainer(resolved, secretStoreFactory = { InMemoryDesktopSecretStore() })
        var controller: DesktopMomentsController? = null
        try {
            val now = 100_000L
            val card = CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1)
            val session = ChatSession("session", card.id, "Synthetic", lastMessageTime = now, createdAt = 1, updatedAt = 1)
            container.jsonFileStorage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
            container.jsonFileStorage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
            container.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            val store = DesktopMomentStore(container.jsonFileStorage, container.dataOperationCoordinator)
            val approved = MomentTask.create(card.id, "missing-original-session", now - 2)
            val unapproved = MomentTask.create(card.id, session.id, now - 1)
            store.saveTask(approved); store.saveTask(unapproved)
            val admission = DesktopMomentAdmissionStore(root, parent.resolve("local-only"))
            admission.save(DesktopMomentAdmission(true, mapOf(approved.id to admission.taskHash(approved))))
            val calls = AtomicInteger()
            val generator = DesktopMomentGenerator { _, selected, _, _, _, task, _, _, _, _ ->
                assertEquals(session.id, selected.id); assertEquals(session.id, task.sessionId)
                calls.incrementAndGet(); MomentGenerationResult.Skipped("synthetic decision")
            }
            fun create() = DesktopMomentsController(store, container.settingsRepository, container.characterRepository,
                container.chatRepository, container.dataOperationCoordinator, container.taskRuntime, generator,
                admissionStore = admission, clock = { now }, tickMillis = 60_000)
            val first = create(); controller = first
            withTimeout(15_000) { first.state.first { it.tasks.any { t -> t.id == approved.id && t.status == MomentTaskStatus.SKIPPED } } }
            withTimeout(5000) { first.workRows.first { rows -> rows.none { it.active } } }
            assertTrue(first.workRows.value.isEmpty()) // Skipped publication never becomes a success toast/history.
            assertEquals(1, calls.get())
            assertEquals(unapproved, store.tasks().single { it.id == unapproved.id })
            first.closeAndDrain(); controller = null
            assertTrue(admission.load().active)
            val second = create(); controller = second
            withTimeout(10_000) { second.state.first { it.scheduling } }
            assertEquals(1, calls.get())
            second.closeAndDrain(); controller = null
            val imported = MomentTask.create(card.id, session.id, now)
            val source = parent.resolve("restored-task.json")
            Files.writeString(source, kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(MomentTask.serializer(), imported))
            val relative = "entities/moment_tasks/${imported.id}.json"
            val restore = DesktopFullBackupCommitJournal(root)
            restore.schedule(mapOf(relative to source), mapOf(relative to null)); restore.recover()
            val before = store.tasks()
            val third = create(); controller = third
            withTimeout(10_000) { third.state.first { it.error != null } }
            assertFalse(third.state.value.scheduling)
            assertEquals(1, calls.get()); assertEquals(before, store.tasks())
        } finally { controller?.closeAndDrain(); container.close(); ownership.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `loaded enabled and pending never send AI and stopped manual generation retains a resumable checkpoint`() = runBlocking {
        val parent = Files.createTempDirectory("moments-controller-")
        val root = Files.createDirectory(parent.resolve("app-data"))
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        var controller: DesktopMomentsController? = null
        val cleanupRelease = CompletableDeferred<Unit>()
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
            val cleanupEntered = CompletableDeferred<Unit>()
            fun create() = DesktopMomentsController(store, container.settingsRepository, container.characterRepository,
                container.chatRepository, container.dataOperationCoordinator, container.taskRuntime, generator, { receipt ->
                    cleanupEntered.complete(Unit); cleanupRelease.await(); cleanup.finish(receipt)
                })
            val first = create(); controller = first
            container.taskCenter.watchMoments(first)
            withTimeout(10_000) { first.state.first { it.enabled && it.tasks.size == 1 } }
            assertEquals(0, calls.get()); assertFalse(first.state.value.scheduling)
            assertEquals(imported, store.tasks().single())
            first.generate(card.id)
            withTimeout(10_000) { entered.await() }
            val row = withTimeout(5000) { container.taskCenter.state.first { it.rows.any { row -> row.kind == DesktopWorkKind.MOMENT && row.active } } }
                .rows.single { it.kind == DesktopWorkKind.MOMENT && it.active }
            assertTrue(container.taskRuntime.ownedWork.value.none { it.draftSessionId == "moments:${session.id}" })
            assertTrue(container.taskCenter.requestStop(row.id))
            withTimeout(5000) { first.workRows.first { it.single().stopRequested } }
            withTimeout(5000) { cleanupEntered.await() }
            assertTrue(first.workRows.value.single().active)
            val closeEntered = CompletableDeferred<Unit>()
            val closing = async { closeEntered.complete(Unit); first.closeAndDrain() }
            withTimeout(5000) { closeEntered.await() }
            assertFalse(closing.isCompleted)
            cleanupRelease.complete(Unit); closing.await(); controller = null
            assertEquals(DesktopWorkPhase.CANCELLED, first.workRows.value.single().phase)
            assertFalse(first.stopTask(first.workRows.value.single().id))
            assertFalse(Files.exists(root.resolve(image.await())))
            val interrupted = store.tasks().single { it.id != imported.id }
            assertEquals(MomentTaskStatus.FAILED, interrupted.status)
            assertEquals("synthetic-completed-stage", store.posts().single().generationCheckpoint)
            assertEquals(imported, store.tasks().first { it.id == imported.id })
            val second = create(); controller = second
            withTimeout(10_000) { second.state.first { it.tasks.size == 2 } }
            assertEquals(1, calls.get()); assertFalse(second.state.value.scheduling)
        } finally {
            cleanupRelease.complete(Unit)
            controller?.closeAndDrain(); container.close(); parent.toFile().deleteRecursively()
        }
    }
}
