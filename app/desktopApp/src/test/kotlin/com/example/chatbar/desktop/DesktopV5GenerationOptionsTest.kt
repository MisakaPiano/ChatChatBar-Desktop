@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.msgpack.core.MessagePack
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopV5GenerationOptionsTest {
    @Test fun `actual Medium transparent request retains alpha bytes and original recipe through durable reopen`(): Unit = runBlocking {
        val root = Files.createTempDirectory("v5-options-")
        try {
            val gate = DesktopDataOperationCoordinator()
            val storage = JsonFileStorage(root, gate)
            val resources = DesktopCharacterResourceStore(root)
            val pixels = intArrayOf(0, 0x80446688.toInt(), 0xff123456.toInt(), 0x20112233)
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 2, 2, pixels, 0, 2) }
            val png = DesktopImageEditing.png(image)
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val wire = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                assertEquals("nai-diffusion-5-full-medium", wire.getValue("model").jsonPrimitive.content)
                val p = wire.getValue("parameters").jsonObject
                assertEquals(14, p.getValue("steps").jsonPrimitive.int)
                assertTrue(p.getValue("tag_hint_transparent_background").jsonPrimitive.boolean)
                assertTrue(wire.getValue("input").jsonPrimitive.content.endsWith("transparent background"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body(frame(png).toResponseBody()).build()
            }.build()
            val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "synthetic-only-token") }
            val draft = NovelAiStudioDraft(basePrompt = "synthetic scene", negativePrompt = "retained custom",
                selectedModel = NovelAiImageModel.V5_FULL, v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
                    steps = 37, cfgRescale = .4f, sampler = NovelAiSampler.DPM_PLUS_PLUS_2M,
                    effort = NovelAiEffort.MEDIUM, transparentBackground = true))
            val repo = NovelAiStudioStateRepository(storage)
            repo.updateDraft { draft }
            val runtime = DesktopNovelAiGenerationRuntime(secrets, DesktopNovelAiGenerationStore(storage, resources, gate)::persist, client)
            val entry = runtime.generate(draft, maxRateLimitRetries = 0)
            assertEquals(1, requests)
            val restored = JsonFileStorage(root).loadEntity(DesktopNovelAiGenerationStore.KEY, entry.id, NovelAiGenerationHistoryEntry.serializer())!!
            assertEquals(entry, restored); assertEquals(37, restored.recipe.settings.steps)
            assertEquals(NovelAiEffort.MEDIUM, restored.recipe.settings.effort)
            assertEquals("retained custom", restored.recipe.negativePrompt)
            val bytes = resources.readBytes(restored.images.single().path)
            assertContentEquals(png, bytes)
            assertContentEquals(pixels, DesktopImageEditing.decode(bytes).getRGB(0, 0, 2, 2, null, 0, 2))
            val reopenedDraft = NovelAiStudioStateRepository(JsonFileStorage(root)).loadDraft()
            assertEquals(draft.v5Settings, reopenedDraft.v5Settings)
            assertEquals(draft.v5Settings.copy(effort = NovelAiEffort.HIGH).sampler,
                reopenedDraft.activeSettings.copy(effort = NovelAiEffort.HIGH).effectiveSampler)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `focused transparent inpaint preserves alpha inside crop and exact old pixels outside`(): Unit = runBlocking {
        val root = Files.createTempDirectory("v5-alpha-focus-")
        try {
            val resources = DesktopCharacterResourceStore(root)
            val base = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, 512, 512, IntArray(512 * 512) { 0xff336699.toInt() }, 0, 512)
            }
            val bytes = DesktopImageEditing.png(base)
            val path = resources.materializeImage(com.example.chatbar.domain.card.PackagedImage("base.png", Base64.getEncoder().encodeToString(bytes)), 1, "v5alpha")
            val draft = NovelAiStudioDraft(selectedModel = NovelAiImageModel.V5_FULL,
                v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, transparentBackground = true),
                imageGuidance = NovelAiImageGuidanceDraft(action = NovelAiGenerationAction.INPAINT,
                    baseImage = NovelAiStudioAssetRef(path = path, width = 512, height = 512),
                    focusedInpaintRegion = NovelAiFocusedInpaintRegion(.125f, .125f, .75f, .75f), focusedInpaintMinimumContext = 32))
            val prepared = DesktopNovelAiGuidance(root, resources, InMemoryDesktopSecretStore()).prepare(draft)
            val generated = BufferedImage(prepared.requestSize.width, prepared.requestSize.height, BufferedImage.TYPE_INT_ARGB)
            val result = DesktopImageEditing.decode(prepared.compose(DesktopImageEditing.png(generated)))
            assertEquals(0, result.getRGB(256, 256))
            assertEquals(base.getRGB(0, 0), result.getRGB(0, 0))
            assertContentEquals(bytes, resources.readBytes(path))
        } finally { root.toFile().deleteRecursively() }
    }

    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `normal settings UI can choose Medium and transparency without losing High choices`() = runBlocking(edt) {
        var draft by mutableStateOf(NovelAiStudioDraft(selectedModel = NovelAiImageModel.V5_FULL,
            v5Settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, steps = 37, cfgRescale = .4f)))
        val scene = ImageComposeScene(560, 1000, coroutineContext = edt) { StudioGenerationSettings(draft) { draft = it(draft) } }
        fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        fun has(n: SemanticsNode, text: String): Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true || n.children.any { has(it, text) }
        suspend fun frames() { repeat(4) { scene.render().close(); delay(20) } }
        fun click(text: String) {
            val n = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.first { has(it, text) && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(n.boundsInRoot.width > 0 && n.boundsInRoot.height > 0)
            assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke())
        }
        try {
            frames(); click("Medium"); frames(); assertTrue(draft.activeSettings.isMedium)
            click("透明背景"); frames(); assertTrue(draft.activeSettings.effectiveTransparentBackground)
            System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { directory ->
                val p = Path.of(directory).resolve("v5-medium-transparent-settings.png"); Files.createDirectories(p.parent)
                scene.render().use { image -> image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { Files.write(p, it.bytes) } }
            }
            click("High"); frames(); assertEquals(37, draft.activeSettings.effectiveSteps)
            assertEquals(.4f, draft.activeSettings.effectiveCfgRescale)
        } finally { scene.close() }
    }

    private fun frame(bytes: ByteArray): ByteArray {
        val p = MessagePack.newDefaultBufferPacker()
        p.packMapHeader(2); p.packString("event_type"); p.packString("final")
        p.packString("image"); p.packBinaryHeader(bytes.size); p.writePayload(bytes); p.close()
        val data = p.toByteArray(); return ByteBuffer.allocate(data.size + 4).putInt(data.size).put(data).array()
    }
}
