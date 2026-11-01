package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class FormatCardAutoFillGeneratorTest {
    private val valid = """{"name":"合成候选","content":"合成正文\n第二行","userTools":[]}"""
    private val character = CharacterCard("synthetic-card", "合成角色卡", basicSetting = "合成世界", createdAt = 0, updatedAt = 0)
    private val model = ModelConfig("synthetic-model", "合成模型", "https://fixture.invalid/v1", "", "fixture", createdAt = 0)

    @Test fun `success produces candidate after terminal without repair and keeps manual name`() = runBlocking {
        val calls = mutableListOf<FormatCardAutoFillStage>()
        val generator = FormatCardAutoFillGenerator { stage, messages, actualModel ->
            calls += stage
            assertSame(model, actualModel)
            assertEquals(listOf("system", "user"), messages.map { it.role })
            assertEquals(FormatCardAutoFillPromptAuthority.formatCardAutoFillUserPrompt(character, "合成要求", "手填名称"), messages.last().content.jsonPrimitive.content)
            flowOf(StreamEvent.Delta(valid), StreamEvent.Done)
        }
        val result = generator.generateStreaming(character, "合成要求", "手填名称", model)
        assertEquals("手填名称", result.name)
        assertEquals("合成正文\n第二行", result.content)
        assertEquals(listOf(FormatCardAutoFillStage.GENERATE), calls)
    }

    @Test fun `one completed malformed response receives exactly one repair with same model and full original`() = runBlocking {
        val calls = mutableListOf<FormatCardAutoFillStage>()
        val malformed = "完整的合成非JSON输出\n".repeat(8_000)
        var raw = ""; var repair = ""; var issues = 0
        val generator = FormatCardAutoFillGenerator { stage, messages, actual ->
            calls += stage; assertSame(model, actual)
            if (stage == FormatCardAutoFillStage.GENERATE) flowOf(StreamEvent.Delta(malformed), StreamEvent.Done)
            else {
                assertEquals(malformed, messages.last().content.jsonPrimitive.content)
                flowOf(StreamEvent.Delta(valid), StreamEvent.Done)
            }
        }
        assertEquals("合成候选", generator.generateStreaming(character, "", "", model,
            onRawText = { raw = it }, onRepairText = { repair = it }, onValidationIssue = { issues++ }).name)
        assertEquals(malformed, raw); assertEquals(valid, repair); assertEquals(1, issues)
        assertEquals(listOf(FormatCardAutoFillStage.GENERATE, FormatCardAutoFillStage.REPAIR), calls)
    }

    @Test fun `invalid repair cannot start a third request or apply an incomplete tool set`() = runBlocking {
        var calls = 0
        val generator = FormatCardAutoFillGenerator { _, _, _ -> calls++; flowOf(StreamEvent.Delta("{}"), StreamEvent.Done) }
        assertFailsWith<RuntimeException> { generator.generateStreaming(character, "", "", model) }
        assertEquals(2, calls)
    }

    @Test fun `transport refusal truncation early EOF and empty completion never enter JSON repair`() = runBlocking {
        val refusal = AiTaskRefusalException(AiTaskFailureKind.REFUSAL)
        val cases = listOf(
            flowOf(StreamEvent.Delta(valid), StreamEvent.Error("refused", cause = refusal)),
            flowOf(StreamEvent.Delta(valid), StreamEvent.Error("length", cause = ModelResponseTruncatedException())),
            flowOf<StreamEvent>(StreamEvent.Delta(valid)), flowOf<StreamEvent>(StreamEvent.Done),
            flowOf(StreamEvent.Delta(valid), StreamEvent.Done, StreamEvent.Error("late failure")),
        )
        cases.forEach { events ->
            var calls = 0; var raw = ""
            val generator = FormatCardAutoFillGenerator { _, _, _ -> calls++; events }
            assertFailsWith<RuntimeException> { generator.generateStreaming(character, "", "", model, onRawText = { raw = it }) }
            assertEquals(1, calls)
            if (events !== cases[3]) assertEquals(valid, raw)
        }
    }

    @Test fun `reasoning stays separate and cancellation keeps final partial preview`() = runBlocking {
        var reasoning = ""; var raw = ""
        val good = FormatCardAutoFillGenerator { _, _, _ -> flowOf(StreamEvent.ReasoningDelta("合成思考"), StreamEvent.Delta(valid), StreamEvent.Done) }
        good.generateStreaming(character, "", "", model, onRawText = { raw = it }, onReasoningText = { stage, text ->
            assertEquals(FormatCardAutoFillStage.GENERATE, stage); reasoning = text
        })
        assertEquals(valid, raw); assertEquals("合成思考", reasoning)
        var calls = 0
        val cancelled = FormatCardAutoFillGenerator { _, _, _ -> flow {
            calls++; emit(StreamEvent.Delta("合成片段")); throw CancellationException("fixture-stop")
        } }
        assertFailsWith<CancellationException> { cancelled.generateStreaming(character, "", "", model, onRawText = { raw = it }) }
        assertEquals(1, calls); assertEquals("合成片段", raw)
    }
}
