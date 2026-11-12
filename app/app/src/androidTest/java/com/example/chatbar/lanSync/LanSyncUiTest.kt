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
            withTimeout(20_000) { while (app.chatRepository.getSession("ccb-lan-preview-sample-session") == null || runtime.state.value.busy) delay(100) }
            val session = requireNotNull(app.chatRepository.getSession("ccb-lan-preview-sample-session"))
            assertFalse(session.longTermMemoryEnabled)
            assertNull(session.modelId)
            val message = app.chatRepository.getMessagesReadOnly(session.id).single()
            assertEquals(2, message.alternativeVersionIds.size)
            assertEquals(2, message.alternatives.size)
            assertEquals(message.alternativeVersionIds[message.currentAlternativeIndex], message.currentAlternativeVersionId)
            val image = requireNotNull(BitmapFactory.decodeFile(message.images.single()))
            image.recycle()
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertFalse(it.isFinishing); assertFalse(it.isDestroyed) }
            assertEquals(message, app.chatRepository.getMessagesReadOnly(session.id).single())
        } finally { scenario.close() }
    }
}
