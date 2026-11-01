package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.prompt.AiTaskFailureKind
import com.example.chatbar.domain.prompt.AiTaskRefusalException
import com.example.chatbar.domain.search.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WorldBookAiCoreTest {
    private val model = ModelConfig("model", "合成模型", "https://example.test/v1", "fixture", "synthetic", createdAt = 1)
    private val book = WorldBook("book", "合成世界书")
    private val offline = object : WorldBookResearchPort {
        override suspend fun research(request: String, book: WorldBook, targets: List<WorldBookEntry>, model: ModelConfig,
            options: CharacterResearchOptions, referenceDocument: CharacterReferenceDocument?, session: WorldBookResearchSession?,
            resumeFrom: ResearchDebugSnapshot?, onSession: (WorldBookResearchSession) -> Unit,
            onDebug: (ResearchDebugSnapshot) -> Unit, onVisibleOutput: (String, String, String) -> Unit,
            onStatus: (String) -> Unit) = WorldBookResearchResult(session = session ?: WorldBookResearchSession())
    }

    @Test fun `create resumes after completed batch and never persists or overwrites original entries`() = runBlocking {
        var checkpoint = WorldBookCreateCheckpoint()
        var calls = 0
        val core = WorldBookAiCore({ error("explicit model must be retained") }, WorldBookAiGateway { op, stage, _, actual, delta, _ ->
            assertEquals(WorldBookAiOperation.CREATE, op); assertEquals(WorldBookAiStage.GENERATE, stage)
            assertEquals(model, actual)
            calls++
            when (calls) {
                1 -> """{"entries":[{"name":"山脉","keys":["群峰"]}],"hasMore":true}""".also(delta)
                2 -> { delta("incomplete"); throw IllegalStateException("fixture failure") }
                else -> """{"entries":[{"name":"河流","keys":["清溪"]}],"hasMore":false}""".also(delta)
            }
        }, offline)
        assertFailsWith<IllegalStateException> {
            core.createEntriesStreaming("合成要求", book, model, onCheckpoint = { checkpoint = it })
        }
        assertEquals(listOf("山脉"), checkpoint.candidates.map { it.name })
        assertEquals(2, checkpoint.batchNumber)
        val firstId = checkpoint.candidates.single().candidateId
        val result = core.createEntriesStreaming("合成要求", book, model, resumeFrom = checkpoint)
        assertEquals(listOf("山脉", "河流"), result.candidates.map { it.name })
        assertEquals(firstId, result.candidates.first().candidateId)
        assertEquals(3, calls)
        assertTrue(book.entries.isEmpty())
        val selected = WorldBookAiPolicy.applyCreatedEntries(book.entries, result.candidates, setOf(firstId)) { "saved" }
        assertEquals("山脉", selected.single().name); assertTrue(selected.single().content.isEmpty())
    }

    @Test fun `fill keeps target identity across five item batches and repairs only rejected batch`() = runBlocking {
        val targets = (1..6).map { WorldBookEntry("id$it", "主题$it", listOf("关键词$it")) }
        val original = book.copy(entries = targets + WorldBookEntry("filled", "已有", content = "保留"))
        val stages = mutableListOf<WorldBookAiStage>()
        val core = WorldBookAiCore({ model }, WorldBookAiGateway { op, stage, messages, _, delta, _ ->
            assertEquals(WorldBookAiOperation.FILL, op); stages += stage
            val input = Json.parseToJsonElement(messages.last().content.jsonPrimitive.content).jsonObject
            val ids = input.getValue("targets").jsonArray.map { it.jsonObject.getValue("targetId").jsonPrimitive.content }
            val raw = if (stages.size == 1) """{"entries":[{"targetId":"unknown","content":"忽略"}]}"""
                else buildJsonObject { put("entries", buildJsonArray { ids.forEach { id ->
                    add(buildJsonObject { put("targetId", id); put("content", "正文-$id") })
                } }) }.toString()
            raw.also(delta)
        }, offline)
        val result = core.fillEmptyContentsStreaming("合成要求", original, original.entries)
        assertEquals(listOf(WorldBookAiStage.GENERATE, WorldBookAiStage.REPAIR, WorldBookAiStage.GENERATE), stages)
        assertEquals(targets.map { it.id }, result.candidates.map { it.targetId })
        assertEquals(6, result.checkpoint.nextTargetIndex)
        assertEquals(original.entries, targets + original.entries.last())
        val applied = WorldBookAiPolicy.applyFilledContents(original.entries, result.candidates, setOf("id6"))
        assertEquals(targets.take(5), applied.take(5)); assertEquals("正文-id6", applied[5].content)
        assertEquals("保留", applied.last().content)
    }

    @Test fun `refusal or cancellation never triggers repair and leaves incomplete batch uncommitted`() = runBlocking {
        for (failure in listOf(AiTaskRefusalException(AiTaskFailureKind.REFUSAL), CancellationException("fixture"))) {
            var checkpoint = WorldBookCreateCheckpoint(); var calls = 0
            val core = WorldBookAiCore({ model }, WorldBookAiGateway { _, _, _, _, delta, _ ->
                calls++; delta("""{"entries":[]}"""); throw failure
            }, offline)
            val actual = runCatching { core.createEntriesStreaming("合成要求", book, onCheckpoint = { checkpoint = it }) }.exceptionOrNull()
            assertSame(failure, actual); assertEquals(1, calls)
            assertTrue(checkpoint.candidates.isEmpty()); assertEquals(1, checkpoint.batchNumber)
        }
    }
}
