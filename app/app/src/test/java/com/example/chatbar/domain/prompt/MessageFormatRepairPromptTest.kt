package com.example.chatbar.domain.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatRepairPromptTest {
    @Test
    fun `android facade and FORMAT_REPAIR REPAIR context use shared contract`() {
        org.junit.Assert.assertEquals(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT,
            PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT)
        org.junit.Assert.assertEquals(FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER,
            PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER)
        val context = AiTaskContext(AiTaskKind.FORMAT_REPAIR, AiTaskStage.REPAIR)
        assertFalse(context.preservesInputRoles)
        val messages = listOf(
            com.example.chatbar.domain.chat.ChatApiMessage.text("system", PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()),
            com.example.chatbar.domain.chat.ChatApiMessage.text("user", PromptTemplates.messageFormatRepairUserPrompt("卡", "段", "全文")))
        org.junit.Assert.assertEquals(FormatRepairPromptAuthority.messageFormatRepairUserPrompt("卡", "段", "全文"),
            messages[1].content.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        org.junit.Assert.assertEquals(AuxiliaryMessageAssembler.assemble(messages, false),
            AiTaskMessageAssembler.assemble(messages, context))
    }

    @Test
    fun `system prompt includes unchanged result protocol marker`() {
        assertTrue(
            PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.contains(
                PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER
            )
        )
    }

    @Test
    fun `omits empty optional rule blocks`() {
        val prompt = PromptTemplates.messageFormatRepairUserPrompt(
            formatCard = " ",
            segmentedBubbleFormat = null,
            message = "正文"
        )

        assertFalse(prompt.contains("\n\n"))
        assertTrue(prompt.trimEnd().endsWith("正文"))
    }

    @Test
    fun `includes only supplied rules and message`() {
        val prompt = PromptTemplates.messageFormatRepairUserPrompt(
            formatCard = "格式卡内容",
            segmentedBubbleFormat = "角色名规则",
            message = "待修复正文"
        )

        val formatIndex = prompt.indexOf("格式卡内容")
        val segmentedIndex = prompt.indexOf("角色名规则")
        val messageIndex = prompt.indexOf("待修复正文")

        assertTrue(formatIndex >= 0)
        assertTrue(segmentedIndex > formatIndex)
        assertTrue(messageIndex > segmentedIndex)
        assertTrue(prompt.trimEnd().endsWith("待修复正文"))
    }
}
