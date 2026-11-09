package com.example.chatbar.desktop

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopTrayControllerTest {
    private class Backend(private val supported: Boolean = true) : DesktopTrayBackend {
        lateinit var actions: DesktopTrayActions
        val messages = MutableStateFlow<List<Pair<DesktopWorkKind, DesktopWorkPhase>>>(emptyList())
        val summary = MutableStateFlow<DesktopTrayTaskSummary?>(null)
        var failSummary = false
        var detached = false
        override suspend fun attach(actions: DesktopTrayActions): Boolean { this.actions = actions; return supported }
        override suspend fun notify(kind: DesktopWorkKind, phase: DesktopWorkPhase) { messages.update { it + (kind to phase) } }
        override suspend fun detach() { detached = true }
        override suspend fun updateSummary(summary: DesktopTrayTaskSummary) {
            if (failSummary) error("SYNTHETIC_PRIVATE_DETAIL")
            this.summary.value = summary
        }
    }

    @Test fun `unsupported tray never hides the window and exit is an explicit original callback`() = runBlocking {
        fixture { center, store ->
            val backend = Backend(false); val tray = DesktopTrayController(center, store, backend)
            var exits = 0
            try {
                tray.initialize({}, { exits++ })
                tray.hide(); assertTrue(tray.state.value.windowVisible); assertFalse(tray.state.value.available)
                assertEquals(0, exits)
                backend.actions.exit(); assertEquals(1, exits)
            } finally { tray.closeAndDrain() }
            assertTrue(backend.detached)
        }
    }

    @Test fun `explicit hide preserves tasks and restoration opens the existing task center`() = runBlocking {
        fixture { center, store ->
            val backend = Backend(); val tray = DesktopTrayController(center, store, backend)
            val rows = MutableStateFlow(listOf(DesktopWorkRow("original", DesktopWorkKind.CHAT, DesktopWorkPhase.RUNNING, 1)))
            center.watch("actual", rows)
            var shows = 0; var exits = 0
            try {
                tray.initialize({ shows++ }, { exits++ })
                withTimeout(5000) { center.state.first { it.rows.size == 1 } }
                tray.hide(); assertFalse(tray.state.value.windowVisible)
                assertTrue(center.state.value.rows.single().active); assertEquals(0, exits)
                val before = center.openRequests.value
                backend.actions.tasks()
                assertTrue(tray.state.value.windowVisible); assertEquals(1, shows)
                assertEquals(before + 1, center.openRequests.value)
                tray.prepareExit(); tray.hide(); assertTrue(tray.state.value.windowVisible)
            } finally { tray.closeAndDrain() }
            assertTrue(center.state.value.rows.single().active)
        }
    }

    @Test fun `notification preference persists without replacing unrelated settings and sends fixed terminal metadata only`() = runBlocking {
        fixture { center, store ->
            store.updateLatest { it.copy(colorStyle = DesktopColorStyle.CCB_NATIVE, taskNotifications = false) }
            val backend = Backend(); val tray = DesktopTrayController(center, store, backend)
            val initial = DesktopWorkRow("historical", DesktopWorkKind.DOCUMENT, DesktopWorkPhase.COMPLETED, 1,
                contextLabel = "Private document name")
            val rows = MutableStateFlow(listOf(initial)); center.watch("owner", rows)
            try {
                tray.initialize({}, {})
                withTimeout(5000) { center.state.first { it.rows.size == 1 } }
                assertFalse(tray.state.value.notificationsEnabled); assertTrue(backend.messages.value.isEmpty())
                tray.setNotifications(true)
                withTimeout(5000) { tray.state.first { it.notificationsEnabled } }
                val saved = assertIs<DesktopSettingsLoadResult.Loaded>(store.load()).document.settings
                assertEquals(DesktopColorStyle.CCB_NATIVE, saved.colorStyle); assertTrue(saved.taskNotifications)
                val active = initial.copy(id = "fresh", phase = DesktopWorkPhase.RUNNING)
                rows.value = listOf(initial, active)
                withTimeout(5000) { center.state.first { it.rows.any { row -> row.active } } }
                rows.value = listOf(initial, active.copy(phase = DesktopWorkPhase.COMPLETED))
                withTimeout(5000) { backend.messages.first { it.size == 1 } }
                assertEquals(listOf(DesktopWorkKind.DOCUMENT to DesktopWorkPhase.COMPLETED), backend.messages.value)
                tray.setNotifications(false)
                withTimeout(5000) { tray.state.first { !it.notificationsEnabled } }
                assertFalse(assertIs<DesktopSettingsLoadResult.Loaded>(store.load()).document.settings.taskNotifications)
            } finally { tray.closeAndDrain() }
        }
    }

    @Test fun `main close preference defaults off persists and never changes explicit tray exit`() = runBlocking {
        fixture { center, store ->
            val backend = Backend(); val tray = DesktopTrayController(center, store, backend)
            var exits = 0
            try {
                tray.initialize({}, { exits++ })
                assertFalse(tray.hideOnWindowClose()); assertTrue(tray.state.value.windowVisible)
                tray.setCloseWindowToTray(true)
                withTimeout(5000) { tray.state.first { it.closeWindowToTray } }
                assertTrue(assertIs<DesktopSettingsLoadResult.Loaded>(store.load()).document.settings.closeWindowToTray)
                assertTrue(tray.hideOnWindowClose()); assertFalse(tray.state.value.windowVisible)
                assertEquals(0, exits)
                backend.actions.exit(); assertEquals(1, exits) // The actual Main callback owns guard/drain, never hide.
                tray.show(); tray.setCloseWindowToTray(false)
                withTimeout(5000) { tray.state.first { !it.closeWindowToTray } }
                assertFalse(tray.hideOnWindowClose()); assertTrue(tray.state.value.windowVisible)
            } finally { tray.closeAndDrain() }
            val reopened = DesktopTrayController(center, store, Backend(false))
            try {
                store.updateLatest { it.copy(closeWindowToTray = true) }
                reopened.initialize({}, {})
                assertTrue(reopened.state.value.closeWindowToTray)
                assertFalse(reopened.hideOnWindowClose()); assertTrue(reopened.state.value.windowVisible)
            } finally { reopened.closeAndDrain() }
        }
    }

    @Test fun `failed tray preference write retains effective choice and corrupt source bytes`() = runBlocking {
        fixture { center, store ->
            store.updateLatest { it.copy(closeWindowToTray = true) }
            val tray = DesktopTrayController(center, store, Backend())
            try {
                tray.initialize({}, {})
                val corrupt = "{invalid settings}".toByteArray()
                Files.write(store.settingsPath, corrupt)
                tray.setCloseWindowToTray(false)
                withTimeout(5000) { tray.state.first { it.error != null } }
                assertTrue(tray.state.value.closeWindowToTray)
                assertContentEquals(corrupt, Files.readAllBytes(store.settingsPath))
            } finally { tray.closeAndDrain() }
        }
    }

    @Test fun `tray counts actual owner waits stops and unconfirmed rows then updates on completion without private labels`() = runBlocking {
        fixture { center, store ->
            val backend = Backend(); val tray = DesktopTrayController(center, store, backend)
            val running = DesktopWorkRow("one", DesktopWorkKind.CHAT, DesktopWorkPhase.RUNNING, 1, contextLabel = "PRIVATE_BODY")
            val waiting = running.copy(id = "two", kind = DesktopWorkKind.VOICE, phase = DesktopWorkPhase.WAITING)
            val stopping = running.copy(id = "three", kind = DesktopWorkKind.IMAGE, stopRequested = true)
            val unknown = running.copy(id = "four", phase = DesktopWorkPhase.UNCONFIRMED)
            val rows = MutableStateFlow(listOf(running, waiting, stopping, unknown)); center.watch("actual", rows)
            try {
                tray.initialize({}, {})
                val first = withTimeout(5000) { backend.summary.filterNotNull().first { it.active == 3 } }
                assertEquals(1, first.running); assertEquals(1, first.waiting); assertEquals(1, first.stopping); assertEquals(1, first.unconfirmed)
                assertEquals(mapOf(DesktopWorkKind.CHAT to 1, DesktopWorkKind.VOICE to 1, DesktopWorkKind.IMAGE to 1), first.kinds)
                assertFalse("PRIVATE_BODY" in first.tooltip || first.menuLines.any { "PRIVATE_BODY" in it })
                assertTrue(first.tooltip.length <= 63); assertEquals(5, first.menuLines.size)
                tray.hide(); assertFalse(tray.state.value.windowVisible)
                rows.value = listOf(running.copy(phase = DesktopWorkPhase.COMPLETED), waiting.copy(phase = DesktopWorkPhase.CANCELLED), unknown)
                val final = withTimeout(5000) { backend.summary.filterNotNull().first { it.active == 0 && it.unconfirmed == 1 } }
                assertEquals(0, final.running); assertEquals(0, final.waiting); assertEquals(0, final.stopping)
                assertFalse(final.stateUnavailable)
                assertTrue(desktopTrayTaskSummary(DesktopTaskCenterState(error = "PRIVATE_FAILURE")).stateUnavailable)
            } finally { tray.closeAndDrain() }
        }
    }

    @Test fun `summary backend failure restores visible window instead of hiding an unreachable task state`() = runBlocking {
        fixture { center, store ->
            store.updateLatest { it.copy(closeWindowToTray = true) }
            val backend = Backend(); val tray = DesktopTrayController(center, store, backend)
            val rows = MutableStateFlow<List<DesktopWorkRow>>(emptyList()); center.watch("actual", rows)
            try {
                tray.initialize({}, {}); assertTrue(tray.hideOnWindowClose())
                backend.failSummary = true
                rows.value = listOf(DesktopWorkRow("new", DesktopWorkKind.CHAT, DesktopWorkPhase.RUNNING, 1))
                withTimeout(5000) { tray.state.first { !it.available && it.windowVisible } }
                assertFalse(tray.hideOnWindowClose())
                assertFalse(tray.state.value.error.orEmpty().contains("SYNTHETIC_PRIVATE_DETAIL"))
                assertTrue(center.state.value.rows.single().active)
            } finally { tray.closeAndDrain() }
        }
    }

    private suspend fun fixture(body: suspend (DesktopTaskCenter, DesktopSettingsStore) -> Unit) {
        val root = Files.createTempDirectory("desktop-tray-test-")
        val gate = DesktopDataOperationCoordinator()
        val center = DesktopTaskCenter()
        try { body(center, DesktopSettingsStore(root, gate)) }
        finally {
            center.closeAndDrain(); gate.closeAndDrain()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
