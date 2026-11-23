package com.example.chatbar.ui.chat

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.lanSync.LanSyncActivity
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The ordinary composer entry against actual Android foreground and storage owners. */
class ChatOrdinarySendNativeTest {
    @Test
    fun anEmptyOrdinaryImageUriLeavesThePreviouslySelectedAttachmentIntact(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanSyncOrdinarySendFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        check(app.packageName in setOf("com.example.chatbar.lansync", "com.example.chatbar.lansyncnext"))
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        withTimeout(60_000) { while (!app.lanSyncStartupReady) delay(50) }
        check(!app.lanSyncOwner.hasGroupEvidence())
        val sessionId = UUID.randomUUID().toString()
        val cardId = UUID.randomUUID().toString()
        val sourceRoot = Files.createTempDirectory(app.cacheDir.toPath(), "synthetic-chat-image-source-")
        val valid = sourceRoot.resolve("selected.png")
        val empty = Files.createFile(sourceRoot.resolve("empty.png"))
        var selected: Path? = null
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try { Files.newOutputStream(valid).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            val original = Files.readAllBytes(valid)
            app.characterRepository.save(CharacterCard(cardId, "Synthetic image selection", createdAt = 1, updatedAt = 1))
            app.chatRepository.createSession(ChatSession(sessionId, cardId, "Synthetic image selection", createdAt = 1, updatedAt = 1))
            ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java)).use { scenario ->
                lateinit var owner: ChatViewModel
                scenario.onActivity { owner = ViewModelProvider(it, ChatViewModelFactory(sessionId))[ChatViewModel::class.java] }
                await { owner.draftLoaded.value }
                val copied = CompletableDeferred<String>()
                withContext(Dispatchers.Main) {
                    owner.copyUriToLocalFile(Uri.fromFile(valid.toFile())) { path ->
                        owner.addDraftImage(path)
                        copied.complete(path)
                    }
                }
                val selectedPath = Paths.get(withTimeout(30_000) { copied.await() })
                selected = selectedPath
                fun entries() = Files.newDirectoryStream(selectedPath.parent, "chat_img_*").use { it.toSet() }
                val before = entries()
                val wrongSuccess = CompletableDeferred<String>()
                withContext(Dispatchers.Main) {
                    owner.copyUriToLocalFile(Uri.fromFile(empty.toFile())) { wrongSuccess.complete(it) }
                }
                await { app.chatRepository.getMessagesReadOnly(sessionId).any { it.content.contains("所选图片为空") } }
                assertFalse(wrongSuccess.isCompleted)
                assertEquals(before, entries())
                assertEquals(listOf(selectedPath.toString()), owner.composerDraft.value.images)
                assertTrue(original.contentEquals(Files.readAllBytes(selectedPath)))
                assertTrue(original.contentEquals(Files.readAllBytes(valid)))
                assertEquals(0L, Files.size(empty))
            }
        } finally {
            app.chatRepository.deleteSession(sessionId)
            app.characterRepository.delete(cardId)
            selected?.let { Files.deleteIfExists(it) }
            check(sourceRoot.toFile().deleteRecursively())
        }
    }

    @Test
    fun maintenanceRefusalRetainsTheDraftAndOneRetryCommitsExactlyOnce() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("lanSyncOrdinarySendFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        check(app.packageName in setOf("com.example.chatbar.lansync", "com.example.chatbar.lansyncnext"))
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        withTimeout(60_000) { while (!app.lanSyncStartupReady) delay(50) }
        check(!app.lanSyncOwner.hasGroupEvidence())

        val sessionId = UUID.randomUUID().toString()
        val cardId = UUID.randomUUID().toString()
        val modelId = UUID.randomUUID().toString()
        val previousSettings = app.settingsRepository.getAppSettings()
        val images = app.filesDir.toPath().resolve("images")
        Files.createDirectories(images)
        val image = Files.createTempFile(images, "synthetic-ordinary-send-", ".png")
        val text = "Synthetic ordinary composer input"
        val answer = "Synthetic ordinary reply"
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                Files.newOutputStream(image).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            val originalImage = Files.readAllBytes(image)

            FixtureReply(answer).use { provider ->
                app.modelRepository.saveModel(ModelConfig(
                    id = modelId, displayName = "Synthetic local chat", baseUrl = provider.baseUrl,
                    apiKey = "", modelName = "synthetic-local-chat", isMultimodal = true, createdAt = 1
                ))
                app.settingsRepository.updateAppSettings {
                    it.copy(modelConfigurationMode = ModelConfigurationMode.CUSTOM_API, defaultModelId = modelId,
                        allowCleartextModelApi = true, automaticFormatCheckEnabled = false,
                        siliconFlowApiKey = "", ragInjectionMode = "OFF")
                }
                app.characterRepository.save(CharacterCard(cardId, "Synthetic local character", createdAt = 1, updatedAt = 1))
                app.chatRepository.createSession(ChatSession(
                    sessionId, cardId, "Synthetic local session", modelId = modelId,
                    longTermMemoryEnabled = false, automaticImageGenerationEnabled = false, createdAt = 1, updatedAt = 1
                ))

                ActivityScenario.launch<LanSyncActivity>(Intent(app, LanSyncActivity::class.java)).use { scenario ->
                    lateinit var owner: ChatViewModel
                    scenario.onActivity {
                        owner = ViewModelProvider(it, ChatViewModelFactory(sessionId))[ChatViewModel::class.java]
                    }
                    await { owner.draftLoaded.value && owner.isModelUsable.value && !owner.isArchived.value }
                    val paths = listOf(image.toString())
                    withContext(Dispatchers.Main) {
                        owner.updateDraftInput(text)
                        owner.addDraftImage(paths.single())
                    }
                    await { app.chatRepository.getComposerDraft(sessionId).let { it.content == text && it.imagePaths == paths } }

                    val maintenanceEntered = CompletableDeferred<Unit>()
                    val releaseMaintenance = CompletableDeferred<Unit>()
                    val maintenance = launch(Dispatchers.Default) {
                        // Keep storage open so the actual send can persist and reach AI admission.
                        AiBackgroundWorkManager.withLanSyncMaintenance {
                            maintenanceEntered.complete(Unit)
                            releaseMaintenance.await()
                        }
                    }
                    try {
                        withTimeout(15_000) { maintenanceEntered.await() }
                        assertTrue(withContext(Dispatchers.Main) { owner.sendMessage(text, paths) })
                        await { !owner.isResponding.value && owner.sendFailure.value?.contains("正在同步资料") == true }
                        val draft = app.chatRepository.getComposerDraft(sessionId)
                        assertEquals(text, draft.content)
                        assertEquals(paths, draft.imagePaths)
                        assertNull(draft.submittedMessageId)
                        assertEquals(text, owner.composerDraft.value.text)
                        assertEquals(paths, owner.composerDraft.value.images)
                        assertTrue(app.chatRepository.getMessagesReadOnly(sessionId).isEmpty())
                        assertFalse(provider.request.isCompleted)
                        assertTrue(originalImage.contentEquals(Files.readAllBytes(image)))
                    } finally {
                        releaseMaintenance.complete(Unit)
                        maintenance.join()
                    }

                    withContext(Dispatchers.Main) {
                        assertTrue(owner.sendMessage(text, paths))
                        assertFalse(owner.sendMessage(text, paths))
                    }
                    try {
                        val request = withTimeout(30_000) { provider.request.await() }
                        val messageRows = Json.parseToJsonElement(request).jsonObject.getValue("messages").jsonArray
                        val sentUser = messageRows.map { it.jsonObject }.single {
                            it["role"]?.jsonPrimitive?.content == "user" && text in it["content"].toString()
                        }
                        assertTrue("data:image/" in sentUser.getValue("content").toString())
                        val user = app.chatRepository.getMessagesReadOnly(sessionId).single()
                        assertEquals(MessageRole.USER, user.role)
                        assertEquals(text, user.content)
                        assertEquals(paths, user.images)
                        val afterCommit = app.chatRepository.getComposerDraft(sessionId)
                        assertTrue(afterCommit.content.isEmpty() && afterCommit.imagePaths.isEmpty())
                        assertNull(afterCommit.submittedMessageId)
                        assertTrue(owner.composerDraft.value.text.isEmpty() && owner.composerDraft.value.images.isEmpty())
                    } finally { provider.releaseReply() }

                    await { !owner.isResponding.value && app.chatRepository.getMessagesReadOnly(sessionId).any { it.content == answer } }
                    val messages = app.chatRepository.getMessagesReadOnly(sessionId)
                    assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
                    assertEquals(answer, messages.last().content)
                    assertNull(owner.sendFailure.value)
                    assertTrue(originalImage.contentEquals(Files.readAllBytes(image)))
                }
            }
        } finally {
            app.chatRepository.deleteSession(sessionId)
            app.characterRepository.delete(cardId)
            app.modelRepository.deleteModel(modelId)
            app.settingsRepository.saveAppSettings(previousSettings)
            Files.deleteIfExists(image)
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(30_000) {
        while (!condition()) delay(25)
    }

    private class FixtureReply(answer: String) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val baseUrl = "http://127.0.0.1:" + listener.localPort + "/v1"
        val request = CompletableDeferred<String>()
        private val reply = CountDownLatch(1)
        @Volatile private var connection: Socket? = null
        private val worker = thread(isDaemon = true, name = "ordinary-send-synthetic-provider") {
            try {
                listener.accept().use { socket ->
                    connection = socket
                    if (listener.isClosed) return@thread
                    socket.soTimeout = 20_000
                    val input = DataInputStream(socket.getInputStream().buffered())
                    check(readLine(input) == "POST /v1/chat/completions HTTP/1.1")
                    var length: Int? = null
                    var remainingHeaders = 64
                    while (true) {
                        val header = readLine(input)
                        if (header.isEmpty()) break
                        check(remainingHeaders-- > 0)
                        if (header.substringBefore(':').equals("Content-Length", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val bodyLength = requireNotNull(length)
                    check(bodyLength in 1..1024 * 1024)
                    val body = ByteArray(bodyLength)
                    input.readFully(body)
                    request.complete(body.toString(Charsets.UTF_8))
                    check(reply.await(20, TimeUnit.SECONDS))
                    val response = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + answer + "\"}}]}\n\n" +
                        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n").toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().buffered().use { output ->
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " +
                            response.size + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.write(response)
                    }
                }
            } catch (error: Exception) {
                request.completeExceptionally(error)
            }
        }

        private fun readLine(input: InputStream): String {
            val output = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next >= 0 && output.size() < 4_096)
                if (next == '\n'.code) return output.toString("US-ASCII").removeSuffix("\r")
                output.write(next)
            }
        }

        fun releaseReply() { reply.countDown() }

        override fun close() {
            releaseReply()
            listener.close()
            connection?.close()
            worker.join(5_000)
            check(!worker.isAlive)
        }
    }
}
