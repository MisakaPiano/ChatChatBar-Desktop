package com.example.chatbar.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class DesktopWorkKind(val label: String) {
    CHAT("对话生成"), IMAGE("图片生成"), FORMAT_REPAIR("消息格式修复"), DOCUMENT("文档索引"),
    CARD_DESIGN("格式卡生成"), CREATIVE("创作任务"), HISTORY_INDEX("历史索引"), VOICE("语音生成"), MEMORY("记忆维护"), MOMENT("朋友圈任务"),
    BACKUP("备份"), MERGE("数据合并"), SAVE_SLOT("会话存档"), CHARACTER_AI("角色卡创作"),
    WORLD_BOOK_AI("世界书创作"), CHARACTER_IMAGE("角色图片")
}
internal enum class DesktopWorkPhase { RUNNING, WAITING, COMPLETED, FAILED, CANCELLED, UNCONFIRMED }
internal enum class DesktopWorkDetail { MEMORY, FORMAT_DRAFT, BACKUP_EXPORT, BACKUP_CHECK }
internal data class DesktopWorkTarget(val route: DesktopPrimaryRoute, val sessionId: String? = null,
    val cardId: String? = null, val subjectId: String? = null, val draftSessionId: String? = null,
    val detail: DesktopWorkDetail? = null)

/** Body-free projection only. A row never contains a provider message, prompt, candidate or credential. */
internal data class DesktopWorkRow(val id: String, val kind: DesktopWorkKind, val phase: DesktopWorkPhase,
    val createdAt: Long, val target: DesktopWorkTarget? = null, val completed: Int? = null, val total: Int? = null,
    val canStop: Boolean = false, val canRetry: Boolean = false, val stopRequested: Boolean = false,
    val contextLabel: String? = null) {
    val active get() = phase == DesktopWorkPhase.RUNNING || phase == DesktopWorkPhase.WAITING
}
internal data class DesktopTaskCenterState(val rows: List<DesktopWorkRow> = emptyList(), val error: String? = null)
internal data class DesktopWorkNotice(val kind: DesktopWorkKind, val phase: DesktopWorkPhase, val target: DesktopWorkTarget?)

/** Observes existing owners. Closing this observer does not cancel or replace any business owner. */
internal class DesktopTaskCenter(dispatcher: CoroutineDispatcher = Dispatchers.Default, private val historyLimit: Int = 80,
    private val describe: suspend (DesktopWorkTarget) -> String? = { null }) {
    private class Binding(val stop: suspend (String) -> Boolean, val retry: suspend (String) -> Boolean) {
        var rows = emptyList<DesktopWorkRow>()
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val bindings = linkedMapOf<String, Binding>()
    private val requestedStops = mutableSetOf<String>()
    private val labels = mutableMapOf<DesktopWorkTarget, String>()
    private val lookingUp = mutableSetOf<DesktopWorkTarget>()
    private val mutable = MutableStateFlow(DesktopTaskCenterState())
    val state = mutable.asStateFlow()
    private val mutableOpenRequests = MutableStateFlow(0L)
    val openRequests = mutableOpenRequests.asStateFlow()
    fun requestOpen() { mutableOpenRequests.update { it + 1 } }
    private val mutableNotices = MutableSharedFlow<DesktopWorkNotice>(extraBufferCapacity = 32)
    val notices = mutableNotices.asSharedFlow()
    private var closed = false
    init { require(historyLimit > 0) }

    fun watch(owner: String, rows: Flow<List<DesktopWorkRow>>, stop: suspend (String) -> Boolean = { false },
        retry: suspend (String) -> Boolean = { false }) {
        val binding = Binding(stop, retry)
        synchronized(lock) { check(!closed && owner.isNotBlank() && owner !in bindings); bindings[owner] = binding }
        scope.launch {
            try { rows.distinctUntilChanged().collect { current -> synchronized(lock) {
                check(current.map { it.id }.distinct().size == current.size && current.all { it.id.isNotBlank() })
                val previous = binding.rows.associateBy { it.id }
                current.forEach { row ->
                    if (!row.active && previous[row.id]?.active == true)
                        mutableNotices.tryEmit(DesktopWorkNotice(row.kind, row.phase, row.target))
                }
                binding.rows = current
                publish()
                current.mapNotNull { it.target }.distinct().forEach { target ->
                    if (target !in labels && lookingUp.add(target)) scope.launch {
                        val label = try { describe(target)?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(160) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        synchronized(lock) {
                            labels[target] = label?.takeIf { it.isNotBlank() } ?: fallbackLabel(target)
                            lookingUp.remove(target); publish()
                        }
                    }
                }
            } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "部分任务状态无法更新，请回到原功能页面核对。") } }
        }
    }
    private fun key(owner: String, id: String) = "$owner/$id"
    private fun fallbackLabel(target: DesktopWorkTarget): String = when {
        target.sessionId != null -> "会话 · ${target.sessionId.take(12)}"
        target.subjectId != null -> "文档 · ${target.subjectId.take(12)}"
        target.cardId != null -> "角色卡 · ${target.cardId.take(12)}"
        target.draftSessionId != null -> "创作草稿 · ${target.draftSessionId.take(12)}"
        else -> "生图 Studio"
    }
    private fun publish() {
        labels.keys.retainAll(bindings.values.flatMap { binding -> binding.rows.mapNotNull { it.target } }.toSet())
        val all = bindings.flatMap { (owner, binding) -> binding.rows.map { row ->
            val id = key(owner, row.id)
            if (!row.active) requestedStops.remove(id)
            row.copy(id = id, stopRequested = id in requestedStops,
                contextLabel = row.contextLabel ?: row.target?.let { labels[it] ?: fallbackLabel(it) })
        } }
        val activeIds = all.filter { it.active }.map { it.id }.toSet()
        requestedStops.retainAll(activeIds)
        val sorted = all.sortedWith(compareByDescending<DesktopWorkRow> { it.active }.thenByDescending { it.createdAt }.thenBy { it.id })
        mutable.value = mutable.value.copy(rows = sorted.filter { it.active } + sorted.filterNot { it.active }.take(historyLimit))
    }
    fun requestStop(id: String): Boolean = command(id, stopping = true)
    fun retry(id: String): Boolean = command(id, stopping = false)
    private fun command(id: String, stopping: Boolean): Boolean {
        val pair = synchronized(lock) {
            if (closed) return false
            val found = bindings.entries.firstNotNullOfOrNull { (owner, binding) ->
                binding.rows.firstOrNull { key(owner, it.id) == id }?.let { binding to it }
            } ?: return false
            val row = found.second
            if (stopping && (!row.active || !row.canStop || id in requestedStops) || !stopping && (row.active || !row.canRetry)) return false
            if (stopping) { requestedStops += id; publish() }
            found
        }
        scope.launch {
            val accepted = try { if (stopping) pair.first.stop(pair.second.id) else pair.first.retry(pair.second.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!accepted) synchronized(lock) {
                requestedStops.remove(id); publish()
                mutable.update { it.copy(error = "任务状态已变化或当前不能执行，请回到原页面核对。") }
            }
        }
        return true
    }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun navigationBusy() { mutable.update { it.copy(error = "当前管理操作尚未结束，请稍后从任务中心前往。") } }
    fun targetUnavailable() {
        mutable.update { it.copy(error = "任务对应的内容已变化或暂时不可用，请在原页面核对。") }
        requestOpen()
    }
    suspend fun closeAndDrain() {
        synchronized(lock) { closed = true }
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
