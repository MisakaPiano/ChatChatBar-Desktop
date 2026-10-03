package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import kotlin.test.*

class DesktopChatDensityTest {
    @Test fun `chat controls use compact pointer targets icons and chrome spacing`() {
        assertTrue(DesktopChatControlDensity.TARGET_DP in 28..32)
        assertTrue(DesktopChatControlDensity.ICON_DP in 16..18)
        assertTrue(DesktopChatControlDensity.GAP_DP in 0..4)
        assertEquals(880f, DesktopChatReadingWidth.forAvailableWidth(1200f))
    }

    @Test fun `reasoning starts collapsed and toggles only presentation state`() {
        val source = ChatMessage.create("s", MessageRole.ASSISTANT, "Answer", reasoningContent = "Reason")
        val presented = desktopPresentMessage(source, null, null, true)
        val expansion = DesktopPresentationExpansion(presented.defaultReasoningExpanded)
        assertFalse(expansion.expanded)
        expansion.toggle()
        assertTrue(expansion.expanded)
        expansion.toggle()
        assertFalse(expansion.expanded)
        assertEquals("Reason", source.reasoningContent)
        assertEquals("Answer", source.content)
    }

    @Test fun `status expansion respects either default and independent alternative state`() {
        for (default in listOf(false, true)) {
            val first = DesktopPresentationExpansion(default)
            val otherAlternative = DesktopPresentationExpansion(default)
            assertEquals(default, first.expanded)
            first.toggle()
            assertEquals(!default, first.expanded)
            assertEquals(default, otherAlternative.expanded)
        }
        val message = ChatMessage.create("s", MessageRole.ASSISTANT, "```status\nReady\n```")
        assertTrue(desktopPresentMessage(message, null, null, true).segments.single().statusDefaultExpanded)
    }

    @Test fun `grouped alternatives preserve one based count and boundary availability`() {
        val message = ChatMessage.create("s", MessageRole.ASSISTANT, "one").copy(alternatives = listOf("one", "two", "three"))
        for (index in 0..2) {
            val navigation = desktopAlternativeNavigation(message.copy(currentAlternativeIndex = index), setOf(message.id))!!
            assertEquals(index + 1, navigation.current)
            assertEquals(3, navigation.total)
            assertEquals(index > 0, navigation.canPrevious)
            assertEquals(index < 2, navigation.canNext)
        }
        assertNull(desktopAlternativeNavigation(message, emptySet()))
    }

    @Test fun `toolbar partitions existing eligibility without adding or losing actions`() {
        // Every possible input subset, including empty streaming actions, is partitioned exactly once.
        val all = DesktopMessageAction.entries
        for (mask in 0 until (1 shl all.size)) {
            val eligible = all.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val primary = desktopFooterMessageActions(eligible)
            val overflow = desktopOverflowMessageActions(eligible)
            assertEquals(eligible.toSet(), (primary + overflow).toSet())
            assertTrue(primary.none { it == DesktopMessageAction.EDIT || it == DesktopMessageAction.DELETE })
            assertTrue(overflow.all { it == DesktopMessageAction.EDIT || it == DesktopMessageAction.DELETE })
            assertEquals(eligible.size, primary.size + overflow.size)
        }
    }

    @Test fun `user assistant and retry actions retain authoritative eligibility`() {
        val user = ChatMessage.create("s", MessageRole.USER, "Question")
        val assistant = ChatMessage.create("s", MessageRole.ASSISTANT, "Answer")
        val error = ChatMessage.create("s", MessageRole.SYSTEM, "错误: failed")
        val messages = listOf(user, assistant, error)
        for ((message, expected) in listOf(user to listOf(DesktopMessageAction.COPY),
            assistant to listOf(DesktopMessageAction.COPY, DesktopMessageAction.REGENERATE),
            error to listOf(DesktopMessageAction.COPY, DesktopMessageAction.RETRY))) {
            val actions = desktopMessageActions(messages, message, null)
            assertEquals(expected, desktopFooterMessageActions(actions))
            assertEquals(listOf(DesktopMessageAction.EDIT, DesktopMessageAction.DELETE), desktopOverflowMessageActions(actions))
        }
    }

    @Test fun `active generation keeps mutation controls unavailable`() {
        val user = ChatMessage.create("s", MessageRole.USER, "Question")
        val assistant = ChatMessage.create("s", MessageRole.ASSISTANT, "Answer")
        val running = DesktopTaskEntry("task", DesktopTaskKind.REAL_CHAT, "s", 1,
            operation = DesktopChatOperation.REGENERATE, targetMessageId = assistant.id)
        for (message in listOf(user, assistant)) {
            val actions = desktopMessageActions(listOf(user, assistant), message, running)
            assertEquals(listOf(DesktopMessageAction.COPY), desktopFooterMessageActions(actions))
            assertTrue(desktopOverflowMessageActions(actions).isEmpty())
        }
    }
}
