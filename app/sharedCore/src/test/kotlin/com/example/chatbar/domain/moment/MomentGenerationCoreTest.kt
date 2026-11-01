package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MomentGenerationCoreTest {
    private val card = CharacterCard("card", "合成卡", greeting = "excluded-greeting-fixture",
        characters = listOf(CharacterInfo("person", "合成作者", appearanceImage = "images/synthetic.png")), createdAt = 1, updatedAt = 1)
    private val session = ChatSession("session", "card", "合成会话", longTermMemory = "excluded-legacy-memory", createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("model", "合成模型", "https://fixture.invalid/v1", "fixture-key", "fixture-model", createdAt = 1)
    private val messages = (1..4).map { ChatMessage("m$it", "session", MessageRole.ASSISTANT,
        "body-fixture-$it" + "长".repeat(700), createdAt = it.toLong(), updatedAt = it.toLong()) }
    private class Gateway(val generate: String = """{"text":"合成动态","senderName":"合成作者","isPrivate":true}""",
        val decision: Boolean = true, val failure: Throwable? = null) : MomentTextGateway {
        val requests = mutableListOf<Pair<MomentTextOperation, List<ChatApiMessage>>>()
        override suspend fun completeText(messages: List<ChatApiMessage>, modelConfig: ModelConfig,
            operation: MomentTextOperation): String = error("Normal generation must use streaming")
        override fun streamText(messages: List<ChatApiMessage>, modelConfig: ModelConfig,
            operation: MomentTextOperation): Flow<StreamEvent> = flow {
            requests += operation to messages
            if (operation == MomentTextOperation.GENERATE && failure != null) throw failure
            emit(StreamEvent.Delta(if (operation == MomentTextOperation.JUDGE) """{"shouldPost":$decision}""" else generate))
            emit(StreamEvent.Done)
        }
    }
    private fun core(gateway: Gateway) = MomentGenerationCore(gateway,
        promptDesigner = { error("Text-only generation must not open an image designer") },
        imageService = { error("Text-only generation must not open an image client") },
        saveImage = { _, _ -> error("No image save expected") }, loadToken = { null },
        compiledMemoryProvider = { "compiled-memory-fixture" })

    @Test fun `text mode uses compiled memory and last three complete bodies without opening image owners`() = runBlocking {
        val gateway = Gateway(); val core = core(gateway)
        val result = assertIs<MomentGenerationResult.Posted>(core.generate(card, session, messages, null, model, null,
            scheduledAt = 123, autoGenerateImages = false))
        assertEquals(listOf(MomentTextOperation.GENERATE), gateway.requests.map { it.first })
        val request = gateway.requests.last().second.joinToString { it.content.toString() }
        assertFalse(request.contains("body-fixture-1")); messages.takeLast(3).forEach { assertTrue(request.contains(it.content)) }
        assertTrue(request.contains("compiled-memory-fixture"))
        assertFalse(request.contains("excluded-greeting-fixture")); assertFalse(request.contains("excluded-legacy-memory"))
        assertEquals("person", result.post.senderCharacterId); assertEquals("images/synthetic.png", result.post.senderAvatar)
        assertEquals(123L, result.post.scheduledAt); assertEquals(0, result.post.baseLikeCount); assertNull(result.post.imagePath)
    }

    @Test fun `completed decision and draft checkpoint resumes without another text request`() = runBlocking {
        val gateway = Gateway(); val core = core(gateway)
        val checkpoint = MomentGenerationCheckpoint(MomentPostDecision(true), MomentDraft(senderName = "合成作者", text = "续作"))
        assertEquals(checkpoint, core.decodeCheckpoint(core.encodeCheckpoint(checkpoint)))
        val result = assertIs<MomentGenerationResult.Posted>(core.generate(card, session, messages, null, model, null,
            scheduledAt = 123, resumeFrom = checkpoint))
        assertEquals("续作", result.post.text); assertTrue(gateway.requests.isEmpty())
    }

    @Test fun `writing failure or cancellation creates no false checkpoint and propagates`() = runBlocking {
        for (failure in listOf(IllegalStateException("synthetic refusal"), CancellationException("synthetic cancellation"))) {
            val gateway = Gateway(failure = failure); val checkpoints = mutableListOf<MomentGenerationCheckpoint>()
            val error = assertFails { core(gateway).generate(card, session, messages, null, model, null,
                scheduledAt = 123, onCheckpoint = { checkpoints += it }) }
            if (failure is CancellationException) assertIs<CancellationException>(error) else assertIs<IllegalStateException>(error)
            assertEquals(failure.message, error.message)
            assertTrue(checkpoints.isEmpty())
            assertEquals(listOf(MomentTextOperation.GENERATE), gateway.requests.map { it.first })
        }
    }

    @Test fun `147 ignores old negative decision and draft flag without repeating a completed request`() = runBlocking {
        val gateway = Gateway(decision = false)
        val checkpoint = MomentGenerationCheckpoint(MomentPostDecision(false, "old"),
            MomentDraft(shouldPost = false, senderName = "合成作者", text = "旧草稿仍可续作"))
        val result = assertIs<MomentGenerationResult.Posted>(core(gateway).generate(card, session, messages, null, model, null,
            scheduledAt = 123, resumeFrom = checkpoint))
        assertEquals("旧草稿仍可续作", result.post.text)
        assertTrue(gateway.requests.isEmpty())
        val fresh = Gateway(generate = """{"shouldPost":false,"text":"有效正文"}""")
        assertIs<MomentGenerationResult.Posted>(core(fresh).generate(card, session, messages, null, model, null, scheduledAt = 123))
        assertEquals(listOf(MomentTextOperation.GENERATE), fresh.requests.map { it.first })
    }
}
