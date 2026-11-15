package com.example.chatbar.domain.prompt

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.OpenAiChatRequestSerializer
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.test.*

/** Historical repair protocol is retained; the independently adopted envelope is verified by role and input custody. */
class FormatRepairPromptContractTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/format-repair/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun inputs() = listOf(
        ChatApiMessage.text("system", FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()),
        ChatApiMessage.text("user", FormatRepairPromptAuthority.messageFormatRepairUserPrompt(
            " 合成格式 ", " 合成人物规则 ", "完整正文\n第二段 😀"))
    )
    private fun assembled() = AuxiliaryMessageAssembler.assemble(inputs(), preservesInputRoles = false)
    private fun asJson(messages: List<ChatApiMessage>) = JsonArray(messages.map { message ->
        buildJsonObject { put("role", message.role); put("content", message.content) }
    })

    @Test fun `unchanged repair protocol keeps its independently frozen source`() {
        val ledger = Json.parseToJsonElement(resource("source-ledger.json")).jsonObject
        assertEquals(ledger.getValue("repairSystemSha256").jsonPrimitive.content,
            digest(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT))
        assertEquals(ledger.getValue("repairMarkerSha256").jsonPrimitive.content,
            digest(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER))
    }

    @Test fun `repair uses seven messages with input and assistant tail in their declared positions`() {
        val messages = assembled()
        assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant"), messages.map { it.role })
        assertEquals(inputs()[1].content, messages[4].content)
        assertEquals(JsonPrimitive(AuxiliaryPromptAuthority.GENERAL_CONTEXT_APPROVAL_ASSISTANT_PROMPT), messages[5].content)
        assertEquals(JsonPrimitive(AuxiliaryPromptAuthority.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT), messages[6].content)
        val system = messages[0].content.jsonPrimitive.content
        assertTrue(system.startsWith(AuxiliaryPromptAuthority.GENERAL_SYSTEM_PROMPT))
        assertTrue(system.contains(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()))
        assertTrue(system.endsWith(AuxiliaryPromptAuthority.GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT))
    }

    @Test fun `repeated assembly never duplicates confirmation envelope`() {
        val once = assembled()
        assertEquals(once, AuxiliaryMessageAssembler.assemble(once, preservesInputRoles = false))
    }

    @Test fun `serializer retains every effective message`() {
        val serialized = OpenAiChatRequestSerializer.serialize(assembled(), ModelConfig(
            id = "fixture", displayName = "Fixture", baseUrl = "https://example.test/v1",
            apiKey = "synthetic-only", modelName = "fixture-model", createdAt = 0
        ), stream = true, disableThinking = true)
        assertEquals(asJson(assembled()), Json.parseToJsonElement(serialized).jsonObject.getValue("messages"))
    }

    @Test fun `optional rules preserve baseline literal order and full input`() {
        val full = "# 完整原文\n[对白]()\n😀\n"
        val cardHeading = "{格式卡要求}（禁止覆盖应用格式卡中的占位文本，如对选项的描述）"
        for (card in listOf(null, "", " \n", " 卡 ")) {
            for (segmented in listOf(null, "", " \n", " 段 ")) {
                val expected = when {
                    card == " 卡 " && segmented == " 段 " -> "$cardHeading\n卡\n\n{分段气泡格式}\n段\n\n{待修复消息}\n$full"
                    card == " 卡 " -> "$cardHeading\n卡\n\n{待修复消息}\n$full"
                    segmented == " 段 " -> "{分段气泡格式}\n段\n\n{待修复消息}\n$full"
                    else -> "{待修复消息}\n$full"
                }
                assertEquals(expected, FormatRepairPromptAuthority.messageFormatRepairUserPrompt(card, segmented, full))
            }
        }
    }
}
