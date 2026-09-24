package com.example.chatbar.domain.card

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SillyTavernCardParserTest {
    @Test
    fun parsesRepresentativeV1AndToleratesUnknownFields() {
        val card = SillyTavernCardParser.parseJson(
            """{"name":"V1","description":"描述","personality":"性格","scenario":"场景","first_mes":"你好","mes_example":"例子","unknown":{"future":true}}""",
        )

        assertEquals("V1", card.name)
        assertEquals("描述", card.description)
        assertEquals("性格", card.personality)
        assertEquals("场景", card.scenario)
        assertEquals("你好", card.firstMes)
        assertEquals("例子", card.mesExample)
    }

    @Test
    fun parsesRepresentativeV2Fields() {
        val card = SillyTavernCardParser.parseJson(
            """{"spec":"chara_card_v2","data":{"name":"V2","description":"描述","personality":"性格","scenario":"场景","first_mes":"首句","mes_example":"例句","system_prompt":"系统","post_history_instructions":"尾部","alternate_greetings":["A","B"],"creator_notes":"注释","tags":["tag"],"creator":"作者","character_version":"2","extensions":{"x":1},"character_book":{"name":"book","entries":[]}}}""",
        )

        assertEquals("V2", card.name)
        assertEquals(listOf("A", "B"), card.alternateGreetings)
        assertEquals("系统", card.systemPrompt)
        assertEquals("尾部", card.postHistoryInstructions)
        assertEquals("注释", card.creatorNotes)
        assertEquals(listOf("tag"), card.tags)
        assertEquals("作者", card.creator)
        assertEquals("2", card.characterVersion)
        assertEquals("{\"x\":1}", card.extensions)
        assertEquals("{\"name\":\"book\",\"entries\":[]}", card.characterBook)
    }

    @Test
    fun v2RequiresData() {
        assertFailsWith<IllegalArgumentException> {
            SillyTavernCardParser.parseJson("""{"spec":"chara_card_v2"}""")
        }
    }

    @Test
    fun extractsCaseInsensitiveCharaUsingMimeBase64AndRetainsOriginalPng() {
        val base = onePixelPng()
        val json = """{"name":"PNG","description":"描述"}"""
        val encoded = Base64.getMimeEncoder(4, "\n".toByteArray())
            .encodeToString(json.toByteArray())
        val png = PngTextChunks.insertTextChunk(base, "cHaRa", encoded)

        val extracted = SillyTavernCardParser.extractCharaChunk(png)
        val parsed = SillyTavernCardParser.parseJson(checkNotNull(extracted), png)

        assertEquals(json, extracted)
        assertContentEquals(png, parsed.pngBytes)
    }

    @Test
    fun missingCharaChunkReturnsNull() {
        assertNull(SillyTavernCardParser.extractCharaChunk(onePixelPng()))
    }

    private fun onePixelPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )
}
