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
    var useSessionPlan by remember(message.id) { mutableStateOf(false) }
    var allowAnlas by remember(message.id) { mutableStateOf(false) }
    var estimate by remember(message.id) { mutableStateOf("重新生成 · 费用未知") }
    var savedPlan by remember(message.id) { mutableStateOf(ImageBatchPreferences()) }
    var editingPlan by remember(message.id) { mutableStateOf<ImageBatchPreferences?>(null) }
    val scope = rememberCoroutineScope()
    val tasks by service.taskStates.collectAsState()
    val sourceBusy = desktopImageSourceBusy(tasks, message.sessionId, message.id)
    LaunchedEffect(message.id, metadata) {
        try { translation = service.translationEnabled(); settings = service.initialSettings(message, draft)
            savedPlan = service.sessionBatchPreferences(message); status = "生成新图片并关联当前消息；原图保留" }
        catch (_: Exception) { status = "无法读取会话或原图参数" }
    }
    LaunchedEffect(message.id, settings, useSessionPlan, allowAnlas, savedPlan) {
        val launch = settings ?: return@LaunchedEffect
        estimate = try { service.previewRegeneration(message, launch, useSessionPlan, allowAnlas) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { "重新生成 · 费用未知" }
    }
    val initialSize = rememberDesktopImageWindowSize(840.dp, 820.dp)
    DialogWindow(onCloseRequest = onClose, title = "编辑图片 Prompt", icon = desktopImageWindowIcon(),
        resizable = true, state = rememberDialogState(size = initialSize)) {
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
                    StudioSwitch("使用本会话保存的批量计划", useSessionPlan) { useSessionPlan = !useSessionPlan }
                    BootstrapButton("会话批量设置", secondary = true) { editingPlan = editingPlan?.let { null } ?: savedPlan }
                    editingPlan?.let { current ->
                        DesktopSessionImageBatchSettings(current) { editingPlan = it }
                        BootstrapButton("保存会话生图计划", enabled = current.validationError() == null && !sourceBusy, secondary = true) {
                            scope.launch {
                                try { service.saveBatchPreferences(message, savedPlan, current)
                                    savedPlan = current; editingPlan = null; status = "会话计划已保存；未启动请求" }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { status = "会话计划保存未完成，请重新打开检查" }
                            }
                        }
                    }
                    if (!useSessionPlan) {
                        StatusText("普通重新生成：1 张；保留原图 Prompt 与尺寸，除非你在这里修改。")
                        StudioSwitch("允许消耗 Anlas", allowAnlas) { allowAnlas = !allowAnlas }
                    }
                    StudioField("Steps", s.steps.toString()) { it.toIntOrNull()?.let { steps -> settings = s.copy(steps = steps) } }
                    StudioNumericField("Guidance", s.guidance.toString()) { it.toFloatOrNull()?.takeIf { value -> value.isFinite() && value in 1f..10f }?.let { value -> settings = s.copy(guidance = value) } }
                    CompactChoice("Seed", NovelAiSeedMode.entries, s.seedMode, { it.name }) { settings = s.copy(seedMode = it) }
                    if (s.seedMode == NovelAiSeedMode.FIXED) StudioField("Seed", s.seed.toString()) { it.toLongOrNull()?.let { seed -> settings = s.copy(seed = seed) } }
                    s.requestValidationError(draft.characterPrompts.size)?.let { StatusText(it) }
                }
            }
            StudioActions {
                BootstrapButton(estimate, enabled = !sourceBusy && draft.canRegenerate && settings?.requestValidationError(draft.characterPrompts.size) == null && settings != null) {
                    scope.launch {
                        try { service.generate(message, draft, requireNotNull(settings), useSessionPlan, allowAnlas); onClose() }
                        catch (_: Exception) { status = "无法启动；请检查参数或正在运行的任务" }
                    }
                }
                BootstrapButton("重置") { draft = metadata.toRegenerationDraft() }
                BootstrapButton("取消", onClick = onClose)
            }
        }
    }
}
