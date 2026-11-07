package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

private enum class DesktopBackupTask(val label: String, val detail: DesktopWorkDetail, val kind: DesktopWorkKind) {
    EXPORT("导出备份", DesktopWorkDetail.BACKUP_EXPORT, DesktopWorkKind.BACKUP),
    INSPECT("检查备份", DesktopWorkDetail.BACKUP_CHECK, DesktopWorkKind.BACKUP_CHECK),
    RESTORE_PREPARATION("准备下次启动恢复", DesktopWorkDetail.BACKUP_CHECK, DesktopWorkKind.RESTORE_PREPARATION)
}

internal data class DesktopBackupExportResult(val success: Boolean, val message: String, val path: java.nio.file.Path? = null)

internal data class DesktopFullBackupState(val busy: Boolean = false, val message: String = "",
    val error: String? = null, val summary: AppBackupSummary? = null,
    val desktopOnly: Boolean = false, val restartRequired: Boolean = false, val catalog: BackupExportCatalog? = null,
    val exportResult: DesktopBackupExportResult? = null, val passwordRequest: DesktopBackupPasswordRequest? = null)

/** The application owns the operation; leaving the panel cannot abandon a scheduled restore. */
internal class DesktopFullBackupController(private val service: DesktopFullBackupService,
    private val picker: DesktopFilePicker, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(DesktopFullBackupState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private var closed = false
    private var operation: Job? = null
    private var activeTaskId: String? = null
    private val mutableWork = MutableStateFlow<List<DesktopWorkRow>>(emptyList())
    val workRows = mutableWork.asStateFlow()
    private var prepared: DesktopFullBackupArchive.Prepared? = null
    private var passwordFile: java.nio.file.Path? = null
    private var passwordFlowActive = false

    fun refreshExportCatalog() = action(null) {
        val catalog = service.catalog()
        mutable.update { it.copy(catalog = catalog) }
        "已读取导出清单"
    }

    fun export(password: CharArray?, includeCredentials: Boolean = false, selection: AppBackupSelection? = null) = action(password, exporting = true, task = DesktopBackupTask.EXPORT) { cancelled ->
        val path = picker.pickSaveFile(TYPE, "ChatBar.cbbackup") ?: return@action cancelled("已取消保存，可以重新选择目标")
        val result = service.exportModernWithReport(path, password, includeCredentials, selection)
        val message = (if (includeCredentials) "备份已按所选范围导出，包含所选凭据及关联模型密钥。" else "备份已导出至新文件，不含 API 密钥等凭据。") +
            DesktopHistoricalMomentAvatarPolicy.warning(result.missingHistoricalAvatars)
        mutable.update { it.copy(exportResult = DesktopBackupExportResult(true, message, path.toAbsolutePath().normalize())) }
        message
    }

    fun dismissExportResult() { mutable.update { it.copy(exportResult = null) } }

    fun inspect(password: CharArray?) = action(password, task = DesktopBackupTask.INSPECT) { cancelled ->
        val path = picker.pickOpenFile(TYPE) ?: return@action cancelled("已取消检查")
        releaseInspection()
        val result = service.inspect(path, password)
        prepared = result
        mutable.update { it.copy(summary = result.summary,
            desktopOnly = result.desktopOnly || result.records.keys.any(DesktopBackupPolicy::isDesktopOnly)) }
        "文件校验完成；尚未替换当前数据。请检查摘要后决定是否恢复。"
    }

    fun verifyFile(password: CharArray?) = verifyFile(password, chooseFile = true)
    fun retryFilePassword(password: CharArray) = verifyFile(password, chooseFile = false)
    private fun verifyFile(password: CharArray?, chooseFile: Boolean) = action(password, task = DesktopBackupTask.INSPECT,
        passwordFlow = true) { cancelled ->
        val path = if (chooseFile) picker.pickOpenFile(TYPE) else passwordFile
        if (path == null) { releaseInspection(); return@action cancelled("已取消检查") }
        releaseInspection()
        passwordFile = path
        val result = service.verifyFile(path, password)
        passwordFile = null
        mutable.update { it.copy(summary = result.summary, desktopOnly = result.desktopOnly) }
        "文件完整性与格式校验完成；没有导入或替换当前数据。恢复请使用合并预览。" +
            (if (result.historicalDesktopV5Settings) " 已保留旧 Desktop 生图设置扩展，不代表官方 1.4.7 支持。" else "") +
            DesktopHistoricalMomentAvatarPolicy.warning(result.missingHistoricalAvatars)
    }

    fun restore() = action(null, task = DesktopBackupTask.RESTORE_PREPARATION) {
        service.scheduleRestore(requireNotNull(prepared))
        releaseInspection()
        "恢复已准备。请正常退出应用，下次启动将在打开聊天前完成恢复；原数据和恢复记录保留。"
    }

    fun discardInspection() = action(null, cleanupOnly = true) {
        releaseInspection()
        if (service.restartRequired) "检查已关闭；已准备的恢复记录仍保留，请正常退出。" else "已取消检查，当前数据未改变"
    }
    private var releaseWhenIdle = false
    fun leavePasswordFlow() = synchronized(lock) {
        if (passwordFlowActive) operation?.cancel()
        else if (mutable.value.passwordRequest != null) discardInspection()
    }
    fun leavePanel() = synchronized(lock) {
        if (mutable.value.busy) {
            releaseWhenIdle = true
            if (passwordFlowActive) operation?.cancel()
        } else discardInspection()
    }

    private suspend fun releaseInspection() {
        prepared?.let { service.release(it) }
        prepared = null
        passwordFile = null
        mutable.update { it.copy(summary = null, desktopOnly = false, passwordRequest = null) }
    }
    private fun markStopRequested(id: String?) {
        if (id != null) mutableWork.update { rows -> rows.map {
            if (it.id == id && it.active) it.copy(stopRequested = true, canStop = false) else it
        } }
    }
    fun stop() { synchronized(lock) {
        if (operation?.isCompleted == false) { operation?.cancel(); markStopRequested(activeTaskId) }
    } }
    fun stopTask(id: String): Boolean = synchronized(lock) {
        if (activeTaskId != id || operation?.isCompleted != false) false
        else { operation?.cancel(); markStopRequested(id); true }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun action(password: CharArray?, cleanupOnly: Boolean = false, exporting: Boolean = false,
        task: DesktopBackupTask? = null, passwordFlow: Boolean = false, work: suspend ((String) -> String) -> String) {
        synchronized(lock) {
            if (closed || mutable.value.busy || service.restartRequired && !cleanupOnly) { password?.fill('\u0000'); return }
            mutableWork.update { rows -> rows.map { if (it.phase == DesktopWorkPhase.WAITING) it.copy(phase = DesktopWorkPhase.CANCELLED) else it } }
            passwordFlowActive = passwordFlow
            mutable.update { it.copy(busy = true, error = null, message = "正在准备和核验…", exportResult = null, passwordRequest = null) }
            val taskId = task?.let { UUID.randomUUID().toString() }
            activeTaskId = taskId
            var outcome = DesktopWorkPhase.COMPLETED
            if (task != null && taskId != null) mutableWork.update { rows ->
                (listOf(DesktopWorkRow(taskId, task.kind, DesktopWorkPhase.RUNNING, System.currentTimeMillis(),
                    target = DesktopWorkTarget(DesktopPrimaryRoute.DATA, detail = task.detail),
                    canStop = true, contextLabel = task.label)) + rows).take(40)
            }
            // Even a request cancelled before its dispatcher runs must enter the cleanup boundary.
            operation = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    currentCoroutineContext().ensureActive()
                    val message = work { message -> outcome = DesktopWorkPhase.CANCELLED; message }
                    currentCoroutineContext().ensureActive()
                    mutable.update { it.copy(message = message) }
                }
                catch (_: CancellationException) {
                    outcome = DesktopWorkPhase.CANCELLED
                    try {
                        releaseInspection()
                        mutable.update { it.copy(message = if (service.restartRequired)
                            "当前工作已停止，但仍有已准备或待核对的恢复记录；停止不会撤销它。请核对恢复提示，正常退出后由启动恢复检查处理。"
                            else "操作已停止；临时检查文件已释放，原文件和恢复证据保留") }
                    } catch (_: Throwable) {
                        outcome = DesktopWorkPhase.UNCONFIRMED
                        mutable.update { it.copy(error = "临时文件清理尚未确认，已保留异常现场；请勿自行清理恢复记录。") }
                    }
                }
                catch (failure: Throwable) {
                    outcome = DesktopWorkPhase.FAILED
                    val reason = if (failure.suppressed.any { it is DesktopBackupScratchCleanupException })
                        "临时文件清理尚未确认，已保留异常现场；请勿自行清理恢复记录。"
                    else when (failure) {
                        is DesktopBackupCompatibilityException -> failure.kind.userMessage()
                        is DesktopFullBackupSource.Refusal -> failure.userMessage()
                        is DesktopBackupRecords.ResourceLimit -> failure.userMessage()
                        is DesktopBackupScratchCleanupException -> "临时文件清理尚未确认，已保留异常现场；请勿自行清理恢复记录。"
                        is DesktopAudioDrainException -> "音频尚未确认安全停止，恢复未提交。"
                        is DesktopDataOperationRejectedException -> "数据操作暂不可用，请检查是否需要正常退出。"
                        is DesktopBackupExportFailure -> "备份导出未完成（${failure.stage}）。原数据与凭据保留，请核对文件和资源。"
                        is DesktopBackupMissingResource -> "“${failure.ownerLabel}”缺少资源“${failure.resourceName}”。请补回文件，或在导出清单中取消对应角色卡；原数据未修改。"
                        is DesktopPendingSpeakerRenameExport -> failure.userMessage()
                        is DesktopFullBackupWriter.Refusal -> failure.userMessage()
                        else -> when (failure.message) {
                            "UNCLASSIFIED_ROOT_ENTRY" -> "发现未识别的本机数据目录，备份未导出；请保留原目录以便核对。"
                            "UNCLASSIFIED_HIDDEN_BACKUP_ENTRY" -> "发现未能归类的隐藏数据，备份未导出；原文件保留。"
                            "DESKTOP_CUSTODY_REQUIRES_FULL_SELECTION" -> "当前资料含不可分割的历史保全内容，请使用完整选择导出；未生成部分备份。"
                            else -> "操作未完成。请检查密码、格式、资源、可用空间及文件名；原文件与证据保留。"
                        }
                    }
                    val retrySource = passwordFile
                    if (passwordFlow && retrySource != null && currentCoroutineContext().isActive &&
                        !service.restartRequired && failure.requestsBackupPasswordRetry()) {
                        outcome = DesktopWorkPhase.WAITING
                        mutable.update { it.copy(passwordRequest = DesktopBackupPasswordRequest(retrySource.fileName.toString().take(160),
                            if ((failure as DesktopFullBackupSource.Refusal).passwordRequired) null else reason),
                            error = null, message = "等待存档密码；所选文件已保留，当前资料未改变。") }
                    } else {
                        passwordFile = null
                        mutable.update { it.copy(error = reason, message = "", exportResult =
                            if (exporting) DesktopBackupExportResult(false, reason) else null) }
                    }
                } finally {
                    password?.fill('\u0000')
                    // Panel departure does not cancel an application-owned restore transaction.
                    // Release its inspection when the operation reaches a safe completion boundary.
                    while (true) {
                        val release = synchronized(lock) {
                            if (releaseWhenIdle) { releaseWhenIdle = false; true }
                            else {
                                mutable.update { it.copy(busy = false, restartRequired = service.restartRequired) }
                                passwordFlowActive = false
                                false
                            }
                        }
                        if (!release) break
                        if (outcome == DesktopWorkPhase.WAITING) outcome = DesktopWorkPhase.CANCELLED
                        try { releaseInspection() } catch (_: Throwable) {
                            outcome = DesktopWorkPhase.UNCONFIRMED
                            mutable.update { it.copy(error = "临时文件清理尚未确认，已保留异常现场；请勿自行清理恢复记录。") }
                        }
                    }
                }
            }.also { job -> job.invokeOnCompletion { failure ->
                password?.fill('\u0000')
                if (taskId != null) {
                    val phase = when {
                        outcome == DesktopWorkPhase.UNCONFIRMED -> outcome
                        service.restartRequired && (failure != null || outcome != DesktopWorkPhase.COMPLETED) -> DesktopWorkPhase.UNCONFIRMED
                        failure is CancellationException -> DesktopWorkPhase.CANCELLED
                        failure != null -> DesktopWorkPhase.FAILED
                        else -> outcome
                    }
                    mutableWork.update { rows -> rows.map { if (it.id == taskId) it.copy(phase = phase, canStop = false, stopRequested = false) else it } }
                }
            } }
        }
    }
    suspend fun closeAndDrain() {
        synchronized(lock) { closed = true }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        releaseInspection()
        mutableWork.update { rows -> rows.map { if (it.phase == DesktopWorkPhase.WAITING) it.copy(phase = DesktopWorkPhase.CANCELLED) else it } }
    }
    companion object { private val TYPE = DesktopFileType("ChatBar 整应用备份", listOf("cbbackup"), "cbbackup") }
}
