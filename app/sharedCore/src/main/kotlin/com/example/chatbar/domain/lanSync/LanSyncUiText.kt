package com.example.chatbar.domain.lanSync

/** Display only: raw excluded types remain in snapshots/state and never gain deletion authority. */
fun lanSyncExcludedSummary(types: Set<String>): String? {
    val derivedIndexes = setOf("chat_message_indexes", "chat_message_order_backups")
    val groups = listOf(
        "聊天输入草稿" to setOf("chat_drafts"),
        "卡片编辑草稿" to setOf("edit_drafts"),
        "工作室当前草稿" to setOf("novelai_studio_draft", "novelai_studio_guidance_checkpoint"),
        "工作室撤销历史" to setOf("novelai_studio_history_undo", "desktop_studio_edit_history"),
        "生图设计对话" to setOf("novelai_design_conversations", "novelai_design_current"),
        "模型与连接设置" to setOf("model_configs", "embedding_configs", "embedding_model_config", "retrieval_model_config"),
        "应用与设备设置" to setOf("app_settings", "desktop_backup_preferences", "preset_import_state", "model_configuration_migration",
            "character_speaker_migration_state", "world_book_migration_state"),
        "阅读与界面偏好" to setOf("chat_scroll_positions", "novelai_history_fold_preferences"),
        "桌面角色背景" to setOf("desktop_character_backgrounds"),
        "本机任务与恢复记录" to setOf("memory_commit_journals", "pending_deletions"),
        "本机缓存" to setOf("novelai_prompt_translation_cache"),
    )
    val labels = groups.filter { (_, keys) -> types.any { it in keys } }.map { it.first }.toMutableList()
    val known = derivedIndexes + groups.flatMap { it.second }
    val unknown = types.count { it !in known }
    if (unknown > 0) labels += "其他未参与资料（${unknown}类）"
    return labels.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "本次未参与：")
}
