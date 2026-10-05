package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MessageAlternativeVersionPolicyTest {
    @Test
    fun `new version id skips regenerated bubble message id collision`() {
        val message = message(
            alternatives = listOf("原版本"),
            alternativeVersionIds = listOf("message-1"),
            currentAlternativeIndex = 0,
            currentAlternativeVersionId = "message-1"
        )
        val candidates = ArrayDeque(listOf("message-1", "version-2"))

        val versionId = MessageAlternativeVersionPolicy.newVersionId(message) {
            candidates.removeFirst()
        }

        assertEquals("version-2", versionId)
    }

    @Test
    fun `legacy alternatives receive deterministic stable ids`() {
        val message = message(
            alternatives = listOf("版本一", "版本二"),
            currentAlternativeIndex = 1
        )

        val firstRead = MessageAlternativeVersionPolicy.versions(message)
        val secondRead = MessageAlternativeVersionPolicy.versions(message)

        assertEquals(firstRead.map { it.id }, secondRead.map { it.id })
        assertNotEquals(firstRead[0].id, firstRead[1].id)
        assertEquals(firstRead[1].id, MessageAlternativeVersionPolicy.activeVersionId(message))
    }

    @Test
    fun `append keeps ids paired when oldest version is trimmed`() {
        val original = message(
            alternatives = listOf("一", "二", "三"),
            alternativeVersionIds = listOf("v1", "v2", "v3"),
            currentAlternativeIndex = 2,
            currentAlternativeVersionId = "v3"
        )

        val updated = MessageAlternativeVersionPolicy.append(
            message = original,
            content = "四",
            newVersionId = "v4",
            maxVersions = 3,
            updatedAt = 20
        )

        assertEquals(listOf("二", "三", "四"), updated.alternatives)
        assertEquals(listOf("v2", "v3", "v4"), updated.alternativeVersionIds)
        assertEquals("v4", updated.currentAlternativeVersionId)
        assertEquals(2, updated.currentAlternativeIndex)
    }

    @Test
    fun `editing selected version preserves history and switching back keeps edited text`() {
        val original = message(
            alternatives = listOf("一", "二"),
            alternativeVersionIds = listOf("v1", "v2"),
            currentAlternativeIndex = 0,
            currentAlternativeVersionId = "v1"
        )

        val selected = MessageAlternativeVersionPolicy.select(original, 1, updatedAt = 20)
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(
            selected,
            "编辑后的二",
            updatedAt = 30
        )

        assertEquals("二", selected.content)
        assertEquals("v2", selected.currentAlternativeVersionId)
        assertEquals(listOf("一", "编辑后的二"), edited.alternatives)
        assertEquals(listOf("v1", "v2"), edited.alternativeVersionIds)
        assertEquals("v2", MessageAlternativeVersionPolicy.activeVersionId(edited))
        val previous = MessageAlternativeVersionPolicy.select(edited, 0)
        assertEquals("一", previous.displayContent)
        assertEquals("v1", MessageAlternativeVersionPolicy.activeVersionId(previous))
        assertEquals("编辑后的二", MessageAlternativeVersionPolicy.select(previous, 1).displayContent)
    }

    @Test
    fun `editing middle legacy version preserves all other versions and stable ids`() {
        val original = message(listOf("一", "二", "三"), currentAlternativeIndex = 1)
        val ids = MessageAlternativeVersionPolicy.versions(original).map { it.id }
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(original, "修改", updatedAt = 30)
        assertEquals(listOf("一", "修改", "三"), edited.alternatives)
        assertEquals(ids, edited.alternativeVersionIds)
        assertEquals(ids[1], edited.currentAlternativeVersionId)
        assertEquals(1, edited.currentAlternativeIndex)
        assertEquals(30L, edited.updatedAt)
    }

    @Test
    fun `editing message without alternatives preserves identity without inventing history`() {
        val original = message(listOf("原文"), currentAlternativeIndex = 0)
            .copy(alternatives = emptyList())
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(original, "修改")
        assertEquals("修改", edited.displayContent)
        assertEquals(emptyList<String>(), edited.alternatives)
        assertEquals(original.id, edited.currentAlternativeVersionId)
    }

    private fun message(
        alternatives: List<String>,
        alternativeVersionIds: List<String> = emptyList(),
        currentAlternativeIndex: Int,
        currentAlternativeVersionId: String? = null
    ): ChatMessage = ChatMessage(
        id = "message-1",
        sessionId = "session-1",
        role = MessageRole.ASSISTANT,
        content = alternatives[currentAlternativeIndex],
        alternatives = alternatives,
        alternativeVersionIds = alternativeVersionIds,
        currentAlternativeIndex = currentAlternativeIndex,
        currentAlternativeVersionId = currentAlternativeVersionId,
        createdAt = 1,
        updatedAt = 1
    )
}
