package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopLanSyncMomentsMaintenanceTest {
    @Test fun retainedLocalGrantsCannotAuthorizeChangedImportedOrResurrectedTasks() = runBlocking {
        withTimeout(10000) {
            val parent = Files.createTempDirectory("lan-moment-maintenance-")
            val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
                DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
                secretStoreFactory = { InMemoryDesktopSecretStore() })
            var owner: DesktopMomentsController? = null
            try {
                container.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
                val store = DesktopMomentStore(container.jsonFileStorage, container.dataOperationCoordinator)
                val local = DesktopMomentAdmissionStore(container.appDataRoot, parent.resolve("private"))
                val original = listOf("unchanged", "changed", "resurrected").map {
                    MomentTask(it, "historical-card", "historical-session", Long.MAX_VALUE, createdAt = 1, updatedAt = 1)
                }
                original.forEach { store.saveTask(it) }
                local.save(DesktopMomentAdmission(true, original.associate { it.id to local.taskHash(it) }))
                owner = DesktopMomentsController(store, container.settingsRepository, container.characterRepository,
                    container.chatRepository, container.dataOperationCoordinator, container.taskRuntime,
                    DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("No provider call authorized") },
                    admissionStore = local)
                val controller = requireNotNull(owner)
                // Wait for restoration, without bypassing the real owner initialization barrier.
                controller.withLanSyncMaintenance(true) { assertEquals(original.map { it.id }.toSet(), local.load().tasks.keys) }
                store.saveTask(original[2].copy(status = MomentTaskStatus.COMPLETED))
                val incoming = original[0].copy(id = "incoming", requiresLocalScheduleApproval = true)
                val interrupted = incoming.copy(id = "interrupted", status = MomentTaskStatus.RUNNING)
                controller.withLanSyncMaintenance(true) {
                    assertFalse(local.load().active)
                    assertEquals(setOf("unchanged", "changed"), local.load().tasks.keys)
                    store.saveTask(original[1].copy(scheduledAt = Long.MAX_VALUE - 1, requiresLocalScheduleApproval = true))
                    store.saveTask(original[2]); store.saveTask(incoming); store.saveTask(interrupted)
                }
                assertEquals(setOf("unchanged"), local.load().tasks.keys)
                assertTrue(local.load().active)
                assertEquals(MomentTaskStatus.RUNNING, store.tasks().single { it.id == "interrupted" }.status)
                assertTrue(store.tasks().single { it.id == "incoming" }.requiresLocalScheduleApproval)
                assertEquals(setOf("unchanged"), local.load().tasks.keys)
            } finally { owner?.closeAndDrain(); container.close(); parent.toFile().deleteRecursively() }
        }
    }

    @Test fun admissionStorageFailureRefusesPublicationAndLeavesSchedulingPaused() = runBlocking {
        withTimeout(10000) {
            val parent = Files.createTempDirectory("lan-moment-storage-refusal-")
            val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
                DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
                secretStoreFactory = { InMemoryDesktopSecretStore() })
            var owner: DesktopMomentsController? = null
            try {
                container.settingsRepository.initialize()
                val blocked = parent.resolve("blocked-private-home")
                Files.writeString(blocked, "synthetic blocker")
                owner = DesktopMomentsController(DesktopMomentStore(container.jsonFileStorage, container.dataOperationCoordinator),
                    container.settingsRepository, container.characterRepository, container.chatRepository,
                    container.dataOperationCoordinator, container.taskRuntime,
                    DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("No provider call authorized") },
                    admissionStore = DesktopMomentAdmissionStore(container.appDataRoot, blocked))
                val controller = requireNotNull(owner)
                var published = false
                assertEquals("SYNC_MOMENT_ADMISSION_STORAGE", assertFailsWith<IllegalStateException> {
                    controller.withLanSyncMaintenance(true) { published = true }
                }.message)
                assertFalse(published)
                assertFalse(controller.state.value.scheduling)
                assertNotNull(controller.state.value.error)
                container.taskRuntime.withIdleMaintenance {}
            } finally { owner?.closeAndDrain(); container.close(); parent.toFile().deleteRecursively() }
        }
    }
}
