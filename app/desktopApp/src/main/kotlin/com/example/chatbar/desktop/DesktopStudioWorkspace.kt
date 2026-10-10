package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Process-local geometry only. Generated paths, drafts and history retain their existing owners. */
internal class DesktopStudioWorkspaceState {
    var mode by mutableStateOf("展开预览")
    var fraction by mutableStateOf(.49f)
    var compactExpanded by mutableStateOf(false)
    var diagnostics by mutableStateOf(false)

    fun previewWidth(width: Float): Float = when (mode) {
        "预览专注" -> width
        "仅缩略图" -> 116f
        else -> (width * fraction).coerceIn(300f, (width - 332f).coerceAtLeast(300f))
    }
    fun drag(width: Float, desired: Float) {
        if (desired <= 160f) mode = "仅缩略图"
        else {
            mode = "展开预览"
            fraction = desired.coerceIn(300f, width - 332f) / width
        }
    }
}

/** Preview is a root-height sibling; only its content reserves the caption-controls band. */
@Composable
internal fun DesktopStudioWorkspace(
    state: DesktopStudioWorkspaceState = remember { DesktopStudioWorkspaceState() },
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    captions: (@Composable () -> Unit)? = null,
    diagnostics: (@Composable () -> Unit)? = null,
    compact: @Composable (onExpand: () -> Unit) -> Unit,
    editor: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    preview: @Composable (rail: Boolean) -> Unit,
) {
    val editorScroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxSize().background(DesktopBootstrapColors.background).clipToBounds()) {
        val width = maxWidth.value
        val wide = width >= 900f
        val focus = wide && state.mode == "预览专注"
        val expanded = wide || state.compactExpanded
        val previewWidth = if (wide) state.previewWidth(width) else minOf(width, 640f)
        val editorWidth = if (wide && !focus) width - previewWidth - 12f else width
        val captionWidth = if (captions != null) 126f else 0f
        val navWidth = if (focus || (!wide && expanded)) minOf(240f, width - captionWidth)
            else minOf(editorWidth, width - captionWidth)
        // Keep editor composition/scroll alive under focus and drawer; neither writes a draft.
        Column(Modifier.width(editorWidth.dp).fillMaxHeight()
            .then(if (focus || (!wide && expanded)) Modifier.clearAndSetSemantics {} else Modifier)
            .padding(top = if (navigation != null) 44.dp else 0.dp)) {
            if (diagnostics != null) StudioActions {
                StudioAction("NovelAI Studio", selected = !state.diagnostics) { state.diagnostics = false }
                StudioAction("高级 / 诊断", selected = state.diagnostics) { state.diagnostics = true }
            }
            if (!wide && !expanded && !state.diagnostics) compact { state.compactExpanded = true }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Column(Modifier.fillMaxSize()
                    .then(if (state.diagnostics) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier)
                    .verticalScroll(editorScroll).padding(12.dp)
                    .semantics { contentDescription = "Studio Prompt 编辑区" }) { editor() }
                if (state.diagnostics) diagnostics?.invoke()
            }
            Box(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card)
                .semantics { contentDescription = "Studio 固定生成栏" }.padding(8.dp), contentAlignment = Alignment.CenterEnd) { footer() }
        }
        if (expanded) {
            if (wide && !focus) {
                // Pointer input is stable during drag; accumulate physical deltas independently of remeasurement.
                Box(Modifier.offset(x = editorWidth.dp).width(12.dp).fillMaxHeight()
                    .semantics { contentDescription = "调整预览宽度" }
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR)))
                    .pointerInput(width) {
                        var draggedWidth = 0f
                        detectDragGestures(onDragStart = { draggedWidth = state.previewWidth(width) }) { change, delta ->
                            change.consume()
                            draggedWidth -= delta.x / density
                            state.drag(width, draggedWidth)
                        }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(DesktopBootstrapColors.border))
                    Box(Modifier.width(6.dp).height(48.dp).background(DesktopBootstrapColors.primary))
                }
            }
            Column(Modifier.align(Alignment.CenterEnd).width(previewWidth.dp).fillMaxHeight()
                .background(DesktopBootstrapColors.card).border(1.dp, DesktopBootstrapColors.border)
                .semantics { contentDescription = "Studio 全高预览面板" }
                .padding(top = if (captions != null || navigation != null) 44.dp else 0.dp)
                .padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!wide) StudioAction("收起预览") { state.compactExpanded = false }
                else if (state.mode == "仅缩略图") StudioAction("展开预览") { state.mode = "展开预览" }
                else StudioActions {
                    CompactChoice("预览", listOf("展开预览", "仅缩略图", "预览专注"), state.mode, { it }) { state.mode = it }
                    if (focus) StudioAction("返回编辑") { state.mode = "展开预览" }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { preview(wide && state.mode == "仅缩略图") }
            }
        }
        // Only this measured left title region is draggable. Preview/divider outside it remain CLIENT.
        navigation?.let { Box(Modifier.width(navWidth.coerceAtLeast(0f).dp).align(Alignment.TopStart)) { it() } }
        captions?.let { Box(Modifier.width(captionWidth.dp).align(Alignment.TopEnd)) { it() } }
    }
}
