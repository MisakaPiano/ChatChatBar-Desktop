package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class DesktopNovelAiEndpointPolicyTest {
    @Test fun `original generation guard stays narrow and each operation admits only its fixed official routes`() {
        val permitted = mapOf(
            DesktopNovelAiHttpPurpose.GENERATION to setOf("/user/subscription", "/ai/generate-image-stream"),
            DesktopNovelAiHttpPurpose.VIBE_ENCODING to setOf("/ai/encode-vibe"),
            DesktopNovelAiHttpPurpose.POST_PROCESS to setOf("/ai/generate-image-stream", "/ai/upscale"))
        val paths = permitted.values.flatten().toSet() + "/ai/unknown"
        for ((purpose, allowed) in permitted) {
            var reached = 0
            val client = secureNovelAiClient(purpose).newBuilder().addInterceptor { chain ->
                reached++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body("{}".toResponseBody()).build()
            }.build()
            try {
                assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
                for (path in paths) {
                    val previous = reached
                    val request = Request.Builder().url("https://image.novelai.net$path").build()
                    if (path in allowed) client.newCall(request).execute().use { assertEquals(200, it.code) }
                    else assertFailsWith<IllegalStateException> { client.newCall(request).execute().close() }
                    assertEquals(previous + if (path in allowed) 1 else 0, reached)
                }
                for (url in listOf("http://image.novelai.net${allowed.first()}", "https://image.novelai.net:444${allowed.first()}",
                    "https://foreign.invalid${allowed.first()}")) {
                    val previous = reached
                    val request = Request.Builder().url(url).build()
                    if (request.url.scheme == "http")
                        assertFailsWith<java.io.IOException> { client.newCall(request).execute().close() }
                    else assertFailsWith<IllegalStateException> { client.newCall(request).execute().close() }
                    assertEquals(previous, reached)
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun `actual guidance encodes a new V45 reference through production guard then reuses original cache and retained encoding`() = runBlocking {
        val root = Files.createTempDirectory("vibe-production-route-")
        val image = DesktopImageEditing.png(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB))
        val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "synthetic-vibe-key") }
        var calls = 0
        val client = secureNovelAiClient(DesktopNovelAiHttpPurpose.VIBE_ENCODING).newBuilder().addInterceptor { chain ->
            calls++; assertEquals("/ai/encode-vibe", chain.request().url.encodedPath)
            assertEquals("Bearer synthetic-vibe-key", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(byteArrayOf(1, 2, 3).toResponseBody()).build()
        }.build()
        try {
            Files.createDirectories(root.resolve("images")); Files.write(root.resolve("images/reference.png"), image)
            val asset = NovelAiStudioAssetRef(path = "images/reference.png", sha256 = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }, width = 64, height = 64)
            val reference = NovelAiVibeReferenceDraft(asset = asset)
            val draft = NovelAiStudioDraft(basePrompt = "synthetic", selectedModel = NovelAiImageModel.V4_5_FULL,
                imageGuidance = NovelAiImageGuidanceDraft(referenceMode = NovelAiReferenceMode.VIBE, vibes = listOf(reference)))
            val guidance = DesktopNovelAiGuidance(root, DesktopCharacterResourceStore(root), secrets, vibeClient = client)
            assertEquals(1, guidance.vibeCacheMisses(draft))
            val first = guidance.prepare(draft)
            assertEquals("AQID", first.guidance.vibes.single().encoding)
            assertEquals(0, guidance.vibeCacheMisses(draft)); assertEquals(1, calls)
            assertEquals(first.guidance.vibes, guidance.prepare(draft).guidance.vibes)
            assertEquals(first.guidance.vibes, guidance.prepare(draft.copy(imageGuidance = first.retainedGuidance)).guidance.vibes)
            assertEquals(1, calls)
            assertEquals("synthetic-vibe-key", secrets.load(DesktopCredentialKey.NovelAiToken))
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); check(root.toFile().deleteRecursively()) }
    }

    @Test fun `actual upscale uses its fixed guarded route and retains single attempt rejection semantics`() = runBlocking {
        val input = DesktopImageEditing.png(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB))
        val output = DesktopImageEditing.png(BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB))
        val secrets = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.NovelAiToken, "synthetic-upscale-key") }
        var calls = 0; var code = 200
        val client = secureNovelAiClient(DesktopNovelAiHttpPurpose.POST_PROCESS).newBuilder().addInterceptor { chain ->
            calls++; assertEquals("/ai/upscale", chain.request().url.encodedPath)
            val body = if (code == 200) """{"images":[{"image":"${Base64.getEncoder().encodeToString(output)}"}]}""" else "synthetic-refusal"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body.toResponseBody()).build()
        }.build()
        try {
            val processor = DesktopNovelAiPostProcessor(secrets, client)
            assertContentEquals(output, processor.process(input, NovelAiPostProcessTab.UPSCALE, null, NovelAiEnhanceOptions(), null) {})
            assertEquals(1, calls); code = 429
            assertFailsWith<DesktopNovelAiRequestException> { processor.process(input, NovelAiPostProcessTab.UPSCALE, null, NovelAiEnhanceOptions(), null) {} }
            assertEquals(2, calls)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
