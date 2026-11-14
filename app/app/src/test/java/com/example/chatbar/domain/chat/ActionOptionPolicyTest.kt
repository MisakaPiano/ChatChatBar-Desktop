package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.image.NovelAiPromptDesigner
import org.junit.Assert.*
import org.junit.Test

class ActionOptionPolicyTest {
    private val options = "**【行动选项】**\n1.**靠近窗边**\n\n2. **留在原地**"
    private fun message(text: String, role: MessageRole = MessageRole.ASSISTANT) =
        ChatMessage("m", "s", role, text, createdAt = 1, updatedAt = 1)

    @Test fun explicitChoicesRecognizeSupportedWrappersWithoutOrdinaryLists() {
        for (wrapped in listOf(options, "---\n$options\n---", "————————\n$options\n————————", "```text\n$options\n```")) {
            assertEquals(listOf("靠近窗边", "留在原地"), ActionOptionPolicy.choices(wrapped))
            assertEquals("", ActionOptionPolicy.strip(wrapped))
        }
        val narrative = "今天完成两件事：\n1. 去了车站\n2. 回到家里"
        assertEquals(narrative, ActionOptionPolicy.strip(narrative))
        assertTrue(ActionOptionPolicy.choices(narrative).isEmpty())
    }

    @Test fun previousAndEarlierAssistantHistoryOmitChoicesWhilePreservingUserTextAndStatePolicy() {
        val content = "已经发生的正文。\n---\n$options\n---\n```text\n人物状态：站在窗边\n```"
        for (zone in ChatHistoryPromptZone.entries) {
            val projected = ChatHistoryPromptPolicy.sourceText(message(content), false, zone)
            assertTrue(projected.contains("已经发生的正文"))
            assertTrue(projected.contains("人物状态"))
            assertFalse(projected.contains("留在原地"))
        }
        assertEquals(content, message(content).displayContent)
        assertEquals(options, ChatHistoryPromptPolicy.sourceText(message(options, MessageRole.USER), true, ChatHistoryPromptZone.PREVIOUS_TURN))
    }

    @Test fun imageAnchorProjectionOmitsUnselectedChoicesAndKeepsSelectedUserAction() {
        val source = message("正文人物站在室内。\n---\n$options\n---")
        val projected = NovelAiPromptDesigner.contextForAnchor(listOf(source), source.id).single()
        assertTrue(projected.displayContent.contains("正文人物站在室内"))
        assertFalse(projected.displayContent.contains("靠近窗边"))
        assertTrue(source.displayContent.contains("靠近窗边"))
        val selected = message("靠近窗边", MessageRole.USER)
        assertEquals(selected.displayContent, NovelAiPromptDesigner.contextForAnchor(listOf(selected), selected.id).single().displayContent)
    }

    @Test fun caretInsertionPreservesBothSidesAndRepeatedChoicesAppendWithoutDeduplication() {
        val first = ActionOptionPolicy.insert("前面后面", 2, "行动甲", false)
        assertEquals("前面\n行动甲\n后面", first.text)
        val second = ActionOptionPolicy.insert(first.text, first.cursor, "行动乙", true)
        val third = ActionOptionPolicy.insert(second.text, second.cursor, "行动乙", true)
        assertEquals("前面\n行动甲\n后面\n行动乙\n行动乙", third.text)
        assertEquals(third.text.length, third.cursor)
        assertEquals("已有文字\n行动甲", ActionOptionPolicy.insert("已有文字", null, "行动甲", false).text)
    }
}
