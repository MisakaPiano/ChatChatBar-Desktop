package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import kotlin.math.roundToInt

internal fun desktopPositionRequestSize(draft: NovelAiStudioDraft): NovelAiImageSize {
    if (draft.imageGuidance.action != NovelAiGenerationAction.INPAINT) return draft.activeSettings.imageSize()
    val base = requireNotNull(draft.imageGuidance.baseImage)
    return NovelAiFocusedInpaintPlanner.plan(base.width, base.height,
        requireNotNull(draft.imageGuidance.focusedInpaintRegion), draft.imageGuidance.focusedInpaintMinimumContext).requestSize
}

private fun desktopPositionPercent(value: Float): String =
    ((value * 1000).roundToInt() / 10f).toString().removeSuffix(".0")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopStudioCharacters(draft: NovelAiStudioDraft, infrastructure: DesktopNovelAiInfrastructure,
    translation: Boolean, edit: ((NovelAiStudioDraft) -> NovelAiStudioDraft) -> Unit) {
    var positions by remember { mutableStateOf(false) }
    StudioActions {
        StudioAction("角色位置 · ${if (draft.activeSettings.useCharacterPositions) "自定义" else "自动"}", enabled = draft.activeCharacters.isNotEmpty()) { positions = true }
        StudioAction("添加角色", enabled = draft.activeCharacters.size < draft.selectedModel.maxCharacters) { edit { it.copy(characters = it.characters + NovelAiCharacterPromptDraft()) } }
    }
    draft.characters.forEachIndexed { index, character -> key(character.id) {
        var focused by remember { mutableStateOf(false) }
        var pinned by remember { mutableStateOf(false) }
        var requestingFocus by remember { mutableStateOf(false) }
        var focusRevision by remember { mutableStateOf<Long?>(null) }
        var expandedEditorOpen by remember { mutableStateOf(false) }
        var negative by remember { mutableStateOf(character.negativeExpanded) }
        val showNegative = negative && !draft.activeSettings.isMedium
        val expanded = pinned || focused || requestingFocus || expandedEditorOpen
        fun openEditor() { requestingFocus = true; focusRevision = (focusRevision ?: 0) + 1 }
        fun change(transform: (NovelAiCharacterPromptDraft) -> NovelAiCharacterPromptDraft) = edit { it.copy(characters = it.characters.map { c -> if (c.id == character.id) transform(c) else c }) }
        Column(Modifier.fillMaxWidth().background(if (character.enabled) DesktopBootstrapColors.card else DesktopBootstrapColors.muted)
            .border(1.dp, DesktopBootstrapColors.border)
            .padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                StatusText("角色 ${index + 1} · ${if (character.enabled) "已启用" else "已停用"}")
                listOf(-1 to "↑", 1 to "↓").forEach { (offset, label) ->
                    StudioAction(label, enabled = index + offset in draft.characters.indices) { edit { current ->
                        val list = current.characters.toMutableList(); val from = list.indexOfFirst { it.id == character.id }; val to = from + offset
                        if (from >= 0 && to in list.indices) list.add(to, list.removeAt(from))
                        current.copy(characters = list)
                    } }
                }
                StudioSwitch("", character.enabled, Modifier.semantics {
                    contentDescription = "角色 ${index + 1} 参与生图"
                }) {
                    change { it.copy(enabled = !it.enabled) }
                }
                StudioAction("删除", icon = DesktopAppIcons.Delete) { edit { it.copy(characters = it.characters.filterNot { c -> c.id == character.id }) } }
                StudioAction(if (pinned) "自动收起" else "固定展开", selected = pinned) { pinned = !pinned }
            }
            Column(Modifier.alpha(if (character.enabled) 1f else .6f)
                .onFocusChanged { focused = it.hasFocus }.focusGroup()) {
                if (expanded) {
                    key(showNegative) {
                        CompositionLocalProvider(LocalDesktopStudioFieldScope provides "person:${character.id}") {
                        StudioPromptField(if (showNegative) "角色负面" else "角色正向",
                            if (showNegative) character.negativePrompt else character.prompt,
                            infrastructure, translation, focusRevision,
                            onEditorFocusChanged = { active -> if (active) requestingFocus = false },
                            onExpandedEditorChanged = { open -> expandedEditorOpen = open; if (!open) openEditor() },
                            compact = true, inlineHeader = { expand -> StudioActions {
                                StudioAction("正向 Prompt", selected = !showNegative) { negative = false; openEditor() }
                                if (!draft.activeSettings.isMedium) StudioAction("负面 Prompt", selected = showNegative) { negative = true; openEditor() }
                                StudioAction("展开", icon = DesktopAppIcons.ExpandComposer, onClick = expand)
                                StatusText(character.center?.let { "位置 ${desktopPositionPercent(it.x)}%, ${desktopPositionPercent(it.y)}%" } ?: "位置 · 自动")
                            } }) { text ->
                            change { if (showNegative) it.copy(negativePrompt = text) else it.copy(prompt = text) }
                        }
                        }
                    }
                } else BasicText(character.prompt.ifBlank { "尚未填写正向 Prompt" },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "展开角色 ${index + 1} Prompt" }
                        .focusProperties { canFocus = false }.clickable { openEditor() },
                    style = TextStyle(color = DesktopBootstrapColors.foreground), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    } }
    if (positions) DesktopImageToolWindow("角色位置", { positions = false }, width = 640.dp, height = 650.dp) {
        DesktopCharacterPositions(draft, edit)
    }
}

@Composable
internal fun DesktopCharacterPositions(draft: NovelAiStudioDraft, edit: ((NovelAiStudioDraft) -> NovelAiStudioDraft) -> Unit) {
    val active = draft.activeCharacters
    var selectedId by remember { mutableStateOf(active.firstOrNull()?.id) }
    val selected = active.firstOrNull { it.id == selectedId } ?: active.firstOrNull()
    val custom = draft.activeSettings.useCharacterPositions
    val openedModel = draft.selectedModel
    fun updateCenter(change: (DesignedCharacterCenter) -> DesignedCharacterCenter) {
        val personId = selected?.id ?: return
        edit { current ->
            val activeNow = current.activeCharacters
            val index = activeNow.indexOfFirst { it.id == personId }
            if (current.selectedModel != openedModel || !current.activeSettings.useCharacterPositions || index < 0) current
            else {
                val before = NovelAiCharacterPositionPolicy.center(activeNow[index], index, activeNow.size, openedModel)
                val center = NovelAiCharacterPositionPolicy.normalize(change(before), openedModel)
                current.copy(characters = current.characters.map { if (it.id == personId) it.copy(center = center) else it })
            }
        }
    }
    fun setCenter(point: DesignedCharacterCenter) = updateCenter { point }
    StudioChips("定位", listOf(false, true), custom, { if (it) "自定义" else "自动" }) { enabled ->
        edit { if (it.selectedModel == openedModel) it.withActiveSettings(it.activeSettings.copy(useCharacterPositions = enabled)) else it }
    }
    StudioChips("启用角色", active.map { it.id }, selected?.id, { id -> "角色 ${draft.characters.indexOfFirst { it.id == id } + 1}" }) { selectedId = it }
    StatusText(if (draft.selectedModel == NovelAiImageModel.V4_5_FULL) "V4.5 · 5 × 5 网格吸附" else "V5 · 连续坐标")
    val color = DesktopBootstrapColors.primary
    val border = DesktopBootstrapColors.border
    val requestSize = runCatching { desktopPositionRequestSize(draft) }.getOrNull()
    if (draft.imageGuidance.action == NovelAiGenerationAction.INPAINT)
        StatusText(if (requestSize == null) "请先配置有效的聚焦区域" else "聚焦重绘：位置相对于请求裁剪区域，而非整张原图")
    val currentSetter by rememberUpdatedState<(DesignedCharacterCenter) -> Unit>(::setCenter)
    BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
    val ratio = requestSize?.let { it.width.toFloat() / it.height } ?: 1f
    val canvasWidth = minOf(maxWidth.value, maxHeight.value * ratio).dp
    Canvas(Modifier.width(canvasWidth).height(canvasWidth / ratio).background(DesktopBootstrapColors.input).border(1.dp, border)
        .pointerInput(custom, selected?.id) { detectTapGestures { if (custom && selected != null) currentSetter(DesignedCharacterCenter(it.x / size.width, it.y / size.height)) } }
        .pointerInput(custom, selected?.id) { detectDragGestures { change, _ -> if (custom && selected != null) { change.consume(); currentSetter(DesignedCharacterCenter(change.position.x / size.width, change.position.y / size.height)) } } }) {
        if (draft.selectedModel == NovelAiImageModel.V4_5_FULL) (1..4).forEach { i ->
            drawLine(border, Offset(size.width * i / 5, 0f), Offset(size.width * i / 5, size.height))
            drawLine(border, Offset(0f, size.height * i / 5), Offset(size.width, size.height * i / 5))
        }
        active.forEachIndexed { index, role ->
            val c = NovelAiCharacterPositionPolicy.center(role, index, active.size, draft.selectedModel)
            drawCircle(if (role.id == selected?.id) color else color.copy(alpha = .4f), if (role.id == selected?.id) 12.dp.toPx() else 8.dp.toPx(), Offset(c.x * size.width, c.y * size.height))
        }
    }
    }
    selected?.let { role ->
        val center = NovelAiCharacterPositionPolicy.center(role, active.indexOf(role), active.size, draft.selectedModel)
        if (custom) {
            key(openedModel, role.id) {
                CompositionLocalProvider(LocalDesktopStudioFieldScope provides "positions:$openedModel:${role.id}") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusText("X %")
                        StudioNumericInput("X %", desktopPositionPercent(center.x), Modifier.fillMaxWidth()) { text ->
                            text.toFloatOrNull()?.takeIf { it.isFinite() }?.let { value -> updateCenter { it.copy(x = value / 100) } }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusText("Y %")
                        StudioNumericInput("Y %", desktopPositionPercent(center.y), Modifier.fillMaxWidth()) { text ->
                            text.toFloatOrNull()?.takeIf { it.isFinite() }?.let { value -> updateCenter { it.copy(y = value / 100) } }
                        }
                    }
                }
            }
        }
    }
    StudioAction("均匀重置", enabled = custom) { edit { current ->
        if (current.selectedModel != openedModel || !current.activeSettings.useCharacterPositions) current
        else current.copy(characters = current.characters.map { if (it.enabled) it.copy(center = null) else it })
    } }
}
