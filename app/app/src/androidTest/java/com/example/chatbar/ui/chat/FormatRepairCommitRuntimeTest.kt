package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real repair stream -> real ChatViewModel -> JSON storage, with deterministic local HTTP barriers. */
class FormatRepairCommitRuntimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private data class Fixture(val app: ChatBarApp, val vm: ChatViewModel, val session: ChatSession,
        val anchor: ChatMessage, val other: ChatMessage, val release: CountDownLatch)

    @Test fun repairCompletionDoesNotRestoreImageDeletedDuringRequest() = withFixture { f ->
        deleteImage(f, f.anchor)
        finish(f)
        val current = cold(f, f.anchor.id)!!
        assertTrue("Repair must not restore a deleted image reference", current.images.isEmpty())
        assertEquals(f.anchor.content, current.content)
        assertNull(current.formatRepairNotice)
    }

    @Test fun repairCompletionDoesNotResurrectDeletedSource() = withFixture { f ->
        runBlocking { f.app.chatRepository.deleteMessage(f.anchor.id, f.session.id) }
        finish(f)
        assertNull("A stale repair must not recreate its deleted source", cold(f, f.anchor.id))
        assertNotNull(cold(f, f.other.id))
    }

    @Test fun repairFailureDoesNotOverwriteConcurrentImageDeletion() = withFixture(fail = true) { f ->
        deleteImage(f, f.anchor)
        finish(f)
        val current = cold(f, f.anchor.id)!!
        assertTrue("Even the failure-notice write must reject a stale source", current.images.isEmpty())
        assertEquals(f.anchor.content, current.content)
        assertNull(current.formatRepairNotice)
    }

    @Test fun otherMessageImageDeletionDoesNotBlockRepairOrLoseItsRecoveryPair() = withFixture { f ->
        deleteImage(f, f.other)
        finish(f)
        val current = cold(f, f.anchor.id)!!
        assertEquals("Synthetic repaired text", current.displayContent)
        assertEquals(f.anchor.content, current.formatRepairNotice!!.originalContent)
        assertEquals(current.displayContent, current.formatRepairNotice!!.targetContent)
        assertEquals(f.anchor.images, current.images)
        val other = cold(f, f.other.id)!!
        assertTrue(other.images.isEmpty())
        assertEquals(f.other.content, other.content)
    }

    private fun deleteImage(f: Fixture, message: ChatMessage) {
        rule.runOnIdle { f.vm.deleteImage(message.id, message.images.single()) }
        rule.waitUntil(15000) {
            runBlocking { f.app.chatRepository.getMessage(message.id, f.session.id)?.images?.isEmpty() == true }
        }
        assertTrue("Repair is still in flight while the separate image action commits", f.vm.isResponding.value)
    }

    private fun finish(f: Fixture) {
        f.release.countDown()
        rule.waitUntil(15000) { !f.vm.isResponding.value }
    }

    private fun cold(f: Fixture, id: String): ChatMessage? = runBlocking {
        ChatRepository(JsonFileStorage(f.app)).getMessage(id, f.session.id)
    }

    private fun withFixture(fail: Boolean = false, block: (Fixture) -> Unit) {
        val app = ChatBarApp.instance
        check(app.packageName == "com.example.chatbar.repaircommit.r1")
        runBlocking { withTimeout(30000) { app.dependenciesReady.first { it } } }
        // dependenciesReady publishes service construction; the cold-start model/settings
        // migrations run separately and must finish before installing this test's configuration.
        runBlocking { withTimeout(30000) { app.settingsRepository.isInitialized.first { it } } }
        rule.setContent { ChatBarTheme { CbText("Synthetic format repair commit checks") } }
        val settings = runBlocking { app.settingsRepository.getAppSettings() }
        val store = ViewModelStore()
        val seen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        val event = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", "Synthetic repaired text") })
            put("finish_reason", "stop")
        }) }) }.toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(request.path == "/v1/chat/completions")
                check(request.getHeader("Authorization") == "Bearer synthetic-repair-test-key")
                seen.countDown()
                check(release.await(30, TimeUnit.SECONDS))
                return if (fail) MockResponse().setResponseCode(400).setBody("synthetic repair failure")
                else MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: $event\n\ndata: [DONE]\n\n")
            }
        }
        server.start()
        val model = ModelConfig.create("Synthetic repair model", server.url("/v1").toString(),
            "synthetic-repair-test-key", "synthetic-model")
        val card = CharacterCard.create("Synthetic repair card ${UUID.randomUUID()}")
        val session = ChatSession.create(card.id, "Synthetic repair commit").copy(modelId = model.id,
            longTermMemoryEnabled = false, automaticImageGenerationEnabled = false)
        val imageFiles = List(2) { File(app.filesDir, "images/repair-commit-${UUID.randomUUID()}.png").also {
            it.parentFile!!.mkdirs()
            it.writeBytes(java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR4nGP4zwAE/xkaAAt+An+rqPSSAAAAAElFTkSuQmCC"))
        } }
        val anchor = ChatMessage.create(session.id, MessageRole.ASSISTANT, "Synthetic original text",
            images = listOf(imageFiles[0].absolutePath), orderKey = MESSAGE_ORDER_STEP)
        val other = ChatMessage.create(session.id, MessageRole.ASSISTANT, "Synthetic other message",
            images = listOf(imageFiles[1].absolutePath), orderKey = 2 * MESSAGE_ORDER_STEP)
        try {
            runBlocking {
                app.modelRepository.saveModel(model)
                app.characterRepository.save(card)
                app.chatRepository.createSession(session)
                app.chatRepository.addMessage(anchor)
                app.chatRepository.addMessage(other)
                app.settingsRepository.saveAppSettings(settings.copy(allowCleartextModelApi = true,
                    assistantSegmentedBubblesEnabled = true, formatRepairModelId = model.id))
            }
            lateinit var vm: ChatViewModel
            rule.runOnIdle { vm = ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java] }
            rule.waitUntil(15000) { vm.session.value?.id == session.id && vm.messages.value.any { it.id == anchor.id } }
            rule.runOnIdle { vm.repairMessageFormat(anchor.id) }
            val started = seen.await(15, TimeUnit.SECONDS)
            assertTrue("Actual loopback request must start: responding=${vm.isResponding.value}, " +
                "segmented=${app.settingsRepository.currentAppSettings.assistantSegmentedBubblesEnabled}, " +
                "repairModelMatches=${app.settingsRepository.currentAppSettings.formatRepairModelId == model.id}, " +
                "notice=${cold(Fixture(app, vm, session, anchor, other, release), anchor.id)?.formatRepairNotice?.kind}", started)
            block(Fixture(app, vm, session, anchor, other, release))
        } finally {
            release.countDown()
            rule.runOnIdle { store.clear() }
            runBlocking {
                app.settingsRepository.saveAppSettings(settings)
                app.voiceMessageRepository.deleteForSession(session.id)
                app.chatRepository.deleteMessagesForSession(session.id)
                app.chatRepository.deleteSessionRecord(session.id)
            }
            imageFiles.forEach { it.delete() }
            server.shutdown()
        }
    }
}
