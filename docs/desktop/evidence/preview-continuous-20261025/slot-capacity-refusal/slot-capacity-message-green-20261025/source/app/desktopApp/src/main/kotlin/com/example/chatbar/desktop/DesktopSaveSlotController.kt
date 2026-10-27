package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DesktopSaveSlotState(val open: Boolean = false, val sessionId: String? = null,
    val slots: List<SaveSlotSummary> = emptyList(), val busy: Boolean = false, val message: String = "", val error: String? = null,
    val bindings: DesktopSessionBindingChoices? = null)

internal class DesktopSaveSlotController(private val service: DesktopSaveSlotService, private val picker: DesktopFilePicker,
    private val afterChange: suspend (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(DesktopSaveSlotState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private val lock = Any()
    private var closed = false
    fun open(sessionId: String) {
        synchronized(lock) {
            if (closed || mutable.value.busy) return
            mutable.value = DesktopSaveSlotState(open = true, sessionId = sessionId)
        }
        action { "" }
    }
    fun closePanel() { mutable.update { it.copy(open = false) } }
    fun create(name: String, imagePolicy: SaveSlotImagePolicy, includeAudio: Boolean) = action { id ->
        service.create(id, name, imagePolicy, includeAudio) { progress -> mutable.update { it.copy(message = progress) } }
        "存档已保存"
    }
    fun import() = action { id ->
        val path = picker.pickOpenFile(FILE_TYPE) ?: return@action "已取消导入"
        service.import(path, id)
        "存档已导入列表；当前聊天未改变"
    }
    fun export(slot: SaveSlotSummary) = action {
        val safeName = slot.name.replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_").take(80).ifBlank { "ChatBar" }
        val path = picker.pickSaveFile(FILE_TYPE, "$safeName.cbsave") ?: return@action "已取消导出"
        service.export(slot.id, path)
        "存档已导出至所选新文件"
    }
    fun restore(slot: SaveSlotSummary) = action { id ->
        service.restore(slot.id, id) { progress -> mutable.update { it.copy(message = progress) } }
        afterChange(id)
        "存档已恢复；恢复前的会话已另存保全"
    }
    fun delete(slot: SaveSlotSummary) = action { id ->
        service.delete(slot.id, id)
        "存档已删除；其他存档和聊天中的图片、录音保持不变"
    }
    fun duplicate() = action { id ->
        val copied = service.duplicate(id) { progress -> mutable.update { it.copy(message = progress) } }
        afterChange(copied.id)
        "会话副本已创建，原会话保留"
    }
    fun loadBindings() = action { id ->
        val choices = service.bindingChoices(id)
        mutable.update { it.copy(bindings = choices) }
        ""
    }
    fun rebind(characterId: String, books: List<String>) = action { id ->
        service.rebind(id, characterId, books)
        afterChange(id)
        mutable.update { it.copy(bindings = null) }
        "会话资源已重新绑定；原内容和原格式卡保留，世界书计时已重置。"
    }
    fun closeBindings() { mutable.update { it.copy(bindings = null) } }
    fun stop() { synchronized(lock) { operation }?.cancel() }
    private fun action(work: suspend (String) -> String) {
        synchronized(lock) {
            val id = mutable.value.sessionId ?: return
            if (closed || mutable.value.busy) return
            mutable.update { it.copy(busy = true, error = null, message = "正在准备…") }
            operation = scope.launch {
                try {
                    val message = work(id)
                    val slots = service.list(id)
                    mutable.update { it.copy(slots = slots, message = message) }
                } catch (_: CancellationException) {
                    mutable.update { it.copy(message = "已停止；已完成的存档保留，未确认的数据不会自动清理") }
                } catch (error: Throwable) {
                    val message = when (error) {
                        is DesktopSaveSlotArchive.CapacityRefused -> if (error.stage == "CAPTURE")
                            "完整存档超过 256 MiB 上限，未提交或替换当前会话。原会话和准备文件已保留；可用全应用备份保全大容量会话，并在新建会话中导入较小存档。"
                            else "存档超过 256 MiB 上限，未开始恢复。原文件与当前会话保持不变；全应用备份使用独立的容量限制。"
                        is DesktopAudioDrainException -> "音频未能安全停止，未开始存档提交。请安全停止播放后重试。"
                        is DesktopDataOperationRejectedException -> "当前数据操作不可用；若应用要求重启，请先正常退出。"
                        else -> "操作未完成，原文件与恢复证据已保留。请检查存档、资源和目标文件名后重试。"
                    }
                    mutable.update { it.copy(error = message, message = "") }
                } finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }
    suspend fun closeAndDrain() {
        synchronized(lock) { closed = true }
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
    companion object { private val FILE_TYPE = DesktopFileType("ChatBar 会话存档", listOf("cbsave", "json"), "cbsave") }
}
