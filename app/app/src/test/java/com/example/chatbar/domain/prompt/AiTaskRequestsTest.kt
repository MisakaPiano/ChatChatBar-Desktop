package com.example.chatbar.domain.prompt

import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.CleartextHttpChatTemplatePolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class AiTaskRequestsTest {
    @Test
    fun everySceneHasProfileAndPreservesActualMultimodalInput() {
        val original = listOf(
            ChatApiMessage.text("system", "fixture-system"),
            ChatApiMessage.text("user", "old input"),
            ChatApiMessage.text("assistant", "old response"),
            ChatApiMessage.text("system", "fixture-format"),
            ChatApiMessage.withImages("user", "actual input", listOf("aW1hZ2U="))
        )
        AiTaskKind.entries.forEach { kind ->
            val context = AiTaskContext(kind)
            assertTrue(context.profile.templateSymbols.isNotEmpty())
            val result = AiTaskMessageAssembler.assemble(original, context)
            assertEquals(original.size + 2, result.size)
            assertEquals(listOf("system", "user", "assistant"), result.take(3).map { it.role })
            assertEquals(original.drop(1), result.drop(3))
            assertEquals(result, AiTaskMessageAssembler.assemble(result, context))
            assertEquals(original.last(), result.last())
        }
    }

    @Test
    fun userOnlyMemoryInputGetsSystemAndDoesNotBecomeAcknowledgement() {
        val input = listOf(ChatApiMessage.text("user", """{"throughT":7,"source":"fixture"}"""))
        val context = AiTaskContext(AiTaskKind.MEMORY_HEAD)
        val result = AiTaskMessageAssembler.assemble(input, context)
        assertEquals(listOf("system", "user", "assistant", "user"), result.map { it.role })
        assertEquals(input.single(), result.last())
    }

    @Test
    fun httpAdaptationPreservesFinalUserAndImageAndHttpsKeepsRoles() {
        val context = AiTaskContext(AiTaskKind.IMAGE_DESIGN)
        val result = AiTaskMessageAssembler.assemble(listOf(
            ChatApiMessage.text("system", "fixture"),
            ChatApiMessage.text("system", "evidence"),
            ChatApiMessage.withImage("user", "final request", "aW1hZ2U=")
        ), context)
        val http = CleartextHttpChatTemplatePolicy.adaptMessages(result, true, "http://localhost/v1")
        assertEquals(result.last(), http.last())
        assertEquals("assistant", http[3].role)
        assertEquals(result, CleartextHttpChatTemplatePolicy.adaptMessages(result, true, "https://example.test/v1"))
    }

    @Test
    fun fingerprintsTrackTemplateSourceAndStageNotRequestId() {
        val first = AiTaskContext.templateFingerprint("template-A", AiTaskKind.FORMAT_CARD, AiTaskStage.GENERATE)
        assertEquals(first, AiTaskContext.templateFingerprint("template-A", AiTaskKind.FORMAT_CARD, AiTaskStage.GENERATE))
        assertNotEquals(first, AiTaskContext.templateFingerprint("template-B", AiTaskKind.FORMAT_CARD, AiTaskStage.GENERATE))
        assertNotEquals(first, AiTaskContext.templateFingerprint("template-A", AiTaskKind.FORMAT_CARD, AiTaskStage.REPAIR))
    }

    @Test
    fun relatedStagesShareTaskButNeverRequestId() = runBlocking {
        withContext(AiTaskRun("logical-operation")) {
            val first = AiTaskContext(AiTaskKind.CHARACTER_FILL).forRequest()
            val repair = AiTaskContext(AiTaskKind.CHARACTER_FILL, AiTaskStage.REPAIR).forRequest()
            assertEquals("logical-operation", first.taskId)
            assertEquals(first.taskId, repair.taskId)
            assertNotEquals(first.requestId, repair.requestId)
        }
    }

    @Test
    fun explicitRefusalAndFilterAreTerminalButNormalNegativeJudgmentsAreNot() {
        assertEquals(AiTaskFailureKind.REFUSAL, AiTaskRefusalPolicy.failure("", "stop", true)?.kind)
        assertEquals(AiTaskFailureKind.CONTENT_FILTER, AiTaskRefusalPolicy.failure("", "content_filter", false)?.kind)
        assertNotNull(AiTaskRefusalPolicy.failure("抱歉，我无法完成这个请求。", "stop", false))
        listOf(
            """{"refused":true,"complete":false}""",
            """{"summary":"角色拒绝了邀请"}""",
            "她说：“抱歉，我无法完成这个请求。”",
            "我不能同意这个请求。她站起身，离开房间。",
            "[[CHATBAR_FORMAT_OK]]",
            "false"
        ).forEach { assertNull(it, AiTaskRefusalPolicy.failure(it, "stop", false)) }
    }

    @Test
    fun responseMarkersHandleNullAndArrayContent() {
        assertFalse(AiTaskRefusalPolicy.responseRefused("""{"choices":[{"message":{"content":"ok","refusal":null}}]}"""))
        assertTrue(AiTaskRefusalPolicy.responseRefused("""{"choices":[{"message":{"content":[{"type":"refusal","refusal":"no"}]}}]}"""))
    }
}
