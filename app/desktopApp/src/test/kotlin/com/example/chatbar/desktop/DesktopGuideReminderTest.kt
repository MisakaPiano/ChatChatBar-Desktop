package com.example.chatbar.desktop

import com.example.chatbar.data.operation.AppDataOperationGate
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopGuideReminderTest {
    @Test fun `missing and legacy preference reads do not persist and defer lasts only this process`() = runTest {
        val parent = Files.createTempDirectory("guide-reminder-"); val root = parent.resolve("data")
        val gate = DesktopDataOperationCoordinator(); val store = DesktopSettingsStore(root, gate)
        try {
            val controller = DesktopGuideReminderController(store)
            controller.load(); assertTrue(controller.state.value.pending); assertFalse(Files.exists(root))
            controller.deferForRun(); assertFalse(controller.state.value.pending); assertFalse(Files.exists(root))
            val reopened = DesktopGuideReminderController(store); reopened.load(); assertTrue(reopened.state.value.pending)
            store.updateLatest { it }
            val raw = Json.parseToJsonElement(Files.readString(store.settingsPath)).jsonObject.toMutableMap()
            raw.remove("guideAcknowledgedVersion")
            Files.writeString(store.settingsPath, JsonObject(raw).toString())
            val oldBytes = Files.readAllBytes(store.settingsPath)
            val legacy = DesktopGuideReminderController(store); legacy.load()
            assertEquals(0, legacy.state.value.acknowledgedVersion); assertTrue(legacy.state.value.pending)
            assertContentEquals(oldBytes, Files.readAllBytes(store.settingsPath))
        } finally { gate.closeAndDrain(); parent.toFile().deleteRecursively() }
    }

    @Test fun `explicit suppression preserves latest preferences unknown fields and future guide versions`() = runTest {
        val root = Files.createTempDirectory("guide-reminder-latest-")
        val gate = DesktopDataOperationCoordinator(); val store = DesktopSettingsStore(root, gate)
        try {
            store.updateLatest { it }
            val raw = Json.parseToJsonElement(Files.readString(store.settingsPath)).jsonObject.toMutableMap()
            raw["future-setting"] = buildJsonObject { put("keep", true) }
            Files.writeString(store.settingsPath, JsonObject(raw).toString())
            val controller = DesktopGuideReminderController(store); controller.load()
            store.updateLatest { it.copy(uiLanguage = DesktopUiLanguage.EN, taskNotifications = false) }
            controller.suppressFutureReminder()
            assertFalse(controller.state.value.pending); assertNull(controller.state.value.problem)
            val saved = assertIs<DesktopSettingsLoadResult.Loaded>(store.load()).document.settings
            assertEquals(CURRENT_DESKTOP_GUIDE_VERSION, saved.guideAcknowledgedVersion)
            assertEquals(DesktopUiLanguage.EN, saved.uiLanguage); assertFalse(saved.taskNotifications)
            assertEquals(raw["future-setting"], Json.parseToJsonElement(Files.readString(store.settingsPath)).jsonObject["future-setting"])
            val reopened = DesktopGuideReminderController(store); reopened.load(); assertFalse(reopened.state.value.pending)
            val stale = DesktopGuideReminderController(store)
            store.updateLatest { it.copy(guideAcknowledgedVersion = 0) }; stale.load()
            store.updateLatest { it.copy(guideAcknowledgedVersion = 9) }; stale.suppressFutureReminder()
            assertEquals(9, stale.state.value.acknowledgedVersion)
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `invalid settings and failed atomic save preserve originals and suppression remains retryable`() = runTest {
        val root = Files.createTempDirectory("guide-reminder-refusal-"); val gate = DesktopDataOperationCoordinator()
        val working = DesktopSettingsStore(root, gate)
        var reject = true
        val failing = DesktopSettingsStore(root, gate, { "guide-fixture" }) { temporary, target ->
            if (reject) throw IOException("private-path-key")
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        try {
            working.updateLatest { it }
            val bytes = Files.readAllBytes(working.settingsPath)
            val controller = DesktopGuideReminderController(failing); controller.load(); controller.suppressFutureReminder()
            assertEquals(DesktopGuideReminderProblem.SAVE, controller.state.value.problem)
            assertTrue(controller.state.value.pending); assertFalse(controller.state.value.busy)
            assertContentEquals(bytes, Files.readAllBytes(working.settingsPath))
            reject = false; controller.suppressFutureReminder(); assertFalse(controller.state.value.pending)
            for (value in listOf(JsonPrimitive(-1), JsonPrimitive("invalid"))) {
                val invalid = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject.toMutableMap()
                invalid["guideAcknowledgedVersion"] = value
                Files.writeString(working.settingsPath, JsonObject(invalid).toString())
                val original = Files.readAllBytes(working.settingsPath)
                val reader = DesktopGuideReminderController(working); reader.load()
                assertEquals(DesktopGuideReminderProblem.READ, reader.state.value.problem)
                reader.suppressFutureReminder(); assertEquals(DesktopGuideReminderProblem.SAVE, reader.state.value.problem)
                assertContentEquals(original, Files.readAllBytes(working.settingsPath))
            }
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `cancellation and closed admission never acknowledge the guide or replace settings`() = runTest {
        val root = Files.createTempDirectory("guide-reminder-cancel-"); val gate = DesktopDataOperationCoordinator()
        val store = DesktopSettingsStore(root, gate)
        try {
            store.updateLatest { it }; val before = Files.readAllBytes(store.settingsPath)
            val ready = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val blockedGate = object : AppDataOperationGate {
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                    ready.complete(Unit); release.await(); return operation()
                }
            }
            val controller = DesktopGuideReminderController(DesktopSettingsStore(root, blockedGate))
            val pending = launch { controller.suppressFutureReminder() }
            ready.await(); pending.cancelAndJoin()
            assertFalse(controller.state.value.busy); assertEquals(0, controller.state.value.acknowledgedVersion)
            assertNull(controller.state.value.problem); assertContentEquals(before, Files.readAllBytes(store.settingsPath))
            val closed = DesktopGuideReminderController(store); closed.load(); gate.closeAndDrain()
            closed.suppressFutureReminder(); assertEquals(DesktopGuideReminderProblem.SAVE, closed.state.value.problem)
            assertTrue(closed.state.value.pending); assertContentEquals(before, Files.readAllBytes(store.settingsPath))
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
