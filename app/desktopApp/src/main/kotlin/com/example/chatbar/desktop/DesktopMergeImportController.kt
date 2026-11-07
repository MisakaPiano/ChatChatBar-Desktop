package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private enum class DesktopMergeTask(val kind: DesktopWorkKind, val label: String) {
    INSPECT(DesktopWorkKind.MERGE_CHECK, "检查合并备份"), COMMIT(DesktopWorkKind.MERGE, "准备合并结果")
}

internal data class DesktopMergeImportState(val busy: Boolean = false, val ready: Boolean = false,
    val code: Int = 0, val files: Int = 0, val versionName: String = "",
    val modelDetails: Map<String, DesktopMergeImportService.ModelDetails> = emptyMap(), val conflicts: List<DesktopMergePreview.Conflict> = emptyList(),
    val credentials: List<DesktopMergeCredentials.GlobalConflict> = emptyList(),
    val imagePreferencesChoiceRequired: Boolean = false, val message: String = "", val error: String? = null,
    val restartRequired: Boolean = false, val destination: Path? = null, val desktopParticipation: Boolean = false,
    val failureDetails: String? = null, val historicalDesktopV5Settings: Boolean = false,
    val passwordRequest: DesktopBackupPasswordRequest? = null)

/** Holds secret-bearing preparation privately; UI state contains only presentation metadata. */
internal class DesktopMergeImportController(private val service: DesktopMergeImportService,
    private val picker: DesktopFilePicker, private val directories: DesktopDirectoryPicker,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val applicationHomeResolver: () -> DesktopApplicationHomeResult = DesktopApplicationHome::resolve) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(DesktopMergeImportState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private var closed = false
    private var operation: Job? = null
    private var activeTaskId: String? = null
    private val mutableWork = MutableStateFlow<List<DesktopWorkRow>>(emptyList())
    val workRows = mutableWork.asStateFlow()
    private var preview: DesktopMergeImportService.Preview? = null
    private var passwordFile: Path? = null
    private var passwordFlowActive = false
    @Volatile private var externalPasswordFlow = false

    fun inspect(password: CharArray?) { inspectFile(null, password) }
    fun inspectFile(source: Path?, password: CharArray?) = actionJob(password, DesktopMergeTask.INSPECT) { outcome ->
        val path = source ?: picker.pickOpenFile(TYPE) ?: return@actionJob run { outcome(DesktopWorkPhase.CANCELLED); "已取消选择" }
        passwordFile = null
        inspectSelectedFile(path, password)
    }
    fun selectFile() { inspectPasswordFile(null, true) }
    fun retryFilePassword(password: CharArray) { inspectPasswordFile(password, false) }
    fun inspectExternalFile(source: Path, password: CharArray? = null) = inspectPasswordFile(password, false, source)
    fun retryExternalFilePassword(source: Path, password: CharArray) = inspectPasswordFile(password, false, source, true)
    private fun inspectPasswordFile(password: CharArray?, chooseFile: Boolean, externalSource: Path? = null,
        requireRetained: Boolean = false): Job? {
        if (externalSource == null && externalPasswordFlow) { password?.fill('\u0000'); return null }
        if (requireRetained && (!externalPasswordFlow || passwordFile != externalSource)) { password?.fill('\u0000'); return null }
        return actionJob(password, DesktopMergeTask.INSPECT,
            passwordFlow = true, externalPassword = externalSource != null) { outcome ->
            val path = externalSource ?: if (chooseFile) picker.pickOpenFile(TYPE) else passwordFile
            if (path == null) { passwordFile = null; outcome(DesktopWorkPhase.CANCELLED); return@actionJob "已取消选择" }
            passwordFile = path
            externalPasswordFlow = externalSource != null
            inspectSelectedFile(path, password)
        }
    }
    private suspend fun inspectSelectedFile(path: Path, password: CharArray?): String {
        preview = null; mutable.update { DesktopMergeImportState(busy = true) }
        val result = service.inspectWithProgress(path, password, ::showProgress)
        preview = result
        mutable.update { it.copy(ready = true, code = result.code, files = result.fileCount, versionName = result.versionName, modelDetails = result.modelDetails,
            conflicts = result.data.conflicts, credentials = result.credentialConflicts,
            imagePreferencesChoiceRequired = result.imagePreferencesChoiceRequired,
            desktopParticipation = result.hasDesktopParticipation, historicalDesktopV5Settings = result.historicalDesktopV5Settings) }
        passwordFile = null
        return "已校验备份。请选择冲突处理方式；电脑当前数据尚未改变。"
    }
    fun commit(choices: Map<String, DesktopMergePreview.Choice>, credentials: Map<ImportCredentialKind, DesktopMergeCredentials.Choice>,
        imagePreferencesFromPhone: Boolean?, confirmed: Boolean) = action(null, DesktopMergeTask.COMMIT) { outcome ->
        check(confirmed)
        val selected = requireNotNull(preview)
        val parent = directories.pickDirectory() ?: return@action run { outcome(DesktopWorkPhase.CANCELLED); "已取消选择合并目录，可以重新选择" }
        val target = parent.resolve("ChatChatBar-merged-${UUID.randomUUID()}")
        mutable.update { it.copy(destination = target) }
        val refused = when (val home = applicationHomeResolver()) {
            is DesktopApplicationHomeResult.Available -> try {
                if (DesktopProgramDataBoundary.conflicts(home.path, target)) MERGE_PROGRAM_DATA_NOTICE else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "程序目录身份未通过核对，尚未创建合并目录；原检查结果与选择保留，请检查安装状态后重试。" }
            is DesktopApplicationHomeResult.Failure -> "程序目录身份未确认，尚未创建合并目录；原检查结果与选择保留。"
            is DesktopApplicationHomeResult.Unavailable -> null
        }
        if (refused != null) {
            outcome(DesktopWorkPhase.FAILED)
            mutable.update { it.copy(error = refused) }
            return@action "原检查结果与冲突选择已保留，可以重新选择合并位置。"
        }
        when (service.commitWithProgress(selected, target, choices, credentials, confirmed, imagePreferencesFromPhone, ::showProgress)) {
            DesktopMergeImportService.Publication.COMMITTED -> {
                outcome(DesktopWorkPhase.COMPLETED)
                "合并已完成准备。请正常退出，再次启动将打开包含两边内容的新目录。原电脑目录与手机文件保留。"
            }
            DesktopMergeImportService.Publication.INDETERMINATE -> {
                outcome(DesktopWorkPhase.UNCONFIRMED)
                "启动位置的写入结果尚未确认。已暂停写入并保留两边目录，请正常退出后检查启动结果。"
            }
        }
    }
    fun discard() { discardExternal() }
    fun discardExternal() = actionJob(null) {
        service.discard(); preview = null; passwordFile = null
        mutable.update { DesktopMergeImportState(busy = true) }
        "已取消合并，当前电脑数据未改变"
    }
    fun leavePasswordFlow() = synchronized(lock) {
        if (externalPasswordFlow) return@synchronized
        if (passwordFlowActive) operation?.cancel()
        else if (mutable.value.passwordRequest != null) discard()
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

    private fun showProgress(progress: DesktopMergeImportService.Progress) = synchronized(lock) {
        if (!closed && mutable.value.busy) {
            mutable.update { it.copy(message = progress.message()) }
            activeTaskId?.let { id -> mutableWork.update { rows -> rows.map {
                if (it.id == id && it.active) it.copy(contextLabel = progress.message().take(160)) else it
            } } }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun actionJob(password: CharArray?, task: DesktopMergeTask? = null,
        passwordFlow: Boolean = false, externalPassword: Boolean = false,
        work: suspend ((DesktopWorkPhase) -> Unit) -> String): Job? = synchronized(lock) {
        if (closed || mutable.value.busy || service.restartRequired) { password?.fill('\u0000'); return@synchronized null }
        mutableWork.update { rows -> rows.map { if (it.phase == DesktopWorkPhase.WAITING) it.copy(phase = DesktopWorkPhase.CANCELLED) else it } }
        passwordFlowActive = passwordFlow
        if (passwordFlow) externalPasswordFlow = externalPassword
        mutable.update { it.copy(busy = true, error = null, failureDetails = null, passwordRequest = null) }
        val taskId = task?.let { UUID.randomUUID().toString() }
        activeTaskId = taskId
        var outcome: DesktopWorkPhase? = null
        if (task != null && taskId != null) mutableWork.update { rows ->
            (listOf(DesktopWorkRow(taskId, task.kind, DesktopWorkPhase.RUNNING, System.currentTimeMillis(),
                target = DesktopWorkTarget(DesktopPrimaryRoute.DATA, detail = DesktopWorkDetail.MERGE_IMPORT),
                canStop = true, contextLabel = task.label)) + rows).take(40)
        }
        val job = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                val message = work { outcome = it }
                mutable.update { it.copy(message = message) }
            } catch (_: CancellationException) {
                outcome = DesktopWorkPhase.CANCELLED
                withContext(NonCancellable) { service.discard() }; preview = null; passwordFile = null
                mutable.update { it.copy(ready = false, message = if (service.restartRequired) "合并提交已进入启动切换阶段，请正常退出。"
                    else "已停止；原电脑目录与手机文件保留。未完成的新目录不会自动清理。") }
            } catch (failure: Throwable) {
                outcome = DesktopWorkPhase.FAILED
                val retainPreview = failure.message == "MERGE_DESTINATION_APPLICATION_HOME_UNSAFE" && !service.restartRequired
                if (!retainPreview) {
                    try { withContext(NonCancellable) { service.discard() } }
                    catch (cleanup: Throwable) { outcome = DesktopWorkPhase.UNCONFIRMED; failure.addSuppressed(cleanup) }
                    preview = null
                }
                val message = when {
                    failure.message == "MERGE_DESTINATION_APPLICATION_HOME_UNSAFE" -> MERGE_PROGRAM_DATA_NOTICE
                    failure is DesktopFullBackupSource.Refusal -> failure.userMessage()
                    failure is DesktopBackupRecords.ResourceLimit -> failure.userMessage()
                    failure is DesktopDraftPersistenceException -> "聊天草稿尚未保存成功，已停止准备合并以免遗漏内容。请保留当前草稿；备份文件未改变。"
                    failure is DesktopReadingPositionPersistenceException -> "阅读位置尚未保存成功，已停止准备合并；当前数据和备份文件保留。"
                    failure is DesktopBackupMissingResource -> "电脑中的“${failure.ownerLabel}”缺少资源“${failure.resourceName}”，无法准备完整合并；原卡和备份文件保留。"
                    failure.message == "MERGE_COMPUTER_CHANGED_REINSPECT" || failure.message == "MERGE_COMPUTER_CREDENTIAL_CHANGED" -> "电脑内容在预览后发生变化，请重新检查备份再选择冲突。"
                    failure.message == "MERGE_BOOTSTRAP_SOURCE_REQUIRED" -> "当前为命令行或便携目录模式，请从正常安装启动后执行合并。"
                    failure.message == "RECORDING_MESSAGE_MISSING" -> "录音缺少所属消息，无法安全合并；原始备份与准备结果已保留。"
                    failure.message == "RECORDING_SESSION_MISMATCH" -> "录音与消息所属会话不一致，无法安全合并；原始数据保留。"
                    failure.message == "RECORDING_VERSION_UNRESOLVED_WITHOUT_SOURCE_CUSTODY" -> "录音引用的版本不存在，且缺少完整原始保全凭证；未切换当前目录。"
                    failure.message == "MERGE_PREPARATION_SPACE" -> "可用空间不足以同时保留原件和合并准备结果，请选择空间充足的磁盘。"
                    failure is DesktopTaskDrainTimeoutException -> "生成任务尚未确认停止，请正常退出后重试。"
                    else -> "合并未提交或尚未确认。请检查密码、文件格式、资源、可用空间和所选目录；原始数据与失败现场保留。"
                }
                val retrySource = passwordFile
                if (passwordFlow && retrySource != null && currentCoroutineContext().isActive && outcome != DesktopWorkPhase.UNCONFIRMED &&
                    !service.restartRequired && failure.requestsBackupPasswordRetry()) {
                    outcome = DesktopWorkPhase.WAITING
                    mutable.update { it.copy(ready = false, error = null,
                        passwordRequest = DesktopBackupPasswordRequest(retrySource.fileName.toString().take(160),
                            if ((failure as DesktopFullBackupSource.Refusal).passwordRequired) null else message,
                            externalQueue = externalPasswordFlow),
                        message = "等待存档密码；所选文件已保留，当前资料未改变。", failureDetails = null) }
                } else {
                    passwordFile = null
                    mutable.update { it.copy(ready = retainPreview && preview != null, error = message, message = "", failureDetails = desktopMergeFailureDetails(failure)) }
                }
            } finally {
                password?.fill('\u0000')
                synchronized(lock) {
                    passwordFlowActive = false
                    if (passwordFile == null) externalPasswordFlow = false
                    mutable.update { it.copy(busy = false, restartRequired = service.restartRequired,
                        ready = preview != null && !service.restartRequired) }
                }
            }
        }
        operation = job
        job.invokeOnCompletion { failure ->
            password?.fill('\u0000')
            if (taskId != null) {
                val phase = when {
                    outcome == DesktopWorkPhase.UNCONFIRMED -> DesktopWorkPhase.UNCONFIRMED
                    task == DesktopMergeTask.COMMIT && outcome == DesktopWorkPhase.COMPLETED && service.restartRequired -> DesktopWorkPhase.COMPLETED
                    service.restartRequired && (failure != null || outcome == DesktopWorkPhase.CANCELLED || outcome == DesktopWorkPhase.FAILED) -> DesktopWorkPhase.UNCONFIRMED
                    outcome == DesktopWorkPhase.CANCELLED && failure != null && failure !is CancellationException -> DesktopWorkPhase.UNCONFIRMED
                    failure is CancellationException -> DesktopWorkPhase.CANCELLED
                    failure != null -> DesktopWorkPhase.FAILED
                    else -> outcome ?: DesktopWorkPhase.COMPLETED
                }
                mutableWork.update { rows -> rows.map { if (it.id == taskId) it.copy(phase = phase, canStop = false,
                    stopRequested = false, contextLabel = task?.label) else it } }
            }
        }
        job
    }
    private fun action(password: CharArray?, task: DesktopMergeTask? = null, work: suspend ((DesktopWorkPhase) -> Unit) -> String) {
        actionJob(password, task, work = work)
    }
    suspend fun closeAndDrain() {
        synchronized(lock) { closed = true }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        service.discard(); preview = null; passwordFile = null
        mutable.update { it.copy(passwordRequest = null) }
        mutableWork.update { rows -> rows.map { if (it.phase == DesktopWorkPhase.WAITING) it.copy(phase = DesktopWorkPhase.CANCELLED) else it } }
        // Destination ownership belongs to the service, closed only AFTER old repositories drain.
    }
    companion object {
        private val TYPE = DesktopFileType("ChatBar 整应用备份", listOf("cbbackup"), "cbbackup")
        private const val MERGE_PROGRAM_DATA_NOTICE = "所选位置与程序安装目录重叠，或程序目录身份未通过检查。尚未创建或提交合并目录；请选择安装目录之外的位置，原目录、备份和冲突选择保留。"
    }
}

/** Code locations only; exception messages, record IDs, filesystem paths and values stay private. */
internal fun desktopMergeFailureDetails(failure: Throwable): String {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val lines = mutableListOf<String>()
    fun append(value: Throwable, depth: Int) {
        if (depth > 3 || !visited.add(value)) return
        lines += value.javaClass.name.take(180)
        if (value is DesktopFullBackupSource.Refusal && value.stage.matches(Regex("[A-Z0-9_]{1,120}"))) {
            lines += "stage=${value.stage}"
            lines += value.failureLocations
        }
        value.stackTrace.asSequence().filter { it.className.startsWith("com.example.chatbar.") }.take(8).forEach {
            lines += "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        value.cause?.let { append(it, depth + 1) }
        value.suppressed.take(2).forEach { append(it, depth + 1) }
    }
    append(failure, 0)
    return lines.take(36).joinToString("\n")
}

internal fun DesktopMergeImportService.Progress.message(): String = when (stage) {
    DesktopMergeImportService.Stage.AUTHENTICATING_SOURCE -> "正在认证并严格检查备份。大容量文件可能需要较长时间。"
    DesktopMergeImportService.Stage.READING_COMPUTER -> "正在读取电脑数据并核对冲突，当前目录尚未改变。"
    DesktopMergeImportService.Stage.DRAINING_TASKS -> "正在等待生成任务与未完成写入停止。"
    DesktopMergeImportService.Stage.CHECKING_SOURCE -> "正在重新核对两边数据，防止使用过期的预览。"
    DesktopMergeImportService.Stage.SAFETY_SNAPSHOT -> "正在保存电脑数据的恢复前快照。"
    DesktopMergeImportService.Stage.PREPARING_ROOT -> if (verifiedFiles > 0) "正在准备新目录，已写入并核对 $verifiedFiles 个文件；合并尚未提交。"
        else "正在重映射存档与资源、准备新目录；合并尚未提交。"
    DesktopMergeImportService.Stage.PROTECTING_CREDENTIALS -> "正在保护并回读新目录的凭据。"
    DesktopMergeImportService.Stage.VERIFYING_RESULT -> "正在核对完整结果和原始状态；合并尚未提交。"
    DesktopMergeImportService.Stage.SWITCHING_STARTUP -> "正在提交下次启动位置，请等待本次操作结束。"
}
