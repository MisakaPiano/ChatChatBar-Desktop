package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.*
import kotlin.test.*

class WorldBookAiPolicyTest {
    @Test fun `created candidate selection appends blank entries preserving existing book behavior and identity`() {
        val existing = WorldBookEntry("existing", "旧条目", listOf("原关键词"), "原正文", enabled = false,
            insertionOrder = 17, constant = true, sticky = 4, useRegex = true, extensions = """{"fixture":true}""")
        val book = WorldBook("book", "合成世界书", entries = listOf(existing))
        val planned = WorldBookAiPolicy.constrainCreatedEntries(book, emptyList(), listOf(
            CreateEntryResponse("旧条目", listOf("重复")), CreateEntryResponse("新条目", listOf("新词")),
            CreateEntryResponse("重复关键词", listOf("新词"))), remaining = 5, idFactory = { "candidate" })
        assertEquals(1, planned.size)
        assertEquals(listOf(existing), WorldBookAiPolicy.applyCreatedEntries(book.entries, planned, emptySet()))
        val applied = WorldBookAiPolicy.applyCreatedEntries(book.entries, planned, setOf("candidate")) { "new-entry" }
        assertEquals(existing, applied.first())
        assertEquals(WorldBookEntry("new-entry", "新条目", listOf("新词"), ""), applied.last())
        assertEquals(listOf(existing), book.entries)
    }

    @Test fun `fill constraints and apply recheck preserve nonblank or unselected targets and all other fields`() {
        val blank = WorldBookEntry("blank", "空正文", listOf("合成"), enabled = false, priority = 3,
            position = WorldBookPosition.AFTER_CHAR, secondaryKeys = listOf("第二关键词"), group = "合成分组", cooldown = 2)
        val filled = blank.copy(id = "filled", content = "原正文")
        val result = WorldBookAiPolicy.constrainFilledEntries(listOf(blank, filled), listOf(
            FillEntryResponse("blank", "  新正文  "), FillEntryResponse("filled", "不应覆盖"), FillEntryResponse("unknown", "未知")))
        assertEquals(1, result.candidates.size); assertEquals(2, result.rejections.size)
        val applied = WorldBookAiPolicy.applyFilledContents(listOf(blank, filled), result.candidates, setOf("blank"))
        assertEquals(listOf(blank.copy(content = "新正文"), filled), applied)
        val manuallyFilled = blank.copy(content = "预览后手动填写")
        assertEquals(listOf(manuallyFilled, filled),
            WorldBookAiPolicy.applyFilledContents(listOf(manuallyFilled, filled), result.candidates, setOf("blank")))
        assertEquals(listOf(blank, filled),
            WorldBookAiPolicy.applyFilledContents(listOf(blank, filled), result.candidates, emptySet()))
    }
}
