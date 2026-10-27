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
    fun `select and edit preserve every version identity`() {
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
    }

    @Test
    fun `middle edit preserves metadata and siblings through selection and serialization`() {
        val original = message(listOf("first", "middle", "last"), listOf("a", "b", "c"), 1, "b").copy(
            images = listOf("owned.png"), reasoningContent = "reasoning", generatedFromMessageId = "source",
            sourceTurnId = "turn", sourceTurnOrder = 4, orderKey = 42,
            formatRepairNotice = com.example.chatbar.data.local.entity.MessageFormatRepairNotice(
                com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind.APPLIED, "middle", "before"),
            generatedImageMetadata = listOf(com.example.chatbar.data.local.entity.GeneratedImageMetadata(
                "owned.png", "caption", negativePrompt = "negative", sizePreset = "fixture", width = 8, height = 8))
        )
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(original, "edited", 30)
        assertEquals(original.copy(content = "edited", alternatives = listOf("first", "edited", "last"), updatedAt = 30), edited)
        val other = MessageAlternativeVersionPolicy.select(edited, 2, 31)
        val returned = MessageAlternativeVersionPolicy.select(other, 1, 32)
        assertEquals("edited", returned.displayContent)
        assertEquals("b", returned.currentAlternativeVersionId)
        val json = kotlinx.serialization.json.Json
        assertEquals(returned, json.decodeFromString(ChatMessage.serializer(), json.encodeToString(ChatMessage.serializer(), returned)))
    }

    @Test
    fun `legacy ids normalize without mutating the source or losing alternatives`() {
        val original = message(listOf("same", "same", "last"), currentAlternativeIndex = 1)
        val expectedIds = MessageAlternativeVersionPolicy.versions(original).map { it.id }
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(original, "new", 20)
        assertEquals(listOf("same", "new", "last"), edited.alternatives)
        assertEquals(expectedIds, edited.alternativeVersionIds)
        assertEquals(expectedIds[1], edited.currentAlternativeVersionId)
        assertEquals(emptyList<String>(), original.alternativeVersionIds)
        assertEquals(edited, MessageAlternativeVersionPolicy.editCurrentContent(original, "new", 20))
    }

    @Test
    fun `plain and invalid-index historical messages retain their display fallback`() {
        val plain = ChatMessage("plain", "session", MessageRole.USER, "old", createdAt = 1, updatedAt = 1)
        val edited = MessageAlternativeVersionPolicy.editCurrentContent(plain, "new", 20)
        assertEquals(plain.copy(content = "new", currentAlternativeVersionId = "plain", updatedAt = 20), edited)
        val invalid = plain.copy(alternatives = listOf("a", "b"), currentAlternativeIndex = 99)
        val repaired = MessageAlternativeVersionPolicy.editCurrentContent(invalid, "new", 20)
        assertEquals("new", repaired.displayContent)
        assertEquals(invalid.alternatives, repaired.alternatives)
        assertEquals(99, repaired.currentAlternativeIndex)
    }

    @Test
    fun `segment edits preserve candidates while empty deletion keeps existing classification`() {
        val original = message(listOf("first", "before [middle]() after", "last"), listOf("a", "b", "c"), 1, "b")
        val segment = parseRoleplayTextSegments(original.displayContent).first { it.kind == RoleplaySegmentKind.DIALOGUE }
        val edited = requireNotNull(editRoleplayMessageSegment(original, segment.start, segment.endExclusive, "[new]()", 20).message)
        assertEquals(listOf("first", "before [new]() after", "last"), edited.alternatives)
        assertEquals(original.alternativeVersionIds, edited.alternativeVersionIds)
        val removed = requireNotNull(editRoleplayMessageSegment(original, segment.start, segment.endExclusive, "", 20).message)
        assertEquals(listOf("first", "before  after", "last"), removed.alternatives)
        val full = original.copy(content = "[only]()", alternatives = listOf("first", "[only]()", "last"))
        assertEquals(null, editRoleplayMessageSegment(full, 0, full.displayContent.length, "", 20).message)
        val withImage = full.copy(images = listOf("owned.png"))
        val outcome = editRoleplayMessageSegment(withImage, 0, withImage.displayContent.length, "", 20)
        assertEquals(true, outcome.deleteMemoryForMessage)
        assertEquals(listOf("first", "", "last"), outcome.message?.alternatives)
        assertEquals(withImage.images, outcome.message?.images)
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
