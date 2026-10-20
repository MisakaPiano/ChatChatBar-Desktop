package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.MemoryCommit
import com.example.chatbar.data.local.entity.MemoryCommitJournal
import com.example.chatbar.data.local.entity.MemoryCompressionTransaction
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemorySessionSnapshot
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemorySnapshot
import com.example.chatbar.data.local.entity.MemorySourceRepairStatus
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTierRevision

class MemoryRepository(
    private val storage: JsonFileStorage,
    private val strictReads: Boolean = false,
    private val dataGate: com.example.chatbar.data.operation.AppDataOperationGate = com.example.chatbar.data.operation.NoOpAppDataOperationGate,
    private val validatePath: (java.nio.file.Path) -> Unit = {},
) {
    suspend fun getNode(id: String): MemoryNode? =
        read(NODE_TYPE, id, MemoryNode.serializer())

    suspend fun getNodes(ids: List<String>): List<MemoryNode> = ids.mapNotNull { id ->
        getNode(id).also { check(!strictReads || it != null) { "记忆节点缺失；保留原状态" } }
    }

    suspend fun getReachableNodes(rootIds: List<String>): List<MemoryNode> {
        val result = linkedMapOf<String, MemoryNode>()
        val pending = ArrayDeque(rootIds.distinct())
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (id in result) continue
            val node = getNode(id) ?: if (strictReads) error("记忆依赖节点缺失；保留原状态") else continue
            result[id] = node
            node.childIds.forEach(pending::addLast)
        }
        return result.values.toList()
    }

    suspend fun getNodesForSession(sessionId: String): List<MemoryNode> =
        query(NODE_TYPE, MemoryNode.serializer()) { it.sessionId == sessionId }

    suspend fun saveNodes(nodes: List<MemoryNode>) {
        if (nodes.isEmpty()) return
        storage.saveAll(NODE_TYPE, nodes.associateBy { it.id }, MemoryNode.serializer())
    }

    suspend fun getState(sessionId: String): MemorySessionState? = dataGate.withNormalOperation {
        recoverJournals(sessionId)
        read(STATE_TYPE, sessionId, MemorySessionState.serializer())
    }

    suspend fun saveState(state: MemorySessionState) {
        storage.saveEntity(STATE_TYPE, state.sessionId, state, MemorySessionState.serializer())
    }

    /** Crash-safe multi-file commit. State pointer is always written last. */
    suspend fun commitStateLast(
        expectedStateRevision: Long,
        nextState: MemorySessionState,
        nodes: List<MemoryNode> = emptyList(),
        revisions: List<MemoryTierRevision> = emptyList(),
        transactions: List<MemoryCompressionTransaction> = emptyList(),
        deleteNodeIds: List<String> = emptyList(),
        deleteRevisionIds: List<String> = emptyList(),
        deleteTransactionIds: List<String> = emptyList()
    ) = dataGate.withNormalOperation {
        val journal = MemoryCommitJournal(
            id = MemoryCommitJournal.newId(),
            sessionId = nextState.sessionId,
            expectedStateRevision = expectedStateRevision,
            nodes = nodes,
            revisions = revisions,
            transactions = transactions,
            deleteNodeIds = deleteNodeIds,
            deleteRevisionIds = deleteRevisionIds,
            deleteTransactionIds = deleteTransactionIds,
            nextState = nextState
        )
        if (strictReads) {
            validateJournal(journal)
            val current = read(STATE_TYPE, nextState.sessionId, MemorySessionState.serializer())
            check((current?.revision ?: 0L) == expectedStateRevision) { "记忆提交版本已经变化" }
        }
        storage.saveEntity(JOURNAL_TYPE, journal.id, journal, MemoryCommitJournal.serializer())
        applyJournal(journal)
        storage.deleteEntity<MemoryCommitJournal>(JOURNAL_TYPE, journal.id)
    }

    private suspend fun recoverJournals(sessionId: String) {
        val journals = query(JOURNAL_TYPE, MemoryCommitJournal.serializer()) {
            it.sessionId == sessionId
        }.sortedBy { it.createdAt }
        for (journal in journals) {
            if (strictReads) validateJournal(journal)
            val current = read(STATE_TYPE, sessionId, MemorySessionState.serializer())
            when (if (strictReads) current?.revision ?: 0L else current?.revision) {
                journal.expectedStateRevision,
                journal.nextState.revision -> applyJournal(journal)
                else -> if (strictReads) error("记忆恢复版本不匹配；日志与原数据保留") else Unit
            }
            storage.deleteEntity<MemoryCommitJournal>(JOURNAL_TYPE, journal.id)
        }
    }

    private suspend fun applyJournal(journal: MemoryCommitJournal) {
        saveNodes(journal.nodes)
        saveRevisions(journal.revisions)
        journal.transactions.forEach { saveTransaction(it) }
        storage.saveEntity(
            STATE_TYPE,
            journal.sessionId,
            journal.nextState,
            MemorySessionState.serializer()
        )
        journal.deleteNodeIds.distinct().forEach { id ->
            storage.deleteEntity<MemoryNode>(NODE_TYPE, id)
        }
        journal.deleteRevisionIds.distinct().forEach { id ->
            storage.deleteEntity<MemoryTierRevision>(REVISION_TYPE, id)
        }
        journal.deleteTransactionIds.distinct().forEach { id ->
            storage.deleteEntity<MemoryCompressionTransaction>(TRANSACTION_TYPE, id)
        }
    }

    suspend fun getRevision(id: String): MemoryTierRevision? =
        read(REVISION_TYPE, id, MemoryTierRevision.serializer())

    suspend fun saveRevision(revision: MemoryTierRevision) {
        storage.saveEntity(REVISION_TYPE, revision.id, revision, MemoryTierRevision.serializer())
    }

    suspend fun saveRevisions(revisions: List<MemoryTierRevision>) {
        if (revisions.isEmpty()) return
        storage.saveAll(
            REVISION_TYPE,
            revisions.associateBy { it.id },
            MemoryTierRevision.serializer()
        )
    }

    suspend fun history(
        sessionId: String,
        tier: MemoryTier,
        limit: Int = Int.MAX_VALUE
    ): List<MemoryTierRevision> {
        if (limit <= 0) return emptyList()
        val state = getState(sessionId) ?: return emptyList()
        var revisionId = state.page(tier).currentRevisionId
        val visited = mutableSetOf<String>()
        val result = mutableListOf<MemoryTierRevision>()
        while (revisionId != null && result.size < limit) {
            check(visited.add(revisionId)) { "记忆revision历史存在循环：$revisionId" }
            val revision = getRevision(revisionId) ?: if (strictReads) error("记忆历史节点缺失；保留原状态") else break
            check(revision.sessionId == sessionId && revision.tier == tier) {
                "记忆revision历史跨会话或跨分页"
            }
            if (revision.visible) result += revision
            revisionId = revision.parentRevisionId
        }
        return result
    }

    suspend fun allRevisions(sessionId: String): List<MemoryTierRevision> =
        query(REVISION_TYPE, MemoryTierRevision.serializer()) { it.sessionId == sessionId }
            .sortedBy { it.createdAt }

    suspend fun saveTransaction(transaction: MemoryCompressionTransaction) {
        storage.saveEntity(
            TRANSACTION_TYPE,
            transaction.id,
            transaction,
            MemoryCompressionTransaction.serializer()
        )
    }

    suspend fun getTransaction(id: String): MemoryCompressionTransaction? =
        read(
            TRANSACTION_TYPE,
            id,
            MemoryCompressionTransaction.serializer()
        )

    suspend fun allTransactions(sessionId: String): List<MemoryCompressionTransaction> =
        query(TRANSACTION_TYPE, MemoryCompressionTransaction.serializer()) {
            it.sessionId == sessionId
        }

    /** 从最近物化点应用增删delta。 */
    suspend fun materializeRevision(revisionId: String): List<String> {
        val visiting = mutableSetOf<String>()
        suspend fun materialize(id: String): List<String> {
            check(visiting.add(id)) { "记忆revision存在循环：$id" }
            val revision = getRevision(id) ?: error("记忆revision不存在：$id")
            revision.snapshotNodeIds?.let { snapshot ->
                visiting.remove(id)
                return snapshot
            }
            val base = revision.parentRevisionId
                ?.let { materialize(it) }
                .orEmpty()
            val removed = revision.removedNodeIds.toSet()
            val result = (base.filterNot { it in removed } + revision.addedNodeIds).distinct()
            visiting.remove(id)
            return result
        }
        return materialize(revisionId)
    }

    suspend fun snapshot(sessionId: String): MemorySnapshot? {
        val state = getState(sessionId) ?: return null
        val reachable = getReachableNodes(state.activeNodeIds + state.legacyReferenceNodeIds)
        return MemorySnapshot(
            state = state.toSnapshot(),
            nodes = reachable
        )
    }

    /** v2草稿SaveSlot兼容。 */
    suspend fun snapshotLegacyCommit(commitId: String): MemorySnapshot? {
        val commit = getLegacyCommit(commitId) ?: return null
        val all = getNodesForSession(commit.sessionId).associateBy { it.id }
        val reachable = linkedMapOf<String, MemoryNode>()
        fun visit(id: String) {
            if (id in reachable) return
            val node = all[id] ?: return
            reachable[id] = node
            node.childIds.forEach(::visit)
        }
        (commit.activeNodeIds + commit.legacyReferenceNodeIds).forEach(::visit)
        return MemorySnapshot(legacyCommit = commit, nodes = reachable.values.toList())
    }

    // ===== v2草稿兼容读取；迁移完成后不再创建 =====

    suspend fun getLegacyCommit(id: String): MemoryCommit? =
        read(LEGACY_COMMIT_TYPE, id, MemoryCommit.serializer())

    suspend fun legacyHistory(sessionId: String): List<MemoryCommit> =
        query(LEGACY_COMMIT_TYPE, MemoryCommit.serializer()) { it.sessionId == sessionId }
            .sortedBy { it.createdAt }

    suspend fun deleteForSession(sessionId: String) {
        storage.deleteWhere(NODE_TYPE, MemoryNode.serializer()) { it.sessionId == sessionId }
        storage.deleteWhere(STATE_TYPE, MemorySessionState.serializer()) { it.sessionId == sessionId }
        storage.deleteWhere(REVISION_TYPE, MemoryTierRevision.serializer()) { it.sessionId == sessionId }
        storage.deleteWhere(
            TRANSACTION_TYPE,
            MemoryCompressionTransaction.serializer()
        ) { it.sessionId == sessionId }
        storage.deleteWhere(LEGACY_COMMIT_TYPE, MemoryCommit.serializer()) { it.sessionId == sessionId }
        storage.deleteWhere(JOURNAL_TYPE, MemoryCommitJournal.serializer()) { it.sessionId == sessionId }
    }

    /** Desktop reset uses the existing redo journal, so a crash cannot leave half-deleted memory.
     * Historical legacy commits are retained as non-active evidence; the new v4 state is authority. */
    suspend fun resetForFullRegeneration(nextState: MemorySessionState) {
        if (!strictReads) {
            deleteForSession(nextState.sessionId)
            saveState(nextState)
            return
        }
        dataGate.withNormalOperation {
            val current = requireNotNull(getState(nextState.sessionId))
            check(nextState.revision > current.revision && nextState.activeNodeIds.isEmpty())
            commitStateLast(current.revision, nextState,
                deleteNodeIds = getNodesForSession(nextState.sessionId).map { it.id },
                deleteRevisionIds = allRevisions(nextState.sessionId).map { it.id },
                deleteTransactionIds = query(TRANSACTION_TYPE, MemoryCompressionTransaction.serializer()) {
                    it.sessionId == nextState.sessionId
                }.map { it.id })
        }
    }

    private suspend fun <T : Any> read(type: String, id: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (!strictReads) return storage.loadEntity(type, id, serializer)
        require(id.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        var found: T? = null
        storage.visitEntitiesStrict(type, serializer, validatePath = validatePath) { storageId, value ->
            checkIdentity(storageId, value)
            if (storageId == id) found = value
        }
        return found
    }

    private suspend fun <T : Any> query(type: String, serializer: kotlinx.serialization.KSerializer<T>, predicate: (T) -> Boolean): List<T> {
        if (!strictReads) return storage.query(type, serializer, predicate)
        val result = ArrayList<T>()
        var retained = 0L
        storage.visitEntitiesStrict(type, serializer, validatePath = validatePath) { id, value ->
            checkIdentity(id, value)
            if (predicate(value)) {
                retained += storage.json.encodeToString(serializer, value).length * 2L
                check(result.size < 20_000 && retained <= 64L * 1024 * 1024) { "记忆读取超过预算" }
                result += value
            }
        }
        return result
    }

    private fun checkIdentity(id: String, value: Any) {
        val actual = when (value) {
            is MemorySessionState -> value.sessionId
            is MemoryNode -> value.id
            is MemoryTierRevision -> value.id
            is MemoryCompressionTransaction -> value.id
            is MemoryCommit -> value.id
            is MemoryCommitJournal -> value.id
            else -> error("未知记忆记录")
        }
        check(id == actual) { "记忆文件身份不匹配" }
    }

    private suspend fun validateJournal(journal: MemoryCommitJournal) {
        check(journal.nextState.sessionId == journal.sessionId && journal.nextState.revision > journal.expectedStateRevision)
        check(journal.nodes.all { it.sessionId == journal.sessionId })
        check(journal.revisions.all { it.sessionId == journal.sessionId })
        check(journal.transactions.all { it.sessionId == journal.sessionId })
        journal.deleteNodeIds.forEach { id -> check(getNode(id)?.sessionId.let { it == null || it == journal.sessionId }) }
        journal.deleteRevisionIds.forEach { id -> check(getRevision(id)?.sessionId.let { it == null || it == journal.sessionId }) }
        journal.deleteTransactionIds.forEach { id -> check(getTransaction(id)?.sessionId.let { it == null || it == journal.sessionId }) }
    }

    private fun MemorySessionState.toSnapshot(): MemorySessionSnapshot = MemorySessionSnapshot(
        episodeNodeIds = episodePage.activeNodeIds,
        arcNodeIds = arcPage.activeNodeIds,
        eraNodeIds = eraPage.activeNodeIds,
        legacyReferenceNodeIds = legacyReferenceNodeIds,
        head = head,
        timeline = timeline,
        gaps = gaps,
        staleSourcesByNodeId = staleSourcesByNodeId,
        pendingSourceTurnIds = pendingSourceTurnIds,
        episodeCompressionPromptDeclined = episodeCompressionPromptDeclined,
        arcCompressionPromptDeclined = arcCompressionPromptDeclined,
        eraCompressionPromptDeclined = eraCompressionPromptDeclined,
        eraCompressionsSincePrompt = eraCompressionsSincePrompt,
        pendingDecision = pendingDecision,
        fullRegenerationPending = fullRegenerationPending,
        memoryWasEnabled = memoryWasEnabled,
        disabledAfterSourceOrder = disabledAfterSourceOrder,
        recordingStartsAfterSourceOrder = recordingStartsAfterSourceOrder,
        gapRetentionVersion = gapRetentionVersion,
        projectedDeletedSourceTurnIds = projectedDeletedSourceTurnIds,
        backfill = backfill.copy(
            status = if (backfill.status == com.example.chatbar.data.local.entity.MemoryBackfillStatus.RUNNING) {
                com.example.chatbar.data.local.entity.MemoryBackfillStatus.PAUSED
            } else {
                backfill.status
            }
        ),
        sourceRepair = sourceRepair.copy(
            status = if (sourceRepair.status == MemorySourceRepairStatus.RUNNING) {
                MemorySourceRepairStatus.PAUSED
            } else {
                sourceRepair.status
            }
        ),
        archiveFailure = archiveFailure,
        headFailure = headFailure
    )

    companion object {
        private const val NODE_TYPE = "memory_nodes"
        private const val STATE_TYPE = "memory_states"
        private const val REVISION_TYPE = "memory_tier_revisions"
        private const val TRANSACTION_TYPE = "memory_compression_transactions"
        private const val LEGACY_COMMIT_TYPE = "memory_commits"
        private const val JOURNAL_TYPE = "memory_commit_journals"
        const val MATERIALIZED_SNAPSHOT_INTERVAL = 20
    }
}
