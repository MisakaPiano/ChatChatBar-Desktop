package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatCardAutoFillServiceTest {
    private val valid = """{"name":"探索手记","content":"记录眼前的场景。","userTools":[]}"""

    @Test
    fun parsesWholeCandidateWithOrderedToolsAndExactText() {
        val raw = """
            {
              "name":"探索手记",
              "content":"第一段\n第二段",
              "userTools":[
                {"type":"RANDOM_NUMBER","minimum":"-5","maximum":"5"},
                {"type":"STRONG_PROMPT_SUFFIX","text":"第一行\n  第二行  "},
                {"type":"RANDOM_NUMBER","minimum":"7","maximum":"7"}
              ]
            }
        """.trimIndent()
        val draft = FormatCardAutoFillParser.parse("```json\n$raw\n```")
        assertEquals("第一段\n第二段", draft.content)
        assertEquals(listOf(FormatCardUserToolType.RANDOM_NUMBER, FormatCardUserToolType.STRONG_PROMPT_SUFFIX,
            FormatCardUserToolType.RANDOM_NUMBER), draft.userTools.map { it.type })
        assertEquals("第一行\n  第二行  ", draft.userTools[1].text)
    }

    @Test
    fun rejectsMissingNullOrBlankFieldsRatherThanSalvagingNestedObjects() {
        listOf(
            """{"name":"卡","content":"正文"}""",
            """{"name":"卡","content":"正文","userTools":null}""",
            """{"name":" ","content":"正文","userTools":[]}""",
            """{"name":"卡","content":" ","userTools":[]}""",
            """{"nested":$valid}""",
            valid.dropLast(1),
            "[$valid]"
        ).forEach { raw ->
            assertThrows(RuntimeException::class.java) { FormatCardAutoFillParser.parse(raw) }
        }
    }

    @Test
    fun rejectsInvalidToolsWithoutSilentlyDroppingThem() {
        listOf(
            """{"type":"UNKNOWN","text":"保留我"}""",
            """{"type":"RANDOM_NUMBER","minimum":"10","maximum":"1"}""",
            """{"type":"RANDOM_NUMBER","minimum":"2147483648","maximum":"2147483648"}""",
            """{"type":"RANDOM_NUMBER","minimum":"1.5","maximum":"4"}""",
            """{"type":"STRONG_PROMPT_SUFFIX","text":" "}"""
        ).forEach { tool ->
            assertThrows(RuntimeException::class.java) {
                FormatCardAutoFillParser.parse("""{"name":"卡","content":"正文","userTools":[$tool]}""")
            }
        }
    }

    @Test
    fun applyingPreservesManualNameAndDoesNotAlterCandidate() {
        val draft = FormatCardAutoFillParser.parse(valid)
        val filled = FormatCardAutoFillPolicy.prepareApply(null, "  手填名称  ", "", emptyList(), draft)
        assertEquals("  手填名称  ", filled.name)
        assertEquals("探索手记", draft.name)
        assertEquals(draft.content, filled.content)
        assertEquals(draft, FormatCardAutoFillPolicy.prepareApply(null, "", "", emptyList(), draft))
    }

    @Test
    fun applyRejectsExistingCardAndAnyNewlyPopulatedContentOrTools() {
        val draft = FormatCardAutoFillParser.parse(valid)
        assertFalse(FormatCardAutoFillPolicy.canFill("existing", "", emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            FormatCardAutoFillPolicy.prepareApply("existing", "", "", emptyList(), draft)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FormatCardAutoFillPolicy.prepareApply(null, "", "手填正文", emptyList(), draft)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FormatCardAutoFillPolicy.prepareApply(null, "", "", listOf(FormatCardUserToolConfig.randomNumber()), draft)
        }
    }

    @Test
    fun structuredInputIncludesAllCharactersButExcludesMediaAndInactiveFreeformText() {
        val card = character().copy(
            freeformCharacterText = "inactive-freeform",
            characters = listOf(
                CharacterInfo("a", "林一", profile = "人物甲", appearanceImage = "private-portrait", imagePrompt = "private-image-tags"),
                CharacterInfo("b", "林二", relationships = "人物乙关系")
            ),
            customDocuments = listOf(DocumentInfo("doc", "private-document", "private-path", "txt", 0L)),
            avatar = "private-avatar",
            worldBookIds = listOf("private-world-book")
        )
        val payload = PromptTemplates.formatCardAutoFillUserPrompt(card, "保留对白", "我的卡")
        Json.parseToJsonElement(payload)
        listOf("林一", "林二", "人物甲", "人物乙关系", "保留对白", "我的卡").forEach {
            assertTrue(payload.contains(it))
        }
        listOf("inactive-freeform", "private-portrait", "private-image-tags", "private-document", "private-path",
            "private-avatar", "private-world-book").forEach { assertFalse(payload.contains(it)) }
    }

    @Test
    fun freeformInputKeepsLongTextExactlyAndExcludesInactiveStructuredCharacters() {
        val longText = "完整设定\n\"引用\"".repeat(12_000)
        val payload = PromptTemplates.formatCardAutoFillUserPrompt(character().copy(
            editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = longText,
            characters = listOf(CharacterInfo("old", "inactive-character"))
        ), "", "")
        val decoded = Json.parseToJsonElement(payload)
        assertTrue(decoded.toString().contains(Json.encodeToString(kotlinx.serialization.serializer<String>(), longText)))
        assertFalse(payload.contains("inactive-character"))
    }

    @Test
    fun completeStreamReturnsTextAndPublishesFinalPreview() = runBlocking {
        var preview = ""
        val result = collectFormatCardAutoFillText(flowOf(StreamEvent.Delta(valid), StreamEvent.Done)) { preview = it }
        assertEquals(valid, result)
        assertEquals(valid, preview)
    }

    @Test
    fun terminalErrorRejectsEvenCompleteJsonAndPreservesPreview() {
        var preview = ""
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                collectFormatCardAutoFillText(flowOf(StreamEvent.Delta(valid), StreamEvent.Error("length"))) { preview = it }
            }
        }
        assertEquals(valid, preview)
    }

    @Test
    fun missingTerminalAndEmptyCompletionCannotProduceCandidate() {
        listOf(flowOf<StreamEvent>(StreamEvent.Delta(valid)), flowOf<StreamEvent>(StreamEvent.Done)).forEach { events ->
            assertThrows(IllegalStateException::class.java) {
                runBlocking { collectFormatCardAutoFillText(events) {} }
            }
        }
    }

    @Test
    fun cancellationPropagatesWithPartialPreview() {
        var preview = ""
        assertThrows(CancellationException::class.java) {
            runBlocking {
                collectFormatCardAutoFillText(flow {
                    emit(StreamEvent.Delta("partial"))
                    throw CancellationException("stop")
                }) { preview = it }
            }
        }
        assertEquals("partial", preview)
    }

    private fun character() = CharacterCard(
        id = "card", name = "双人探索", basicSetting = "世界设定", greeting = "开场白", createdAt = 0L, updatedAt = 0L
    )
}
