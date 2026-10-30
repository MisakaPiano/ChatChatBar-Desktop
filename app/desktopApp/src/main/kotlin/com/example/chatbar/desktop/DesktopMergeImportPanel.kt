package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.desktop.backup.*

@Composable
internal fun DesktopMergeImportPanel(controller: DesktopMergeImportController, onExit: () -> Unit) {
    val state by controller.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var choices by remember(state.conflicts) { mutableStateOf(emptyMap<String, DesktopMergePreview.Choice>()) }
    var credentials by remember(state.credentials) { mutableStateOf(emptyMap<ImportCredentialKind, DesktopMergeCredentials.Choice>()) }
    var imageFromPhone by remember(state.ready) { mutableStateOf<Boolean?>(null) }
    var confirmed by remember(state.ready) { mutableStateOf(false) }
    var detailsOpen by remember(state.failureDetails) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DesktopDataSectionHeading("导入与合并")
        StatusText("保留电脑现有内容，将手机数据合并到新的独立目录。冲突由你选择，原目录和备份文件保留。")
        StatusText("按官方 1.4.5–1.4.7 格式检查备份，不支持的内容会在提交前拒绝。历史录音和朋友圈原始内容单独保全，不会重新绑定录音版本或执行朋友圈任务。")
        if (!state.restartRequired) {
            BackupPasswordField("加密备份密码（未加密请留空）", password, !state.busy) { password = it }
            ActionRow {
                BootstrapButton("选择备份并检查…", enabled = !state.busy) {
                    val secret = password.takeIf { it.isNotEmpty() }?.toCharArray(); password = ""; controller.inspect(secret)
                }
                if (state.busy) BootstrapButton("停止", secondary = true) { controller.stop() }
            }
        }
        if (state.ready && !state.restartRequired) {
            StatusText("已校验 ${state.files} 个文件 · 来源 ${state.versionName.take(80)}")
            if (state.desktopParticipation) StatusText("该备份包含 Desktop 会话人物开关；旧版手机端不支持此设置。", DesktopBootstrapColors.warning)
            if (state.conflicts.isEmpty()) StatusText("未发现需要选择的数据冲突。电脑和手机各自独有内容将保留。")
            if (state.conflicts.isNotEmpty()) StatusText("已选择 ${choices.size} / ${state.conflicts.size} 项数据冲突")
            state.conflicts.groupBy { conflict ->
                if (conflict.category == "app_settings") mergeSetting(conflict.label)?.group ?: "其他应用设置" else mergeCategory(conflict.category)
            }.forEach { (group, conflicts) ->
                StudioDisclosure("$group · ${conflicts.count { it.key in choices }} / ${conflicts.size}", initiallyOpen = true) {
            conflicts.forEach { conflict ->
                Column(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(6.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusText(if (conflict.category == "app_settings") mergeSetting(conflict.label)?.label ?: conflict.label.ifBlank { "未命名设置" }
                    else conflict.label.ifBlank { "未命名" })
                state.modelDetails[conflict.key]?.let { details ->
                    details.computer.forEach { StatusText("电脑：$it") }
                    details.phone.forEach { StatusText("手机：$it") }
                }
                ActionRow {
                    conflict.choices.forEach { choice ->
                        StudioAction(mergeChoice(choice), selected = choices[conflict.key] == choice,
                            style = StudioActionStyle.CHIP, enabled = !state.busy) { choices = choices + (conflict.key to choice) }
                    }
                }
                }
            }
                }
            }
            state.credentials.forEach { conflict ->
                StatusText("${mergeCredential(conflict.kind)}凭据冲突。密钥不显示；仅写入新目录的独立受保护存储。")
                StatusText("密钥本身不能证明服务端账号，请确认选用的来源。")
                if (conflict.sessionUncompared) StatusText("Community 会话只保全，不会登录、刷新或发起请求。")
                ActionRow {
                    DesktopMergeCredentials.Choice.entries.forEach { choice ->
                        BootstrapButton((if (credentials[conflict.kind] == choice) "✓ " else "") +
                            if (choice == DesktopMergeCredentials.Choice.KEEP_COMPUTER) "保留电脑凭据" else "采用手机凭据",
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
            StudioSwitch("我确认以上选择，并允许把所选凭据安全保存到新目录", confirmed,
                enabled = !state.busy) { confirmed = !confirmed }
            ActionRow {
                BootstrapButton("选择位置并合并…", enabled = !state.busy && confirmed && choices.size == state.conflicts.size &&
                    credentials.size == state.credentials.size && (!state.imagePreferencesChoiceRequired || imageFromPhone != null)) {
                    controller.commit(choices, credentials, imageFromPhone, confirmed)
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
}
private fun mergeChoice(choice: DesktopMergePreview.Choice) = when (choice) {
    DesktopMergePreview.Choice.KEEP_PC -> "保留电脑"
    DesktopMergePreview.Choice.TAKE_PHONE -> "采用手机"
    DesktopMergePreview.Choice.BOTH -> "两份都保留"
    DesktopMergePreview.Choice.SKIP -> "跳过此项"
}
private fun mergeCategory(category: String) = when (category) {
    "chat_sessions" -> "聊天"
    "character_cards" -> "角色卡"
    "model_configs", "retrieval_model_config" -> "模型"
    "embedding_configs", "embedding_model_config" -> "向量模型"
    "format_cards" -> "格式卡"
    "world_books" -> "世界书"
    "app_settings" -> "应用设置"
    else -> "资料"
}
private fun mergeCredential(kind: ImportCredentialKind) = when (kind) {
    ImportCredentialKind.NOVEL_AI -> "NovelAI "
    ImportCredentialKind.FISH -> "Fish Audio "
    ImportCredentialKind.SILICON_FLOW -> "SiliconFlow "
    ImportCredentialKind.COMMUNITY -> "Community "
    else -> "模型 "
}

private fun mergeSetting(field: String) = BackupSettingOptions.all.firstOrNull { it.id == field || field in it.fields }
