package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopMergeImportState(val busy: Boolean = false, val ready: Boolean = false,
    val code: Int = 0, val files: Int = 0, val versionName: String = "",
    val modelDetails: Map<String, DesktopMergeImportService.ModelDetails> = emptyMap(), val conflicts: List<DesktopMergePreview.Conflict> = emptyList(),
    val credentials: List<DesktopMergeCredentials.GlobalConflict> = emptyList(),
    val imagePreferencesChoiceRequired: Boolean = false, val message: String = "", val error: String? = null,
    val restartRequired: Boolean = false, val destination: Path? = null, val desktopParticipation: Boolean = false,
    val failureDetails: String? = null)

/** Holds secret-bearing preparation privately; UI state contains only presentation metadata. */
internal class DesktopMergeImportController(private val service: DesktopMergeImportService,
    private val picker: DesktopFilePicker, private val directories: DesktopDirectoryPicker,
    dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(DesktopMergeImportState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private var closed = false
    private var operation: Job? = null
    private var preview: DesktopMergeImportService.Preview? = null

    fun inspect(password: CharArray?) = action(password) {
        val path = picker.pickOpenFile(TYPE) ?: return@action "已取消选择"
        preview = null; mutable.update { DesktopMergeImportState(busy = true) }
        val result = service.inspectWithProgress(path, password, ::showProgress)
        preview = result
        mutable.update { it.copy(ready = true, code = result.code, files = result.fileCount, versionName = result.versionName, modelDetails = result.modelDetails,
            conflicts = result.data.conflicts, credentials = result.credentialConflicts,
            imagePreferencesChoiceRequired = result.imagePreferencesChoiceRequired,
            desktopParticipation = result.hasDesktopParticipation) }
        "已校验备份。请选择冲突处理方式；电脑当前数据尚未改变。"
    }
    fun commit(choices: Map<String, DesktopMergePreview.Choice>, credentials: Map<ImportCredentialKind, DesktopMergeCredentials.Choice>,
        imagePreferencesFromPhone: Boolean?, confirmed: Boolean) = action(null) {
        check(confirmed)
        val selected = requireNotNull(preview)
        val parent = directories.pickDirectory() ?: return@action "已取消选择合并目录，可以重新选择"
        val target = parent.resolve("ChatChatBar-merged-${UUID.randomUUID()}")
        mutable.update { it.copy(destination = target) }
        when (service.commitWithProgress(selected, target, choices, credentials, confirmed, imagePreferencesFromPhone, ::showProgress)) {
            DesktopMergeImportService.Publication.COMMITTED -> "合并已完成准备。请正常退出，再次启动将打开包含两边内容的新目录。原电脑目录与手机文件保留。"
            DesktopMergeImportService.Publication.INDETERMINATE -> "启动位置的写入结果尚未确认。已暂停写入并保留两边目录，请正常退出后检查启动结果。"
        }
    }
    fun discard() = action(null) {
        service.discard(); preview = null
        mutable.update { DesktopMergeImportState(busy = true) }
        "已取消合并，当前电脑数据未改变"
    }
    fun stop() { synchronized(lock) { operation }?.cancel() }

    private fun showProgress(progress: DesktopMergeImportService.Progress) = synchronized(lock) {
        if (!closed && mutable.value.busy) mutable.update { it.copy(message = progress.message()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun action(password: CharArray?, work: suspend () -> String) = synchronized(lock) {
        if (closed || mutable.value.busy || service.restartRequired) { password?.fill('\u0000'); return@synchronized }
        mutable.update { it.copy(busy = true, error = null, failureDetails = null) }
        operation = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                val message = work()
                mutable.update { it.copy(message = message) }
            } catch (_: CancellationException) {
                withContext(NonCancellable) { service.discard() }; preview = null
                mutable.update { it.copy(ready = false, message = if (service.restartRequired) "合并提交已进入启动切换阶段，请正常退出。"
                    else "已停止；原电脑目录与手机文件保留。未完成的新目录不会自动清理。") }
            } catch (failure: Throwable) {
                try { withContext(NonCancellable) { service.discard() } }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                preview = null
                val message = when {
                    failure is DesktopFullBackupSource.Refusal && failure.passwordRequired -> "该备份已加密，请输入存档密码（8–1024 个字符）后重新检查。当前数据未改变。"
                    failure is DesktopFullBackupSource.Refusal -> "备份读取未通过（${failure.stage}）。请核对备份文件和加密密码；当前数据未改变。"
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
                mutable.update { it.copy(ready = false, error = message, message = "", failureDetails = desktopMergeFailureDetails(failure)) }
            } finally {
                password?.fill('\u0000')
                mutable.update { it.copy(busy = false, restartRequired = service.restartRequired,
                    ready = preview != null && !service.restartRequired) }
            }
        }.also { job -> job.invokeOnCompletion { password?.fill('\u0000') } }
    }
    suspend fun closeAndDrain() {
        synchronized(lock) { closed = true }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        service.discard(); preview = null
        // Destination ownership belongs to the service, closed only AFTER old repositories drain.
    }
    companion object { private val TYPE = DesktopFileType("ChatBar 整应用备份", listOf("cbbackup"), "cbbackup") }
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
