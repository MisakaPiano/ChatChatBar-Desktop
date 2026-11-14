package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.domain.model.GeminiOpenAiEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiCompatibilityTest {
    private fun model(base: String = "https://generativelanguage.googleapis.com/v1beta") = ModelConfig(
        id = "inline", displayName = "Inline", baseUrl = base, apiKey = "synthetic", modelName = "models/inline-gemini",
        createdAt = 0,
    )

    @Test
    fun `only exact official https endpoint is normalized`() {
        listOf("/v1beta", "/v1beta/", "/v1beta/openai", "/v1beta/openai/").forEach { path ->
            val config = model("https://generativelanguage.googleapis.com$path")
            assertEquals(GeminiOpenAiEndpoint.BASE_URL, GeminiOpenAiEndpoint.resolve(config.baseUrl))
            assertEquals("${GeminiOpenAiEndpoint.BASE_URL}/chat/completions", config.chatCompletionsUrl())
        }
        listOf(
            "http://generativelanguage.googleapis.com/v1beta",
            "https://generativelanguage.googleapis.com:8443/v1beta",
            "https://generativelanguage.googleapis.com.attacker.invalid/v1beta",
            "https://user:password@generativelanguage.googleapis.com/v1beta",
            "https://generativelanguage.googleapis.com/v1beta?key=private",
            "https://generativelanguage.googleapis.com/v1beta#fragment",
            "https://generativelanguage.googleapis.com/v1beta/other",
            "https://proxy.invalid/v1beta/openai",
        ).forEach { assertNull(GeminiOpenAiEndpoint.resolve(it), it) }
    }

    @Test
    fun `official request preserves logical input while producing alternating conversation ending user`() {
        val messages = listOf(
            ChatApiMessage.text("system", "root-a"), ChatApiMessage.text("system", "root-b"),
            ChatApiMessage.text("assistant", "prefix"), ChatApiMessage.text("user", "first"),
            ChatApiMessage.text("user", "second"), ChatApiMessage.text("assistant", "answer"),
            ChatApiMessage.text("system", "middle"), ChatApiMessage.text("user", "last"),
            ChatApiMessage.text("assistant", "suffix"), ChatApiMessage.text("system", "trailing"),
        )
        val original = messages.toList()
        val body = Json.parseToJsonElement(OpenAiChatRequestSerializer.serialize(messages, model(), true)).jsonObject
        val wire = body.getValue("messages").jsonArray
        assertEquals(listOf("system", "user", "assistant", "user"), wire.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("inline-gemini", body.getValue("model").jsonPrimitive.content)
        assertEquals("root-a\n\nroot-b", wire.first().jsonObject.getValue("content").jsonPrimitive.content)
        val text = wire.toString()
        listOf("prefix", "first", "second", "answer", "middle", "last", "suffix", "trailing").forEach { assertTrue(text.contains(it)) }
        assertEquals(original, messages)
    }

    @Test
    fun `multimodal user turns retain original image parts in order`() {
        val first = ChatApiMessage.withImage("user", "first", "first-image")
        val second = ChatApiMessage.withImage("user", "second", "second-image")
        val result = GeminiChatTemplatePolicy.adaptMessages(listOf(first, second))
        assertEquals(1, result.size)
        assertEquals(JsonArray(first.content.jsonArray + second.content.jsonArray), result.single().content)
    }

    @Test
    fun `unsupported prefix and suffix preserve original roles and complete content`() {
        val before = ChatApiMessage.withImage("assistant", "prefix", "before-image")
        val after = ChatApiMessage.withImage("assistant", "suffix", "after-image")
        val result = GeminiChatTemplatePolicy.adaptMessages(listOf(before, ChatApiMessage.text("user", "question"), after))
        assertEquals("user", result.single().role)
        val text = result.single().content.jsonPrimitive.content
        val prefix = Json.parseToJsonElement(text.substringBefore("\n\n")).jsonArray.single().jsonObject
        val suffix = Json.parseToJsonElement(text.substringAfterLast("\n\n")).jsonArray.single().jsonObject
        assertEquals("assistant", prefix.getValue("role").jsonPrimitive.content)
        assertEquals(before.content, prefix.getValue("content"))
        assertEquals(after.content, suffix.getValue("content"))
    }

    @Test
    fun `empty system only and assistant only inputs retain every original field`() {
        assertTrue(GeminiChatTemplatePolicy.adaptMessages(emptyList()).isEmpty())
        for (role in listOf("system", "assistant")) {
            val input = listOf(ChatApiMessage.text(role, "one"), ChatApiMessage.text(role, "two"))
            val adapted = GeminiChatTemplatePolicy.adaptMessages(input)
            assertEquals("user", adapted.single().role)
            val quoted = Json.parseToJsonElement(adapted.single().content.jsonPrimitive.content).jsonArray
            assertEquals(input.map { it.role }, quoted.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals(input.map { it.content }, quoted.map { it.jsonObject.getValue("content") })
        }
    }

    @Test
    fun `proxy template name does not enable official role or model rewriting`() {
        val config = model("https://proxy.invalid/v1/").copy(templateType = ModelTemplate.GEMINI)
        val messages = listOf(ChatApiMessage.text("user", "question"), ChatApiMessage.text("system", "tail"))
        val wire = Json.parseToJsonElement(OpenAiChatRequestSerializer.serialize(messages, config, false)).jsonObject
        assertEquals("models/inline-gemini", wire.getValue("model").jsonPrimitive.content)
        assertEquals(listOf("user", "system"), wire.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("https://proxy.invalid/v1/chat/completions", config.chatCompletionsUrl())
    }

    @Test
    fun `explicit experimental mode retains its existing message envelope`() {
        val input = listOf(ChatApiMessage.text("system", "root"), ChatApiMessage.text("user", "question"), ChatApiMessage.text("system", "tail"))
        val standard = OpenAiChatRequestSerializer.serialize(input, model(), false)
        val experimental = OpenAiChatRequestSerializer.serialize(input, model(), false, experimentalApiMode = true)
        assertFalse(standard == experimental)
        val request = Json.parseToJsonElement(experimental).jsonObject
        assertEquals("inline-gemini", request.getValue("model").jsonPrimitive.content)
        val contextTool = request.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "context" }
        val context = Json.parseToJsonElement(contextTool.getValue("description").jsonPrimitive.content).jsonArray
        assertEquals(listOf("user", "system"), context.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals(listOf(JsonPrimitive("question"), JsonPrimitive("tail")), context.map { it.jsonObject.getValue("content") })
    }
}
