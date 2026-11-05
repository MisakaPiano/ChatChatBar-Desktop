package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.image.*
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.msgpack.core.MessagePack
import kotlin.test.*

class Moment147RuntimeTest {
    private val card = CharacterCard("card", "Synthetic", createdAt = 1, updatedAt = 1)
    private val session = ChatSession("session", card.id, "Synthetic", createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("model", "Synthetic", "https://example.invalid", "synthetic-key", "model", createdAt = 1)
    private val messages = listOf(ChatMessage("message", session.id, MessageRole.ASSISTANT, "Synthetic context", createdAt = 1, updatedAt = 1))
    private fun noText() = object : MomentTextGateway {
        override suspend fun completeText(messages: List<ChatApiMessage>, modelConfig: ModelConfig, operation: MomentTextOperation): String = error("completed checkpoint")
        override fun streamText(messages: List<ChatApiMessage>, modelConfig: ModelConfig, operation: MomentTextOperation): Flow<StreamEvent> = error("completed checkpoint")
    }

    @Test fun `normal image runtime sends card generation settings in final request without judge or draft replay`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val image = byteArrayOf(1, 2, 3)
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2); packer.packString("event_type"); packer.packString("final")
        packer.packString("image"); packer.packBinaryHeader(image.size); packer.writePayload(image); packer.close()
        val payload = packer.toByteArray()
        val frame = ByteBuffer.allocate(payload.size + 4).putInt(payload.size).put(payload).array()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val bytes = Buffer(); checkNotNull(chain.request().body).writeTo(bytes)
            requests += Json.parseToJsonElement(bytes.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(frame.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
        try {
            val settings = NovelAiCharacterImageSettings(NovelAiSampler.EULER, 24, 5f, 0.3f, true)
            val configured = card.copy(defaultImageGenerationSettings = settings)
            var saves = 0
            val core = MomentGenerationCore(noText(), { error("completed design checkpoint") }, { NovelAiImageService(client) },
                { _, bytes -> assertContentEquals(image, bytes); saves++; "images/synthetic.png" }, { "synthetic-token" })
            val checkpoint = MomentGenerationCheckpoint(MomentPostDecision(false),
                MomentDraft(shouldPost = false, text = "Synthetic post"), NovelAiPromptPlan("synthetic tags", emptyList()))
            val posted = assertIs<MomentGenerationResult.Posted>(core.generate(configured, session, messages, null, model, model,
                scheduledAt = 1, resumeFrom = checkpoint))
            assertEquals(1, saves); assertEquals("images/synthetic.png", posted.post.imagePath)
            val parameters = requests.single().getValue("parameters").jsonObject
            assertEquals(settings.sampler.apiId, parameters.getValue("sampler").jsonPrimitive.content)
            assertEquals(settings.steps, parameters.getValue("steps").jsonPrimitive.int)
            assertEquals(settings.guidance, parameters.getValue("scale").jsonPrimitive.float)
            assertEquals(settings.cfgRescale, parameters.getValue("cfg_rescale").jsonPrimitive.float)
            assertEquals(1024, parameters.getValue("width").jsonPrimitive.int)
            assertEquals(1024, parameters.getValue("height").jsonPrimitive.int)
            assertEquals(58.0 * kotlin.math.sqrt(128.0 * 128 / (104 * 152)),
                parameters.getValue("skip_cfg_above_sigma").jsonPrimitive.double, 1e-9)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun `debug requests generation once without judge and propagates cancellation`() = runBlocking {
        for (cancel in listOf(false, true)) {
            val operations = mutableListOf<MomentTextOperation>()
            val gateway = object : MomentTextGateway {
                override suspend fun completeText(messages: List<ChatApiMessage>, modelConfig: ModelConfig, operation: MomentTextOperation): String {
                    operations += operation
                    if (cancel) throw CancellationException("synthetic cancel")
                    return """{"shouldPost":false,"text":"Synthetic post"}"""
                }
                override fun streamText(messages: List<ChatApiMessage>, modelConfig: ModelConfig, operation: MomentTextOperation): Flow<StreamEvent> = error("debug uses complete mode")
            }
            val core = MomentGenerationCore(gateway, { error("no image") }, { error("no image") }, { _, _ -> error("no image") }, { null })
            if (cancel) assertFailsWith<CancellationException> { core.debugGenerateNow(card, session, messages, null, model, null) }
            else assertEquals("Synthetic post", assertNotNull(core.debugGenerateNow(card, session, messages, null, model, null).post).text)
            assertEquals(listOf(MomentTextOperation.GENERATE), operations)
        }
    }
}
