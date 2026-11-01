package com.example.chatbar.desktop

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopTrayControllerTest {
    private class Backend(private val supported: Boolean = true) : DesktopTrayBackend {
        lateinit var actions: DesktopTrayActions
        val messages = MutableStateFlow<List<Pair<DesktopWorkKind, DesktopWorkPhase>>>(emptyList())
        var detached = false
        override suspend fun attach(actions: DesktopTrayActions): Boolean { this.actions = actions; return supported }
        override suspend fun notify(kind: DesktopWorkKind, phase: DesktopWorkPhase) { messages.update { it + (kind to phase) } }
        override suspend fun detach() { detached = true }
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
