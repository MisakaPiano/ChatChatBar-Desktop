package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import java.util.UUID

/** Shared original candidate constraints and explicit selected-entry application. */
object WorldBookAiPolicy {
    fun constrainCreatedEntries(
        book: WorldBook,
        completed: List<WorldBookEntryPlanCandidate>,
        rawEntries: List<CreateEntryResponse>,
        remaining: Int,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): List<WorldBookEntryPlanCandidate> {
        val usedNames = (book.entries.map { it.name } + completed.map { it.name })
            .map(String::entryIdentity)
            .filter(String::isNotBlank)
            .toMutableSet()
        val usedKeySets = (book.entries.map { it.keys } + completed.map { it.keys })
            .map(List<String>::normalizedKeySet)
            .filter(Set<String>::isNotEmpty)
            .toMutableSet()
        val result = mutableListOf<WorldBookEntryPlanCandidate>()
        rawEntries.take(minOf(WORLD_BOOK_AI_BATCH_SIZE, remaining)).forEach { raw ->
            val name = raw.name.trim()
            val keys = raw.keys.map(String::trim).filter(String::isNotBlank)
                .distinctBy { it.lowercase() }.take(12)
            val nameKey = name.entryIdentity()
            val keySet = keys.normalizedKeySet()
            if (nameKey.isBlank() || keys.isEmpty()) return@forEach
            if (!usedNames.add(nameKey)) return@forEach
            if (keySet.isNotEmpty() && !usedKeySets.add(keySet)) return@forEach
            result += WorldBookEntryPlanCandidate(idFactory(), name, keys)
        }
        return result
    }

    fun constrainFilledEntries(
        targets: List<WorldBookEntry>,
        rawEntries: List<FillEntryResponse>
    ): FillConstraintResult {
        val targetsById = targets.associateBy(WorldBookEntry::id)
        val seen = mutableSetOf<String>()
        val rejections = mutableListOf<String>()
        val constrained = rawEntries.mapNotNull { item ->
            val target = targetsById[item.targetId]
            if (target == null) {
                rejections += "已丢弃未知目标 ID：${item.targetId.take(80)}"
                return@mapNotNull null
            }
            if (target.content.isNotBlank()) {
                rejections += "已丢弃正文非空目标：${item.targetId}"
                return@mapNotNull null
            }
            if (!seen.add(item.targetId)) {
                rejections += "已丢弃重复目标：${item.targetId}"
                return@mapNotNull null
            }
            if (item.content.isBlank()) {
                rejections += "已丢弃空正文结果：${item.targetId}"
                return@mapNotNull null
            }
            WorldBookContentCandidate(
                targetId = target.id,
                name = target.name,
                keys = target.keys,
                content = item.content.trim()
            )
        }
        return FillConstraintResult(constrained, rejections)
    }

    fun decideCreateContinuation(
        responseHasMore: Boolean,
        newCandidateCount: Int,
        totalCandidateCount: Int
    ): CreateContinuationDecision = when {
        !responseHasMore -> CreateContinuationDecision(false)
        totalCandidateCount >= WORLD_BOOK_AI_CREATE_LIMIT -> CreateContinuationDecision(
            false,
            "已达到单次创建 50 条上限"
        )
        newCandidateCount == 0 -> CreateContinuationDecision(
            false,
            "AI 表示仍有条目，但本批没有产生新的有效候选，已停止以防重复循环"
        )
        else -> CreateContinuationDecision(true)
    }

    fun applyCreatedEntries(
        current: List<WorldBookEntry>,
        candidates: List<WorldBookEntryPlanCandidate>,
        selectedIds: Set<String>,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): List<WorldBookEntry> = current + candidates
        .filter { it.candidateId in selectedIds }
        .map { candidate ->
            WorldBookEntry(
                id = idFactory(),
                name = candidate.name,
                keys = candidate.keys,
                content = ""
            )
        }

    fun applyFilledContents(
        current: List<WorldBookEntry>,
        candidates: List<WorldBookContentCandidate>,
        selectedIds: Set<String>
    ): List<WorldBookEntry> {
        val contentById = candidates
            .filter { it.targetId in selectedIds && it.content.isNotBlank() }
            .associate { it.targetId to it.content.trim() }
        return current.map { entry ->
            val content = contentById[entry.id]
            if (content != null && entry.content.isBlank()) entry.copy(content = content) else entry
        }
    }
}

fun isWorldBookAiCheckpointCompatible(
    checkpointSignature: String,
    currentSignature: String
): Boolean = checkpointSignature.isNotBlank() && checkpointSignature == currentSignature

private fun String.entryIdentity(): String = trim().lowercase().replace(Regex("\\s+"), "")

private fun List<String>.normalizedKeySet(): Set<String> =
    map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()
