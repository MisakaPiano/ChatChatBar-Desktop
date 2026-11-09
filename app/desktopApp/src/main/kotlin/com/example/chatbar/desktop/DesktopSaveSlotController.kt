package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

private enum class DesktopSaveSlotTask(val label: String, val recordWork: Boolean = true, val refreshSlots: Boolean = true) {
    LIST("读取存档列表", recordWork = false), BINDING_OPTIONS("读取会话资源候选", recordWork = false, refreshSlots = false),
    CREATE("保存会话存档"), IMPORT("导入存档到列表"), EXPORT("导出会话存档"), RESTORE("恢复会话存档"),
    DELETE("删除会话存档"), DUPLICATE("复制会话"), REBIND("重新绑定会话资源")
}

internal data class DesktopSaveSlotState(val open: Boolean = false, val sessionId: String? = null,
    val externalImportReceipt: DesktopExternalImportReceipt? = null,
    val slots: List<SaveSlotSummary> = emptyList(), val busy: Boolean = false, val message: String = "", val error: String? = null,
    val bindings: DesktopSessionBindingChoices? = null, val errorDetails: String? = null,
    val listError: String? = null, val listErrorDetails: String? = null)

internal class DesktopSaveSlotController(private val service: DesktopSaveSlotService, private val picker: DesktopFilePicker,
    private val afterChange: suspend (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(DesktopSaveSlotState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var activeTaskId: String? = null
    private val mutableWork = MutableStateFlow<List<DesktopWorkRow>>(emptyList())
    val workRows = mutableWork.asStateFlow()
    private val lock = Any()
    private var closed = false
    private var bindingRevision = 0L
    fun open(sessionId: String) { openFileContext(sessionId) }
    internal fun openFileContext(sessionId: String): Job? {
        synchronized(lock) {
            if (closed || mutable.value.busy) return null
            bindingRevision++
            mutable.value = DesktopSaveSlotState(open = true, sessionId = sessionId)
        }
        return actionJob { "" }
    }
    fun closePanel() { synchronized(lock) { bindingRevision++; mutable.update { it.copy(open = false, bindings = null) } } }
    fun revealTaskPanel(sessionId: String): Boolean = synchronized(lock) {
        if (closed || mutable.value.busy && mutable.value.sessionId != sessionId) false
        else if (mutable.value.busy) { mutable.update { it.copy(open = true) }; true }
        else openFileContext(sessionId) != null
    }
    fun create(name: String, imagePolicy: SaveSlotImagePolicy, includeAudio: Boolean) = action(DesktopSaveSlotTask.CREATE) { id ->
        service.create(id, name, imagePolicy, includeAudio) { progress -> mutable.update { it.copy(message = progress) } }
        "存档已保存"
    }
    fun import() = action(DesktopSaveSlotTask.IMPORT) { id ->
        val path = picker.pickOpenFile(FILE_TYPE) ?: return@action cancelledSelection("已取消导入")
        service.import(path, id)
        "存档已导入列表；当前聊天未改变"
    }
    fun importFile(path: java.nio.file.Path, attempt: DesktopExternalImportAttempt? = null) = actionJob(DesktopSaveSlotTask.IMPORT) { id ->
        val slot = service.import(path, id)
        mutable.update { it.copy(externalImportReceipt = attempt?.receipt(slot.id) ?: it.externalImportReceipt) }
        "存档已导入列表；当前聊天未改变"
    }
    fun export(slot: SaveSlotSummary) = action(DesktopSaveSlotTask.EXPORT) {
        val safeName = slot.name.replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_").take(80).ifBlank { "ChatBar" }
        val path = picker.pickSaveFile(FILE_TYPE, "$safeName.cbsave") ?: return@action cancelledSelection("已取消导出")
        service.export(slot.id, path)
        "存档已导出至所选新文件"
    }
    fun restore(slot: SaveSlotSummary) = action(DesktopSaveSlotTask.RESTORE, afterResult = { id -> afterChange(id) }) { id ->
        service.restore(slot.id, id) { progress -> mutable.update { it.copy(message = progress) } }
        "存档已恢复；恢复前的会话已另存保全"
    }
    fun delete(slot: SaveSlotSummary) = action(DesktopSaveSlotTask.DELETE) { id ->
        service.delete(slot.id, id)
        "存档已删除；其他存档和聊天中的图片、录音保持不变"
    }
    fun duplicate() {
        var copiedId: String? = null
        action(DesktopSaveSlotTask.DUPLICATE, afterResult = { afterChange(requireNotNull(copiedId)) }) { id ->
            val copied = service.duplicate(id) { progress -> mutable.update { it.copy(message = progress) } }
            copiedId = copied.id
            "会话副本已创建，原会话保留"
        }
    }
    fun loadBindings() { synchronized(lock) {
        if (closed || mutable.value.busy) return
        val revision = ++bindingRevision
        mutable.update { it.copy(bindings = null) }
        action(DesktopSaveSlotTask.BINDING_OPTIONS) { id ->
            val choices = service.bindingChoices(id)
            synchronized(lock) {
                if (!closed && revision == bindingRevision && mutable.value.open && mutable.value.sessionId == id)
                    mutable.update { it.copy(bindings = choices) }
            }
            ""
        }
    } }
    fun refreshList() = action(DesktopSaveSlotTask.LIST) { "存档列表已刷新" }
    fun rebind(characterId: String, books: List<String>) = action(DesktopSaveSlotTask.REBIND, afterResult = { id -> afterChange(id) }) { id ->
        service.rebind(id, characterId, books)
        mutable.update { it.copy(bindings = null) }
        "会话资源已重新绑定；原内容和原格式卡保留，世界书计时已重置。"
    }
    fun closeBindings() { synchronized(lock) { bindingRevision++; mutable.update { it.copy(bindings = null) } } }
    private suspend fun cancelledSelection(message: String): String {
        currentCoroutineContext()[DesktopSaveSlotWorkEvidence]?.pickerCancelled = true
        return message
    }
    private fun markStopRequested(id: String?) {
        if (id != null) mutableWork.update { rows -> rows.map {
            if (it.id == id && it.active) it.copy(stopRequested = true, canStop = false) else it
        } }
    }
    fun stop() { synchronized(lock) {
        if (operation?.isCompleted == false) { bindingRevision++; operation?.cancel(); markStopRequested(activeTaskId) }
    } }
    fun stopTask(id: String): Boolean = synchronized(lock) {
        if (activeTaskId != id || operation?.isCompleted != false) false
        else { bindingRevision++; operation?.cancel(); markStopRequested(id); true }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun actionJob(task: DesktopSaveSlotTask = DesktopSaveSlotTask.LIST,
        afterResult: (suspend (String) -> Unit)? = null, work: suspend (String) -> String): Job? = synchronized(lock) {
            val id = mutable.value.sessionId ?: return@synchronized null
            if (closed || mutable.value.busy) return@synchronized null
            mutable.update { it.copy(busy = true, error = null, errorDetails = null, message = "正在准备…") }
            val taskId = if (task.recordWork) UUID.randomUUID().toString() else null
            activeTaskId = taskId
            val evidence = DesktopSaveSlotWorkEvidence()
            var outcome = DesktopWorkPhase.COMPLETED
            var primaryReturned = false
            var secondaryStage = "PRIMARY"
            if (taskId != null) mutableWork.update { rows ->
                (listOf(DesktopWorkRow(taskId, DesktopWorkKind.SAVE_SLOT, DesktopWorkPhase.RUNNING, System.currentTimeMillis(),
                    target = DesktopWorkTarget(DesktopPrimaryRoute.CHAT, sessionId = id, detail = DesktopWorkDetail.SAVE_SLOT),
                    canStop = true, contextLabel = task.label)) + rows).take(40)
            }
            val job = scope.launch(evidence, start = CoroutineStart.ATOMIC) {
                try {
                    currentCoroutineContext().ensureActive()
                    val message = work(id)
                    primaryReturned = true
                    mutable.update { it.copy(message = message + (if (task.recordWork) service.completedWorkCleanupNotice else null)
                        ?.let { notice -> "；$notice" }.orEmpty()) }
                    if (afterResult != null) {
                        secondaryStage = "UI_REFRESH"
                        try { afterResult(id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            mutable.update { it.copy(error = "操作已完成，但页面刷新失败；可从会话列表重新打开，无需重复操作。",
                                errorDetails = desktopSaveSlotFailureDetails(task.label, secondaryStage, error)) }
                        }
                    }
                    if (task.refreshSlots) {
                        secondaryStage = "SLOT_LIST"
                        try {
                            val slots = service.list(id)
                            mutable.update { it.copy(slots = slots, listError = null, listErrorDetails = null) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            mutable.update { it.copy(listError = if (task.recordWork)
                                "${task.label}已完成，但存档列表刷新失败；可单独重试列表，无需重复执行操作。"
                                else "存档列表读取失败；原存档保留，可单独重试列表。",
                                listErrorDetails = desktopSaveSlotFailureDetails(task.label, "SLOT_LIST", error)) }
                        }
                    }
                } catch (_: CancellationException) {
                    outcome = if (primaryReturned && task.recordWork && !evidence.pickerCancelled) DesktopWorkPhase.COMPLETED
                        else if (evidence.publicationAttempted) DesktopWorkPhase.UNCONFIRMED else DesktopWorkPhase.CANCELLED
                    mutable.update { if (primaryReturned && task.recordWork && secondaryStage == "UI_REFRESH")
                            it.copy(error = "操作已完成，页面刷新已停止；可从会话列表重新打开。")
                        else if (primaryReturned && task.recordWork) it.copy(listError = "操作已完成，列表刷新已停止；可单独重试列表。")
                        else it.copy(message = "已停止；已完成的存档保留，未确认的数据不会自动清理") }
                } catch (error: Throwable) {
                    outcome = if (evidence.publicationAttempted) DesktopWorkPhase.UNCONFIRMED else DesktopWorkPhase.FAILED
                    val message = when (error) {
                        is DesktopSaveSlotArchive.CapacityRefused -> if (error.stage == "CAPTURE")
                            "完整存档超过 256 MiB 上限，未提交或替换当前会话。原会话和准备文件已保留；可用全应用备份保全大容量会话，并在新建会话中导入较小存档。"
                            else "存档超过 256 MiB 上限，未开始恢复。原文件与当前会话保持不变；全应用备份使用独立的容量限制。"
                        is DesktopAudioDrainException -> "音频未能安全停止，未开始存档提交。请安全停止播放后重试。"
                        is DesktopDataOperationRejectedException -> "当前数据操作不可用；若应用要求重启，请先正常退出。"
                        is DesktopSessionResourceReadException -> "无法读取${error.resource.label}；未更换会话资源，原数据保留。可展开详情定位资源。"
                        else -> "${task.label}未完成，原文件与恢复证据已保留。请展开详情定位原因后重试。"
                    }
                    mutable.update { it.copy(error = message, errorDetails = desktopSaveSlotFailureDetails(task.label, "PRIMARY", error), message = "") }
                } finally { mutable.update { it.copy(busy = false) } }
            }
            operation = job
            job.invokeOnCompletion { failure ->
                if (taskId != null) {
                    val phase = when {
                        evidence.pickerCancelled -> DesktopWorkPhase.CANCELLED
                        primaryReturned -> DesktopWorkPhase.COMPLETED
                        failure != null && evidence.publicationAttempted -> DesktopWorkPhase.UNCONFIRMED
                        failure is CancellationException -> DesktopWorkPhase.CANCELLED
                        failure != null -> DesktopWorkPhase.FAILED
                        else -> outcome
                    }
                    mutableWork.update { rows -> rows.map { if (it.id == taskId)
                        it.copy(phase = phase, canStop = false, stopRequested = false) else it } }
                }
            }
            job
    }
    private fun action(task: DesktopSaveSlotTask = DesktopSaveSlotTask.LIST,
        afterResult: (suspend (String) -> Unit)? = null, work: suspend (String) -> String) { actionJob(task, afterResult, work) }
    suspend fun closeAndDrain() {
        synchronized(lock) { closed = true; bindingRevision++ }
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
    companion object { private val FILE_TYPE = DesktopFileType("ChatBar 会话存档", listOf("cbsave", "json"), "cbsave") }
}
