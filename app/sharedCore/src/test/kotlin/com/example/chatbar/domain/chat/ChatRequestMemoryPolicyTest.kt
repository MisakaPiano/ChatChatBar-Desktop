package com.example.chatbar.domain.chat

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatRequestMemoryPolicyTest {
    private val archive = "【ARCHIVE｜历史档案】\n塞尔达与用户抵达便利店。"
    private val worldBookAndRag = "【世界书】\n世界设定\n\n【RAG｜召回资料】\n召回内容"
    private val head = "【HEAD｜当前状态】\n当前状态"

    @Test
    fun orderedDynamicMessagesKeepWorldBookArchiveHeadOrder() {
        val messages = ChatRequestMemoryPolicy.orderedDynamicMessages(
            worldBookAndRag = worldBookAndRag,
            archive = archive,
            headAndTimeline = head,
            playerName = "用户",
            botName = "塞尔达",
        )

        ChatRequestMemoryPolicy.requireArchiveIncluded(messages, archive)
        assertEquals(listOf(worldBookAndRag, archive, head), messages.map { it.content.jsonPrimitive.content })
        assertTrue(messages.all { it.role == "system" })
    }

    @Test
    fun archiveAndHeadReplaceSessionPlaceholders() {
        val messages = ChatRequestMemoryPolicy.orderedDynamicMessages(
            worldBookAndRag = worldBookAndRag,
            archive = "【ARCHIVE｜历史档案】\n\$username与{user}遇见{char}。",
            headAndTimeline = "【HEAD｜当前状态】\n\$botname正在等待\$username。",
            playerName = "林夏",
            botName = "塞尔达\n公主",
        )

        assertEquals("【ARCHIVE｜历史档案】\n林夏与林夏遇见塞尔达\n公主。", messages[1].content.jsonPrimitive.content)
        assertEquals("【HEAD｜当前状态】\n塞尔达\n公主正在等待林夏。", messages[2].content.jsonPrimitive.content)
    }

    @Test
    fun expectedArchiveMissingFromHeadOnlyRequestIsRejected() {
        val messages = listOf(ChatApiMessage.text("system", "【HEAD｜当前状态】\n当前状态"))

        val failure = assertFailsWith<IllegalStateException> {
            ChatRequestMemoryPolicy.requireArchiveIncluded(messages, archive)
        }

        assertTrue(failure.message.orEmpty().contains("Archive未写入最终请求"))
    }
}
