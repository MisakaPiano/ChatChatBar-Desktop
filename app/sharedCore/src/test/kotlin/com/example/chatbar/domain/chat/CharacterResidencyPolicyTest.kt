package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import kotlin.test.*

class CharacterResidencyPolicyTest {
    private val a = CharacterInfo.create("甲")
    private val b = CharacterInfo.create("乙")
    private val card = CharacterCard.create("Card").copy(characters = listOf(a, b))
    private fun reply(text: String) = ChatMessage.create("s", MessageRole.ASSISTANT, text)
    private fun turn(n: Int, text: String) = listOf(
        ChatMessage.create("s", MessageRole.USER, "Input $n", sourceTurnId = "t$n", sourceTurnOrder = n.toLong(), orderKey = n * 10L),
        reply(text).copy(sourceTurnId = "t$n", sourceTurnOrder = n.toLong(), orderKey = n * 10L + 1))

    @Test fun `explicit speaker evidence does not depend on avatar resources or card enable gate`() {
        val gated = card.copy(characters = listOf(a.copy(desktopEnabled = false), b))
        assertEquals(setOf(a.id), CharacterResidencyPolicy.presentPersonIds(gated, reply("<n=\"甲\"/>[我回来了。]()")))
        assertEquals(setOf(b.id), CharacterResidencyPolicy.presentPersonIds(card, reply("<n=\"乙\"/>『**先等等。**』")))
        assertTrue(CharacterResidencyPolicy.presentPersonIds(card, reply("甲一直坐在旁边，没有说话。")).isEmpty())
        assertTrue(CharacterResidencyPolicy.presentPersonIds(card.copy(characters = listOf(a, a.copy(id = "duplicate"))),
            reply("<n=\"甲\"/>[你好。]()")).isEmpty())
    }

    @Test fun `ending status ownership counts but ordinary code middle status and action options do not`() {
        val status = "```状态栏\n人物：甲\n位置：房间\n人物：乙\n动作：等候\n```"
        assertEquals(setOf(a.id, b.id), CharacterResidencyPolicy.presentPersonIds(card, reply(status)))
        assertTrue(CharacterResidencyPolicy.presentPersonIds(card, reply(status + "\n\n故事继续。")).isEmpty())
        assertTrue(CharacterResidencyPolicy.presentPersonIds(card, reply("```python\n人物：甲\n位置：房间\n```")).isEmpty())
        assertTrue(CharacterResidencyPolicy.presentPersonIds(card, reply("---\n行动选项\n人物：甲\n1. 等待\n2. 离开\n---")).isEmpty())
        assertTrue(CharacterResidencyPolicy.presentPersonIds(card, reply("```状态栏\n人物：甲\n位置：房间")).isEmpty())
    }

    @Test fun `two absent source turns retire display while continuation messages do not add turns`() {
        val first = turn(1, "<n=\"甲\"/>[你好。]()")
        val second = turn(2, "窗外下着雨。")
        assertTrue(a.id in CharacterResidencyPolicy.recent(card, first + second).residentPersonIds)
        val continuation = reply("雨还没有停。").copy(sourceTurnId = "t2", sourceTurnOrder = 2, orderKey = 22)
        assertTrue(a.id in CharacterResidencyPolicy.recent(card, first + second + continuation).residentPersonIds)
        val third = turn(3, "天空放晴了。")
        val absent = CharacterResidencyPolicy.recent(card, first + second + continuation + third)
        assertTrue(a.id in absent.absentForTwoTurns)
        assertFalse(a.id in absent.residentPersonIds)
        assertTrue(a.id in CharacterResidencyPolicy.recent(card, first + second + third + turn(4, "<n=\"甲\"/>[走吧。]()")).residentPersonIds)
        assertTrue(CharacterResidencyPolicy.recent(card, turn(1, "只有旁白。")).absentForTwoTurns.isEmpty())
    }

    @Test fun `decorated explicit status labels renew presence without prose inference`() {
        for (owner in listOf("♟姓名:甲", "👤 **姓名：**甲", "- **姓名：甲**", "🧑‍🦰Name: 甲", "✨【甲状态】")) {
            assertEquals(setOf(a.id), CharacterResidencyPolicy.presentPersonIds(card,
                reply("---\n$owner\n位置：门口\n---")), owner)
        }
        assertTrue(CharacterResidencyPolicy.presentPersonIds(card,
            reply("---\n👤心情：甲很开心\n位置：门口\n---")).isEmpty())
    }

    @Test fun `plain text fences used by persisted replies establish status ownership`() {
        for (header in listOf("text", "plaintext", "txt", "TEXT")) {
            val text = "```$header\n👤姓名：甲\n位置：门口\n```"
            assertEquals(setOf(a.id), CharacterResidencyPolicy.presentPersonIds(card, reply(text)), header)
            assertTrue(CharacterResidencyPolicy.presentPersonIds(card, reply(text + "\n故事继续。")).isEmpty())
        }
        assertTrue(CharacterResidencyPolicy.presentPersonIds(card,
            reply("```python\n👤姓名：甲\n位置：门口\n```")).isEmpty())
    }
}
