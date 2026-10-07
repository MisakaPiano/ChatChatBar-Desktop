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

@Composable
internal fun DesktopStudioCharacters(draft: NovelAiStudioDraft, infrastructure: DesktopNovelAiInfrastructure,
    translation: Boolean, edit: ((NovelAiStudioDraft) -> NovelAiStudioDraft) -> Unit) {
    var positions by remember { mutableStateOf(false) }
    StudioActions {
        StudioAction("角色位置 · ${if (draft.activeSettings.useCharacterPositions) "自定义" else "自动"}", enabled = draft.activeCharacters.isNotEmpty()) { positions = true }
        StudioAction("添加角色", enabled = draft.activeCharacters.size < draft.selectedModel.maxCharacters) { edit { it.copy(characters = it.characters + NovelAiCharacterPromptDraft()) } }
    }
    draft.characters.forEachIndexed { index, character -> key(character.id) {
        var expanded by remember { mutableStateOf(character.enabled) }
        fun change(transform: (NovelAiCharacterPromptDraft) -> NovelAiCharacterPromptDraft) = edit { it.copy(characters = it.characters.map { c -> if (c.id == character.id) transform(c) else c }) }
        Column(Modifier.fillMaxWidth().background(if (character.enabled) DesktopBootstrapColors.card else DesktopBootstrapColors.muted)
            .border(1.dp, DesktopBootstrapColors.border).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { StatusText("角色 ${index + 1} · ${if (character.enabled) "已启用" else "已停用"}") }
                listOf(-1 to "↑", 1 to "↓").forEach { (offset, label) ->
                    StudioAction(label, enabled = index + offset in draft.characters.indices) { edit { current ->
                        val list = current.characters.toMutableList(); val from = list.indexOfFirst { it.id == character.id }; val to = from + offset
                        if (from >= 0 && to in list.indices) list.add(to, list.removeAt(from))
                        current.copy(characters = list)
                    } }
                }
                StudioAction(if (character.enabled) "✓" else "×", selected = character.enabled) {
                    if (character.enabled) expanded = false
                    change { it.copy(enabled = !it.enabled) }
                }
                StudioAction("删除", icon = DesktopAppIcons.Delete) { edit { it.copy(characters = it.characters.filterNot { c -> c.id == character.id }) } }
                StudioAction(if (expanded) "⌃" else "⌄") { expanded = !expanded }
            }
            Column(Modifier.alpha(if (character.enabled) 1f else .6f)) {
                if (expanded) {
                    StudioPromptField("角色正向", character.prompt, infrastructure, translation) { text -> change { it.copy(prompt = text) } }
                    StudioPromptField("角色负面", character.negativePrompt, infrastructure, translation) { text -> change { it.copy(negativePrompt = text) } }
                    StatusText(character.center?.let { "位置 ${(it.x * 100).roundToInt()}%, ${(it.y * 100).roundToInt()}%" } ?: "位置 · 自动")
                } else BasicText(character.prompt.ifBlank { "尚未填写正向 Prompt" }, style = TextStyle(color = DesktopBootstrapColors.foreground), maxLines = 3, overflow = TextOverflow.Ellipsis)
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
    fun setCenter(point: DesignedCharacterCenter) {
        val center = NovelAiCharacterPositionPolicy.normalize(point, draft.selectedModel)
        edit { current -> current.copy(characters = current.characters.map { if (it.id == selected?.id) it.copy(center = center) else it }) }
    }
    StudioChips("定位", listOf(false, true), custom, { if (it) "自定义" else "自动" }) { enabled ->
        edit { it.withActiveSettings(it.activeSettings.copy(useCharacterPositions = enabled)) }
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
            StudioField("X %", (center.x * 100).roundToInt().toString()) { text -> text.toFloatOrNull()?.takeIf { it.isFinite() }?.let { setCenter(center.copy(x = it / 100)) } }
            StudioField("Y %", (center.y * 100).roundToInt().toString()) { text -> text.toFloatOrNull()?.takeIf { it.isFinite() }?.let { setCenter(center.copy(y = it / 100)) } }
        }
    }
    StudioAction("均匀重置", enabled = custom) { edit { current -> current.copy(characters = current.characters.map { if (it.enabled) it.copy(center = null) else it }) } }
}
