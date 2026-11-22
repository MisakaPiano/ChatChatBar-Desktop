package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.domain.prompt.FormatRepairPromptAuthority

object MessageFormatRepairPolicy {
    fun progressiveOverlay(original: String, repairedPrefix: String): String {
        if (isPossibleUnchangedMarkerPrefix(repairedPrefix)) return original
        if (repairedPrefix.isEmpty()) return original
        val prefixLength = repairedPrefix.codePointCount()
        val originalLength = original.codePointCount()
        if (prefixLength >= originalLength) return repairedPrefix
        val suffixStart = original.offsetByCodePoints(0, prefixLength)
        return repairedPrefix + original.substring(suffixStart)
    }

    fun isUnchangedResult(modelOutput: String): Boolean =
        modelOutput.trim() == FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER

    fun completedRepairNotice(original: String, repaired: String): MessageFormatRepairNotice =
        MessageFormatRepairNotice(
            kind = MessageFormatRepairNoticeKind.APPLIED,
            targetContent = repaired,
            originalContent = original
        )

    fun replaceCurrentDisplayContent(
        message: ChatMessage,
        replacement: String,
        notice: MessageFormatRepairNotice? = applicableNotice(message),
        updatedAt: Long = System.currentTimeMillis()
    ): ChatMessage {
        val alternatives = message.alternatives
        val updatedAlternatives = if (
            alternatives.isNotEmpty() && message.currentAlternativeIndex in alternatives.indices
        ) {
            alternatives.toMutableList().also { it[message.currentAlternativeIndex] = replacement }
        } else {
            alternatives
        }
        val updated = message.copy(
            content = replacement,
            alternatives = updatedAlternatives,
            updatedAt = updatedAt
        )
        return updateNotice(message, updated, notice)
    }

    fun applicableNotice(message: ChatMessage): MessageFormatRepairNotice? {
        val notices = message.repairNotices()
        if (notices.isEmpty()) return null
        val owners = NoticeOwners(message)
        return notices.firstOrNull { owners.owner(it)?.id == owners.activeId }
    }

    internal fun retainVersionNotices(previous: ChatMessage, updated: ChatMessage): ChatMessage =
        updated.withNotices(validNoticesAfterChange(previous, updated))

    internal fun updateCurrentNotice(message: ChatMessage, notice: MessageFormatRepairNotice?): ChatMessage =
        updateNotice(message, message, notice)

    /** A manual edit invalidates only that candidate's recovery, keeping every other owner. */
    fun clearCurrentNotice(message: ChatMessage): ChatMessage = updateCurrentNotice(message, null)

    private fun updateNotice(previous: ChatMessage, updated: ChatMessage,
        notice: MessageFormatRepairNotice?): ChatMessage {
        val activeId = MessageAlternativeVersionPolicy.activeVersionId(updated)
        val others = validNoticesAfterChange(previous, updated).filterNot { it.versionId == activeId }
        return updated.withNotices(listOfNotNull(notice?.copy(versionId = activeId)) + others)
    }

    private fun validNoticesAfterChange(previous: ChatMessage, updated: ChatMessage): List<MessageFormatRepairNotice> {
        val notices = previous.repairNotices()
        if (notices.isEmpty()) return emptyList()
        val owners = NoticeOwners(previous)
        val retained = MessageAlternativeVersionPolicy.versions(updated).associateBy { it.id }
        return notices.mapNotNull { notice ->
            val owner = owners.owner(notice) ?: return@mapNotNull null
            notice.copy(versionId = owner.id).takeIf { retained[owner.id]?.content == owner.content }
        }.distinctBy { it.versionId }
    }

    private fun ChatMessage.repairNotices(): List<MessageFormatRepairNotice> =
        listOfNotNull(formatRepairNotice) + otherFormatRepairNotices

    private fun ChatMessage.withNotices(notices: List<MessageFormatRepairNotice>): ChatMessage =
        copy(formatRepairNotice = notices.firstOrNull(), otherFormatRepairNotices = notices.drop(1))

    /** Build the identity/content indexes once, including for old records without version IDs. */
    private class NoticeOwners(message: ChatMessage) {
        private val versions = MessageAlternativeVersionPolicy.versions(message)
        private val byId = versions.associateBy { it.id }
        private val byContent = versions.groupBy { it.content }
        val activeId = MessageAlternativeVersionPolicy.activeVersionId(message)

        fun owner(notice: MessageFormatRepairNotice): MessageVersionSnapshot? {
            val checkpoint = if (notice.restored) notice.originalContent else notice.targetContent
            if (checkpoint == null) return null
            notice.versionId?.let { return byId[it]?.takeIf { version -> version.content == checkpoint } }
            val matches = byContent[checkpoint].orEmpty()
            return byId[activeId]?.takeIf { it.content == checkpoint } ?: matches.singleOrNull()
        }
    }

    fun recoverableNotice(message: ChatMessage): MessageFormatRepairNotice? =
        applicableNotice(message)?.takeIf { it.originalContent != null && !it.restored }

    fun restoreOriginal(message: ChatMessage, updatedAt: Long = System.currentTimeMillis()): ChatMessage? {
        val notice = recoverableNotice(message) ?: return null
        val original = checkNotNull(notice.originalContent)
        return replaceCurrentDisplayContent(
            message = message,
            replacement = original,
            notice = notice.copy(restored = true),
            updatedAt = updatedAt
        )
    }

    fun redoRepair(message: ChatMessage, updatedAt: Long = System.currentTimeMillis()): ChatMessage? {
        val notice = applicableNotice(message)?.takeIf { it.restored } ?: return null
        return replaceCurrentDisplayContent(message, notice.targetContent, notice.copy(restored = false), updatedAt)
    }

    private fun isPossibleUnchangedMarkerPrefix(outputPrefix: String): Boolean {
        val marker = FormatRepairPromptAuthority.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER
        val trimmedStart = outputPrefix.trimStart()
        if (trimmedStart.isEmpty() || marker.startsWith(trimmedStart)) return true
        if (!trimmedStart.startsWith(marker)) return false
        return trimmedStart.removePrefix(marker).isBlank()
    }
}

private fun String.codePointCount(): Int = codePointCount(0, length)
