package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopImageProcessingUsageTest {
    private class Fixture {
        val root = Files.createTempDirectory("image-processing-usage-")
        val gate = DesktopDataOperationCoordinator()
        val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "synthetic-key") }
        val records = Collections.synchronizedList(mutableListOf<DesktopUsageRecord>())
        val diagnostics = DesktopTransportDiagnosticsOwner(usageSink = { records.add(it) })
        val server = MockWebServer()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        fun rows() = synchronized(records) { records.groupBy { it.id }.values.map { it.maxBy { row -> row.revision } } }
        fun guidance() = DesktopNovelAiGuidance(root, DesktopCharacterResourceStore(root), secrets, gate, client, diagnostics)
        fun draft(): NovelAiStudioDraft {
            val bytes = png(64); Files.createDirectories(root.resolve("images")); Files.write(root.resolve("images/source.png"), bytes)
            val asset = NovelAiStudioAssetRef(path = "images/source.png", sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }, width = 64, height = 64)
            return NovelAiStudioDraft(basePrompt = "synthetic-private", selectedModel = NovelAiImageModel.V4_5_FULL,
                imageGuidance = NovelAiImageGuidanceDraft(referenceMode = NovelAiReferenceMode.VIBE,
                    vibes = listOf(NovelAiVibeReferenceDraft(asset = asset))))
        }
        suspend fun close() {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            check(client.dispatcher.executorService.awaitTermination(10, TimeUnit.SECONDS)); server.close()
            gate.closeAndDrain(); check(root.toFile().deleteRecursively())
        }
    }
    companion object {
        private fun png(size: Int) = DesktopImageEditing.png(BufferedImage(size, size, BufferedImage.TYPE_INT_RGB))
    }
    private fun response(tab: NovelAiPostProcessTab, bytes: ByteArray): MockResponse {
        if (tab == NovelAiPostProcessTab.UPSCALE) return MockResponse().setBody(
            """{"images":[{"image":"${Base64.getEncoder().encodeToString(bytes)}"}]}""")
        val pack = MessagePack.newDefaultBufferPacker()
        pack.packMapHeader(2); pack.packString("event_type"); pack.packString("final")
        pack.packString("image"); pack.packBinaryHeader(bytes.size); pack.writePayload(bytes); pack.close()
        val body = pack.toByteArray()
        return MockResponse().setBody(Buffer().write(ByteBuffer.allocate(body.size + 4).putInt(body.size).put(body).array()))
    }

    private fun markedPng(orientation: Int): ByteArray {
        val image = BufferedImage(64, 96, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 96) for (x in 0 until 64) image.setRGB(x, y,
            (0x80000000L or (x.toLong() shl 16) or (y.toLong() shl 8) or 0x55L).toInt())
        val png = DesktopImageEditing.png(image)
        val type = "eXIf".toByteArray(Charsets.US_ASCII)
        val payload = ImageMetadataStripper.buildMinimalTiffOrientation(orientation)
        val chunk = ByteArrayOutputStream().apply { DataOutputStream(this).apply {
            writeInt(payload.size); write(type); write(payload)
            writeInt(CRC32().apply { update(type); update(payload) }.value.toInt())
        } }.toByteArray()
        return png.copyOfRange(0, png.size - 12) + chunk + png.copyOfRange(png.size - 12, png.size)
    }

    @Test fun `upscale wire physically normalizes non square PNG rotation and mirror before declaring expected output`() = runBlocking {
        val f = Fixture()
        try {
            for ((orientation, expectedCorners) in listOf(
                6 to listOf(0 to 95, 0 to 0, 63 to 95, 63 to 0),
                8 to listOf(63 to 0, 63 to 95, 0 to 0, 0 to 95),
                2 to listOf(63 to 0, 0 to 0, 63 to 95, 0 to 95))) {
                val source = markedPng(orientation)
                val original = source.copyOf()
                val (w, h) = if (orientation >= 5) 96 to 64 else 64 to 96
                val result = DesktopImageEditing.png(BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB))
                f.server.enqueue(response(NovelAiPostProcessTab.UPSCALE, result))
                assertContentEquals(result, DesktopNovelAiPostProcessor(f.secrets, f.client).process(source,
                    NovelAiPostProcessTab.UPSCALE, null, NovelAiEnhanceOptions(), null) {})
                val request = assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/ai/upscale", request.path)
                val wire = Base64.getDecoder().decode(Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    .getValue("image").jsonPrimitive.content)
                val raster = assertNotNull(ImageIO.read(ByteArrayInputStream(wire))) // Raw wire pixels, no EXIF rotation.
                assertEquals(w to h, raster.width to raster.height, "orientation=$orientation wire dimensions")
                assertEquals(1, ImageMetadataStripper.readPngOrientation(wire), "No second rotation at receiver")
                val actualCorners = listOf(raster.getRGB(0, 0), raster.getRGB(w - 1, 0),
                    raster.getRGB(0, h - 1), raster.getRGB(w - 1, h - 1))
                assertEquals(expectedCorners.map { (x, y) -> (0x80000000L or (x.toLong() shl 16) or
                    (y.toLong() shl 8) or 0x55L).toInt() }, actualCorners, "orientation=$orientation wire pixels/alpha")
                assertContentEquals(original, source)
            }
            assertEquals(3, f.server.requestCount)
        } finally { f.close() }
    }

    @Test fun `upscale ordinary transparent PNG retains exact source bytes on wire`() = runBlocking {
        val f = Fixture()
        try {
            val source = markedPng(1)
            val output = DesktopImageEditing.png(BufferedImage(128, 192, BufferedImage.TYPE_INT_ARGB))
            f.server.enqueue(response(NovelAiPostProcessTab.UPSCALE, output))
            assertContentEquals(output, DesktopNovelAiPostProcessor(f.secrets, f.client).process(source,
                NovelAiPostProcessTab.UPSCALE, null, NovelAiEnhanceOptions(), null) {})
            val request = assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            val wire = Base64.getDecoder().decode(Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                .getValue("image").jsonPrimitive.content)
            assertContentEquals(source, wire)
            assertEquals(0x80000055.toInt(), assertNotNull(ImageIO.read(ByteArrayInputStream(wire))).getRGB(0, 0))
            assertEquals(1, f.server.requestCount)
        } finally { f.close() }
    }

    @Test fun `original enhance and upscale have separate units and immutable actual parent without implicit retry`() = runBlocking {
        for (tab in NovelAiPostProcessTab.entries) {
            val f = Fixture()
            try {
                val prompt = NovelAiPromptPlan("synthetic-private", emptyList())
                val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL)
                val parameters = Json.parseToJsonElement(NovelAiImageService(f.client).buildRequestBody(prompt,
                    NovelAiImageSize(64, 64, "Synthetic"), settings)).jsonObject.getValue("parameters").jsonObject
                val source = NovelAiEnhanceSource(prompt, settings, parameters)
                val output = png(if (tab == NovelAiPostProcessTab.UPSCALE) 128 else 64)
                f.server.enqueue(response(tab, output))
                val processor = DesktopNovelAiPostProcessor(f.secrets, f.client, f.diagnostics)
                val parent = DesktopUsageContext(DesktopUsageSource.CHAT, taskId = "original-task", sessionId = "original-session", subjectId = "original-image")
                val credential = DesktopCredentialCall(f.secrets, DesktopCredentialKey.NovelAiToken, "frozen", "synthetic-key", "account")
                withContext(DesktopUsageParent(parent) + credential) {
                    assertContentEquals(output, processor.process(png(64), tab, source, NovelAiEnhanceOptions(), null) {})
                }
                val row = f.rows().single()
                assertEquals(if (tab == NovelAiPostProcessTab.UPSCALE) DesktopUsageMediaKind.NAI_UPSCALE else DesktopUsageMediaKind.NAI_ENHANCE, row.media!!.kind)
                assertEquals(DesktopUsageOutcome.COMPLETED, row.outcome); assertEquals(1L, row.media!!.outputImages)
                assertEquals(output.size.toLong(), row.media!!.outputImageBytes); assertEquals("account", row.accountId)
                assertEquals(parent.taskId, row.taskId); assertEquals(parent.subjectId, row.subjectId)
                if (tab == NovelAiPostProcessTab.UPSCALE) assertEquals("upscale-model-unreported", row.modelId)
                f.server.enqueue(MockResponse().setResponseCode(429))
                assertFailsWith<DesktopNovelAiRequestException> { processor.process(png(64), tab, source, NovelAiEnhanceOptions(), null) {} }
                assertEquals(2, f.server.requestCount)
                assertEquals(setOf(DesktopUsageOutcome.COMPLETED, DesktopUsageOutcome.FAILED), f.rows().map { it.outcome }.toSet())
                assertTrue(f.rows().all { it.counters == DesktopUsageCounters() && it.media!!.requestedEncodings == null })
            } finally { f.close() }
        }
    }

    @Test fun `vibe actual miss records validated encoded bytes and cache or retained payload reuse does not add a request`() = runBlocking {
        val f = Fixture()
        try {
            val draft = f.draft(); val guidance = f.guidance()
            f.server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
            val parent = DesktopUsageContext(DesktopUsageSource.CHARACTER_CARD, taskId = "work", editorSessionId = "draft", personId = "person")
            val result = withContext(DesktopUsageParent(parent)) { guidance.prepare(draft) }
            assertEquals("AQID", result.guidance.vibes.single().encoding)
            assertEquals(result.guidance.vibes, guidance.prepare(draft).guidance.vibes)
            assertEquals(result.guidance.vibes, guidance.prepare(draft.copy(imageGuidance = result.retainedGuidance)).guidance.vibes)
            assertEquals(1, f.server.requestCount)
            val row = f.rows().single()
            assertEquals(DesktopUsageOutcome.COMPLETED, row.outcome); assertEquals(DesktopUsageMediaKind.NAI_VIBE_ENCODING, row.media!!.kind)
            assertEquals(1L, row.media!!.requestedEncodings); assertEquals(3L, row.media!!.outputEncodingBytes)
            assertNull(row.media!!.requestedImages); assertNull(row.media!!.outputImages); assertNull(row.sessionId)
            assertEquals(parent.editorSessionId, row.editorSessionId); assertEquals(parent.personId, row.personId)
            val store = DesktopProviderUsageStore(f.root, f.gate); store.save(row)
            val original = Files.readAllBytes(f.root.resolve("diagnostics/provider-usage/${row.id}.json"))
            for (bad in listOf(row.media!!.copy(outputImages = 1), row.media!!.copy(inputTextCodePoints = 1),
                row.media!!.copy(requestedEncodings = 2), row.media!!.copy(outputEncodingBytes = -1))) {
                assertFails { store.save(row.copy(revision = row.revision + 1, media = bad)) }
            }
            assertContentEquals(original, Files.readAllBytes(f.root.resolve("diagnostics/provider-usage/${row.id}.json")))
            val reopened = DesktopProviderUsageStore(f.root, f.gate).records().single()
            assertEquals(row, reopened); val total = summarizeDesktopUsage(listOf(row, row)).media.single()
            assertEquals(1L, total.requestedEncodings.value); assertEquals(3L, total.outputEncodingBytes.value)
            assertNull(total.outputImageBytes.value)
            assertFalse(original.toString(Charsets.UTF_8).contains("synthetic-key")); assertFalse(original.toString(Charsets.UTF_8).contains("AQID"))
        } finally { f.close() }
    }

    @Test fun `vibe cache publication failure keeps original network completion while rejection or cancellation never succeeds`() = runBlocking {
        val f = Fixture()
        try {
            val draft = f.draft(); val guidance = f.guidance()
            Files.createDirectories(f.root.resolve("auxiliary/novelai")); Files.writeString(f.root.resolve("auxiliary/novelai/vibe-cache"), "original-canary")
            f.server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
            assertFails { guidance.prepare(draft) }
            assertEquals(DesktopUsageOutcome.COMPLETED, f.rows().single().outcome)
            assertEquals("original-canary", Files.readString(f.root.resolve("auxiliary/novelai/vibe-cache")))
            f.server.enqueue(MockResponse().setResponseCode(401))
            assertFails { guidance.prepare(draft) }
            f.server.takeRequest(5, TimeUnit.SECONDS); f.server.takeRequest(5, TimeUnit.SECONDS)
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { guidance.prepare(draft) }
            assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5000) { job.cancelAndJoin() }
            assertEquals(setOf(DesktopUsageOutcome.COMPLETED, DesktopUsageOutcome.FAILED, DesktopUsageOutcome.CANCELLED), f.rows().map { it.outcome }.toSet())
            assertEquals(3, f.server.requestCount); assertTrue(f.rows().all { it.media!!.requestedImages == null })
        } finally { f.close() }
    }
}
