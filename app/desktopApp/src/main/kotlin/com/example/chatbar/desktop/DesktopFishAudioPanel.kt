package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.domain.voice.*
import kotlinx.coroutines.launch

@Composable
internal fun DesktopFishField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = label }
            .border(1.dp, DesktopBootstrapColors.border).padding(8.dp), singleLine = true,
            cursorBrush = DesktopBootstrapColors.inputCaret, textStyle = TextStyle(color = DesktopBootstrapColors.foreground),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

@Composable
internal fun DesktopFishGlobalSettings(state: DesktopModelSettingsState, controller: DesktopModelSettingsController) {
    val draft = state.settings ?: return
    val scope = rememberCoroutineScope()
    ManageHeading("语音与朗读")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("Fish Audio")
        controller.fishCredentials?.let { DesktopFishCredentialPanel(it) }
        SearchableChoice("Fish TTS 模型", FishAudioTtsModels.supported, draft.fishAudioTtsModelId, { it }) {
            value -> controller.editSettings { it.copy(fishAudioTtsModelId = value) }
        }
        if (draft.fishAudioTtsModelId !in FishAudioTtsModels.supported)
            StatusText("不可用的 Fish 模型 · ${draft.fishAudioTtsModelId}", DesktopBootstrapColors.warning)
        val ids = listOf<String?>(null) + state.availableAuxiliaryTextModels.map { it.id } +
            listOfNotNull(draft.voiceTagModelId?.takeIf { id -> state.availableAuxiliaryTextModels.none { it.id == id } })
        SearchableChoice("语音翻译 / 标签模型", ids, draft.voiceTagModelId, { id ->
            if (id == null) "当前会话对话模型" else state.availableAuxiliaryTextModels.firstOrNull { it.id == id }?.displayName
                ?: "不可用 · $id"
        }) { value -> controller.editSettings { it.copy(voiceTagModelId = value) } }
        if (draft.voiceTagModelId != null && state.availableAuxiliaryTextModels.none { it.id == draft.voiceTagModelId })
            StatusText("已选模型不可用；不会自动替换", DesktopBootstrapColors.warning)
        BootstrapButton("听书模式：${if (draft.audiobookModeEnabled) "开启" else "关闭"}", secondary = true) {
            controller.editSettings { it.copy(audiobookModeEnabled = !it.audiobookModeEnabled) }
        }
        if (state.chatDefaultsDirty)
            StatusText("仍有未保存的全局设置更改", DesktopBootstrapColors.warning)
        StatusText("此处保存设置；音色库支持免费文本预览，聊天语音生成尚未接入（PENDING）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton("保存全局设置", enabled = state.chatDefaultsDirty && !state.busy) {
                scope.launch { controller.saveAppSettings() }
            }
            BootstrapButton("放弃设置更改", enabled = state.chatDefaultsDirty && !state.busy, secondary = true) {
                controller.discardChatDefaults()
            }
        }
    }
}

@Composable
internal fun DesktopFishCredentialPanel(controller: DesktopFishCredentialController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var clearConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(controller) { controller.refresh() }
    StatusText("Fish Audio API Key：${if (state.configured) "已配置" else "未配置"}")
    state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
    if (editing) {
        DesktopFishField("新 Fish Audio API Key", input, secret = true) { input = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton("保存密钥", enabled = !state.busy && input.isNotBlank()) {
                val submitted = input; input = ""
                scope.launch { if (controller.save(submitted)) editing = false }
            }
            BootstrapButton("取消密钥编辑", secondary = true, enabled = !state.busy) { input = ""; editing = false }
        }
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BootstrapButton("设置 / 替换 Fish 密钥", secondary = true, enabled = !state.busy) { editing = true }
        BootstrapButton("清除 Fish 密钥", secondary = true, enabled = state.configured && !state.busy) { clearConfirm = true }
    }
    if (clearConfirm) {
        StatusText("确认仅移除 Fish Audio API Key？")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton("确认清除 Fish 密钥", enabled = !state.busy) {
                scope.launch { if (controller.clear()) clearConfirm = false }
            }
            BootstrapButton("保留密钥", secondary = true, enabled = !state.busy) { clearConfirm = false }
        }
    }
}

@Composable
internal fun DesktopFishSessionSettings(draft: ChatSession, edit: ((ChatSession) -> ChatSession) -> Unit) {
    StatusText("语音与朗读")
    SearchableChoice("会话听书模式", listOf<Boolean?>(null, true, false), draft.audiobookModeEnabled,
        { when (it) { null -> "跟随全局"; true -> "开启"; false -> "关闭" } }) { value ->
        edit { it.copy(audiobookModeEnabled = value) }
    }
    DesktopFishField("语音使用语言（留空使用原文）", draft.voiceLanguage.orEmpty()) { value ->
        edit { it.copy(voiceLanguage = value) }
    }
    StatusText("仅保存会话偏好；翻译、语音生成与播放尚未接入（PENDING）")
}

@Composable
internal fun DesktopFishBindingField(entry: CharacterInfo, editor: DesktopCharacterEditorController) {
    val credentials = editor.fishCredentials ?: return
    val state by credentials.state.collectAsState()
    val scope = rememberCoroutineScope()
    val draftId = editor.state.value.draftSessionId ?: return
    var open by remember(draftId, entry.id) { mutableStateOf(false) }
    LaunchedEffect(credentials) { credentials.refresh() }
    StatusText("Fish 音色 · ${entry.fishAudioVoice?.title ?: "未绑定"}")
    entry.fishAudioVoice?.let {
        StatusText("已保存快照 · ${it.referenceId} · ${it.visibility ?: "未知权限"}")
        StatusText("可用性未验证；音色可能已删除或当前 Key 无权访问，不会自动替换")
    }
    if (!state.configured) StatusText("请先在全局设置配置 Fish Audio API Key")
    state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BootstrapButton("选择 / 更换音色", secondary = true, enabled = state.configured && editor.fishLibrary != null) { open = true }
        if (entry.fishAudioVoice != null) BootstrapButton("移除音色绑定", secondary = true) {
            editor.bindFishVoice(draftId, entry.id, null)
        }
    }
    if (open) {
        val picker = remember(draftId, entry.id) { DesktopFishPickerController(scope, editor.fishLibrary!!()::listModels) }
        val preview = remember(picker) { runCatching { editor.fishPreview?.invoke()?.openSession() }.getOrNull() }
        fun closePicker() { picker.close(); preview?.requestClose(); open = false }
        DisposableEffect(picker) { onDispose { picker.close(); preview?.requestClose() } }
        DesktopFishPickerWindow(onClose = ::closePicker) {
            if (preview == null) StatusText("预览资源不可用；目录和显式音色选择仍可使用", DesktopBootstrapColors.warning)
            DesktopFishPickerContent(picker, state.configured, onClose = ::closePicker, preview = preview) { model ->
                // Revalidate the result and original draft/person at the explicit binding click.
                if (state.configured && picker.selectable(model.id) == model)
                    editor.bindFishVoice(draftId, entry.id, model.toBinding())
                closePicker()
            }
        }
    }
}
