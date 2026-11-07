@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class DesktopPostProcessLifetimeTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private class Fixture {
        val parent = Files.createTempDirectory("post-process-lifetime-")
        val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "synthetic") }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { secrets }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val server = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        val input = DesktopImageEditing.png(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB))
        val output = DesktopImageEditing.png(BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB))
        val source = parent.resolve("source.png").also { Files.write(it, input) }
        fun controller() = DesktopNovelAiStudioController(app.jsonFileStorage, app.characterResourceStore, UnconfiguredDesktopFilePicker,
            app.dataOperationCoordinator, {}, app.novelAiGenerationRuntime,
            DesktopNovelAiGuidance(app.appDataRoot, app.characterResourceStore, secrets), app.taskRuntime,
            app.novelAiInfrastructure, app.settingsRepository, app.effectiveModelResolver, app.characterRepository,
            { NovelAiAccountUsage(100, 3, true, 50.0, false) }, { true }, DesktopNovelAiPostProcessor(secrets, client))
        suspend fun close() {
            app.close(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            withContext(Dispatchers.IO) { check(client.dispatcher.executorService.awaitTermination(10, TimeUnit.SECONDS)); server.close() }
            check(parent.toFile().deleteRecursively())
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render(System.nanoTime()).close(); delay(18) } }
    private fun ImageComposeScene.action(label: String) = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick)
    }

    @Test fun `actual processing finishes after pane closes and durable result survives controller reopen without changing source or draft`() = runBlocking(edt) {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            f.server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                entered.complete(Unit); runBlocking { release.await() }
                return MockResponse().setBody("""{"images":[{"image":"${Base64.getEncoder().encodeToString(f.output)}"}]}""")
            } }
            val controller = f.controller(); assertTrue(controller.load()); val draft = controller.repository.loadDraft()
            val scene = ImageComposeScene(650, 1000, coroutineContext = edt) { DesktopImagePostProcessPanel(f.source, controller) { error("Use must remain explicit") } }
            try {
                withTimeout(5_000) { while (scene.action(NovelAiPostProcessTab.UPSCALE.label)?.config?.contains(SemanticsProperties.Disabled) != false) scene.frames() }
                assertTrue(scene.action(NovelAiPostProcessTab.UPSCALE.label)!!.config[SemanticsActions.OnClick].action!!.invoke())
                withTimeout(5_000) { entered.await() }
            } finally { scene.close(); release.complete(Unit) }
            withTimeout(10_000) { while (f.app.taskRuntime.tasks.value.any { it.status == DesktopTaskStatus.RUNNING }) delay(20) }
            assertEquals(DesktopTaskStatus.COMPLETED, f.app.taskRuntime.tasks.value.first().status)
            val records = f.app.jsonFileStorage.scanEntitiesStrict("desktop_image_post_process_results", JsonObject.serializer())
            assertEquals(1, records.size, "Completed paid result must have durable ownership after its Pane is gone")
            val record = assertIs<JsonFileStorage.EntityReadResult.Valid<JsonObject>>(records.single().result).value
            val path = record.getValue("imagePath").jsonPrimitive.content
            assertContentEquals(f.output, f.app.characterResourceStore.readBytes(path))
            val reopened = f.controller(); assertTrue(reopened.load())
            assertEquals(draft, reopened.repository.loadDraft()); assertContentEquals(f.input, Files.readAllBytes(f.source))
            val nextScene = ImageComposeScene(650, 1000, coroutineContext = edt) { DesktopImagePostProcessPanel(f.source, reopened) { error("Use must remain explicit") } }
            try { withTimeout(5_000) { while (nextScene.action("保存 PNG") == null) nextScene.frames() } }
            finally { nextScene.close() }
            assertEquals(1, f.server.requestCount)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun `actual post processing pane never stops an unrelated NovelAI owner or starts a second request`() = runBlocking(edt) {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val controller = f.controller(); assertTrue(controller.load())
            val other = f.app.taskRuntime.launchNovelAi("Other owned image work") { entered.complete(Unit); release.await() }
            entered.await()
            val scene = ImageComposeScene(650, 1000, coroutineContext = edt) { DesktopImagePostProcessPanel(f.source, controller) {} }
            try {
                scene.frames()
                scene.action("停止")?.takeUnless { it.config.contains(SemanticsProperties.Disabled) }?.let {
                    assertTrue(it.config[SemanticsActions.OnClick].action!!.invoke()); scene.frames()
                }
                assertEquals(DesktopTaskStatus.RUNNING, f.app.taskRuntime.tasks.value.single { it.taskId == other }.status,
                    "This Pane must not cancel a different task")
                assertNull(scene.action("停止")?.takeUnless { it.config.contains(SemanticsProperties.Disabled) })
                assertEquals(0, f.server.requestCount)
            } finally { scene.close() }
        } finally { release.complete(Unit); f.close() }
    }
}
