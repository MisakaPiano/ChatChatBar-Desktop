package com.example.chatbar.domain.budget

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PromptBudgetEstimatesTest {
    private val target = PromptEstimateTarget("合成模型")
    @Test fun `explicit capacity survives model persistence but never changes final wire or invents capacity from output caps`() {
        val model = ModelConfig("model", "Synthetic", "https://example.test/v1", "", "synthetic", maxOutputTokens = 900,
            createdAt = 1)
        val messages = listOf(ChatApiMessage.text("user", "a".repeat(1000)))
        val originalWire = OpenAiChatRequestSerializer.serialize(messages, model, true)
        val configured = model.copy(desktopContextWindowTokens = 1000)
        val codec = Json { encodeDefaults = true }
        val stored = codec.encodeToString(ModelConfig.serializer(), configured)
        assertEquals(configured, codec.decodeFromString(ModelConfig.serializer(), stored))
        assertFalse(codec.encodeToString(ModelConfig.serializer(), model).contains("desktopContextWindowTokens"))
        assertNull(model.promptEstimateTarget().contextWindowTokens)
        assertEquals(originalWire, OpenAiChatRequestSerializer.serialize(messages, configured, true))
        val report = WirePromptBudgetEstimator.estimate(originalWire, configured.promptEstimateTarget())
        assertEquals(1000L, report.target.contextWindowTokens)
        assertEquals(40.0, report.estimatedTextContextPercent)
        assertEquals(WeightedCharacterTokenEstimator.METHOD_ID, report.methodId)
        assertFailsWith<IllegalArgumentException> { model.copy(desktopContextWindowTokens = 0) }
        assertFailsWith<IllegalArgumentException> { model.copy(desktopContextWindowTokens = -1) }
    }
    @Test fun `editor distinguishes modes and disabled fields without changing card or pretending activation`() {
        val person = CharacterInfo("person", "合成人物", profile = "原条目设定", desktopEnabled = false)
        val card = CharacterCard("card", "合成卡", characters = listOf(person), freeformCharacterText = "自由正文",
            avatar = "secret-like-path-not-counted", createdAt = 1, updatedAt = 1)
        val structured = EditorPromptBudgetEstimator.character(card, target)
        val freeform = EditorPromptBudgetEstimator.character(card.copy(editMode = CharacterEditMode.FREEFORM), target)
        assertEquals(PromptEstimateScope.STORED_EDITOR_FIELDS, structured.scope)
        assertTrue(structured.sections.any { it.id.endsWith(":profile") && it.estimate.tokens > 0 })
        assertFalse(freeform.sections.any { it.id.endsWith(":profile") })
        assertTrue(freeform.sections.any { it.id == "freeform" && it.estimate.tokens > 0 })
        assertFalse(structured.sections.any { it.id == "avatar" })
        assertNull(structured.target.contextWindowTokens)
        assertEquals(person, card.characters.single()); assertFalse(card.characters.single().desktopEnabled)
    }

    @Test fun `wire estimates actual overridden messages instead of trusting stale trace and excludes image bytes`() {
        val original = ChatApiMessage.text("user", "原始文本")
        val trace = listOf(MainChatLogicalMessageTrace(original, MainChatLogicalMessageSource.CURRENT_USER, false))
        val overridden = buildJsonObject { put("messages", buildJsonArray { add(buildJsonObject {
            put("role", "user"); put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "实际变更后的文本") })
                add(buildJsonObject { put("type", "image_url"); put("image_url", "very-long-base64".repeat(1000)) })
            })
        }) }) }.toString()
        val result = WirePromptBudgetEstimator.estimate(overridden, target, trace)
        assertEquals(PromptEstimateScope.FINAL_REQUEST_TEXT, result.scope)
        assertFalse(result.attributedToLogicalSections); assertEquals(1, result.excludedNonTextParts)
        assertEquals(WeightedCharacterTokenEstimator.estimate("实际变更后的文本").tokens, result.estimatedTextTokens)
    }

    @Test fun `exact cleartext adaptation can attribute but merged tail cannot mislabel sections`() {
        val messages = listOf(ChatApiMessage.text("system", "核心"), ChatApiMessage.text("system", "人物"), ChatApiMessage.text("user", "输入"))
        val trace = messages.mapIndexed { index, message -> MainChatLogicalMessageTrace(message,
            listOf(MainChatLogicalMessageSource.CORE, MainChatLogicalMessageSource.CHARACTER, MainChatLogicalMessageSource.CURRENT_USER)[index], false) }
        fun body(items: List<ChatApiMessage>) = buildJsonObject { put("messages", buildJsonArray { items.forEach { add(buildJsonObject {
            put("role", it.role); put("content", it.content)
        }) } }) }.toString()
        val actual = CleartextHttpChatTemplatePolicy.adaptMessages(messages, true, "http://127.0.0.1/v1")
        assertTrue(WirePromptBudgetEstimator.estimate(body(actual), target, trace, true, "http://127.0.0.1/v1").attributedToLogicalSections)
        assertFalse(WirePromptBudgetEstimator.estimate(body(actual), target, trace).attributedToLogicalSections)
        val named = Json.parseToJsonElement(body(messages)).jsonObject.toMutableMap().also { root ->
            root["messages"] = JsonArray(messages.map { message -> buildJsonObject {
                put("role", message.role); put("content", message.content); put("name", "extra-field")
            } })
        }.let(::JsonObject).toString()
        val extraFields = WirePromptBudgetEstimator.estimate(named, target, trace)
        assertFalse(extraFields.attributedToLogicalSections); assertTrue(extraFields.additionalMessageFieldsExcluded)
        val trailing = messages + ChatApiMessage.text("system", "尾部")
        val merged = CleartextHttpChatTemplatePolicy.adaptMessages(trailing, true, "http://127.0.0.1/v1")
        assertFalse(WirePromptBudgetEstimator.estimate(body(merged), target, trace, true, "http://127.0.0.1/v1").attributedToLogicalSections)
    }

    @Test fun `world book and format count stored fields separately from runtime effects and preserve settings`() {
        val entry = WorldBookEntry("entry", "合成", listOf("触发"), "正文", enabled = false)
        val book = WorldBook("book", "书", entries = listOf(entry))
        val estimate = EditorPromptBudgetEstimator.worldBook(book, target)
        assertTrue(estimate.sections.any { it.id.endsWith(":content") && it.estimate.tokens > 0 })
        assertFalse(estimate.attributedToLogicalSections); assertFalse(book.entries.single().enabled)
        val format = FormatCard("format", "合成", "格式正文", listOf(FormatCardUserToolConfig.randomNumber(),
            FormatCardUserToolConfig.strongPromptSuffix().copy(text = "额外指令")), createdAt = 1)
        val result = EditorPromptBudgetEstimator.format(format, target)
        assertEquals(2, result.sections.size)
        assertEquals(result.sections.sumOf { it.estimate.tokens }, result.estimatedTextTokens)
        assertEquals(0L, WeightedCharacterTokenEstimator.estimate("").tokens)
    }
}
