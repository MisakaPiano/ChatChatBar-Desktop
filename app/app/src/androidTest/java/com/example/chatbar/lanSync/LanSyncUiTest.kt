package com.example.chatbar.lanSync

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Fresh cloud-only preview application, no user profile, accounts, or provider calls. */
class LanSyncUiTest {
    @Test fun syncActivityReopensAndOfflineSampleUsesActualRepositories() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(60_000) { while (!app.lanSyncStartupReady) {
            check(!app.lanSyncRecoveryFailed) { "SYNTHETIC_STARTUP_RECOVERY_FAILED" }; delay(100)
        } }
        val scenario = ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java))
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertFalse(it.isFinishing); assertFalse(it.isDestroyed) }
            val runtime = app.lanSyncOwner.runtime
            runtime.createPreviewSample()
            withTimeout(20_000) { while (app.chatRepository.getSession("ccb-lan-preview-sample-v2-session") == null || runtime.state.value.busy) delay(100) }
            val session = requireNotNull(app.chatRepository.getSession("ccb-lan-preview-sample-v2-session"))
            assertFalse(session.longTermMemoryEnabled)
            assertNull(session.modelId)
            val message = app.chatRepository.getMessagesReadOnly(session.id).single()
            assertEquals(2, message.alternativeVersionIds.size)
            assertEquals(2, message.alternatives.size)
            assertEquals(message.alternativeVersionIds[message.currentAlternativeIndex], message.currentAlternativeVersionId)
            val image = requireNotNull(BitmapFactory.decodeFile(message.images.single()))
            image.recycle()
            val post = requireNotNull(app.momentRepository.getPost("ccb-lan-preview-sample-v2-post"))
            assertEquals(2, post.desktopImageVersions.size)
            val colours = post.desktopImageVersions.map { version ->
                val bitmap = requireNotNull(BitmapFactory.decodeFile(version.imagePath))
                try { assertEquals(32, bitmap.width); assertEquals(32, bitmap.height); bitmap.getPixel(0, 0) }
                finally { bitmap.recycle() }
            }
            assertNotEquals(colours[0], colours[1])
            val plans = app.momentRepository.getAllTasks().filter { it.id.startsWith("ccb-lan-preview-sample-v2-") }
            assertEquals(2, plans.size)
            assertTrue(plans.all { it.requiresLocalScheduleApproval })
            assertEquals(setOf(com.example.chatbar.data.local.entity.MomentTaskStatus.PENDING,
                com.example.chatbar.data.local.entity.MomentTaskStatus.RUNNING), plans.map { it.status }.toSet())
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertFalse(it.isFinishing); assertFalse(it.isDestroyed) }
            assertEquals(message, app.chatRepository.getMessagesReadOnly(session.id).single())
        } finally { scenario.close() }
    }
}
