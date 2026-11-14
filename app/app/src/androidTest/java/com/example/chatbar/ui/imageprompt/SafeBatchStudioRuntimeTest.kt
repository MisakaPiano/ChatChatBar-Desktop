package com.example.chatbar.ui.imageprompt

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.repository.NovelAiStudioRepository
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.components.SafeBatchReviewFiles
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.CbText
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.msgpack.core.MessagePack

/** Real Android Studio VM/job/foreground/save owners; terminal fake HTTP only. */
class SafeBatchStudioRuntimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private data class RequestRecord(val count: Int, val prompt: String)

    @Test fun studioFreezesLaunchPromptCommitsFourFourTwoAndKeepsLiveEdit() = withRuntime("success") { vm, requests, before ->
        start(vm, 4, 10, 1, 10000)
        rule.waitUntil(15000) { vm.uiState.value.autoCompletedCount >= 4 }
        rule.runOnIdle { vm.updateDraft { it.copy(basePrompt = "synthetic live edit for next run") } }
        rule.waitUntil(15000) { !vm.uiState.value.isBusy }
        assertEquals(ImagePromptToolPhase.FINISHED, vm.uiState.value.phase)
        assertEquals(listOf(4, 4, 2), requests.map { it.count })
        assertTrue(requests.all { it.prompt.contains("synthetic launch freeze") && !it.prompt.contains("live edit") })
        assertEquals("synthetic live edit for next run", vm.uiState.value.draft.basePrompt)
        val saved = reopenedHistory().filter { it.id !in before }
        assertEquals(10, saved.sumOf { it.images.size }); assertEquals(listOf(2, 4, 4), saved.map { it.images.size }.sorted())
        assertTrue(saved.all { it.recipe.basePrompt == "synthetic launch freeze" })
        assertTrue(saved.flatMap { it.images }.all { File(it.path).isFile })
        proof("studio-freeze-and-save", requests, saved)
    }
    @Test fun studio429PausesUntilUserResumesThenHonorsFullCooldown() = withRuntime("429") { vm, requests, before ->
        start(vm, 1, 1, 1, 0)
        rule.waitUntil(15000) { vm.uiState.value.batchProgress?.stage == ImageBatchStage.PAUSED }
        Thread.sleep(500); assertEquals(1, requests.size)
        val resumed = System.nanoTime()
        rule.runOnIdle { vm.resumeBatchRun() }
        rule.waitUntil(15000) { !vm.uiState.value.isBusy }
        assertTrue((System.nanoTime() - resumed) / 1000000 >= 1900)
        assertEquals(ImagePromptToolPhase.FINISHED, vm.uiState.value.phase); assertEquals(2, requests.size)
        val saved = reopenedHistory().filter { it.id !in before }; assertEquals(1, saved.sumOf { it.images.size })
        proof("studio-429-explicit-resume", requests, saved)
    }
    @Test fun studioStopDuringPacingKeepsFirstCommittedBatchAndMakesNoNextRequest() = withRuntime("success") { vm, requests, before ->
        start(vm, 1, 3, 30, 0)
        rule.waitUntil(15000) { vm.uiState.value.batchProgress?.stage == ImageBatchStage.WAITING }
        rule.runOnIdle { vm.cancelActiveTask() }
        rule.waitUntil(10000) { !vm.uiState.value.isBusy }
        Thread.sleep(1200); assertEquals(1, requests.size)
        assertEquals(ImagePromptToolPhase.CANCELLED, vm.uiState.value.phase)
        val saved = reopenedHistory().filter { it.id !in before }; assertEquals(1, saved.sumOf { it.images.size })
        proof("studio-stop-keeps-history", requests, saved)
    }
    private fun start(vm: ImagePromptToolViewModel, count: Int, target: Int, seconds: Int, budget: Long) {
        rule.waitUntil(20000) { vm.uiState.value.draftLoaded }
        rule.runOnIdle {
            vm.updateDraft { it.copy(basePrompt = "synthetic launch freeze", characters = emptyList(),
                selectedModel = NovelAiImageModel.V4_5_FULL, followDefaultNovelAiImageModel = false,
                imageGuidance = NovelAiImageGuidanceDraft(), continuousModeEnabled = true, continuousTargetCount = target,
                v45Settings = NovelAiGenerationSettings(count = count), batchPacing = ImageBatchPacing(fixedSeconds = seconds)) }
            vm.setBatchBudget(budget); vm.generateImage()
        }
        rule.waitUntil(5000) { vm.uiState.value.batchConfirmation != null }
        rule.runOnIdle { vm.confirmBatchRun() }
    }
    private fun withRuntime(scenario: String, block: (ImagePromptToolViewModel, List<RequestRecord>, Set<String>) -> Unit) {
        val app = ChatBarApp.instance
        runBlocking { withTimeout(30000) { app.dependenciesReady.first { it } } }
        rule.setContent { ChatBarTheme { CbText("Synthetic Studio runtime checks") } }
        check(app.packageName == "com.example.chatbar.safebatch.n1") { "Refusing a non-isolated app" }
        val oldImage = app.novelAiImageService; val oldAccount = app.novelAiAccountService
        val oldToken = app.novelAiCredentialStore.load()
        val requests = Collections.synchronizedList(mutableListOf<RequestRecord>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().header("Authorization")?.contains("synthetic-studio-runtime-") == true)
            if (chain.request().url.encodedPath == "/user/subscription") Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic account")
                .body("""{"active":true,"tier":3,"trainingStepsLeft":10000,"usage":{"percent":100,"isNegative":false}}""".toResponseBody("application/json".toMediaType())).build()
            else {
                check(chain.request().url.encodedPath == "/ai/generate-image-stream")
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                val count = body.getValue("parameters").jsonObject.getValue("n_samples").jsonPrimitive.int
                requests += RequestRecord(count, body.getValue("input").jsonPrimitive.content)
                val limited = scenario == "429" && requests.size == 1
                val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR4nGP4zwAE/xkaAAt+An+rqPSSAAAAAElFTkSuQmCC")
                val payload = if (limited) byteArrayOf() else List(count) { frame(png) }.fold(byteArrayOf()) { a, b -> a + b }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (limited) 429 else 200)
                    .message("synthetic image").header("Retry-After", "2").body(payload.toResponseBody("application/octet-stream".toMediaType())).build()
            }
        }.build()
        val store = ViewModelStore()
        try {
            app.novelAiImageService = NovelAiImageService(client); app.novelAiAccountService = NovelAiAccountService(client)
            app.novelAiCredentialStore.save("synthetic-studio-runtime-${UUID.randomUUID()}")
            var vm: ImagePromptToolViewModel? = null
            rule.runOnIdle { vm = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ImagePromptToolViewModel::class.java] }
            val before = reopenedHistory().map { it.id }.toSet()
            block(requireNotNull(vm), requests, before)
        } finally {
            rule.runOnIdle { store.clear() }
            app.novelAiImageService = oldImage; app.novelAiAccountService = oldAccount
            if (oldToken == null) app.novelAiCredentialStore.clear() else app.novelAiCredentialStore.save(oldToken)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    private fun reopenedHistory(): List<NovelAiGenerationHistoryEntry> = runBlocking {
        val app = ChatBarApp.instance
        val repository = NovelAiStudioRepository(com.example.chatbar.data.local.JsonFileStorage(app), app.novelAiImageStorage)
        repository.initialize(); repository.history.first()
    }
    private fun proof(name: String, requests: List<RequestRecord>, saved: List<NovelAiGenerationHistoryEntry>) {
        val directory = File(rule.activity.getExternalFilesDir(null), "safe-batch-review").also { it.mkdirs() }
        val original = File(directory, "$name.json")
        original.writeText(buildJsonObject {
            put("syntheticOnly", true); put("actualStudioViewModel", true)
            put("httpCounts", JsonArray(requests.map { JsonPrimitive(it.count) }))
            put("savedHistoryImages", saved.sumOf { it.images.size })
            put("allFilesExist", saved.flatMap { it.images }.all { File(it.path).isFile })
        }.toString())
        SafeBatchReviewFiles.retain(rule.activity, original)
    }
    private fun frame(image: ByteArray): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker(); packer.packMapHeader(2)
        packer.packString("event_type"); packer.packString("final"); packer.packString("image")
        packer.packBinaryHeader(image.size); packer.writePayload(image); packer.close()
        val bytes = packer.toByteArray(); return ByteBuffer.allocate(bytes.size + 4).putInt(bytes.size).put(bytes).array()
    }
}
