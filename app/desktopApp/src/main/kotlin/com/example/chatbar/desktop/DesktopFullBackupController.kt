package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopFullBackupState(val busy: Boolean = false, val message: String = "",
    val error: String? = null, val summary: AppBackupSummary? = null,
    val desktopOnly: Boolean = false, val restartRequired: Boolean = false)

/** The application owns the operation; leaving the panel cannot abandon a scheduled restore. */
internal class DesktopFullBackupController(private val service: DesktopFullBackupService,
    private val picker: DesktopFilePicker) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(DesktopFullBackupState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private var closed = false
    private var operation: Job? = null
    private var prepared: DesktopFullBackupArchive.Prepared? = null

    fun export(password: CharArray?) = action(password) {
        val path = picker.pickSaveFile(TYPE, "ChatBar.cbbackup") ?: return@action "已取消保存，可以重新选择目标"
        service.export(path, password)
        "备份已导出至新文件。API 密钥和受保护凭据未导出。"
    }

    fun inspect(password: CharArray?) = action(password) {
        val path = picker.pickOpenFile(TYPE) ?: return@action "已取消检查"
        prepared = null
        mutable.update { it.copy(summary = null) }
        val result = service.inspect(path, password)
        prepared = result
        mutable.update { it.copy(summary = result.summary,
            desktopOnly = result.records.keys.any { path -> path.startsWith("entities/desktop_") }) }
        "文件校验完成；尚未替换当前数据。请检查摘要后决定是否恢复。"
    }

    fun restore() = action(null) {
        service.scheduleRestore(requireNotNull(prepared))
        prepared = null
        mutable.update { it.copy(summary = null) }
        "恢复已准备。请正常退出应用，下次启动将在打开聊天前完成恢复；原数据和恢复记录保留。"
    }

    fun discardInspection() = synchronized(lock) {
        if (!mutable.value.busy) { prepared = null; mutable.update { it.copy(summary = null, message = "已取消恢复，当前数据未改变") } }
    }
    fun stop() { synchronized(lock) { operation }?.cancel() }

    private fun action(password: CharArray?, work: suspend () -> String) {
        synchronized(lock) {
            if (closed || mutable.value.busy || service.restartRequired) { password?.fill('\u0000'); return }
            mutable.update { it.copy(busy = true, error = null, message = "正在准备和核验…") }
            operation = scope.launch(start = CoroutineStart.LAZY) {
                try { val message = work(); mutable.update { it.copy(message = message) } }
                catch (_: CancellationException) { mutable.update { it.copy(message = "操作已停止；源文件和已准备的恢复证据保留") } }
                catch (failure: Throwable) {
                    val reason = when (failure) {
                        is DesktopAudioDrainException -> "音频尚未确认安全停止，恢复未提交。"
                        is DesktopDataOperationRejectedException -> "数据操作暂不可用，请检查是否需要正常退出。"
                        else -> "操作未完成。请检查密码、格式、资源、可用空间及文件名。含凭据或不支持的数据会拒绝恢复；原文件与证据保留。"
                    }
                    mutable.update { it.copy(error = reason, message = "") }
                } finally {
                    password?.fill('\u0000')
                    mutable.update { it.copy(busy = false, restartRequired = service.restartRequired) }
                }
            }.also { job -> job.invokeOnCompletion { password?.fill('\u0000') }; job.start() }
        }
    }
    suspend fun closeAndDrain() {
        synchronized(lock) { closed = true }
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
    companion object { private val TYPE = DesktopFileType("ChatBar 整应用备份", listOf("cbbackup"), "cbbackup") }
}
