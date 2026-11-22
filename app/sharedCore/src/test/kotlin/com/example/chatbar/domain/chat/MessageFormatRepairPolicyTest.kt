package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.prompt.FormatRepairPromptAuthority
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.serialization.json.Json

class MessageFormatRepairPolicyTest {
    private fun twoVersionRepairPoints(): ChatMessage {
        val original = message().copy(content = "raw A", alternatives = listOf("raw A", "raw B"),
            alternativeVersionIds = listOf("a", "b"), currentAlternativeIndex = 0, currentAlternativeVersionId = "a")
        val a = MessageFormatRepairPolicy.replaceCurrentDisplayContent(original, "fixed A",
            MessageFormatRepairPolicy.completedRepairNotice("raw A", "fixed A"))
        val undoneA = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(a))
        val b = MessageAlternativeVersionPolicy.select(undoneA, 1)
        return MessageFormatRepairPolicy.replaceCurrentDisplayContent(b, "fixed B",
            MessageFormatRepairPolicy.completedRepairNotice("raw B", "fixed B"))
    }

    @Test fun multipleVersionRecoveryPointsSurviveSerializationAndIndependentRestore() {
        val source = twoVersionRepairPoints()
        val encoded = Json.encodeToString(ChatMessage.serializer(), source)
        val cold = Json.decodeFromString(ChatMessage.serializer(), encoded)
        val a = MessageAlternativeVersionPolicy.select(cold, 0)
        assertTrue(MessageFormatRepairPolicy.applicableNotice(a)?.restored == true)
        val redoneA = requireNotNull(MessageFormatRepairPolicy.redoRepair(a))
        assertEquals(listOf("fixed A", "fixed B"), redoneA.alternatives)
        val b = MessageAlternativeVersionPolicy.select(redoneA, 1)
        val restoredB = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(b))
        assertEquals(listOf("fixed A", "raw B"), restoredB.alternatives)
        assertTrue(MessageFormatRepairPolicy.recoverableNotice(MessageAlternativeVersionPolicy.select(restoredB, 0)) != null)
    }

    @Test fun editsDeletionAndPruningKeepOnlyUnchangedSurvivingRecoveryOwners() {
        val source = twoVersionRepairPoints()
        val editedB = MessageFormatRepairPolicy.clearCurrentNotice(
            MessageAlternativeVersionPolicy.editCurrentContent(source, "manually edited B"))
        assertNull(MessageFormatRepairPolicy.applicableNotice(editedB))
        assertTrue(MessageFormatRepairPolicy.applicableNotice(MessageAlternativeVersionPolicy.select(editedB, 0))?.restored == true)
        val removedB = requireNotNull(MessageAlternativeVersionPolicy.remove(source, "b"))
        assertTrue(MessageFormatRepairPolicy.applicableNotice(removedB)?.restored == true)
        val pruned = MessageAlternativeVersionPolicy.append(source, "new C", "c", maxVersions = 1)
        assertNull(pruned.formatRepairNotice)
        assertTrue(pruned.otherFormatRepairNotices.isEmpty())
    }

    @Test fun oldSingleSlotStillDecodesAndEmptyExtraSlotsDoNotChangeLegacyWireShape() {
        val legacy = """{"id":"m","sessionId":"s","role":"ASSISTANT","content":"raw","createdAt":1,"updatedAt":1,"formatRepairNotice":{"kind":"APPLIED","targetContent":"fixed","originalContent":"raw","restored":true}}"""
        val message = Json.decodeFromString(ChatMessage.serializer(), legacy)
        assertTrue(message.otherFormatRepairNotices.isEmpty())
        assertEquals("fixed", MessageFormatRepairPolicy.redoRepair(message)?.displayContent)
        val encoded = Json { encodeDefaults = true }.encodeToString(ChatMessage.serializer(), message)
        assertFalse(encoded.contains("otherFormatRepairNotices"))
    }

    @Test
    fun progressiveOverlay_replacesByUnicodeCodePoint() {
        assertEquals("修😀尾", MessageFormatRepairPolicy.progressiveOverlay("原😀尾", "修😀"))
        assertEquals("完整更长", MessageFormatRepairPolicy.progressiveOverlay("原文", "完整更长"))
        assertEquals("原文", MessageFormatRepairPolicy.progressiveOverlay("原文", ""))
    }

    @Test
    fun progressiveOverlay_hidesPossibleUnchangedMarker() {
        val marker = FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER

        assertEquals("原文", MessageFormatRepairPolicy.progressiveOverlay("原文", "[[CHAT"))
        assertEquals("原文", MessageFormatRepairPolicy.progressiveOverlay("原文", "\n$marker"))
        assertEquals("修文", MessageFormatRepairPolicy.progressiveOverlay("原文", "修"))
    }

    @Test
    fun unchangedResult_requiresExactTrimmedMarker() {
        val marker = FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER

        assertTrue(MessageFormatRepairPolicy.isUnchangedResult(marker))
        assertTrue(MessageFormatRepairPolicy.isUnchangedResult("\n$marker \n"))
        assertFalse(MessageFormatRepairPolicy.isUnchangedResult("$marker\n额外内容"))
        assertFalse(MessageFormatRepairPolicy.isUnchangedResult("原文"))
    }

    @Test
    fun completedRepairNotice_keepsOriginalForNormalLengthRepair() {
        val original = "原始回复"
        val repaired = "错误回复"
        val notice = MessageFormatRepairPolicy.completedRepairNotice(original, repaired)

        assertEquals(MessageFormatRepairNoticeKind.APPLIED, notice.kind)
        assertEquals(original, notice.originalContent)
        assertEquals(repaired, notice.targetContent)

        val updated = MessageFormatRepairPolicy.replaceCurrentDisplayContent(
            message = message().copy(content = original),
            replacement = repaired,
            notice = notice,
            updatedAt = 9
        )
        val restored = MessageFormatRepairPolicy.restoreOriginal(updated, updatedAt = 10)

        assertEquals(original, restored?.displayContent)
        assertTrue(restored?.formatRepairNotice?.restored == true)
        assertEquals(repaired, restored?.formatRepairNotice?.targetContent)
    }

    @Test
    fun completedRepairNotice_acceptsLargeLengthChangeAndKeepsOriginal() {
        val notice = MessageFormatRepairPolicy.completedRepairNotice("原始回复很长", "坏")

        assertEquals(MessageFormatRepairNoticeKind.APPLIED, notice.kind)
        assertEquals("原始回复很长", notice.originalContent)
        assertEquals("坏", notice.targetContent)
    }

    @Test
    fun recoverableNotice_keepsRollbackPointAcrossNoOpRepair() {
        val notice = MessageFormatRepairPolicy.completedRepairNotice("原始回复", "修复回复")
        val repaired = message().copy(
            content = "修复回复",
            formatRepairNotice = notice
        )

        assertEquals(notice, MessageFormatRepairPolicy.recoverableNotice(repaired))

        val failed = repaired.copy(
            formatRepairNotice = MessageFormatRepairNotice(
                kind = MessageFormatRepairNoticeKind.ERROR,
                targetContent = repaired.displayContent
            )
        )
        assertNull(MessageFormatRepairPolicy.recoverableNotice(failed))
    }

    @Test
    fun replacementAndRestore_preserveOtherAlternatives() {
        val notice = MessageFormatRepairNotice(
            kind = MessageFormatRepairNoticeKind.STOPPED,
            targetContent = "fixed-b",
            originalContent = "b"
        )
        val updated = MessageFormatRepairPolicy.replaceCurrentDisplayContent(
            message = message().copy(
                content = "b",
                alternatives = listOf("a", "b", "c"),
                currentAlternativeIndex = 1
            ),
            replacement = "fixed-b",
            notice = notice,
            updatedAt = 9
        )

        assertEquals(listOf("a", "fixed-b", "c"), updated.alternatives)
        assertEquals("fixed-b", updated.content)
        assertEquals(notice.copy(versionId = MessageAlternativeVersionPolicy.activeVersionId(updated)), MessageFormatRepairPolicy.applicableNotice(updated))

        val restored = MessageFormatRepairPolicy.restoreOriginal(updated, updatedAt = 10)
        assertEquals(listOf("a", "b", "c"), restored?.alternatives)
        assertEquals("b", restored?.content)
        assertTrue(restored?.formatRepairNotice?.restored == true)
        assertEquals("fixed-b", MessageFormatRepairPolicy.redoRepair(requireNotNull(restored))?.displayContent)
    }

    @Test
    fun noticeIsHiddenWhenAnotherAlternativeIsSelected() {
        val message = message().copy(
            content = "fixed",
            alternatives = listOf("fixed", "other"),
            currentAlternativeIndex = 1,
            formatRepairNotice = MessageFormatRepairNotice(
                kind = MessageFormatRepairNoticeKind.LENGTH_ANOMALY,
                targetContent = "fixed",
                originalContent = "original"
            )
        )

        assertNull(MessageFormatRepairPolicy.applicableNotice(message))
    }

    @Test
    fun markerPrefixesAndEmojiRespectExistingOverlayContract() {
        val marker = FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER
        for (size in 0..marker.length) {
            assertEquals("😀原文", MessageFormatRepairPolicy.progressiveOverlay("😀原文", " "+marker.take(size)))
        }
        assertEquals("🐟尾", MessageFormatRepairPolicy.progressiveOverlay("😀尾", "🐟"))
        assertEquals("🐟😀", MessageFormatRepairPolicy.progressiveOverlay("原文", "🐟😀"))
        assertFalse(MessageFormatRepairPolicy.isUnchangedResult(marker + "extra"))
    }

    @Test
    fun repairAndRestorePreserveCandidateIdentityAndNonTargetMetadata() {
        val original = message().copy(content = "b", alternatives = listOf("a", "b", "c"),
            alternativeVersionIds = listOf("va", "vb", "vc"), currentAlternativeIndex = 1,
            currentAlternativeVersionId = "vb", images = listOf("owned.png"),
            reasoningContent = "reason", sourceTurnId = "turn", sourceTurnOrder = 7, orderKey = 55)
        val notice = MessageFormatRepairPolicy.completedRepairNotice("b", "fixed")
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(original, "fixed", notice, 9)
        assertEquals(original.copy(content = "fixed", alternatives = listOf("a", "fixed", "c"),
            formatRepairNotice = notice.copy(versionId = "vb"), updatedAt = 9), repaired)
        assertEquals(original.copy(formatRepairNotice = notice.copy(versionId = "vb", restored = true), updatedAt = 10),
            MessageFormatRepairPolicy.restoreOriginal(repaired, 10))
    }

    @Test fun `local undo redo survives serialization and refuses another identical candidate`() {
        val original = message().copy(content = "original", alternatives = listOf("original", "original"),
            alternativeVersionIds = listOf("a", "b"), currentAlternativeVersionId = "a")
        val applied = MessageFormatRepairPolicy.replaceCurrentDisplayContent(original, "fixed",
            MessageFormatRepairPolicy.completedRepairNotice("original", "fixed"), 2)
        val restored = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(applied, 3))
        val json = kotlinx.serialization.json.Json
        val reopened = json.decodeFromString(ChatMessage.serializer(), json.encodeToString(ChatMessage.serializer(), restored))
        assertNull(MessageFormatRepairPolicy.restoreOriginal(reopened))
        val other = MessageAlternativeVersionPolicy.select(reopened, 1, 4)
        assertNull(MessageFormatRepairPolicy.redoRepair(other))
        val returned = MessageAlternativeVersionPolicy.select(other, 0, 5)
        val redone = requireNotNull(MessageFormatRepairPolicy.redoRepair(returned, 6))
        assertEquals(listOf("fixed", "original"), redone.alternatives)
        assertEquals(listOf("a", "b"), redone.alternativeVersionIds)
        assertEquals(false, redone.formatRepairNotice?.restored)
        assertNull(MessageFormatRepairPolicy.redoRepair(reopened.copy(content = "manual edit", alternatives = listOf("manual edit", "original"))))
    }

    @Test
    fun noApplicableRecoveryDoesNotRestoreAndInvalidIndexKeepsAlternatives() {
        assertNull(MessageFormatRepairPolicy.restoreOriginal(message()))
        val invalid = message().copy(alternatives = listOf("a"), currentAlternativeIndex = 9)
        val replaced = MessageFormatRepairPolicy.replaceCurrentDisplayContent(invalid, "new", updatedAt = 5)
        assertEquals(listOf("a"), replaced.alternatives)
        assertEquals("new", replaced.displayContent)
    }

    private fun message() = ChatMessage(
        id = "m",
        sessionId = "s",
        role = MessageRole.ASSISTANT,
        content = "text",
        createdAt = 1,
        updatedAt = 1
    )
}
