package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.prompt.AiTaskRefusalException
import com.example.chatbar.domain.prompt.AiTaskFailureKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CharacterCardCandidateGeneratorTest {
    private val card = CharacterCard("synthetic-card", "合成卡", greeting = "原开场",
        characters = listOf(CharacterInfo("person", "合成人物", profile = "既有简介")), createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("model", "合成模型", "http://127.0.0.1:1/v1", "", "synthetic", createdAt = 1)

    @Test fun `completed fill uses exact model preserves request image order and fills blanks only`() = runBlocking {
        var observed: List<ChatApiMessage>? = null
        var checkpoint = ""
        val output = """{"name":"不得覆盖","characters":[{"targetIndex":0,"profile":"不得覆盖","appearance":"新外观"}]}"""
        val generator = CharacterCardCandidateGenerator { op, stage, messages, selected ->
            assertEquals(CharacterCardAiOperation.FILL, op); assertEquals(CharacterCardAiStage.GENERATE, stage)
            assertSame(model, selected); observed = messages
            flowOf(StreamEvent.Delta(output), StreamEvent.Done)
        }
        val result = generator.fill(card, CharacterCardAiPayload.fill("合成要求", card), model,
            directImages = listOf("image-one", "image-two"), onCheckpoint = { checkpoint = it })
        assertEquals(output, checkpoint)
        val parts = assertNotNull(observed).last().content.jsonArray
        assertTrue(parts[1].toString().contains("image-one")); assertTrue(parts[2].toString().contains("image-two"))
        val merged = CharacterCardAiPayload.mergeFill(card, result)
        assertEquals(card.name, merged.name); assertEquals("既有简介", merged.characters.single().profile)
        assertEquals("新外观", merged.characters.single().appearance)
    }

    @Test fun `error partial and unconfirmed terminal never checkpoint or start repair`() = runBlocking {
        for (failure in listOf<Throwable?>(null, IllegalStateException("synthetic failure"),
            ModelResponseTruncatedException(), AiTaskRefusalException(AiTaskFailureKind.REFUSAL))) {
            var calls = 0; var checkpoint = ""; var preview = ""
            val generator = CharacterCardCandidateGenerator { _, _, _, _ ->
                calls++
                flow { emit(StreamEvent.Delta("{\"name\":\"partial\"}"))
                    if (failure != null) emit(StreamEvent.Error("synthetic failure", cause = failure)) }
            }
            assertFails { generator.fill(card, "{}", model, onCheckpoint = { checkpoint = it }, onRaw = { preview = it }) }
            assertEquals(1, calls); assertEquals("", checkpoint); assertTrue(preview.contains("partial"))
        }
    }

    @Test fun `repair retry reuses only successfully completed raw generation with fresh exact repair model`() = runBlocking {
        var checkpoint = ""; val stages = mutableListOf<CharacterCardAiStage>()
        val generator = CharacterCardCandidateGenerator { _, stage, _, selected ->
            stages += stage; assertSame(model, selected)
            if (stage == CharacterCardAiStage.GENERATE) flowOf(StreamEvent.Delta("not-json"), StreamEvent.Done)
            else flowOf(StreamEvent.Error("repair failed"))
        }
        assertFails { generator.fill(card, "{}", model, onCheckpoint = { checkpoint = it }) }
        assertEquals("not-json", checkpoint)
        val replacement = model.copy(modelName = "fresh-repair-model")
        var calls = 0
        val retry = CharacterCardCandidateGenerator { _, stage, messages, selected ->
            calls++; assertEquals(CharacterCardAiStage.REPAIR, stage); assertSame(replacement, selected)
            assertEquals("not-json", messages.last().content.jsonPrimitive.content)
            flowOf(StreamEvent.Delta("""{"characters":[{"targetIndex":0,"appearance":"重试外观"}]}"""), StreamEvent.Done)
        }
        val result = retry.fill(card, "{}", replacement, completedRaw = checkpoint)
        assertEquals(1, calls); assertEquals("重试外观", result.characters.single().appearance)
    }

    @Test fun `cancel preserves partial preview without reusable raw or repair`() = runBlocking {
        val started = CompletableDeferred<Unit>(); var preview = ""; var checkpoint = ""; var calls = 0
        val generator = CharacterCardCandidateGenerator { _, _, _, _ -> flow {
            calls++; emit(StreamEvent.Delta("partial")); started.complete(Unit); awaitCancellation()
        } }
        val job = launch { generator.fill(card, "{}", model, onCheckpoint = { checkpoint = it }, onRaw = { preview = it }) }
        withTimeout(3_000) { started.await() }; job.cancelAndJoin()
        assertEquals("partial", preview); assertEquals("", checkpoint); assertEquals(1, calls)
    }

    @Test fun `rewrite repair receives exact mode schema and materializes retained person fields`() = runBlocking {
        val requests = mutableListOf<Pair<CharacterCardAiStage, List<ChatApiMessage>>>()
        val generator = CharacterCardCandidateGenerator { operation, stage, messages, _ ->
            assertEquals(CharacterCardAiOperation.REWRITE, operation); requests += stage to messages
            flowOf(StreamEvent.Delta(if (stage == CharacterCardAiStage.GENERATE) "not-json" else
                """{"characters":[{"id":"person","appearance":"改写外观"}]}"""), StreamEvent.Done)
        }
        val result = generator.rewrite(card, CharacterCardAiPayload.rewrite("改外观", card), model)
        assertEquals(listOf(CharacterCardAiStage.GENERATE, CharacterCardAiStage.REPAIR), requests.map { it.first })
        val repair = Json.parseToJsonElement(requests.last().second.last().content.jsonPrimitive.content).jsonObject
        assertEquals(CharacterCardAiPayload.rewriteSchema(card), repair["outputSchema"])
        assertEquals("既有简介", result.characters.single().profile); assertEquals("改写外观", result.characters.single().appearance)
        assertEquals("原开场", result.greeting)
    }
}
