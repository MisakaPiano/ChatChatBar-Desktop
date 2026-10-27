package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopMergeImportState(val busy: Boolean = false, val ready: Boolean = false,
    val code: Int = 0, val files: Int = 0, val conflicts: List<DesktopMergePreview.Conflict> = emptyList(),
    val credentials: List<DesktopMergeCredentials.GlobalConflict> = emptyList(),
    val imagePreferencesChoiceRequired: Boolean = false, val message: String = "", val error: String? = null,
    val restartRequired: Boolean = false, val destination: Path? = null)

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
        val result = service.inspect(path, password)
        preview = result
        mutable.update { it.copy(ready = true, code = result.code, files = result.fileCount,
            conflicts = result.data.conflicts, credentials = result.credentialConflicts,
            imagePreferencesChoiceRequired = result.imagePreferencesChoiceRequired) }
        "已校验备份。请选择冲突处理方式；电脑当前数据尚未改变。"
    }
    fun commit(choices: Map<String, DesktopMergePreview.Choice>, credentials: Map<ImportCredentialKind, DesktopMergeCredentials.Choice>,
        imagePreferencesFromPhone: Boolean?, confirmed: Boolean) = action(null) {
        check(confirmed)
        val selected = requireNotNull(preview)
        val parent = directories.pickDirectory() ?: return@action "已取消选择合并目录，可以重新选择"
        val target = parent.resolve("ChatChatBar-merged-${UUID.randomUUID()}")
        mutable.update { it.copy(destination = target) }
        when (service.commit(selected, target, choices, credentials, confirmed, imagePreferencesFromPhone)) {
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun action(password: CharArray?, work: suspend () -> String) = synchronized(lock) {
        if (closed || mutable.value.busy || service.restartRequired) { password?.fill('\u0000'); return@synchronized }
        mutable.update { it.copy(busy = true, error = null) }
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
                withContext(NonCancellable) { service.discard() }; preview = null
                val message = when {
                    failure.message == "MERGE_COMPUTER_CHANGED_REINSPECT" || failure.message == "MERGE_COMPUTER_CREDENTIAL_CHANGED" -> "电脑内容在预览后发生变化，请重新检查备份再选择冲突。"
                    failure.message == "MERGE_BOOTSTRAP_SOURCE_REQUIRED" -> "当前为命令行或便携目录模式，请从正常安装启动后执行合并。"
                    failure is DesktopTaskDrainTimeoutException -> "生成任务尚未确认停止，请正常退出后重试。"
                    else -> "合并未提交或尚未确认。请检查密码、文件格式、资源、可用空间和所选目录；原始数据与失败现场保留。"
                }
                mutable.update { it.copy(ready = false, error = message, message = "") }
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
