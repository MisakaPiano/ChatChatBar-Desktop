package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import kotlin.test.*

class ChatHistoryActionProjectionTest {
    private fun message(id: String, role: MessageRole, content: String, order: Long) =
        ChatMessage(id, "s", role, content, orderKey = order, createdAt = order, updatedAt = order)
    private val options = "————————\n【行动选项】\n1. OLD_NOT_TAKEN_LEFT\n2. OLD_NOT_TAKEN_RIGHT\n————————"
    private fun known(messages: List<ChatMessage>, complete: Set<String>) = ChatHistoryActionProjection.prepare(messages, complete,
        if (messages.lastOrNull()?.role == MessageRole.USER) ChatHistoryActionRequestSource.PERSISTED_USER
        else ChatHistoryActionRequestSource.UI_CONTINUATION)

    @Test fun `image user protects its earlier prefix but later independent action sources remain filterable`() {
        val a = message("a", MessageRole.ASSISTANT, options, 1)
        val imageUser = message("image", MessageRole.USER, "依照图示去做会更合适，请直接推进后续剧情。", 2)
            .copy(images = listOf("synthetic-image-reference"))
        val all = listOf(a, imageUser)
        assertEquals(options, known(all, all.map { it.id }.toSet()).project(a, options))
        val b = message("b", MessageRole.ASSISTANT, options.replace("OLD", "NEW"), 3)
        val current = message("current", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 4)
        val history = all + listOf(b, current)
        val p = known(history, history.map { it.id }.toSet())
        assertEquals(options, p.project(a, options))
        assertFalse(p.project(b, b.displayContent).contains("NEW_NOT_TAKEN"))
        assertEquals(imageUser.displayContent, p.project(imageUser, imageUser.displayContent))
    }

    @Test fun `pronoun and same action references protect earlier choices without interpreting user intent`() {
        for (reference in listOf("I will carry it out tomorrow.", "I shall proceed with them tomorrow.",
            "Tomorrow we will carry it out quietly.", "Tomorrow we shall proceed with them quietly.",
            "Tomorrow we shall follow the same plan quietly.", "Tomorrow we shall follow the aforementioned instruction quietly.",
            "I choose B and then wait for her.", "Tomorrow choose B and then wait quietly.", "那就选乙，然后再看看她接下来的反应。",
            "I shall follow the same plan tomorrow.", "I shall follow the aforementioned instruction tomorrow.",
            "我们照旧处理接下来的安排并等待结果。", "我们照常处理接下来的安排并等待结果。", "如上处理接下来的安排并等待结果。",
            "按照她所说的继续处理接下来的事情。", "按他说的做，然后安静等待事情结束。", "按你说的办，然后安静等待事情结束。",
            "照她的安排继续处理接下来的事情。", "照他的安排继续处理接下来的事情。", "照你的安排继续处理接下来的事情。", "照我的安排继续处理接下来的事情。")) {
            val a = message("a", MessageRole.ASSISTANT, options, 1)
            val user = message("u", MessageRole.USER, reference, 2)
            val all = listOf(a, user)
            assertEquals(options, known(all, all.map { it.id }.toSet()).project(a, options), reference)
        }
        assertFalse(ChatHistoryActionProjection.mayReferToEarlierChoices("Tomorrow I will visit the quiet botanical garden."))
    }

    @Test fun `ordinary explicit text removes only reliable assistant actions while user source stays exact`() {
        val a = message("a", MessageRole.ASSISTANT, "衣着：青色大衣\n$options\n```status\n位置：广场\n```", 1)
        val user = message("u", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 2)
        val p = known(listOf(a, user), setOf(a.id, user.id))
        val result = p.project(a, a.displayContent)
        assertFalse(result.contains("OLD_NOT_TAKEN")); assertTrue(result.contains("青色大衣")); assertTrue(result.contains("位置：广场"))
        assertEquals(user.displayContent, p.project(user, user.displayContent))
        assertTrue(a.displayContent.contains("OLD_NOT_TAKEN"))
    }

    @Test fun `historical unresolved references protect the earlier prefix but not later independent action sources`() {
        for (reference in listOf("1", "１", "②", "⑴，然后看看她接下来的反应。", "贰，然后看看她接下来的反应。", "选择第二项，然后观察她的反应。", "1。接着观察她会如何回答。", "就照刚才的那个行动做。", "I choose two and wait quietly.", "请继续生成接下来发生的故事。", "Please continue the story for a while.", "好", "unknown", "сделай это")) {
            val a = message("a", MessageRole.ASSISTANT, options, 1)
            val u = message("u", MessageRole.USER, reference, 2)
            val b = message("b", MessageRole.ASSISTANT, options.replace("OLD", "NEW"), 3)
            val current = message("current", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 4)
            val all = listOf(a, u, b, current)
            val p = known(all, all.map { it.id }.toSet())
            assertEquals(a.displayContent, p.project(a, a.displayContent), reference)
            assertFalse(p.project(b, b.displayContent).contains("NEW_NOT_TAKEN"), reference)
            assertEquals(reference, p.project(u, reference))
        }
    }

    @Test fun `missing tail proof stale candidate duplicate identity and unknown order preserve originals`() {
        val a = message("a", MessageRole.ASSISTANT, options, 1)
        val u = message("u", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 2)
        val sparse = known(listOf(a, u), setOf(u.id))
        assertEquals(options, sparse.project(a, options))
        val valid = known(listOf(a, u), setOf(a.id, u.id))
        assertEquals(options, valid.project(a.copy(updatedAt = 9), options))
        assertEquals(options, ChatHistoryActionProjection.prepare(listOf(a, u), setOf(a.id, u.id)).project(a, options))
        for (all in listOf(listOf(u, a), listOf(a, a, u), listOf(a, u.copy(sessionId = "other")))) {
            val p = known(all, all.map { it.id }.toSet())
            assertEquals(options, p.project(a, options))
        }
    }

    @Test fun `retain an unknown letter reference without treating ordinary English as a choice`() {
        val source = "旁白\n<choices>\nA) Read the map\nB) Wait by the door\n</choices>"
        val assistant = message("a", MessageRole.ASSISTANT, source, 1)
            .copy(actionOptionsFormat = ActionOptionsFormat("<choices>", "</choices>", "", ActionOptionLabels.LETTERS))
        for (reference in listOf(
            "Let's go with B and wait quietly.",
            "Perhaps ‘b’ would work better here.",
            "Let's go with A and wait quietly.",
            "She suggested B so we will wait quietly.",
            "She suggested A so we will wait quietly.",
            "a 然后静静地等待门口下一轮反应。",
            "A然后静静地等待门口下一轮反应。",
            "a　然后静静地等待门口下一轮反应。"
        )) {
            val user = message("u", MessageRole.USER, reference, 2)
            val projection = known(listOf(assistant, user), setOf(assistant.id, user.id))
            assertEquals(source, projection.project(assistant, source), reference)
        }
        for (text in listOf("I'll open a window while we're waiting nearby.", "I open a window and wait nearby.")) {
            val independent = message("u", MessageRole.USER, text, 2)
            val projection = known(listOf(assistant, independent), setOf(assistant.id, independent.id))
            assertEquals("旁白\n", projection.project(assistant, source))
        }
        val nameEndingInA = message("u", MessageRole.USER, "Maria 然后静静地等待门口下一轮反应。", 2)
        val nameProjection = known(listOf(assistant, nameEndingInA), setOf(assistant.id, nameEndingInA.id))
        assertEquals("旁白\n", nameProjection.project(assistant, source))
        val nineOptions = ('A'..'I').joinToString("\n") { "$it) Action $it" }
        val longMenu = assistant.copy(content = "旁白\n<choices>\n$nineOptions\n</choices>")
        val referenceI = message("u", MessageRole.USER, "She suggested I so we will wait quietly.", 2)
        val projection = known(listOf(longMenu, referenceI), setOf(longMenu.id, referenceI.id))
        assertEquals(longMenu.content, projection.project(longMenu, longMenu.content))

        val lowerMenu = assistant.copy(content = source.replace("A)", "a)").replace("B)", "b)"))
        val lowerReference = message("u", MessageRole.USER, "a 然后静静地等待门口下一轮反应。", 2)
        val lowerProjection = known(listOf(lowerMenu, lowerReference), setOf(lowerMenu.id, lowerReference.id))
        assertEquals(lowerMenu.content, lowerProjection.project(lowerMenu, lowerMenu.content))
        val numbered = assistant.copy(content = "旁白\n$options", actionOptionsFormat = null)
        val numberedProjection = known(listOf(numbered, lowerReference), setOf(numbered.id, lowerReference.id))
        assertEquals("旁白\n", numberedProjection.project(numbered, numbered.content))
    }

    @Test fun `unreliable list narration code and legacy status projection keep their separate semantics`() {
        val text = "旁白与衣着：蓝色长裙\n清单\n1. 地图\n2. 水\n```text\n$options\n```\n" +
            "————————\n【行动选项】\n1. 地图\n3. 水\n————————"
        val a = message("a", MessageRole.ASSISTANT, text, 1)
        val p = known(listOf(a), setOf(a.id))
        assertEquals(text, p.project(a, text))
        val reliable = a.copy(content = "旁白\n$options\n```status\nSTATUS_STAYS\n```")
        val q = known(listOf(reliable), setOf(a.id))
        val previous = ChatHistoryPromptPolicy.sourceText(reliable, true, ChatHistoryPromptZone.PREVIOUS_TURN)
        assertTrue(q.project(reliable, previous).contains("STATUS_STAYS"))
        val earlier = ChatHistoryPromptPolicy.sourceText(reliable, true, ChatHistoryPromptZone.EARLIER_HISTORY)
        assertFalse(q.project(reliable, earlier).contains("STATUS_STAYS"))
    }

    @Test fun `trusted regeneration without a current user may still have older users in its candidate universe`() {
        val old = message("u", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 1)
        val a = message("a", MessageRole.ASSISTANT, options, 2)
        val all = listOf(old, a)
        val control = ChatHistoryActionProjection.prepare(all, all.map { it.id }.toSet(),
            ChatHistoryActionRequestSource.NO_USER_REGENERATION)
        assertFalse(control.project(a, options).contains("OLD_NOT_TAKEN"))
        assertEquals(options, ChatHistoryActionProjection.prepare(all, all.map { it.id }.toSet()).project(a, options))
    }
}
