package com.example.chatbar.domain.prompt

import com.example.chatbar.domain.chat.ChatApiMessage
import kotlinx.serialization.json.*
import kotlin.test.*

class AuxiliaryEnvelopeAdoptionTest {
    @Test fun `single user and system-only input use seven-message envelope and remain idempotent`() {
        for (source in listOf(listOf(ChatApiMessage.text("user", "unchanged input")),
            listOf(ChatApiMessage.text("system", "feature system")))) {
            val result = AuxiliaryMessageAssembler.assemble(source, false)
            assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant"), result.map { it.role })
            assertEquals(source.firstOrNull { it.role == "user" }?.content
                ?: JsonPrimitive(AuxiliaryPromptAuthority.GENERAL_TASK_EMPTY_INPUT), result[4].content)
            assertEquals(result, AuxiliaryMessageAssembler.assemble(result, false))
            assertEquals(result, AuxiliaryMessageAssembler.assemble(result, true))
        }
    }

    @Test fun `image-design preserves variable input roles and exact media objects without double wrapping`() {
        val source = listOf(ChatApiMessage.text("system", "feature system"),
            ChatApiMessage.withImage("user", "look at this", "synthetic-image"),
            ChatApiMessage.text("assistant", "prior design"), ChatApiMessage.text("user", "revision"))
        val result = AuxiliaryMessageAssembler.assemble(source, true)
        assertEquals(source.drop(1), result.subList(4, result.size - 2))
        assertEquals(source.size - 1 + 6, result.size)
        assertEquals(result, AuxiliaryMessageAssembler.assemble(result, true))
        assertEquals(1, result.count { it.content == source[1].content })
        val overhead = AuxiliaryMessageAssembler.addedText(source, true)
        assertFalse(overhead.contains(AuxiliaryPromptAuthority.generalTaskInputHeading(0, "user")))
        assertTrue(overhead.contains(AuxiliaryPromptAuthority.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT))
    }

    @Test fun `merged multiple inputs retain role headings media order and overhead attribution`() {
        val source = listOf(ChatApiMessage.text("assistant", "prior answer"),
            ChatApiMessage.withImage("user", "question", "synthetic-image"))
        val result = AuxiliaryMessageAssembler.assemble(source, false)
        val content = result[4].content.jsonArray
        val image = source[1].content.jsonArray.last()
        assertEquals(image, content.last())
        val overhead = AuxiliaryMessageAssembler.addedText(source)
        source.forEachIndexed { index, message ->
            val heading = AuxiliaryPromptAuthority.generalTaskInputHeading(index, message.role)
            assertTrue(overhead.contains(heading))
            assertTrue(content.any { it.jsonObject["text"] == JsonPrimitive(heading) })
        }
        assertEquals(result, AuxiliaryMessageAssembler.assemble(result, false))
    }
}
