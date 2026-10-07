package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopPhase7StudioTest {
    @Test fun `draft undo guidance and settings survive restart and corrupt singleton blocks overwrite`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-studio-")
        try {
            val storage = JsonFileStorage(root)
            val repo = NovelAiStudioStateRepository(storage)
            val initial = repo.loadDraft()
            assertTrue(initial.followDefaultNovelAiImageModel)
            repo.updateDraft { it.copy(basePrompt = "fixture", selectedModel = NovelAiImageModel.V5_FULL,
                v5Settings = it.v5Settings.copy(seedMode = NovelAiSeedMode.FIXED, seed = 17, customWidth = 768, customHeight = 1024)) }
            repo.saveUndoDraft(repo.loadDraft())
            repo.updateDraft { it.clearPrompts() }
            val restarted = NovelAiStudioStateRepository(JsonFileStorage(root))
            assertEquals("", restarted.loadDraft().basePrompt)
            assertEquals("fixture", restarted.loadUndoDraft()!!.basePrompt)
            assertEquals(17L, restarted.loadUndoDraft()!!.activeSettings.seed)
            Files.writeString(root.resolve("entities/novelai_studio_draft.json"), "{broken")
            assertFails { NovelAiStudioStateRepository(JsonFileStorage(root)).loadDraft() }
            assertEquals("{broken", Files.readString(root.resolve("entities/novelai_studio_draft.json")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `static APNG round trip restores RGBA and excludes cover and metadata`(): Unit = runBlocking {
        val pixels = intArrayOf(0xff112233.toInt(), 0x80446688.toInt(), 0x00000000, -1)
        val png = DesktopImageEditing.png(raster(2, 2, pixels))
        val disguised = DesktopImageTools.disguise(png)
        val source = Files.createTempFile("p7-apng-", ".png")
        try {
            Files.write(source, disguised)
            val inspection = assertNotNull(ApngDisguiseCodec.inspectDisguise(source.toFile()))
            assertEquals(1, inspection.animationFrameCount) // Inspection reports true content frames, excluding sentinel.
            assertEquals(1, inspection.metadata.contentFrameCount)
            val restored = DesktopImageTools.restore(disguised)
            assertContentEquals(pixels, DesktopImageEditing.decode(restored).pixels())
            DesktopImageEditing.requireStatic(restored)
            assertFails { DesktopImageEditing.requireStatic(disguised) }
        } finally { Files.deleteIfExists(source) }
    }

    @Test fun `focused inpaint uses shared mask and preserves every pixel outside crop`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-focus-")
        try {
            val resources = DesktopCharacterResourceStore(root)
            val base = raster(512, 512, IntArray(512 * 512) { 0xff336699.toInt() })
            val png = DesktopImageEditing.png(base)
            val path = resources.materializeImage(PackagedImage("base.png", Base64.getEncoder().encodeToString(png)), 1, "p7test")
            val asset = NovelAiStudioAssetRef(path = path, width = 512, height = 512)
            val draft = NovelAiStudioDraft(basePrompt = "fixture", imageGuidance = NovelAiImageGuidanceDraft(
                action = NovelAiGenerationAction.INPAINT, baseImage = asset,
                focusedInpaintRegion = NovelAiFocusedInpaintRegion(.125f, .125f, .75f, .75f), focusedInpaintMinimumContext = 32))
            val prepared = DesktopNovelAiGuidance(root, resources, InMemoryDesktopSecretStore()).prepare(draft)
            assertEquals(NovelAiGenerationAction.INPAINT, prepared.guidance.action)
            val mask = DesktopImageEditing.decode(Base64.getDecoder().decode(prepared.guidance.maskBase64))
            assertEquals(prepared.requestSize.width, mask.width)
            assertEquals(0xff000000.toInt(), mask.getRGB(0, 0))
            val generated = raster(prepared.requestSize.width, prepared.requestSize.height,
                IntArray(prepared.requestSize.width * prepared.requestSize.height) { 0xffff0000.toInt() })
            val output = DesktopImageEditing.decode(prepared.compose(DesktopImageEditing.png(generated)))
            assertEquals(base.getRGB(0, 0), output.getRGB(0, 0))
            assertEquals(0xffff0000.toInt(), output.getRGB(256, 256))
            assertEquals(512, output.width)
            assertContentEquals(png, resources.readBytes(path))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `V5 pauses precise and vibe without touching SecretStore or losing saved settings`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-paused-reference-")
        try {
            val draft = NovelAiStudioDraft(selectedModel = NovelAiImageModel.V5_FULL,
                imageGuidance = NovelAiImageGuidanceDraft(referenceMode = NovelAiReferenceMode.VIBE,
                    vibes = listOf(NovelAiVibeReferenceDraft(encodedVibe = "fixture-encoding"))))
            val prepared = DesktopNovelAiGuidance(root, DesktopCharacterResourceStore(root), InMemoryDesktopSecretStore()).prepare(draft)
            assertTrue(prepared.guidance.vibes.isEmpty())
            assertEquals(draft.imageGuidance, prepared.retainedGuidance)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `mosaic and rotation leave immutable source unchanged`() {
        val original = raster(4, 4, IntArray(16) { 0xff000000.toInt() or (it * 1000) })
        val before = original.pixels()
        val edited = DesktopImageTools.mosaic(original, NovelAiFocusedInpaintRegion(0f, 0f, .5f, .5f), 2)
        assertEquals(original.getRGB(3, 3), edited.getRGB(3, 3))
        assertContentEquals(before, original.pixels())
        var rotated = original
        repeat(4) { rotated = DesktopImageTools.rotate(rotated) }
        assertContentEquals(before, rotated.pixels())
    }

    @Test fun `Vibe encoding mock request caches safely and error body cannot expose secret`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-vibe-")
        try {
            var calls = 0
            var code = 200
            val token = "fixture-only-token"
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                assertTrue(body.contains("information_extracted"))
                assertFalse(body.contains(token))
                assertEquals("Bearer $token", chain.request().header("Authorization"))
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(code).message("fixture").body(okhttp3.ResponseBody.create(null, if (code == 200) byteArrayOf(1, 2, 3) else token.toByteArray())).build()
            }.build()
            val core = NovelAiVibeEncodingCore(root.toFile(), client, { byteArrayOf(5, 6) }, safeErrorsOnly = true)
            val asset = NovelAiStudioAssetRef(path = "fixture", width = 512, height = 512, sha256 = "inline")
            assertEquals("AQID", core.resolve(token, asset, NovelAiImageModel.V4_5_FULL, 1f))
            assertEquals("AQID", core.resolve(token, asset, NovelAiImageModel.V4_5_FULL, 1f))
            assertEquals(1, calls)
            code = 401
            val failure = assertFails { core.resolve(token, asset.copy(sha256 = "different"), NovelAiImageModel.V4_5_FULL, 1f) }
            assertFalse(failure.stackTraceToString().contains(token))
            assertEquals(2, calls)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `Studio deletion deep copied guidance survives and corrupt authority retains selected file`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-studio-delete-")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            val store = DesktopNovelAiGenerationStore(container.jsonFileStorage, container.characterResourceStore, container.dataOperationCoordinator)
            val png = DesktopImageEditing.png(BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB))
            val entry = store.persist(listOf(png), NovelAiGenerationRecipe(basePrompt = "fixture"))
            val controller = container.novelAiStudioController
            controller.load()
            controller.useHistoryImage(entry.images.single().path, NovelAiImageUseTarget.IMAGE_TO_IMAGE)
            val copy = controller.draft.value!!.imageGuidance.baseImage!!.path
            assertNotEquals(copy, entry.images.single().path)
            controller.toggleSelection(NovelAiHistoryImageSelection(entry.id, entry.images.single().path))
            controller.deleteSelected()
            assertContentEquals(png, container.characterResourceStore.readBytes(copy))
            assertFalse(Files.exists(container.characterResourceStore.resolveOwnedReference(entry.images.single().path)))
            val second = store.persist(listOf(png), NovelAiGenerationRecipe(basePrompt = "second"))
            Files.writeString(root.resolve("entities/novelai_generation_history/${second.id}.json"), "corrupt")
            controller.toggleSelection(NovelAiHistoryImageSelection(second.id, second.images.single().path))
            controller.deleteSelected()
            assertContentEquals(png, container.characterResourceStore.readBytes(second.images.single().path))
        } finally { container.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `automatic handoff rejects missing failed refused truncated stopped and changed replies`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-auto-")
        val secrets = InMemoryDesktopSecretStore()
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { secrets })
        try {
            val session = ChatSession(id = "session", characterCardId = "card", title = "fixture", createdAt = 1,
                updatedAt = 1, automaticImageGenerationEnabled = true)
            container.chatRepository.createSession(session)
            val message = container.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "complete"))
            var handed = 0
            var work: (suspend ((String) -> Unit) -> Unit)? = null
            val service = DesktopAutomaticChatImages(container.chatRepository, container.characterRepository,
                container.settingsRepository, container.effectiveModelResolver, { error("must not design") },
                container.characterResourceStore, container.dataOperationCoordinator, secrets,
                launch = { _, messageId, block -> assertEquals(message.id, messageId); handed++; work = block; "fixture-task" })
            fun result(completion: ProviderCompletionMetadata?) = DesktopRealChatResult(
                ChatMessage.create(session.id, MessageRole.USER, "input"), true, message, completion)
            listOf(null, ProviderCompletionMetadata("length"), ProviderCompletionMetadata("stop", refused = true),
                ProviderCompletionMetadata("stop", transportFailed = true)).forEach { service.completed(result(it)) { false } }
            service.completed(result(ProviderCompletionMetadata("stop"))) { true }
            assertEquals(0, handed)
            service.completed(result(ProviderCompletionMetadata("stop"))) { false }
            assertEquals(1, handed)
            container.chatRepository.updateMessage(message.copy(content = "edited", updatedAt = message.updatedAt + 1))
            assertFails { work!!.invoke {} }
            service.completed(result(ProviderCompletionMetadata("stop"))) { false }
            assertEquals(1, handed)
        } finally { container.close(); root.toFile().deleteRecursively() }
    }
    @Test fun `chat generated images are linked durably with exact metadata and invalid source never commits`(): Unit = runBlocking {
        val root = Files.createTempDirectory("p7-chat-generated-")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            val session = ChatSession(id = "session", characterCardId = "card", title = "fixture", createdAt = 1, updatedAt = 1)
            container.chatRepository.createSession(session)
            val original = container.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "persisted reply"))
            val plan = NovelAiPromptPlan(baseCaption = "style, fixture", stylePrompt = "style",
                characterCaptions = listOf(NovelAiCharacterCaption("character", DesignedCharacterCenter(.2f, .7f), "negative")))
            val png = DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
            val recipe = NovelAiGenerationRecipe(basePrompt = "fixture")
            assertFails { persistDesktopChatImages(container.chatRepository, container.characterResourceStore,
                container.dataOperationCoordinator, original, plan, NovelAiImageSize(512, 512, "fixture"), listOf(png), recipe) { false } }
            assertEquals(1, container.chatRepository.getMessages(session.id).size)
            val entry = persistDesktopChatImages(container.chatRepository, container.characterResourceStore,
                container.dataOperationCoordinator, original, plan, NovelAiImageSize(512, 512, "fixture"), listOf(png), recipe) { true }
            val persisted = com.example.chatbar.data.repository.ChatRepository(JsonFileStorage(root)).getMessages(session.id)
            assertEquals(2, persisted.size)
            val generated = persisted.single { it.id != original.id }
            assertEquals(original.id, generated.generatedFromMessageId)
            assertEquals(entry.images.single().path, generated.images.single())
            assertEquals(plan.toGeneratedImageMetadata(generated.images.single(), NovelAiImageSize(512, 512, "fixture")), generated.generatedImageMetadata.single())
            assertEquals(original, persisted.single { it.id == original.id })
            assertContentEquals(png, container.characterResourceStore.readBytes(generated.images.single()))
        } finally { container.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `animated GIF disguise restore keeps frame count timing and pixels without cover`(): Unit = runBlocking {
        val output = java.io.ByteArrayOutputStream()
        val writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next()
        javax.imageio.ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream; writer.prepareWriteSequence(null)
            listOf(0xffff0000.toInt(), 0xff0000ff.toInt()).forEachIndexed { index, color ->
                val frame = raster(3, 2, IntArray(6) { color })
                val metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(frame), writer.defaultWriteParam)
                val tree = metadata.getAsTree("javax_imageio_gif_image_1.0") as javax.imageio.metadata.IIOMetadataNode
                val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as javax.imageio.metadata.IIOMetadataNode
                control.setAttribute("delayTime", (index + 2).toString())
                control.setAttribute("disposalMethod", "restoreToBackgroundColor")
                metadata.setFromTree("javax_imageio_gif_image_1.0", tree)
                writer.writeToSequence(javax.imageio.IIOImage(frame, null, metadata), writer.defaultWriteParam)
            }
            writer.endWriteSequence(); writer.dispose()
        }
        val disguised = DesktopImageTools.disguise(output.toByteArray())
        val restored = DesktopImageTools.restore(disguised)
        val colors = mutableListOf<Int>()
        val delays = mutableListOf<Long>()
        assertTrue(playDesktopApng(restored, maxPlays = 1) { frame, delay -> colors += frame.getRGB(0, 0); delays += delay })
        assertEquals(listOf(0xffff0000.toInt(), 0xff0000ff.toInt()), colors)
        assertEquals(listOf(20L, 30L), delays)
    }

}
