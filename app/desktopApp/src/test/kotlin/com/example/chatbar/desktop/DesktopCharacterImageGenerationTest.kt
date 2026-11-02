package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.prompt.CharacterCardAiPromptAuthority
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopCharacterImageGenerationTest {
    @Test fun `actual gateway serializes nondefault card parameters for both models and retains avatar size`() = runBlocking {
        MockWebServer().use { server ->
            val owner = DesktopCharacterImageGeneration(secrets(), client(server))
            try {
                for (model in NovelAiImageModel.entries) {
                    val parameters = NovelAiCharacterImageSettings(sampler = NovelAiSampler.EULER, steps = 19,
                        guidance = 4.5f, cfgRescale = 0.35f, varietyPlus = true)
                    server.enqueue(MockResponse().setBody(Buffer().write(frame("final", image))))
                    owner.generate(plan, CharacterAvatarImagePolicy.imageSize, parameters.applyTo(NovelAiGenerationSettings.legacy(123, model = model)))
                    val body = Json.parseToJsonElement(assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
                    assertEquals(model.apiId, body.getValue("model").jsonPrimitive.content)
                    val wire = body.getValue("parameters").jsonObject
                    assertEquals(19, wire.getValue("steps").jsonPrimitive.int)
                    assertEquals(4.5f, wire.getValue("scale").jsonPrimitive.float)
                    assertEquals(0.35f, wire.getValue("cfg_rescale").jsonPrimitive.float)
                    assertEquals(NovelAiSampler.EULER.apiId, wire.getValue("sampler").jsonPrimitive.content)
                    assertEquals(512, wire.getValue("width").jsonPrimitive.int)
                    assertEquals(512, wire.getValue("height").jsonPrimitive.int)
                    assertEquals(model.supportsVarietyPlus, wire.containsKey("skip_cfg_above_sigma"))
                }
                assertEquals(2, server.requestCount)
            } finally { owner.closeAndDrain() }
        }
    }

    private val plan = NovelAiPromptPlan("synthetic", emptyList(), negativePrompt = "synthetic-negative")
    private val settings = NovelAiGenerationSettings.legacy(123)
    private val image get() = DesktopImageEditing.png(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB))
    private fun secrets() = InMemoryDesktopSecretStore().also { it.save(DesktopCredentialKey.NovelAiToken, "synthetic-key") }
    private fun client(server: MockWebServer) = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
    }.build()

    @Test fun `actual provider frames return only completed final bytes and intermediate alone fails`() = runBlocking {
        MockWebServer().use { server ->
            val owner = DesktopCharacterImageGeneration(secrets(), client(server))
            try {
                val bytes = image
                server.enqueue(MockResponse().setBody(Buffer().write(frame("intermediate", bytes) + frame("final", bytes))))
                var previews = 0
                val final = withTimeout(5_000) { owner.generate(plan, CharacterAvatarImagePolicy.imageSize, settings,
                    onPreview = { _, _ -> previews++ }) }
                assertContentEquals(bytes, final); assertEquals(2, previews)
                assertEquals("Bearer synthetic-key", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                server.enqueue(MockResponse().setBody(Buffer().write(frame("intermediate", bytes))))
                assertFailsWith<IllegalStateException> { withTimeout(5_000) { owner.generate(plan, CharacterAvatarImagePolicy.imageSize, settings) } }
                assertEquals(2, server.requestCount)
            } finally { owner.closeAndDrain() }
        }
    }

    @Test fun `cancellation drains actual image request without publishing candidate`() = runBlocking {
        MockWebServer().use { server ->
            val owner = DesktopCharacterImageGeneration(secrets(), client(server))
            try {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                var completed = false
                val job = launch(Dispatchers.IO) {
                    owner.generate(plan, CharacterAvatarImagePolicy.imageSize, settings); completed = true
                }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                withTimeout(5_000) { job.cancelAndJoin() }
                assertFalse(completed); assertEquals(1, server.requestCount)
            } finally { owner.closeAndDrain() }
        }
    }

    @Test fun `actual avatar request keeps original positive negative order default and manual inputs while non avatar is unchanged`() = runBlocking {
        MockWebServer().use { server ->
            val owner = DesktopCharacterImageGeneration(secrets(), client(server))
            var calls = 0
            try {
                for (model in listOf(NovelAiImageModel.V4_5_FULL, NovelAiImageModel.V5_FULL)) {
                    for ((style, character, negative) in listOf(Triple("style", "person", "custom-negative"),
                        Triple("style", "person", ""), Triple("", "manual-avatar", "manual-negative"))) {
                        val planned = CharacterAvatarImagePolicy.promptPlan(style, character, negative)
                        server.enqueue(MockResponse().setBody(Buffer().write(frame("final", image))))
                        owner.generate(planned, CharacterAvatarImagePolicy.imageSize, settings.copy(model = model))
                        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                        val wire = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val parameters = wire.getValue("parameters").jsonObject
                        val positive = (listOf(style, character).filter(String::isNotEmpty) +
                            CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT).joinToString(", ")
                        val combinedNegative = CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(negative) + ", " +
                            CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_NEGATIVE_PROMPT
                        assertEquals(positive, wire.getValue("input").jsonPrimitive.content)
                        assertEquals(positive, parameters.getValue("v4_prompt").jsonObject.getValue("caption").jsonObject.getValue("base_caption").jsonPrimitive.content)
                        assertEquals(combinedNegative, parameters.getValue("negative_prompt").jsonPrimitive.content)
                        assertEquals(combinedNegative, parameters.getValue("v4_negative_prompt").jsonObject.getValue("caption").jsonObject.getValue("base_caption").jsonPrimitive.content)
                        assertEquals(512, parameters.getValue("width").jsonPrimitive.int)
                        assertEquals(512, parameters.getValue("height").jsonPrimitive.int)
                        assertEquals(++calls, server.requestCount)
                    }
                }
                val cover = NovelAiPromptPlan("cover-input", emptyList(), negativePrompt = "cover-negative")
                server.enqueue(MockResponse().setBody(Buffer().write(frame("final", image))))
                owner.generate(cover, NovelAiImageSize(768, 1024, "Fixture cover"), settings)
                val wire = Json.parseToJsonElement(assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()).jsonObject
                assertEquals("cover-input", wire.getValue("input").jsonPrimitive.content)
                assertEquals("cover-negative", wire.getValue("parameters").jsonObject.getValue("negative_prompt").jsonPrimitive.content)
                assertEquals(768, wire.getValue("parameters").jsonObject.getValue("width").jsonPrimitive.int)
                assertEquals(1024, wire.getValue("parameters").jsonObject.getValue("height").jsonPrimitive.int)
                assertEquals(calls + 1, server.requestCount)
            } finally { owner.closeAndDrain() }
        }
    }

    private fun frame(type: String, bytes: ByteArray): ByteArray {
        val pack = MessagePack.newDefaultBufferPacker()
        pack.packMapHeader(3); pack.packString("event_type"); pack.packString(type)
        pack.packString("image"); pack.packBinaryHeader(bytes.size); pack.writePayload(bytes)
        pack.packString("step_ix"); pack.packInt(1); pack.close()
        val payload = pack.toByteArray()
        return ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()
    }
}
