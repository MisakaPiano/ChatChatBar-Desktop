package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.domain.image.*
import kotlinx.coroutines.launch

@Composable
internal fun DesktopChatRegenerationDialog(message: ChatMessage, metadata: GeneratedImageMetadata,
    service: DesktopChatImageRegeneration, onClose: () -> Unit) {
    var draft by remember { mutableStateOf(metadata.toRegenerationDraft()) }
    var settings by remember { mutableStateOf<NovelAiGenerationSettings?>(null) }
    var translation by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("载入原图参数…") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(message.id, metadata) {
        try { translation = service.translationEnabled(); settings = service.initialSettings(message, draft); status = "生成新图片并关联当前消息；原图保留" }
        catch (_: Exception) { status = "无法读取会话或原图参数" }
    }
    DialogWindow(onCloseRequest = onClose, title = "编辑图片 Prompt", state = rememberDialogState(width = 840.dp, height = 820.dp)) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusText(status)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioPromptField("画风", draft.stylePrompt, service.infrastructure, translation) { draft = draft.copy(stylePrompt = it) }
                StudioPromptField("基础 Prompt", draft.baseCaption, service.infrastructure, translation) { draft = draft.copy(baseCaption = it) }
                StudioPromptField("负面 Prompt", draft.negativePrompt, service.infrastructure, translation) { draft = draft.copy(negativePrompt = it) }
                draft.characterPrompts.forEachIndexed { index, character ->
                    StudioPromptField("角色 ${index + 1}", character.prompt, service.infrastructure, translation) { text ->
                        draft = draft.copy(characterPrompts = draft.characterPrompts.mapIndexed { i, c -> if (i == index) c.copy(prompt = text) else c })
                    }
                    StudioPromptField("角色负面", character.negativePrompt, service.infrastructure, translation) { text ->
                        draft = draft.copy(characterPrompts = draft.characterPrompts.mapIndexed { i, c -> if (i == index) c.copy(negativePrompt = text) else c })
                    }
                    BootstrapButton("移除此角色") { draft = draft.removeCharacterPrompt(index) }
                }
                BootstrapButton("添加角色", enabled = draft.characterPrompts.size < (settings?.model?.maxCharacters ?: 0)) { draft = draft.addCharacterPrompt() }
                settings?.let { s ->
                    CompactChoice("模型", NovelAiImageModel.entries, s.model, { it.displayName }) { settings = s.copy(model = it) }
                    StudioField("宽度", s.customWidth.toString()) { it.toIntOrNull()?.let { width -> settings = s.copy(customWidth = width) } }
                    StudioField("高度", s.customHeight.toString()) { it.toIntOrNull()?.let { height -> settings = s.copy(customHeight = height) } }
                    StudioField("数量", s.count.toString()) { it.toIntOrNull()?.let { count -> settings = s.copy(count = count) } }
                    StudioField("Steps", s.steps.toString()) { it.toIntOrNull()?.let { steps -> settings = s.copy(steps = steps) } }
                    StudioField("Guidance", s.guidance.toString()) { it.toFloatOrNull()?.let { value -> settings = s.copy(guidance = value) } }
                    CompactChoice("Seed", NovelAiSeedMode.entries, s.seedMode, { it.name }) { settings = s.copy(seedMode = it) }
                    if (s.seedMode == NovelAiSeedMode.FIXED) StudioField("Seed", s.seed.toString()) { it.toLongOrNull()?.let { seed -> settings = s.copy(seed = seed) } }
                    s.validationError(draft.characterPrompts.size)?.let { StatusText(it) }
                }
            }
            StudioActions {
                BootstrapButton("重新生成", enabled = draft.canRegenerate && settings?.validationError(draft.characterPrompts.size) == null && settings != null) {
                    scope.launch {
                        try { service.generate(message, draft, requireNotNull(settings)); onClose() }
                        catch (_: Exception) { status = "无法启动；请检查参数或正在运行的任务" }
                    }
                }
                BootstrapButton("重置") { draft = metadata.toRegenerationDraft() }
                BootstrapButton("取消", onClick = onClose)
            }
        }
    }
}
