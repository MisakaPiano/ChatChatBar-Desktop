package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.image.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.msgpack.core.MessagePack
import kotlin.test.*

class DesktopCharacterImageGenerationTest {
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

    private fun frame(type: String, bytes: ByteArray): ByteArray {
        val pack = MessagePack.newDefaultBufferPacker()
        pack.packMapHeader(3); pack.packString("event_type"); pack.packString(type)
        pack.packString("image"); pack.packBinaryHeader(bytes.size); pack.writePayload(bytes)
        pack.packString("step_ix"); pack.packInt(1); pack.close()
        val payload = pack.toByteArray()
        return ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()
    }
}
