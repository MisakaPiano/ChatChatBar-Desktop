package com.example.chatbar.desktop

import com.example.chatbar.domain.image.NovelAiHistoryImageSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

internal data class DesktopHistoryBatchExportState(
    val busy: Boolean = false,
    val plan: DesktopHistoryBatchExport.Plan? = null,
    val status: String = "",
    val error: Boolean = false,
)

/** A preview is transient; only an explicit confirmation can write the chosen external directory. */
internal class DesktopHistoryBatchExportController(private val exporter: DesktopHistoryBatchExport) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DesktopHistoryBatchExportState())
    val state = mutableState.asStateFlow()
    @Volatile private var active: Job? = null

    private suspend fun action(body: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        try {
            active = currentCoroutineContext()[Job]
            mutableState.update { it.copy(busy = true, status = "正在核对原图与目录…", error = false) }
            body()
        } catch (cancelled: CancellationException) {
            mutableState.value = DesktopHistoryBatchExportState(status = "已取消，原历史和选择保留。")
            throw cancelled
        } catch (failure: DesktopHistoryBatchExport.Failure) {
            mutableState.value = DesktopHistoryBatchExportState(error = true,
                status = "未能完全回滚，请保留恢复目录并检查：${failure.recoveryDirectory}")
        } catch (failure: Exception) {
            mutableState.value = DesktopHistoryBatchExportState(error = true, status =
                if (failure.message == "HISTORY_EXPORT_ORIGINAL_OMITTED") "所选记录含有未包含原图的图片；请先取消选择这些记录。参数与 Seed 仍可复用。"
                else "批量保存未完成：原图或目标文件可能已变化，或目录不可写。原历史和选择保留，请重新预览。")
        } finally {
            active = null
            mutableState.update { it.copy(busy = false) }
            mutex.unlock()
        }
    }

    suspend fun prepare(selected: List<NovelAiHistoryImageSelection>, title: String) = action {
        // Clear an older preview before a new chooser, including chooser cancellation.
        mutableState.update { it.copy(plan = null) }
        val plan = exporter.prepare(selected, title)
        mutableState.update { it.copy(plan = plan, status = if (plan == null) "已取消选择目录，尚未保存。" else "核对文件名和同名处理后，点击确认保存。") }
    }

    suspend fun save(expected: DesktopHistoryBatchExport.Plan, decisions: Map<NovelAiHistoryImageSelection, DesktopHistoryBatchExport.Decision>) = action {
        check(state.value.plan === expected) { "HISTORY_EXPORT_STALE_PREVIEW" }
        val result = exporter.execute(expected, decisions) { done, total ->
            mutableState.update { it.copy(status = "正在保存 $done / $total") }
        }
        mutableState.value = DesktopHistoryBatchExportState(status = buildString {
            append("已保存 ${result.saved} 张，跳过 ${result.skipped} 张：${expected.directory}")
            if (result.remainingDuplicates > 0) append("；另有 ${result.remainingDuplicates} 份同名副本保留。")
            result.recoveryDirectory?.let { append("；临时目录未完全清理，请保留并检查：$it") }
        })
    }

    fun close(): Boolean {
        if (state.value.busy) return false
        mutableState.value = DesktopHistoryBatchExportState()
        return true
    }
    fun stop() { active?.cancel() }
}
