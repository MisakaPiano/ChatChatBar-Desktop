package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.SpeakerTagRenameTask
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** Official147 ignores the new replay plan and blindly executes the old rename list. */
internal class DesktopPendingSpeakerRenameExport : IllegalStateException("PORTABLE_PENDING_SPEAKER_RENAME") {
    fun userMessage() = "所选角色卡有尚未完成、不能由旧版安卓版安全重试的历史人物改名。请先在桌面版完成恢复，或暂时取消选择该角色卡；原数据和恢复任务保留，未生成备份。"
}

internal fun requirePortableSpeakerReplaySafe(value: JsonObject) {
    val raw = value["pendingSpeakerRenameTasks"] ?: return
    val tasks = backupJson.decodeFromJsonElement(ListSerializer(SpeakerTagRenameTask.serializer()), raw)
    if (tasks.any { task ->
        val overlap = task.renames.any { rename ->
            task.renames.any { it.oldName.trim().equals(rename.newName.trim(), ignoreCase = true) }
        }
        when (task.rewriteProtocolVersion) {
            1 -> overlap
            null -> task.rewritePlan != null || overlap
            else -> true
        }
    }) throw DesktopPendingSpeakerRenameExport()
}
