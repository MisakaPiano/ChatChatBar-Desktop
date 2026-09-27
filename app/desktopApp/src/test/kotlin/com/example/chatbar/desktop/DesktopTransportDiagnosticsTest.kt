package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.OpenAiChatRequestSerializer
import com.example.chatbar.domain.chat.OpenAiSseChunk
import com.example.chatbar.domain.chat.ProviderCompletionMetadata
import com.example.chatbar.domain.chat.ProviderRequestEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTransportDiagnosticsTest {
    @Test
    fun `retained request is final cleartext serialization and https roles remain untouched`() {
        val owner = DesktopTransportDiagnosticsOwner()
        val model = model("fake-secret")
        val messages = listOf(
            ChatApiMessage.text("system", "first"),
            ChatApiMessage.text("user", "question"),
            ChatApiMessage.text("system", "later"),
            ChatApiMessage.text("user", "tail"),
        )
        val cleartext = OpenAiChatRequestSerializer.serialize(
            messages = messages,
            modelConfig = model,
            stream = true,
            allowCleartextHttp = true,
        )
        val https = OpenAiChatRequestSerializer.serialize(
            messages = messages,
            modelConfig = model.copy(baseUrl = "https://local.invalid/v1"),
            stream = true,
            allowCleartextHttp = true,
        )
        owner.begin("http-task", "session", model).onRequest(
            ProviderRequestEvidence("http://127.0.0.1:8080/v1/chat/completions", cleartext),
        )
        owner.begin("https-task", "session", model).onRequest(
            ProviderRequestEvidence("https://local.invalid/v1/chat/completions", https),
        )

        val httpEntry = owner.entries.value.single { it.taskId == "http-task" }
        val httpsEntry = owner.entries.value.single { it.taskId == "https-task" }
        assertEquals(cleartext, httpEntry.serializedRequestBody)
        assertEquals(https, httpsEntry.serializedRequestBody)
        assertEquals(listOf("system", "user", "assistant", "user"), roles(requireNotNull(httpEntry.serializedRequestBody)))
        assertEquals(listOf("system", "user", "system", "user"), roles(requireNotNull(httpsEntry.serializedRequestBody)))
    }

    @Test
    fun `credential fields bearer image payload url query and known key are redacted`() {
        val owner = DesktopTransportDiagnosticsOwner()
        val recorder = owner.begin("privacy", "session", model("fake-api-secret"))
        recorder.onRequest(
            ProviderRequestEvidence(
                url = "https://user:pass@host.invalid/v1/chat/completions?api_key=fake-api-secret#fragment",
                body = """{"api_key":"fake-api-secret","authorization":"Bearer other-secret","messages":[{"content":"Bearer supplied-token data:image/png;base64,AAAAABBBBB"},{"content":"fake-api-secret"}]}""",
            ),
        )
        recorder.onResponseChunk(
            """{"delta":{"content":"Bearer streamed-token data:image/png;base64,CCCCDDDD fake-api-secret"}}""",
            OpenAiSseChunk("Bearer streamed-token", null),
        )
        recorder.onRetry(1, "Bearer retry-token fake-api-secret")
        recorder.finish(DesktopTaskStatus.FAILED, "fake-api-secret Bearer error-token", null)

        val entry = owner.entries.value.single()
        val retained = entry.toString()
        assertFalse(retained.contains("fake-api-secret"))
        assertFalse(retained.contains("other-secret"))
        assertFalse(retained.contains("supplied-token"))
        assertFalse(retained.contains("AAAAABBBBB"))
        assertFalse(retained.contains("CCCCDDDD"))
        assertFalse(retained.contains("user:pass"))
        assertFalse(retained.contains("api_key="))
        assertTrue(requireNotNull(entry.requestUrl).endsWith("/v1/chat/completions"))
        assertTrue(retained.contains("[REDACTED]"))
    }

    @Test
    fun `malformed request url fails closed in diagnostics`() {
        val owner = DesktopTransportDiagnosticsOwner()
        owner.begin("bad-url", "session", model("fake-secret")).onRequest(
            ProviderRequestEvidence("https://user:fake-secret@host.invalid/%zz?token=fake-secret", "{}"),
        )

        val entry = owner.entries.value.single()
        assertEquals("[INVALID URL]", entry.requestUrl)
        assertFalse(entry.toString().contains("fake-secret"))
    }

    @Test
    fun `retry chunks terminal state and retained history are bounded`() {
        val owner = DesktopTransportDiagnosticsOwner(maxEntries = 2)
        val first = owner.begin("one", "session", model("secret"))
        repeat(40) { index ->
            first.onResponseChunk("chunk-$index", null)
        }
        repeat(12) { first.onRetry(it + 1, "retry-$it") }
        first.onCancelled()
        first.finish(
            DesktopTaskStatus.CANCELLED,
            null,
            ProviderCompletionMetadata(finishReason = "stop", transportFailed = true),
        )
        val retained = owner.entries.value.single()
        assertEquals(32, retained.chunks.size)
        assertEquals(8, retained.droppedChunkCount)
        assertEquals(8, retained.retryEvents.size)
        assertTrue(retained.cancellationObserved)
        assertEquals(DesktopTaskStatus.CANCELLED, retained.status)
        assertEquals("stop", retained.finishReason)
        assertEquals(true, retained.transportFailed)
        assertTrue(requireNotNull(retained.completedAt) >= retained.startedAt)

        owner.begin("two", "session", model("secret"))
        owner.begin("three", "session", model("secret"))
        assertEquals(listOf("three", "two"), owner.entries.value.map { it.taskId })
    }

    @Test
    fun `large request preview truncates without altering supplied provider body`() {
        val owner = DesktopTransportDiagnosticsOwner()
        val recorder = owner.begin("large", "session", model("secret"))
        val outbound = """{"messages":[{"content":"${"safe-text".repeat(12_000)}"}]}"""

        recorder.onRequest(ProviderRequestEvidence("https://host.invalid/v1/chat/completions", outbound))

        assertTrue(outbound.length > 65_536)
        assertTrue(requireNotNull(owner.entries.value.single().serializedRequestBody).contains("[truncated]"))
        assertTrue(outbound.endsWith("}]}"))
    }

    private fun roles(body: String): List<String> = Json.parseToJsonElement(body)
        .jsonObject.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }

    private fun model(key: String) = ModelConfig(
        id = "model",
        displayName = "Model",
        baseUrl = "http://127.0.0.1:8080/v1",
        apiKey = key,
        modelName = "fake-model",
        createdAt = 1,
    )
}
