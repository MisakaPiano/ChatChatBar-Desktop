package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SiliconFlowChatTemplatePolicyTest {
    private val model = ModelConfig(id = "synthetic", displayName = "synthetic",
        modelName = "deepseek-ai/DeepSeek-V3.2", baseUrl = "https://api.siliconflow.cn/v1",
        apiKey = "", createdAt = 0L)

    private fun message(role: String, content: JsonElement) = buildJsonObject {
        put("role", role); put("content", content)
    }

    private fun body(messages: List<JsonObject>) = buildJsonObject {
        put("messages", JsonArray(messages)); put("stream", true)
        put("enable_thinking", true); put("thinking_budget", 1024)
    }

    @Test fun `ordinary tail preserves every exact role and content inside last user and is idempotent`() {
        val messages = listOf(message("system", JsonPrimitive("core")),
            message("user", JsonPrimitive("current input")),
            message("system", JsonPrimitive("format rule")),
            message("assistant", JsonPrimitive("reply prefix:")))
        val original = body(messages)
        val adapted = SiliconFlowChatTemplatePolicy.adaptRequest(original, model)
        val sent = adapted.getValue("messages").jsonArray
        assertEquals(listOf("system", "user"), sent.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals(messages.first(), sent.first())
        val content = sent.last().jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(content.startsWith("current input\n\n"))
        assertEquals(JsonArray(messages.drop(2)), Json.parseToJsonElement(content.substringAfter("current input\n\n")))
        assertEquals(original.filterKeys { it != "messages" }, adapted.filterKeys { it != "messages" })
        assertFalse(adapted.containsKey("prefix"))
        assertEquals(adapted, SiliconFlowChatTemplatePolicy.adaptRequest(adapted, model))
        assertEquals(JsonPrimitive("reply prefix:"), messages.last()["content"])
    }

    @Test fun `all text parts flatten but image parts and their order remain native`() {
        val texts = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "first") })
            add(buildJsonObject { put("type", "text"); put("text", "second") })
        }
        val image = buildJsonObject {
            put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:synthetic") })
        }
        val mixed = JsonArray(texts + image)
        val sent = SiliconFlowChatTemplatePolicy.adaptRequest(body(listOf(
            message("system", texts), message("user", mixed),
            message("assistant", JsonPrimitive("tail")))), model).getValue("messages").jsonArray
        assertEquals(JsonPrimitive("first\n\nsecond"), sent.first().jsonObject["content"])
        val parts = sent.last().jsonObject.getValue("content").jsonArray
        assertEquals(mixed, JsonArray(parts.dropLast(1)))
        assertEquals(JsonArray(listOf(message("assistant", JsonPrimitive("tail")))),
            Json.parseToJsonElement(parts.last().jsonObject.getValue("text").jsonPrimitive.content))
    }

    @Test fun `only official hosts and DS32 family change and assistant only input remains represented`() {
        val original = body(listOf(message("system", JsonPrimitive("core")),
            message("assistant", JsonPrimitive("historical content"))))
        for (config in listOf(model.copy(baseUrl = "https://api.siliconflow.cn.other.test/v1"),
            model.copy(baseUrl = "https://api.deepseek.com/v1"), model.copy(modelName = "other/model"))) {
            assertEquals(original, SiliconFlowChatTemplatePolicy.adaptRequest(original, config))
        }
        for (host in listOf("api.siliconflow.cn", "api.siliconflow.com")) {
            for (name in listOf("deepseek-ai/DeepSeek-V3.2", "Pro/deepseek-ai/DeepSeek-V3.2", "deepseek-ai/DeepSeek-V3.2-Exp")) {
                val sent = SiliconFlowChatTemplatePolicy.adaptRequest(original,
                    model.copy(baseUrl = "https://$host/v1", modelName = name)).getValue("messages").jsonArray
                assertEquals("user", sent.last().jsonObject.getValue("role").jsonPrimitive.content)
                assertEquals(JsonArray(listOf(original.getValue("messages").jsonArray.last())),
                    Json.parseToJsonElement(sent.last().jsonObject.getValue("content").jsonPrimitive.content))
            }
        }
    }
}
