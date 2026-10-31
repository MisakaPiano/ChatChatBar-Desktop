package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.chatbar.desktop.backup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class BackupExportPage { OVERVIEW, SETTINGS, CHARACTERS, PROTECTION }

@Composable
internal fun DesktopBackupExportDialog(state: DesktopFullBackupState, onRefresh: () -> Unit, onClose: () -> Unit,
    onExport: (AppBackupSelection, CharArray?) -> Unit, onStop: () -> Unit = {}) {
    val catalog = state.catalog
    var selected by remember { mutableStateOf<AppBackupSelection?>(null) }
    var page by remember { mutableStateOf(BackupExportPage.OVERVIEW) }
    var query by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }
    var expandedCards by remember { mutableStateOf(emptySet<String>()) }
    var encrypt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    LaunchedEffect(catalog) { if (catalog != null) selected = selected?.normalized(catalog) ?: catalog.allSelection() }
    val estimate by produceState<BackupSizeEstimate?>(null, selected, catalog) {
        value = null
        val choice = selected
        if (catalog != null && choice != null && !choice.isEmpty) value = withContext(Dispatchers.IO) {
            try { catalog.estimate(choice) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    fun choose(value: AppBackupSelection) { selected = value }
    DesktopModalSurface { Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight(.94f)
            .background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { DesktopDataSectionHeading(when (page) {
                    BackupExportPage.OVERVIEW -> "选择导出内容"; BackupExportPage.SETTINGS -> "全局设置项"
                    BackupExportPage.CHARACTERS -> "角色卡与会话"; BackupExportPage.PROTECTION -> "导出保护与保存"
                }) }
                BootstrapButton("刷新清单", secondary = true, enabled = !state.busy, onClick = onRefresh)
                BootstrapButton("关闭", secondary = true, enabled = !state.busy, onClick = onClose)
            }
            val choice = selected
            if (catalog != null && choice != null) {
                if (page != BackupExportPage.PROTECTION) ActionRow {
                    BootstrapButton("全选", secondary = true, enabled = !state.busy) { choose(when (page) {
                        BackupExportPage.SETTINGS -> choice.copy(globalOptions = BackupSettingOptions.ids)
                        BackupExportPage.CHARACTERS -> choice.withAllSessions(catalog)
                        else -> catalog.allSelection()
                    }) }
                    if (page == BackupExportPage.OVERVIEW) BootstrapButton("轻量", secondary = true, enabled = !state.busy) { choose(catalog.lightSelection()) }
                    BootstrapButton("清空", secondary = true, enabled = !state.busy) { choose(when (page) {
                        BackupExportPage.SETTINGS -> choice.copy(globalOptions = emptySet())
                        BackupExportPage.CHARACTERS -> choice.copy(characterIds = emptySet(), sessions = emptyMap())
                        else -> AppBackupSelection()
                    }) }
                }
                if (page in setOf(BackupExportPage.SETTINGS, BackupExportPage.CHARACTERS)) BasicTextField(query, { query = it },
                    Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border).padding(10.dp), enabled = !state.busy,
                    singleLine = true, cursorBrush = DesktopBootstrapColors.inputCaret, textStyle = TextStyle(color = DesktopBootstrapColors.foreground),
                    decorationBox = { inner -> Box { if (query.isEmpty()) StatusText("搜索名称"); inner() } })
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (page) {
                        BackupExportPage.OVERVIEW -> {
                            item { BackupExportGroup("全局设置 · 已选 ${choice.globalOptions.size} / ${BackupSettingOptions.all.size} 项", choice.globalOptions.isNotEmpty(), !state.busy,
                                { choose(choice.copy(globalOptions = if (choice.globalOptions.isEmpty()) BackupSettingOptions.ids else emptySet())) }, { page = BackupExportPage.SETTINGS }) }
                            item { BackupExportGroup("角色卡与会话 · ${choice.characterIds.size} 张卡 · ${choice.sessions.size} 个会话", choice.characterIds.isNotEmpty(), !state.busy,
                                { choose(if (choice.characterIds.isEmpty()) choice.withAllSessions(catalog) else choice.copy(characterIds = emptySet(), sessions = emptyMap())) }, { page = BackupExportPage.CHARACTERS }) }
                            item { BackupAllMedia(choice, !state.busy, ::choose) }
                            item { StudioSwitch("生图工作室设置（参数、参考图、草稿、撤销和 AI 设计会话）", choice.studioSettings, !state.busy) { choose(choice.copy(studioSettings = !choice.studioSettings)) } }
                            item { StudioSwitch("生图工作室历史 · ${catalog.historyCount} 个批次", choice.studioHistory, !state.busy) { choose(choice.copy(studioHistory = !choice.studioHistory)) } }
                            if (choice.studioHistory) item { StudioSwitch("包含历史图片", choice.studioHistoryImages, !state.busy) { choose(choice.copy(studioHistoryImages = !choice.studioHistoryImages)) } }
                            item { StatusText("朋友圈原始内容如已保全，会随完整备份保留；桌面版不把这些内容作为可运行朋友圈。") }
                            item { StatusText("关联模型、格式卡和世界书自动带入。轻量省略聊天图片、语音、会话存档和工作室历史原图。") }
                        }
                        BackupExportPage.SETTINGS -> {
                            BackupSettingOptions.all.filter { query.isBlank() || it.label.contains(query, true) || it.group.contains(query, true) }
                                .groupBy { it.group }.forEach { (group, options) ->
                                    item(key = "group:$group") { BackupExportGroup("$group · ${options.count { it.id in choice.globalOptions }} / ${options.size}", options.any { it.id in choice.globalOptions }, !state.busy,
                                        { choose(choice.copy(globalOptions = if (options.any { it.id in choice.globalOptions }) choice.globalOptions - options.map { it.id }.toSet() else choice.globalOptions + options.map { it.id })) },
                                        { expandedGroups = if (group in expandedGroups) expandedGroups - group else expandedGroups + group }) }
                                    if (group in expandedGroups || query.isNotBlank()) items(options, key = { it.id }) { option ->
                                        StudioSwitch(option.label, option.id in choice.globalOptions, !state.busy) { choose(choice.copy(globalOptions = if (option.id in choice.globalOptions) choice.globalOptions - option.id else choice.globalOptions + option.id)) }
                                    }
                                }
                        }
                        BackupExportPage.CHARACTERS -> {
                            item { BackupAllMedia(choice, !state.busy, ::choose) }
                            item { StatusText("先选择角色卡，再选择会话。长期记忆、阅读位置和会话草稿随会话导出。") }
                            catalog.characters.filter { card -> query.isBlank() || card.name.contains(query, true) || catalog.sessions.any { it.characterId == card.id && it.title.contains(query, true) } }.forEach { card ->
                                val sessions = catalog.sessions.filter { it.characterId == card.id }
                                val enabled = card.id in choice.characterIds
                                item(key = "card:${card.id}") { BackupExportGroup("${card.name} · 已选 ${sessions.count { it.id in choice.sessions }} / ${sessions.size} 个会话", enabled, !state.busy,
                                    { choose(choice.withCharacter(card.id, !enabled, catalog)) }, { expandedCards = if (card.id in expandedCards) expandedCards - card.id else expandedCards + card.id }) }
                                if (card.id in expandedCards || query.isNotBlank()) {
                                    item(key = "bulk:${card.id}") { ActionRow {
                                        BootstrapButton("全选该卡会话", secondary = true, enabled = enabled && !state.busy) { choose(choice.withSessions(sessions.map { it.id }, true)) }
                                        BootstrapButton("清空该卡会话", secondary = true, enabled = enabled && !state.busy) { choose(choice.withSessions(sessions.map { it.id }, false)) }
                                    } }
                                    items(sessions, key = { "session:${it.id}" }) { session ->
                                        val media = choice.sessions[session.id]
                                        Column(Modifier.padding(start = 18.dp)) {
                                            StudioSwitch(session.title, media != null, enabled && !state.busy) { choose(choice.withSessions(listOf(session.id), media == null)) }
                                            if (media != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                StudioSwitch("图片", media.images, !state.busy) { choose(choice.copy(sessions = choice.sessions + (session.id to media.copy(images = !media.images)))) }
                                                StudioSwitch("语音", media.voice, !state.busy) { choose(choice.copy(sessions = choice.sessions + (session.id to media.copy(voice = !media.voice)))) }
                                                StudioSwitch("存档", media.archives, !state.busy) { choose(choice.copy(sessions = choice.sessions + (session.id to media.copy(archives = !media.archives)))) }
                                            }
                                        }
                                    }
                                }
                            }
                            val orphanCount = catalog.sessions.count { session -> catalog.characters.none { it.id == session.characterId } }
                            if (orphanCount > 0) item { StatusText("$orphanCount 个会话的角色卡已缺失，无法选择导出。", DesktopBootstrapColors.warning) }
                        }
                        BackupExportPage.PROTECTION -> {
                            item { StatusText("所选内容已准备，可返回修改清单。备份可能包含所选登录凭据及关联模型的 API 密钥。") }
                            item { BootstrapButton("修改导出清单", secondary = true, enabled = !state.busy) { page = BackupExportPage.OVERVIEW } }
                            item { StudioSwitch("密码加密（可选）", encrypt, !state.busy) { encrypt = !encrypt; password = ""; repeatPassword = "" } }
                            if (encrypt) {
                                item { BackupPasswordField("存档密码（8–1024 个字符）", password, !state.busy) { password = it } }
                                item { BackupPasswordField("再次输入密码", repeatPassword, !state.busy) { repeatPassword = it } }
                                if (password != repeatPassword) item { StatusText("两次密码不一致", DesktopBootstrapColors.warning) }
                            } else item { StatusText("当前导出不加密。持有文件者可读取所选聊天和密钥。", DesktopBootstrapColors.warning) }
                        }
                    }
                }
                estimate?.let { StatusText("预计大小约 ${it.bytes / (1024 * 1024)} MB · 按去重资源估算，压缩后通常更小") }
                state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
                if (state.busy) StatusText(state.message) else if (page == BackupExportPage.PROTECTION) StatusText(state.message)
                ActionRow {
                    if (state.busy) BootstrapButton("停止", secondary = true, onClick = onStop)
                    if (page != BackupExportPage.OVERVIEW) BootstrapButton("返回总览", secondary = true, enabled = !state.busy) { page = BackupExportPage.OVERVIEW; query = "" }
                    if (page == BackupExportPage.OVERVIEW) BootstrapButton("下一步", enabled = !choice.isEmpty && !state.busy && estimate != null) { page = BackupExportPage.PROTECTION }
                    if (page == BackupExportPage.PROTECTION) BootstrapButton("选择保存位置并导出", enabled = !choice.isEmpty && !state.busy && (!encrypt || password.length in 8..1024 && password == repeatPassword)) {
                        val secret = if (encrypt) password.toCharArray() else null
                        password = ""; repeatPassword = ""; onExport(choice, secret)
                    }
                }
            } else { Box(Modifier.weight(1f)) { StatusText(state.error ?: "正在读取导出清单…") } }
        }
    } }
}

@Composable private fun BackupExportGroup(label: String, selected: Boolean, enabled: Boolean, toggle: () -> Unit, details: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { StudioSwitch(label, selected, enabled, toggle) }
        BootstrapButton("展开", secondary = true, enabled = enabled, onClick = details)
    }
}
@Composable private fun BackupAllMedia(choice: AppBackupSelection, enabled: Boolean, onChange: (AppBackupSelection) -> Unit) {
    Column {
        listOf(BackupSessionContent.IMAGES to "全部会话图片", BackupSessionContent.VOICE to "全部会话语音", BackupSessionContent.ARCHIVES to "全部会话存档").forEach { (kind, label) ->
            val selected = when (kind) { BackupSessionContent.IMAGES -> choice.sessionImagesDefault; BackupSessionContent.VOICE -> choice.sessionVoiceDefault; BackupSessionContent.ARCHIVES -> choice.sessionArchivesDefault }
            val count = choice.sessions.values.count { when (kind) { BackupSessionContent.IMAGES -> it.images; BackupSessionContent.VOICE -> it.voice; BackupSessionContent.ARCHIVES -> it.archives } }
            StudioSwitch("$label · $count / ${choice.sessions.size} 个会话；新选默认${if (selected) "包含" else "不含"}", selected, enabled) { onChange(choice.withAllSessionContent(kind, !selected)) }
        }
    }
}
@Composable internal fun BackupPasswordField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(value.isEmpty(), enabled) { if (value.isEmpty() || !enabled) visible = false }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusText(label)
        Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, { onChange(it.take(1024)) }, Modifier.weight(1f).padding(10.dp),
                enabled = enabled, singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                cursorBrush = DesktopBootstrapColors.inputCaret, textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
            DesktopIconAction(if (visible) "隐藏密码" else "显示密码",
                if (visible) DesktopAppIcons.HideSecret else DesktopAppIcons.ShowSecret, enabled = enabled) { visible = !visible }
        }
    }
}
