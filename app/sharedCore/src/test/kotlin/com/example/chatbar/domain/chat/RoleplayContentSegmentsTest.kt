package com.example.chatbar.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoleplayContentSegmentsTest {
    @Test
    fun `segment edit preserves other versions and active version identity`() {
        val original = com.example.chatbar.data.local.entity.ChatMessage.create(
            "session", com.example.chatbar.data.local.entity.MessageRole.ASSISTANT, "Before [old]() after",
        ).copy(
            alternatives = listOf("First", "Before [old]() after", "Third"),
            alternativeVersionIds = listOf("v1", "v2", "v3"),
            currentAlternativeIndex = 1,
            currentAlternativeVersionId = "v2",
        )
        val segment = parseRoleplayTextSegments(original.displayContent).single { it.kind == RoleplaySegmentKind.DIALOGUE }
        val edited = requireNotNull(editRoleplayMessageSegment(
            original, segment.start, segment.endExclusive, "[new]()", updatedAt = 42,
        ).message)
        assertEquals(MessageAlternativeVersionPolicy.editCurrentContent(original, "Before [new]() after", 42), edited)
        assertEquals(listOf("First", "Before [new]() after", "Third"), edited.alternatives)
        assertEquals(original.alternativeVersionIds, edited.alternativeVersionIds)
        assertEquals(1, edited.currentAlternativeIndex)
        assertEquals("v2", edited.currentAlternativeVersionId)
        assertEquals("First", MessageAlternativeVersionPolicy.select(edited, 0).displayContent)
        assertEquals("Third", MessageAlternativeVersionPolicy.select(edited, 2).displayContent)
    }

    @Test
    fun `speaker markers are stripped from unsplit assistant content`() {
        val content = "<n=\"林雾\"/>旁白\n<n=“另一人”>[对白]()\n＜ｎ＝＂第三人＂／＞结尾"

        assertEquals("旁白\n[对白]()\n结尾", stripRoleplaySpeakerMarkers(content))
    }

    @Test
    fun `full width speaker marker applies to dialogue`() {
        val dialogue = parseRoleplayTextSegments("＜ｎ＝“林雾”＞[第一句]()")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals("林雾", dialogue.speakerName)
        assertEquals("[第一句]()", dialogue.displayText)
    }

    @Test
    fun `unmarked dialogue inherits previous speaker marker`() {
        val segments = parseRoleplayTextSegments(
            """
            <n="林雾"/>[第一句]()
            [第二句]()
            """.trimIndent()
        ).filter { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals(listOf("林雾", "林雾"), segments.map { it.speakerName })
        assertEquals("[第二句]()", segments[1].rawText)
    }

    @Test
    fun `speaker marker before narration applies to next dialogue`() {
        val segments = parseRoleplayTextSegments("<n=\"林雾\"/>她看向窗外。[第一句]()")

        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE), segments.map { it.kind })
        assertEquals("她看向窗外。", segments[0].displayText)
        assertEquals("林雾", segments[1].speakerName)
        assertEquals("[第一句]()", segments[1].rawText)
    }

    @Test
    fun `speaker marker inside hidden comment does not apply to next dialogue`() {
        val dialogue = parseRoleplayTextSegments("旁白<!-- <n=\"林雾\"/> -->文字[第一句]()")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertNull(dialogue.speakerName)
    }

    @Test
    fun `unmarked thought inherits previous speaker marker after narration`() {
        val segments = parseRoleplayTextSegments(
            """
            <n="林雾"/>[第一句]()
            她停顿了一下。
            『**第二段内心**』
            """.trimIndent()
        )

        val thought = segments.single { it.kind == RoleplaySegmentKind.THOUGHT }
        assertEquals("林雾", thought.speakerName)
        assertEquals("『**第二段内心**』", thought.rawText)
    }

    @Test
    fun `thought without markdown bold markers becomes thought segment`() {
        val thought = parseRoleplayTextSegments("『第二段内心』")
            .single { it.kind == RoleplaySegmentKind.THOUGHT }

        assertEquals("『第二段内心』", thought.rawText)
    }

    @Test
    fun `first unmarked dialogue keeps legacy speaker fallback`() {
        val dialogue = parseRoleplayTextSegments("[第一句]()")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertNull(dialogue.speakerName)
    }

    @Test
    fun `dialogue does not require parentheses after closing bracket`() {
        val dialogue = parseRoleplayTextSegments("[第一句]")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals("[第一句]", dialogue.rawText)
    }

    @Test
    fun `dialogue keeps optional parenthetical content in same segment`() {
        val dialogue = parseRoleplayTextSegments("[第一句](轻声说)")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals("[第一句](轻声说)", dialogue.rawText)
    }

    @Test
    fun `dialogue accepts full width and mixed width markers`() {
        val content = "［全角］（轻声） [半角]（低声) ［混合]()"

        val dialogues = parseRoleplayTextSegments(content)
            .filter { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals(
            listOf("［全角］（轻声）", "[半角]（低声)", "［混合]()"),
            dialogues.map { it.rawText }
        )
        assertEquals(
            dialogues.map { content.substring(it.start, it.endExclusive) },
            dialogues.map { it.rawText }
        )
    }

    @Test
    fun `thought accepts corner bracket width variants and mixed pairs`() {
        val markers = listOf(
            "『双书名号』",
            "「直角引号」",
            "｢半角直角引号｣",
            "『混合直角引号｣"
        )

        markers.forEach { marker ->
            val thought = parseRoleplayTextSegments(marker)
                .single { it.kind == RoleplaySegmentKind.THOUGHT }

            assertEquals(marker, thought.rawText)
        }
    }

    @Test
    fun `full width image marker is not parsed as dialogue`() {
        val segment = parseRoleplayTextSegments("！［图片］（image.png）").single()

        assertEquals(RoleplaySegmentKind.NARRATION, segment.kind)
    }

    @Test
    fun `empty speaker marker does not replace previous valid inherited speaker`() {
        val dialogues = parseRoleplayTextSegments(
            """
            <n="林雾"/>[第一句]()
            <n=""/>[第二句]()
            [第三句]()
            """.trimIndent()
        ).filter { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals("林雾", dialogues[0].speakerName)
        assertEquals("", dialogues[1].speakerName)
        assertEquals("林雾", dialogues[2].speakerName)
    }

    @Test
    fun `shared parser classifies narration dialogue thought and fenced status`() {
        val segments = parseRoleplayTextSegments("旁白<n=\"林雾\"/>[对白]()『心声』\n```status\n状态\n```")

        assertEquals(
            listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE,
                RoleplaySegmentKind.THOUGHT, RoleplaySegmentKind.STATUS),
            segments.map { it.kind },
        )
        assertEquals("林雾", segments[1].speakerName)
        assertEquals("状态", segments.last().displayText)
        assertEquals(false, segments.last().statusDefaultExpanded)
    }

    @Test
    fun `dash fenced options default open while code fenced status defaults closed`() {
        val dash = parseRoleplayTextSegments("开始\n---\n选项一\n---\n结束")
            .single { it.kind == RoleplaySegmentKind.STATUS }
        val code = parseRoleplayTextSegments("```status\n状态\n```")
            .single { it.kind == RoleplaySegmentKind.STATUS }

        assertEquals(true, dash.statusDefaultExpanded)
        assertEquals(false, code.statusDefaultExpanded)
        assertEquals("选项一", dash.displayText)
    }

    @Test
    fun `hidden comments do not enter shared visible segments`() {
        val segments = parseRoleplayTextSegments("正文<!-- hidden metadata -->后文")

        assertEquals("正文后文", segments.joinToString("") { it.displayText })
    }
}
