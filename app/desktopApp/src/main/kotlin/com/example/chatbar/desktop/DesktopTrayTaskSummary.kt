package com.example.chatbar.desktop

internal data class DesktopTrayTaskSummary(val tooltip: String, val menuLines: List<String>,
    val active: Int, val running: Int, val waiting: Int, val stopping: Int, val unconfirmed: Int,
    val kinds: Map<DesktopWorkKind, Int>, val stateUnavailable: Boolean)

/** Counts come from the task center's existing owner projections, never from independent work. */
internal fun desktopTrayTaskSummary(state: DesktopTaskCenterState): DesktopTrayTaskSummary {
    val active = state.rows.filter { it.active }
    val stopping = active.count { it.stopRequested }
    val running = active.count { it.phase == DesktopWorkPhase.RUNNING && !it.stopRequested }
    val waiting = active.count { it.phase == DesktopWorkPhase.WAITING && !it.stopRequested }
    val unconfirmed = state.rows.count { it.phase == DesktopWorkPhase.UNCONFIRMED }
    val header = if (state.error != null) "任务状态需核对 · 已知进行中 ${active.size}"
        else "进行中 ${active.size} · 待核对 $unconfirmed"
    val kinds = DesktopWorkKind.entries.mapNotNull { kind -> active.count { it.kind == kind }.takeIf { it > 0 }?.let { "${kind.label} $it" } }
    val lines = listOf(header, "运行 $running · 等待/排队 $waiting · 停止中 $stopping",
        kinds.take(3).joinToString(" · ").ifBlank { "当前未显示进行中的任务" },
        kinds.drop(3).take(2).joinToString(" · ").let { if (kinds.size > 5) "$it · 另 ${kinds.size - 5} 类" else it }.ifBlank { "详情请打开任务中心" },
        if (state.error != null) "任务状态读取异常，请在任务中心核对" else "无任务也不代表没有未保存内容")
    return DesktopTrayTaskSummary(("ChatChatBar · $header · 运行$running 等待$waiting 停止$stopping").take(63), lines,
        active.size, running, waiting, stopping, unconfirmed, active.groupingBy { it.kind }.eachCount(), state.error != null)
}
