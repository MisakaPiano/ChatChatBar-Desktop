package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import java.util.zip.CRC32
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class DesktopCurrentImageIntegrationTest {
    private fun png(side: Int) = DesktopImageEditing.png(BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB))
    private fun textChunk(png: ByteArray, text: String): ByteArray {
        val payload = ("Comment\u0000" + text).toByteArray()
        val type = "tEXt".toByteArray()
        val chunk = ByteArrayOutputStream().apply { DataOutputStream(this).apply {
            writeInt(payload.size); write(type); write(payload); writeInt(CRC32().apply { update(type); update(payload) }.value.toInt())
        } }.toByteArray()
        return png.copyOfRange(0, png.size - 12) + chunk + png.copyOfRange(png.size - 12, png.size)
    }
    private fun comment(prompt: String) = """{"prompt":"$prompt","uc":"","sampler":"k_euler_ancestral","width":64,"height":64,"steps":28,"seed":1,"model":"nai-diffusion-4-5-full"}"""
    private fun stealth(): ByteArray {
        val payload = buildJsonObject { put("Comment", comment("alpha scene")) }.toString().toByteArray()
        val data = ByteArrayOutputStream().apply { write("stealth_pnginfo".toByteArray()); DataOutputStream(this).writeInt(payload.size * 8); write(payload) }.toByteArray()
        val raster = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 64) for (y in 0 until 64) {
            val bit = x * 64 + y
            val alpha = 254 or if (bit / 8 < data.size) (data[bit / 8].toInt() ushr (7 - bit % 8) and 1) else 1
            raster.setRGB(x, y, (alpha shl 24) or 0x123457)
        }
        return DesktopImageEditing.png(raster)
    }
    @Test fun `ordinary Comment wins invalid Comment falls back and privacy destroys both channels without changing source`() {
        val dir = Files.createTempDirectory("ccb-alpha-local-")
        try {
            val alpha = stealth()
            for ((ordinary, expected) in listOf(comment("ordinary scene") to "ordinary scene", "invalid" to "alpha scene")) {
                val path = dir.resolve("source.png"); val source = textChunk(alpha, ordinary); Files.write(path, source)
                assertEquals(expected, DesktopImageMetadata.readStudio(path.toString())?.positivePrompt)
                val privacy = DesktopImageMetadata.privacyCopy(source); val target = dir.resolve("private.png"); Files.write(target, privacy)
                assertNull(DesktopImageMetadata.read(target.toString())); assertNull(DesktopImageMetadata.readStudio(target.toString()))
                val raster = DesktopImageEditing.decode(privacy)
                assertNull(StealthAlphaMetadata.decode(raster.width, raster.height) { x, y -> raster.getRGB(x, y) ushr 24 })
                assertContentEquals(source, Files.readAllBytes(path))
            }
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun `WebP alpha fallback reads original alpha without a Java ImageIO plugin`() {
        val path = Files.createTempFile("ccb-alpha-webp-", ".webp")
        try {
            org.jetbrains.skia.Image.makeFromEncoded(stealth()).use { image ->
                requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, 100)).use { Files.write(path, it.bytes) }
            }
            assertEquals("alpha scene", DesktopImageMetadata.readStudio(path.toString())?.positivePrompt)
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun `canonical attachment restore is a new copy ordinary APNG is retained and privacy never flattens animation`(): Unit = runBlocking {
        val root = Files.createTempDirectory("ccb-ingress-local-")
        try {
            val store = DesktopChatImages(DesktopCharacterResourceStore(root), ChatRepository(JsonFileStorage(root)))
            val original = png(8); val disguised = DesktopImageTools.disguise(original); val external = root.resolve("external.png"); Files.write(external, disguised)
            val pending = store.prepare(external)
            assertTrue(pending.restoredDisguise)
            assertContentEquals(DesktopImageEditing.decode(original).pixels(), DesktopImageEditing.decode(pending.bytes).pixels())
            assertContentEquals(disguised, Files.readAllBytes(external))
            // Removing only container metadata yields a noncanonical APNG; never guess at its cover.
            val normal = ImageMetadataStripper.stripContainerMetadataToCopy(external.toFile(), root.resolve("cleaned").toFile()).toPath()
            assertNull(ApngDisguiseCodec.inspectDisguise(normal.toFile()))
            val ordinary = store.prepare(normal)
            assertFalse(ordinary.restoredDisguise); assertContentEquals(Files.readAllBytes(normal), ordinary.bytes)
            assertFails { DesktopImageMetadata.privacyCopy(ordinary.bytes) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `upscale fake HTTP exact route shape result and errors have one attempt and no secret artifacts`() = runBlocking {
        val secrets = InMemoryDesktopSecretStore(); val token = "fake-postprocess-secret"
        secrets.save(DesktopCredentialKey.NovelAiToken, token)
        var calls = 0; var responseCode = 200
        val output = png(128)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("/ai/upscale", chain.request().url.encodedPath)
            val body = Json.parseToJsonElement(okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals(setOf("image", "model", "declared_blur_sigma"), body.keys)
            assertEquals("nai-diffusion-5-curated", body.getValue("model").jsonPrimitive.content)
            assertEquals(0, body.getValue("declared_blur_sigma").jsonPrimitive.int)
            assertEquals("Bearer $token", chain.request().header("Authorization"))
            assertFalse(body.toString().contains(token))
            val response = if (responseCode == 200) """{"images":[{"image":"${Base64.getEncoder().encodeToString(output)}"}]}""" else token
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(responseCode).message("fake").body(response.toResponseBody()).build()
        }.build()
        val processor = DesktopNovelAiPostProcessor(secrets, client)
        assertContentEquals(output, processor.process(png(64), NovelAiPostProcessTab.UPSCALE, null, NovelAiEnhanceOptions(), null) {})
        responseCode = 429
        val failure = assertFails { processor.process(png(64), NovelAiPostProcessTab.UPSCALE, null, NovelAiEnhanceOptions(), null) {} }
        assertEquals(2, calls); assertFalse(failure.stackTraceToString().contains(token))
    }
    @Test fun `enhance uses official img2img options without generation retry`() = runBlocking {
        val secrets = InMemoryDesktopSecretStore(); secrets.save(DesktopCredentialKey.NovelAiToken, "fake-enhance")
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val body = Json.parseToJsonElement(okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals("img2img", body.getValue("action").jsonPrimitive.content)
            val parameters = body.getValue("parameters").jsonObject
            assertEquals(1, parameters.getValue("n_samples").jsonPrimitive.int)
            assertEquals(.4f, parameters.getValue("strength").jsonPrimitive.float)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("fake").body("failure".toResponseBody()).build()
        }.build()
        val source = NovelAiEnhanceSource(NovelAiPromptPlan("scene", emptyList()), NovelAiGenerationSettings(), JsonObject(emptyMap()))
        assertFails { DesktopNovelAiPostProcessor(secrets, client).process(png(64), NovelAiPostProcessTab.ENHANCE, source, NovelAiEnhanceOptions(strength = .4f), null) {} }
        assertEquals(1, calls)
    }

    @Test fun `postprocess cancellation cancels transport and never retries or publishes a result`() = runBlocking {
        val secrets = InMemoryDesktopSecretStore(); secrets.save(DesktopCredentialKey.NovelAiToken, "fake-cancel")
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/ai/upscale")).build()) }.build()
            val job = async { DesktopNovelAiPostProcessor(secrets, client).process(png(64), NovelAiPostProcessTab.UPSCALE, null, NovelAiEnhanceOptions(), null) {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            job.cancelAndJoin(); assertTrue(job.isCancelled); assertEquals(1, server.requestCount)
        }
    }

    @Test fun `multi picker and reorder determine persisted pending order and clear stale attachment error`() = runBlocking {
        val root = Files.createTempDirectory("ccb-pending-order-")
        val first = root.resolve("first.png"); val second = root.resolve("second.png")
        Files.write(first, png(8)); Files.write(second, png(16))
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType) = first
            override fun pickOpenFiles(type: DesktopFileType) = listOf(second, first)
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): java.nio.file.Path? = null
        }
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker)
        try {
            c.characterRepository.save(com.example.chatbar.data.local.entity.CharacterCard.create("Fixture").copy(id = "card"))
            c.chatRepository.createSession(com.example.chatbar.data.local.entity.ChatSession(id = "session", characterCardId = "card", title = "Fixture", createdAt = 1, updatedAt = 1))
            val controller = c.primaryChatController; controller.refresh(); controller.selectSession("session")
            controller.pickImage()
            val pending = controller.state.value.pendingImages
            assertEquals(listOf(16, 8), pending.map { DesktopImageEditing.decode(it.bytes).width })
            controller.reorderPendingImage(pending[1].id, 0)
            val ordered = controller.state.value.pendingImages
            assertEquals(listOf(8, 16), ordered.map { DesktopImageEditing.decode(it.bytes).width })
            val message = c.chatImages.persistUser("session", "local only", ordered)
            assertEquals(listOf(8, 16), message.images.map { DesktopImageEditing.decode(c.characterResourceStore.readBytes(it)).width })
            controller.imageIngressFailure(); assertNotNull(controller.state.value.error)
            controller.removePendingImage(pending[0].id); assertNull(controller.state.value.error)
        } finally { c.close(); root.toFile().deleteRecursively() }
    }
    @Test fun `clipboard text is not stolen while file drop preserves order`() {
        val files = listOf(java.io.File("b.png"), java.io.File("a.png"))
        val transfer = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor, java.awt.datatransfer.DataFlavor.stringFlavor)
            override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor in transferDataFlavors
            override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = if (flavor == java.awt.datatransfer.DataFlavor.stringFlavor) "ordinary text" else files
        }
        assertNull(desktopImageTransfer(java.awt.datatransfer.StringSelection("ordinary text"), paste = true))
        assertEquals(files.map { it.toPath() }, desktopImageTransfer(transfer, paste = true)?.paths)
        assertEquals(files.map { it.toPath() }, desktopImageTransfer(transfer, paste = false)?.paths)
    }
}
