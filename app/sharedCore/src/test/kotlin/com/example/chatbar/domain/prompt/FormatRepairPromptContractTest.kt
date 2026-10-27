package com.example.chatbar.domain.prompt

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.OpenAiChatRequestSerializer
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.test.*

/** Fixed resources were extracted from 1.4.1 plus D-036, not from production assembly. */
class FormatRepairPromptContractTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/format-repair/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun golden() = Json.parseToJsonElement(resource("effective-eight-messages.json")).jsonArray
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

    @Test fun `baseline ledger allows only D036 auxiliary confirmation exception`() {
        val ledger = Json.parseToJsonElement(resource("source-ledger.json")).jsonObject
        val constants = ledger.getValue("constants").jsonObject
        val actual = mapOf(
            "GENERAL_SYSTEM_PROMPT" to AuxiliaryPromptAuthority.GENERAL_SYSTEM_PROMPT,
            "GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT" to AuxiliaryPromptAuthority.GENERAL_CREATOR_IDENTITY_SYSTEM_PROMPT,
            "GENERAL_FIRST_ACK_ASSISTANT_PROMPT" to AuxiliaryPromptAuthority.GENERAL_FIRST_ACK_ASSISTANT_PROMPT,
            "GENERAL_CREATIVE_CONTRACT_USER_PROMPT" to AuxiliaryPromptAuthority.GENERAL_CREATIVE_CONTRACT_USER_PROMPT,
            "GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT" to AuxiliaryPromptAuthority.GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT,
            "GENERAL_CONTEXT_APPROVAL_ASSISTANT_PROMPT" to AuxiliaryPromptAuthority.GENERAL_CONTEXT_APPROVAL_ASSISTANT_PROMPT,
            "GENERAL_POST_USER_ACK_ASSISTANT_PROMPT" to AuxiliaryPromptAuthority.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT,
            "GENERAL_POST_USER_IDENTITY_REMINDER_USER_PROMPT" to AuxiliaryPromptAuthority.GENERAL_POST_USER_IDENTITY_REMINDER_USER_PROMPT,
        )
        assertEquals(actual.keys, constants.keys)
        actual.forEach { (name, text) ->
            val entry = constants.getValue(name).jsonObject
            assertEquals(entry.getValue("sha256").jsonPrimitive.content, digest(text), name)
            val exception = name == "GENERAL_CONTRACT_CONFIRMATION_ASSISTANT_PROMPT"
            assertEquals(ledger.getValue(if (exception) "exception" else "baseline"), entry.getValue("source"))
            assertEquals(exception, "baselineSha256" in entry)
            if (exception) assertNotEquals(entry.getValue("baselineSha256"), entry.getValue("sha256"))
        }
        assertEquals(ledger.getValue("repairSystemSha256").jsonPrimitive.content,
            digest(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT))
        assertEquals(ledger.getValue("repairMarkerSha256").jsonPrimitive.content,
            digest(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER))
    }

    @Test fun `effective eight messages match fixed historical golden completely`() {
        val messages = assembled()
        assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant", "user"), messages.map { it.role })
        assertEquals(golden(), asJson(messages))
        assertEquals(1, messages.count { it.content.jsonPrimitive.content.contains("完整正文\n第二段 😀") })
        assertEquals(golden()[4].jsonObject.getValue("content"), messages[4].content)
    }

    @Test fun `repeated assembly never duplicates confirmation envelope`() {
        val once = assembled()
        assertEquals(once, AuxiliaryMessageAssembler.assemble(once, preservesInputRoles = false))
        assertEquals(golden(), asJson(AuxiliaryMessageAssembler.assemble(once, false)))
    }

    @Test fun `serializer retains all effective golden messages`() {
        val serialized = OpenAiChatRequestSerializer.serialize(assembled(), ModelConfig(
            id = "fixture", displayName = "Fixture", baseUrl = "https://example.test/v1",
            apiKey = "synthetic-only", modelName = "fixture-model", createdAt = 0
        ), stream = true, disableThinking = true)
        assertEquals(golden(), Json.parseToJsonElement(serialized).jsonObject.getValue("messages"))
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
