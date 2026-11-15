package com.example.chatbar.domain.lanSync

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncGroupDisplayTest {
    private fun record(type: String, id: String, vararg fields: Pair<String, JsonElement>) = LanSyncRecord(type, id,
        JsonObject(mapOf<String, JsonElement>("id" to JsonPrimitive(id)) + fields))

    @Test fun conversationUsesActualSessionOverrideAndRecordLabelsRetainSeparateIdentities() {
        val session = record("chat_sessions", "session-id", "title" to JsonPrimitive("原会话名"), "displayTitleOverride" to JsonPrimitive("重命名后的会话"))
        val message = record("chat_messages", "message-id", "sessionId" to JsonPrimitive("session-id"), "senderName" to JsonPrimitive("角色甲"))
        val label = LanSyncGroupDisplay.unit("conversation/session-id", listOf(message, session))
        assertEquals("重命名后的会话", label.title)
        assertEquals("会话", label.kind)
        assertEquals("conversation/session-id", label.identifier)
        assertEquals("角色甲", LanSyncGroupDisplay.record(message).title)
        assertEquals(message.key, LanSyncGroupDisplay.record(message).identifier)
        assertEquals("消息", LanSyncGroupDisplay.record(message).kind)
    }

    @Test fun missingBodiesUseReadableKindsWithoutInventingTitlesFromIdsOrHashes() {
        val unit = "conversation/81ba44a9-694e-4d42-a235-60f14bc7214d"
        val label = LanSyncGroupDisplay.unit(unit, emptyList())
        assertEquals("会话", label.title)
        assertEquals(unit, label.identifier)
        val unnamed = record("character_cards", "some-id", "name" to JsonPrimitive(42))
        assertEquals("角色卡", LanSyncGroupDisplay.record(unnamed).title)
        val task = record("moment_tasks", "task-id")
        val post = record("moment_posts", "post-id", "senderName" to JsonPrimitive("角色乙"))
        val moment = LanSyncGroupDisplay.unit("moment-task/task-id", listOf(task, post))
        assertEquals("角色乙", moment.title)
        assertEquals("朋友圈计划与内容", moment.kind)
    }

    @Test fun boundedSingleLineNamesPreserveUnicodeAndNeverRewriteFrozenBodies() {
        val source = "第一行\n第二行\u202e" + "🪷".repeat(180)
        val card = record("character_cards", "a", "name" to JsonPrimitive(source))
        val title = LanSyncGroupDisplay.record(card).title
        assertFalse(title.contains('\n'))
        assertFalse(title.contains('\u202e'))
        assertTrue(title.endsWith('…'))
        assertTrue(title.codePointCount(0, title.length) <= 161)
        assertFalse(title.dropLast(1).last().isHighSurrogate())
        assertEquals(source, card.body.getValue("name").jsonPrimitive.content)
    }

    @Test fun nodeViewLabelsReadOnlyFrozenLocalOrVerifiedIncomingBodiesAfterLiveDataChanges() = runBlocking {
        val root = Files.createTempDirectory("synthetic-group-labels-").toRealPath()
        try {
            val group = LanSyncGroupNodeTest.Group(root)
            val source = group.End(0); val sending = source.enroll()
            val target = group.End(1); val receiving = target.enroll()
            source.card("a", "已验证的传入名称")
            source.send(sending, sending.commit(sending.inspect().review(setOf("character_cards/a"))))
            val incoming = target.scan(receiving)
            assertEquals("已验证的传入名称", LanSyncGroupDisplay.unit(incoming, "character_cards/a").title)
            target.card("a", "核对时的本机名称")
            val local = receiving.inspect()
            assertEquals("核对时的本机名称", LanSyncGroupDisplay.unit(local, "character_cards/a").title)
            target.card("a", "核对后继续编辑的名称")
            assertEquals("核对时的本机名称", LanSyncGroupDisplay.unit(local, "character_cards/a").title)
            assertEquals("已验证的传入名称", LanSyncGroupDisplay.unit(incoming, "character_cards/a").title)
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
