package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.memory.*
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopMemoryState(
    val open: Boolean = false, val sessionId: String? = null, val enabled: Boolean = false,
    val memory: MemorySessionState? = null, val nodes: List<MemoryNode> = emptyList(),
    val tier: MemoryTier = MemoryTier.EPISODE, val history: List<MemoryTierRevision> = emptyList(),
    val historyNodes: List<MemoryNode>? = null, val busy: Boolean = false, val operationBusy: Boolean = false,
    val message: String = "", val error: String? = null, val estimate: MemoryBackfillEstimate? = null,
)

/** View-independent ownership. Closing this panel does not cancel committed/paid maintenance. */
internal class DesktopMemoryController(
    private val storage: JsonFileStorage,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    private val models: EffectiveModelResolver,
    val service: LongTermMemoryService,
    private val repository: MemoryRepository,
    private val gate: DesktopDataOperationCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DesktopMemoryState())
    val state = mutableState.asStateFlow()
    val coordinator = LongTermMemoryAutoMaintenanceCoordinator(object : MemoryMaintenancePlatform {
        override suspend fun <T> run(sessionId: String, requireValidatedInternet: Boolean, block: suspend () -> T): T =
            gate.withNormalOperation(block)
    }, scope, chats, settings, models, service)
    val backfill = coordinator.backfillProgress
    val repair = coordinator.sourceRepairProgress
    init {
        scope.launch { coordinator.runningSessions.collect { running ->
            mutableState.update { it.copy(busy = it.sessionId in running) }
            state.value.sessionId?.takeIf { state.value.open && it !in running }?.let { refreshSafely(it) }
        } }
        scope.launch { storage.observeAll("memory_states", MemorySessionState.serializer())
            .map { rows -> state.value.sessionId?.let { id -> rows.find { it.sessionId == id } } }
            .distinctUntilChanged().collect { if (state.value.open) state.value.sessionId?.let { refreshSafely(it) } }
        }
    }
    fun selectSession(id: String) { coordinator.selectSession(id) }
    fun afterReply(id: String) { coordinator.enqueue(id, MemoryMaintenanceTrigger.REPLY_PERSISTED) }
    fun sourceChanged(id: String) { coordinator.launchSessionAction(id) {
        try { service.refreshForCurrentConditions(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (state.value.sessionId == id) mutableState.update { it.copy(error = "消息已更改，记忆同步尚未完成；下次读取将重新校验来源") } }
    } }
    fun stop() { val id = state.value.sessionId ?: return; scope.launch {
        try { coordinator.stopAndJoin(id); refreshSafely(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = "停止后的记忆状态未确认；保留原数据") } }
    } }
    suspend fun promptView(session: ChatSession, readOnly: Boolean): MemoryPromptView? {
        if (!session.longTermMemoryEnabled || readOnly) return null
        coordinator.selectSession(session.id)
        coordinator.enqueue(session.id, MemoryMaintenanceTrigger.USER_MESSAGE_PERSISTED)
        return service.promptView(session.id)
    }
    fun open(sessionId: String) {
        mutableState.value = DesktopMemoryState(open = true, sessionId = sessionId)
        action { service.ensureMigrated(sessionId); coordinator.activateSession(sessionId) }
    }
    fun closePanel() { mutableState.update { it.copy(open = false) } }
    fun refresh() = action { }
    fun tier(value: MemoryTier) { mutableState.update { it.copy(tier = value, historyNodes = null) }; refresh() }
    fun setEnabled(enabled: Boolean) = action { id -> service.setEnabled(id, enabled) }
    fun estimateBackfill() = action { id -> val estimate = service.estimateBackfill(id); mutableState.update { it.copy(estimate = estimate) } }
    fun startBackfill() { state.value.sessionId?.let(coordinator::enqueueBackfill) }
    fun pauseBackfill() = action { service.pauseBackfill(it) }
    fun repairSources() { state.value.sessionId?.let(coordinator::enqueueSourceRepair) }
    fun pauseRepair() = action { service.pauseSourceRepair(it) }
    fun retry() { state.value.sessionId?.let { coordinator.enqueue(it, MemoryMaintenanceTrigger.RETRY, manual = true) } }
    fun regenerateAll() { state.value.sessionId?.let(coordinator::enqueueFullRegeneration) }
    fun regenerateHead() { state.value.sessionId?.let(coordinator::enqueueHeadRegeneration) }
    fun expandOrCompress(expand: Boolean) = action { id ->
        service.resolveCompressionDecision(id, expand)?.let { coordinator.enqueueCompressionDecisionContinuation(id, it) }
    }
    fun increaseLimit() = action { service.increaseLimit(it) }
    fun editNode(nodeId: String, text: String, onSaved: () -> Unit = {}) = action { service.editNode(it, nodeId, text); onSaved() }
    fun regenerateNode(nodeId: String, onCandidate: (String) -> Unit) = action { id ->
        val session = requireNotNull(chats.getSession(id))
        val config = settings.getAppSettings()
        val model = models.resolveChatModel(session.modelId, config)
        check(model != null && model.hasConfiguredAuthentication(config)) { "请先配置可用会话模型" }
        val candidate = service.regenerateNodeCandidate(id, nodeId, model)
        if (state.value.sessionId == id) onCandidate(candidate)
    }
    fun editHead(head: MemoryHead, onSaved: () -> Unit = {}) = action { service.editHead(it, head); onSaved() }
    fun previewRevision(id: String) = action { val nodes = service.revisionNodes(id); mutableState.update { state -> state.copy(historyNodes = nodes) } }
    fun restoreRevision(id: String) = action { service.restoreTierRevision(it, id) }
    suspend fun cancelAndJoinForDeletion(id: String) = coordinator.cancelAndJoinForSession(id)

    suspend fun <T> withSessionQuiesced(id: String, operation: suspend () -> T): T =
        coordinator.withSessionQuiesced(id, operation)

    private fun action(work: suspend (String) -> Unit) {
        val id = state.value.sessionId ?: return
        if (state.value.operationBusy) return
        mutableState.update { it.copy(operationBusy = true, error = null) }
        val job = coordinator.launchSessionAction(id) {
            try { gate.withNormalOperation { work(id) }; refreshSafely(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (state.value.sessionId == id) mutableState.update {
                it.copy(error = "记忆操作未完成；原数据与恢复记录保留，请检查模型设置或来源后重试") } }
            finally { if (state.value.sessionId == id) mutableState.update { it.copy(operationBusy = false) } }
        }
        if (job == null) mutableState.update { it.copy(operationBusy = false, error = "该会话正在停止或已关闭，请等待恢复后重试") }
    }
    private suspend fun refreshSafely(id: String) {
        try {
            gate.withNormalOperation {
                val session = chats.getSession(id) ?: return@withNormalOperation
                val memory = repository.getState(id)
                val nodes = repository.getNodes(memory?.let { it.episodePage.activeNodeIds + it.arcPage.activeNodeIds + it.eraPage.activeNodeIds + it.legacyReferenceNodeIds }.orEmpty())
                val history = if (state.value.tier == MemoryTier.LEGACY_REFERENCE) emptyList() else repository.history(id, state.value.tier, 100)
                if (state.value.sessionId == id) mutableState.update { it.copy(enabled = session.longTermMemoryEnabled,
                    memory = memory, nodes = nodes, history = history,
                    message = listOfNotNull(session.memoryArchiveError, session.memoryHeadError).joinToString("；")) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (state.value.sessionId == id) mutableState.update { it.copy(error = "无法完整读取记忆；未覆盖原数据") } }
    }
    suspend fun closeAndDrain() { scope.coroutineContext[Job]!!.cancelAndJoin(); coordinator.closeNetworkSubscription() }
}
