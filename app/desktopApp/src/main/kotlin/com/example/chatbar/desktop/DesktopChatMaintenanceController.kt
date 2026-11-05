package com.example.chatbar.desktop

import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.ChatMessageOrderRepairPlan
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class DesktopChatMaintenanceState(
    val sessionId: String? = null,
    val busy: Boolean = false,
    val operation: String = "",
    val preview: ChatMessageOrderRepairPlan? = null,
    val backupAvailable: Boolean = false,
    val message: String = "",
    val error: String? = null,
)

/** App-owned explicit maintenance. Page dismissal never interrupts publication or starts AI repair. */
internal class DesktopChatMaintenanceController(
    private val chats: ChatRepository,
    private val gate: DesktopDataOperationCoordinator,
    private val reservation: DesktopSessionReservation,
    private val rebuildDocuments: suspend (String, (Int, Int) -> Unit) -> Int,
    private val detectSourceChanges: suspend (String) -> Unit,
    private val refreshChat: suspend (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var closed = false
    private var job: Job? = null
    private val mutableState = MutableStateFlow(DesktopChatMaintenanceState())
    val state = mutableState.asStateFlow()

    fun inspectOrder(sessionId: String) = launch(sessionId, "检查消息顺序") {
        reservation.run(sessionId) {
            gate.withImmediateNormalOperation {
                val preview = chats.previewMessageOrderRepair(sessionId)
                val backup = chats.hasMessageOrderBackup(sessionId)
                mutableState.update { it.copy(preview = preview, backupAvailable = backup,
                    message = if (preview.requiresRepair) "检查完成，请核对预览后决定是否修复。" else "消息顺序正常，无需修复。") }
            }
        }
    }

    fun applyOrder(sessionId: String) {
        val preview = state.value.takeIf { it.sessionId == sessionId }?.preview ?: return
        if (!preview.requiresRepair) return
        changeOrder(sessionId, "修复消息顺序") { chats.repairMessageOrder(sessionId, preview.baseline) }
    }

    fun undoOrder(sessionId: String) = changeOrder(sessionId, "撤销顺序修复") {
        chats.restoreMessageOrderBackup(sessionId)
    }

    private fun changeOrder(sessionId: String, operation: String, write: suspend () -> Unit) = launch(sessionId, operation) {
        reservation.run(sessionId) {
            gate.withImmediateNormalOperation {
                // The repository preserves original order keys before writing and rejects stale
                // preview/undo baselines. Finish this bounded write before releasing admission.
                withContext(NonCancellable) {
                    write()
                    mutableState.update { it.copy(preview = null, backupAvailable = chats.hasMessageOrderBackup(sessionId),
                        message = "$operation 已保存。") }
                    try { detectSourceChanges(sessionId) }
                    catch (_: Exception) { mutableState.update { it.copy(error = "顺序已保存，记忆来源检查未完成；请到记忆页重新检查。") } }
                }
            }
        }
        try { refreshChat(sessionId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = "顺序已保存，界面刷新未完成；请重新打开会话。") } }
    }

    fun rebuildRag(sessionId: String) = launch(sessionId, "重建文档 RAG 索引") {
        // Existing document service owns network admission and journal publication. An outer
        // normal data lease here would deadlock its exclusive commit barrier.
        val count = rebuildDocuments(sessionId) { done, total ->
            mutableState.update { it.copy(message = "文档索引 $done/$total；已完成文档保留，尚未完成文档的旧索引保留。") }
        }
        mutableState.update { it.copy(message = if (count == 0) "本角色卡没有参考文档，无需重建。" else "已重建 $count 份参考文档的 RAG 索引。") }
    }

    fun stop() = synchronized(lock) { job?.cancel() }

    private fun launch(sessionId: String, operation: String, work: suspend () -> Unit) = synchronized(lock) {
        if (closed || job?.isCompleted == false) return@synchronized
        val previous = state.value.takeIf { it.sessionId == sessionId }
        mutableState.value = (previous ?: DesktopChatMaintenanceState(sessionId)).copy(busy = true, operation = operation, error = null, message = "准备$operation")
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { work() }
            catch (cancelled: CancellationException) {
                mutableState.update { it.copy(message = "已停止。已完成的操作保留；可重新检查当前状态。") }
                throw cancelled
            } catch (_: DesktopTaskAdmissionException) {
                mutableState.update { it.copy(error = "会话有任务正在进行；请完成或停止后重试。") }
            } catch (_: DesktopDataOperationRejectedException) {
                mutableState.update { it.copy(error = "数据正在维护或等待重启，请稍后重试。") }
            } catch (_: DesktopAudioMaintenanceBusyException) {
                mutableState.update { it.copy(error = "音频正在使用，请停止播放后重试。") }
            } catch (_: Exception) {
                mutableState.update { it.copy(preview = null, error = if (operation == "重建文档 RAG 索引")
                    "重建未完成；已完成文档保留，请检查向量模型和参考文档后重试。"
                    else "操作未完成。预览或备份可能已过期，请重新检查消息顺序；原顺序备份若已建立会保留。") }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
        job = next
        next.start()
    }

    suspend fun closeAndDrain() {
        synchronized(lock) { closed = true }
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
