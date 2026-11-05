package com.example.chatbar.desktop

import com.example.chatbar.data.repository.ConditionalMessageUpdateResult
import com.example.chatbar.data.repository.SettingsRepository

/** Runs inside the existing chat task, before any reply-derived work is scheduled. */
internal class DesktopAutomaticFormatRepair(
    private val settings: SettingsRepository,
    private val controller: () -> DesktopMessageFormatRepairController,
    private val safety: () -> DesktopRepairSafetyGate,
) {
    suspend fun completed(taskId: String, result: DesktopRealChatResult,
        control: DesktopChatGenerationControl): DesktopChatPostProcessResult {
        if (!settings.getAppSettings().automaticFormatCheckEnabled || control.isStopRequested())
            return DesktopChatPostProcessResult(result)
        val state = controller().automatic(taskId, result, control, safety())
        val committed = (state.commit as? ConditionalMessageUpdateResult.Success)?.message
        val retained = committed?.let { result.copy(assistant = it) } ?: result
        val blocked = state.status in setOf(DesktopRepairStatus.COMMIT_UNCERTAIN, DesktopRepairStatus.CONFLICT,
            DesktopRepairStatus.MISSING, DesktopRepairStatus.CORRUPT, DesktopRepairStatus.STORAGE_FAILURE,
            DesktopRepairStatus.CANCELLED) ||
            (state.status == DesktopRepairStatus.ERROR && committed == null) ||
            (state.status == DesktopRepairStatus.REJECTED && state.code !in
                setOf("NO_RULES", "AUDIO_BUSY", "DATA_MAINTENANCE_BUSY"))
        val notice = when {
            blocked -> "格式修复后的消息尚未确认；后续自动处理已暂停，请重新加载并核对消息"
            state.code == "NO_RULES" -> null
            state.code == "AUDIO_BUSY" -> "音频正在使用，格式修复未提交；原回复已保留"
            state.code == "DATA_MAINTENANCE_BUSY" -> "数据正在维护，格式修复未提交；原回复已保留"
            state.status == DesktopRepairStatus.ERROR -> "自动格式检查失败；原回复已保留，可手动重试"
            else -> null
        }
        return DesktopChatPostProcessResult(retained, notice, blocked)
    }
}
