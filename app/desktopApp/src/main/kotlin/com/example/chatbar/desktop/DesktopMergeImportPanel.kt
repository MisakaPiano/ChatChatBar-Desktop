package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.desktop.backup.*

@Composable
internal fun DesktopMergeImportPanel(controller: DesktopMergeImportController, showPasswordDialog: Boolean = true, onExit: () -> Unit) {
    val state by controller.state.collectAsState()
    DisposableEffect(controller) { onDispose { controller.leavePasswordFlow() } }
    var choices by remember(state.conflicts) { mutableStateOf(emptyMap<String, DesktopMergePreview.Choice>()) }
    var credentials by remember(state.credentials) { mutableStateOf(emptyMap<ImportCredentialKind, DesktopMergeCredentials.Choice>()) }
    var imageFromPhone by remember(state.ready) { mutableStateOf<Boolean?>(null) }
    var confirmed by remember(state.ready) { mutableStateOf(false) }
    var detailsOpen by remember(state.failureDetails) { mutableStateOf(false) }
    val mode = state.mode
    val activeConflicts = remember(choices, mode, state.ready, state.conflicts) {
        if (mode == DesktopBackupRestoreMode.MERGE_CHOICES) controller.effectiveConflicts(choices) else emptyList()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DesktopDataSectionHeading("导入与合并")
        StatusText("保留电脑现有内容，将手机数据合并到新的独立目录。冲突由你选择，原目录和备份文件保留。")
        StatusText("按官方 1.4.5–1.4.9 已适配的备份格式检查，不支持的内容会在提交前拒绝。历史录音和朋友圈原始内容单独保全，不会重新绑定录音版本或执行朋友圈任务。")
        StatusText("未勾选的图片与存档按原归属保留；旧录音无法与新正文对应时，保留原录音与记录，不用于新正文朗读。")
        if (!state.restartRequired) {
            ActionRow {
                BootstrapButton("选择备份并检查…", enabled = !state.busy) {
                    controller.selectFile()
                }
                if (state.busy) BootstrapButton("停止", secondary = true) { controller.stop() }
            }
        }
        if (state.ready && !state.restartRequired) {
            StatusText("已校验 ${state.files} 个文件 · 来源 ${state.versionName.take(80)}")
            ActionRow {
                DesktopBackupRestoreMode.entries.forEach { item ->
                    StudioAction(mergeModeLabel(item), selected = mode == item, style = StudioActionStyle.CHIP, enabled = !state.busy) {
                        controller.selectMode(item); choices = emptyMap(); confirmed = false
                    }
                }
            }
            if (mode != DesktopBackupRestoreMode.MERGE_CHOICES) {
                StatusText(when (mode) {
                    DesktopBackupRestoreMode.ADD_COPIES -> "卡片、会话及其内容建立独立副本，内容相同也会新增。全局设置按所选范围更新；凭据和图片处理偏好仍需单独确认。"
                    DesktopBackupRestoreMode.UPDATE_EXISTING -> "先按编号、再按唯一名称更新；无法唯一匹配时保留原内容并新增副本。"
                    else -> "只替换备份明确勾选的范围；该范围为空也会清除对应内容。未选内容仍需的依赖保留；旧无范围的全量备份则替换全部。"
                })
                if (state.modePlanning) StatusText("正在后台计算所选范围与依赖…")
                state.modeError?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                state.modeSummary?.let { plan ->
                    StatusText("新增 ${plan.added} · 更新 ${plan.updated} · 副本 ${plan.copied} · 移除 ${plan.removed}")
                    plan.warnings.take(20).forEach { StatusText(it, DesktopBootstrapColors.warning) }
                    if (plan.warnings.size > 20) StudioDisclosure("查看全部 ${plan.warnings.size} 项提示", initiallyOpen = false) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(plan.warnings) { StatusText(it, DesktopBootstrapColors.warning) }
                        }
                    }
                }
            }
            if (state.desktopParticipation) StatusText("该备份包含 Desktop 扩展（人物／玩家设置、命名账户或动态图片版本）；手机端不保证保留这些内容。", DesktopBootstrapColors.warning)
            if (state.historicalDesktopV5Settings) StatusText("已保留旧 Desktop 备份中的生图设置扩展；这些设置不代表官方 1.4.7 支持。", DesktopBootstrapColors.warning)
            if (activeConflicts.isEmpty() && mode == DesktopBackupRestoreMode.MERGE_CHOICES) StatusText("未发现需要选择的数据冲突。电脑和手机各自独有内容将保留。")
            if (activeConflicts.isNotEmpty()) StatusText("已选择 ${choices.size} / ${activeConflicts.size} 项数据冲突")
            StatusText("先选择卡片的处理方式，再核对相关会话和编辑草稿冲突；更改选择会保留仍然适用的其他选择。")
            activeConflicts.groupBy { conflict ->
                if (conflict.category == "app_settings") mergeSetting(conflict.label)?.group ?: "其他应用设置" else mergeCategory(conflict.category)
            }.forEach { (group, conflicts) ->
                StudioDisclosure("$group · ${conflicts.count { it.key in choices }} / ${conflicts.size}", initiallyOpen = true) {
            conflicts.forEach { conflict ->
                Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(6.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusText(if (conflict.category == "app_settings") mergeSetting(conflict.label)?.label ?: conflict.label.ifBlank { "未命名设置" }
                    else conflict.label.ifBlank { "未命名" })
                if (conflict.targetPcKey == null && DesktopMergePreview.Reason.SAME_NAME in conflict.reasons)
                    StatusText("存在多个同名目标，不会覆盖多个条目；请选择保留电脑、跳过或两份都保留。", DesktopBootstrapColors.warning)
                if (conflict.category == "edit_drafts") StatusText("同一编辑目标已有未保存草稿，请选择保留哪一份；卡片的处理方式不会自动决定草稿来源。")
                (if (conflict.key == DesktopMergePreview.CATALOG_KEY) state.catalogDetails else state.modelDetails[conflict.key])?.let { details ->
                    details.computer.forEach { StatusText("电脑：$it") }
                    details.phone.forEach { StatusText("手机：$it") }
                    details.notice?.let { StatusText(it) }
                }
                ActionRow {
                    conflict.choices.forEach { choice ->
                        StudioAction(mergeChoice(choice), selected = choices[conflict.key] == choice,
                            style = StudioActionStyle.CHIP, enabled = !state.busy) {
                                choices = controller.chooseConflict(choices, conflict.key, choice)
                                confirmed = false
                            }
                    }
                }
                }
            }
                }
            }
            state.credentials.forEach { conflict ->
                val namedAccounts = conflict.kind in setOf(ImportCredentialKind.DESKTOP_LLM_ACCOUNTS,
                    ImportCredentialKind.DESKTOP_NOVELAI_ACCOUNTS, ImportCredentialKind.DESKTOP_FISH_ACCOUNTS)
                StatusText("${mergeCredential(conflict.kind)}凭据冲突。密钥不显示；仅写入新目录的独立受保护存储。")
                StatusText("密钥本身不能证明服务端账号，请确认选用的来源。")
                if (namedAccounts) StatusText("两边账户均保留；这里选择默认账户的来源。模型绑定跟随对应模型的数据选择；同一编号内容不同的账户会另存为“导入”副本。")
                if (conflict.sessionUncompared) StatusText("Community 会话只保全，不会登录、刷新或发起请求。")
                ActionRow {
                    DesktopMergeCredentials.Choice.entries.forEach { choice ->
                        BootstrapButton((if (credentials[conflict.kind] == choice) "✓ " else "") +
                            if (namedAccounts) { if (choice == DesktopMergeCredentials.Choice.KEEP_COMPUTER) "沿用电脑选择" else "采用导入选择" }
                            else if (choice == DesktopMergeCredentials.Choice.KEEP_COMPUTER) "保留电脑凭据" else "采用手机凭据",
                            secondary = credentials[conflict.kind] != choice, enabled = !state.busy) { credentials = credentials + (conflict.kind to choice) }
                    }
                }
            }
            if (state.imagePreferencesChoiceRequired) {
                StatusText("图片编辑设置不同，请选择：")
                ActionRow {
                    BootstrapButton((if (imageFromPhone == false) "✓ " else "") + "保留电脑设置", secondary = imageFromPhone != false,
                        enabled = !state.busy) { imageFromPhone = false }
                    BootstrapButton((if (imageFromPhone == true) "✓ " else "") + "采用手机设置", secondary = imageFromPhone != true,
                        enabled = !state.busy) { imageFromPhone = true }
                }
            }
            StatusText("确认后将停止生成和音频，保留电脑快照，准备完整合并结果并核对凭据。选择保存位置后会创建新的子目录；完成后需要正常退出。")
            StatusText("请选择程序安装目录之外的位置，不要放在安装目录的input、UserData或versions内；原电脑目录和备份文件会保留。")
            StudioSwitch("我确认以上选择，并允许把所选凭据安全保存到新目录", confirmed,
                enabled = !state.busy) { confirmed = !confirmed }
            ActionRow {
                BootstrapButton("选择位置并合并…", enabled = !state.busy && !state.modePlanning && confirmed &&
                    (mode == DesktopBackupRestoreMode.MERGE_CHOICES || state.modeSummary != null) && choices.keys == activeConflicts.mapTo(hashSetOf()) { it.key } &&
                    credentials.size == state.credentials.size && (!state.imagePreferencesChoiceRequired || imageFromPhone != null)) {
                    controller.commit(choices, credentials, imageFromPhone, confirmed, mode)
                }
                BootstrapButton("取消合并", secondary = true, enabled = !state.busy) { controller.discard() }
            }
        }
        if (state.message.isNotBlank()) StatusText(state.message)
        state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
        state.failureDetails?.let { details ->
            BootstrapButton(if (detailsOpen) "收起错误详情" else "查看错误详情", secondary = true) { detailsOpen = !detailsOpen }
            if (detailsOpen) SelectionContainer { StatusText(details) }
        }
        state.destination?.let { StatusText("合并目录：$it") }
        if (state.restartRequired) BootstrapButton("正常退出应用", onClick = onExit)
    }
    state.passwordRequest?.takeIf { showPasswordDialog && !it.externalQueue }?.let { request -> DesktopBackupPasswordDialog(request,
        onPassword = controller::retryFilePassword, onCancel = controller::discard,
        onChooseFile = controller::selectFile) }
}
private fun mergeModeLabel(mode: DesktopBackupRestoreMode) = when (mode) {
    DesktopBackupRestoreMode.MERGE_CHOICES -> "逐项合并"
    DesktopBackupRestoreMode.UPDATE_EXISTING -> "更新已有内容"
    DesktopBackupRestoreMode.ADD_COPIES -> "新增独立副本"
    DesktopBackupRestoreMode.REPLACE_SELECTED -> "替换所选范围"
}
private fun mergeChoice(choice: DesktopMergePreview.Choice) = when (choice) {
    DesktopMergePreview.Choice.KEEP_PC -> "保留电脑"
    DesktopMergePreview.Choice.TAKE_PHONE -> "采用手机"
    DesktopMergePreview.Choice.BOTH -> "两份都保留"
    DesktopMergePreview.Choice.SKIP -> "跳过此项"
}
private fun mergeCategory(category: String) = when (category) {
    "tagCatalog" -> "标签库"
    "chat_sessions" -> "聊天"
    "character_cards" -> "角色卡"
    "model_configs", "retrieval_model_config" -> "模型"
    "embedding_configs", "embedding_model_config" -> "向量模型"
    "format_cards" -> "格式卡"
    "world_books" -> "世界书"
    "moment_posts" -> "朋友圈动态"
    "moment_tasks" -> "朋友圈计划"
    "app_settings" -> "应用设置"
    else -> "资料"
}
private fun mergeCredential(kind: ImportCredentialKind) = when (kind) {
    ImportCredentialKind.NOVEL_AI -> "NovelAI "
    ImportCredentialKind.FISH -> "Fish Audio "
    ImportCredentialKind.SILICON_FLOW -> "SiliconFlow "
    ImportCredentialKind.COMMUNITY -> "Community "
    ImportCredentialKind.DESKTOP_LLM_ACCOUNTS -> "Desktop LLM 命名账户与选择 "
    ImportCredentialKind.DESKTOP_NOVELAI_ACCOUNTS -> "Desktop NovelAI 命名账户与选择 "
    ImportCredentialKind.DESKTOP_FISH_ACCOUNTS -> "Desktop Fish 命名账户与选择 "
    else -> "模型 "
}

private fun mergeSetting(field: String) = BackupSettingOptions.all.firstOrNull { it.id == field || field in it.fields }
