package com.example.chatbar.domain.lanSync

val lanSyncGroupDescription = "把自己的电脑和手机加入同一个设备组。已设置的设备会在应用运行时检查同网资料，日常同步不需要解锁管理。" +
    "同步对话及消息版本、卡片、世界书、记忆、RAG、存档、朋友圈、玩家设定和绘图资料。删除与精简先保留待确认，修改前保全原件。" +
    "账户、密钥、设备设置和聊天输入草稿各端独立保留；模型绑定保留本机选择。工作室撤销历史随当前工作室资料一并选择，电脑后处理结果仍需回电脑查看。"

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
