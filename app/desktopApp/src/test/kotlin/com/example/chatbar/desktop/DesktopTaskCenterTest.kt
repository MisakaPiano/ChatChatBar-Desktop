package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ChatSession
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopTaskCenterTest {
    @Test fun `memory row stops its own session while another session is open and running`(): Unit = runBlocking {
        fixture { app -> supervisorScope {
            app.settingsRepository.saveAppSettings(AppSettings())
            val a = app.chatRepository.createSession(ChatSession.create("synthetic-card", "A").copy(longTermMemoryEnabled = false))
            val b = app.chatRepository.createSession(ChatSession.create("synthetic-card", "B").copy(longTermMemoryEnabled = false))
            app.chatRepository.updateSessionDraft(a.id, "Retained A"); app.chatRepository.updateSessionDraft(b.id, "Retained B")
            val memory = app.memoryController
            memory.open(b.id)
            withTimeout(10000) { combine(memory.state, memory.coordinator.runningSessions) { state, running ->
                !state.operationBusy && running.isEmpty()
            }.first { it } }
            val enteredA = CompletableDeferred<Unit>(); val enteredB = CompletableDeferred<Unit>(); val releaseB = CompletableDeferred<Unit>()
            val actionA = checkNotNull(memory.coordinator.launchSessionAction(a.id) { enteredA.complete(Unit); awaitCancellation() })
            val actionB = checkNotNull(memory.coordinator.launchSessionAction(b.id) { enteredB.complete(Unit); releaseB.await() })
            try {
                withTimeout(5000) { enteredA.await(); enteredB.await() }
                val row = withTimeout(5000) { app.taskCenter.state.first { it.rows.count { row -> row.kind == DesktopWorkKind.MEMORY } == 2 } }
                    .rows.single { it.kind == DesktopWorkKind.MEMORY && it.target?.sessionId == a.id }
                assertEquals(b.id, memory.state.value.sessionId)
                assertTrue(app.taskCenter.requestStop(row.id))
                withTimeout(10000) { actionA.join(); memory.coordinator.runningSessions.first { a.id !in it } }
                assertTrue(actionB.isActive); assertEquals(b.id, memory.state.value.sessionId)
                assertEquals("Retained A", app.chatRepository.getSessionDraft(a.id)); assertEquals("Retained B", app.chatRepository.getSessionDraft(b.id))
                assertTrue(app.chatRepository.getMessagesReadOnly(a.id).isEmpty()); assertTrue(app.chatRepository.getMessagesReadOnly(b.id).isEmpty())
            } finally { releaseB.complete(Unit); actionA.cancelAndJoin(); actionB.cancelAndJoin() }
        } }
    }

    @Test fun `stopping the actual document row waits for its original drain without stopping the other document`(): Unit = runBlocking {
        fixture { app -> supervisorScope {
            val aEntered = CompletableDeferred<Unit>(); val bEntered = CompletableDeferred<Unit>()
            val drainA = CompletableDeferred<Unit>(); val finishB = CompletableDeferred<Unit>()
            val a = launch { app.taskRuntime.withDocumentPreparation("card", "a") {
                aEntered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { drainA.await() } }
            } }
            val b = launch { app.taskRuntime.withDocumentPreparation("card", "b") { bEntered.complete(Unit); finishB.await() } }
            try {
                withTimeout(5000) { aEntered.await(); bEntered.await() }
                val rows = withTimeout(5000) { app.taskCenter.state.first { it.rows.count { row -> row.active } == 2 } }.rows
                val selected = rows.single { it.target?.subjectId == "a" }
                assertEquals(DesktopWorkKind.DOCUMENT, selected.kind)
                assertEquals(1, app.taskRuntime.ownedWork.value.count { "runtime/${it.taskId}" == selected.id })
                assertTrue(app.taskCenter.requestStop(selected.id))
                withTimeout(5000) { app.taskCenter.state.first { it.rows.single { row -> row.id == selected.id }.stopRequested } }
                assertTrue(app.taskRuntime.hasDocumentPreparation("card", "a"))
                assertTrue(app.taskRuntime.hasDocumentPreparation("card", "b")); assertTrue(b.isActive)
                assertFalse(a.isCompleted)
                drainA.complete(Unit); a.join()
                withTimeout(5000) { app.taskCenter.state.first { it.rows.any { row -> row.id == selected.id && row.phase == DesktopWorkPhase.CANCELLED } } }
                assertTrue(b.isActive); finishB.complete(Unit); b.join()
            } finally { drainA.complete(Unit); finishB.complete(Unit); a.cancelAndJoin(); b.cancelAndJoin() }
        } }
    }

    @Test fun `closing the observer leaves actual card design alive and completion remains candidate work only`(): Unit = runBlocking {
        fixture { app -> supervisorScope {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val work = async { app.taskRuntime.withCardDesign("draft") { calls++; entered.complete(Unit); release.await(); "synthetic candidate not applied" } }
            try {
                withTimeout(5000) { entered.await(); app.taskCenter.state.first { it.rows.any { row -> row.kind == DesktopWorkKind.CARD_DESIGN && row.active } } }
                app.taskCenter.closeAndDrain()
                assertTrue(work.isActive); assertEquals(1, calls)
                release.complete(Unit); assertEquals("synthetic candidate not applied", work.await())
                assertEquals(DesktopTaskStatus.COMPLETED, app.taskRuntime.ownedWork.value.single().status)
                assertTrue(app.formatCardRepository.getAll().isEmpty())
            } finally { release.complete(Unit); work.cancelAndJoin() }
        } }
    }

    @Test fun `only observed active to actual terminal transitions notify and cancelled request does not fabricate completion`(): Unit = runBlocking {
        val center = DesktopTaskCenter(Dispatchers.Unconfined)
        val rows = MutableStateFlow(listOf(DesktopWorkRow("old", DesktopWorkKind.CHAT, DesktopWorkPhase.COMPLETED, 1)))
        val notices = mutableListOf<DesktopWorkNotice>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { center.notices.collect { notices += it } }
        try {
            var stopped: String? = null
            center.watch("source", rows, stop = { stopped = it; true })
            yield(); assertTrue(notices.isEmpty())
            rows.value = listOf(DesktopWorkRow("real", DesktopWorkKind.VOICE, DesktopWorkPhase.RUNNING, 2, canStop = true))
            assertTrue(center.requestStop("source/real")); assertEquals("real", stopped)
            assertTrue(center.state.value.rows.single().active)
            assertTrue(center.state.value.rows.single().stopRequested); assertTrue(notices.isEmpty())
            rows.value = rows.value.map { it.copy(phase = DesktopWorkPhase.CANCELLED, canStop = false) }
            withTimeout(5000) { center.state.first { it.rows.single().phase == DesktopWorkPhase.CANCELLED } }; yield()
            assertEquals(listOf(DesktopWorkNotice(DesktopWorkKind.VOICE, DesktopWorkPhase.CANCELLED, null)), notices)
            assertFalse(center.requestStop("source/real"))
        } finally { collector.cancelAndJoin(); center.closeAndDrain() }
    }

    private suspend fun fixture(block: suspend (DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("task-center-synthetic-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        try { block(app) } finally { app.close(); parent.toFile().deleteRecursively() }
    }
}
