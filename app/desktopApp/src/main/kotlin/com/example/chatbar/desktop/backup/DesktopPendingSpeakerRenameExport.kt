package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Official147 ignores replay/source identity and can rewrite text edited after task preparation. */
internal class DesktopPendingSpeakerRenameExport : IllegalStateException("PORTABLE_PENDING_SPEAKER_RENAME") {
    fun userMessage() = "所选角色卡有尚未完成、不能由旧版安卓版安全重试的历史人物改名。请先在桌面版完成恢复，或暂时取消选择该角色卡；原数据和恢复任务保留，未生成备份。"
}

internal fun requirePortableSpeakerReplaySafe(value: JsonObject) {
    val raw = value["pendingSpeakerRenameTasks"] ?: return
    // Selection/schema validation already ran. No current source proof is delegated to the old consumer.
    val tasks = raw as? JsonArray ?: error("PENDING_SPEAKER_TASK_SCHEMA")
    if (tasks.isNotEmpty()) throw DesktopPendingSpeakerRenameExport()
}
