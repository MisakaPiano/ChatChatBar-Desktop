package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.example.chatbar.domain.image.*
import kotlin.math.roundToInt

/** Desktop presentation primitives. Callers retain draft, validation and runtime ownership. */
internal enum class StudioActionStyle { GHOST, CHIP }

@Composable
internal fun StudioAction(label: String, enabled: Boolean = true, selected: Boolean = false,
    icon: ImageVector? = null, style: StudioActionStyle = StudioActionStyle.GHOST, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val color = if (selected) DesktopBootstrapColors.primaryForeground else DesktopBootstrapColors.foreground
    Row(Modifier.heightIn(min = 32.dp).alpha(if (enabled) 1f else .4f)
        .background(if (selected) DesktopBootstrapColors.primary else Color.Transparent, shape)
        .border(1.dp, if (focused) DesktopBootstrapColors.primary else if (style == StudioActionStyle.CHIP) DesktopBootstrapColors.border else Color.Transparent, shape)
        .semantics { if (style == StudioActionStyle.CHIP) this.selected = selected }
        .onFocusChanged { focused = it.isFocused }.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        icon?.let { Image(it, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(color)) }
        BasicText(label, style = TextStyle(color = color, fontSize = 13.sp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> StudioChips(label: String, options: List<T>, selected: T?, text: (T) -> String, choose: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) StatusText(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { item -> key(item) { StudioAction(text(item), selected = item == selected, style = StudioActionStyle.CHIP) { choose(item) } } }
        }
    }
}

@Composable
internal fun StudioSwitch(label: String, selected: Boolean, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.heightIn(min = 32.dp).border(1.dp, if (focused) DesktopBootstrapColors.primary else Color.Transparent, RoundedCornerShape(6.dp))
        .onFocusChanged { focused = it.isFocused }.toggleable(value = selected, role = Role.Switch, onValueChange = { action() })
        .padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(28.dp, 16.dp).background(if (selected) DesktopBootstrapColors.primary else DesktopBootstrapColors.muted, RoundedCornerShape(8.dp)).padding(2.dp)) {
            Box(Modifier.align(if (selected) Alignment.CenterEnd else Alignment.CenterStart).size(12.dp)
                .background(if (selected) DesktopBootstrapColors.primaryForeground else DesktopBootstrapColors.mutedForeground, RoundedCornerShape(6.dp)))
        }
        StatusText(label)
    }
}

@Composable
internal fun StudioDisclosure(label: String, summary: String = "", initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StudioAction((if (open) "▾ " else "▸ ") + label + if (!open && summary.isNotBlank()) " · $summary" else "") { open = !open }
        if (open) content()
    }
}

/** Popup stays anchored to the trigger; focus/keyboard input belongs to the menu while open. */
@Composable
internal fun <T> CompactChoice(label: String, options: List<T>, selected: T?, text: (T) -> String, choose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    Box {
        StudioAction("$label · ${options.indexOf(selected).takeIf { it >= 0 }?.let { text(options[it]) } ?: "选择"} ▾") {
            index = options.indexOf(selected).coerceAtLeast(0); open = true
        }
        if (open) Popup(popupPositionProvider = StudioPopupPosition,
            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            LaunchedEffect(index) { if (options.isNotEmpty()) list.scrollToItem(index.coerceIn(options.indices)) }
            LazyColumn(Modifier.widthIn(min = 200.dp, max = 320.dp).heightIn(max = 270.dp)
                .background(DesktopBootstrapColors.card, RoundedCornerShape(8.dp)).border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.Escape -> { open = false; true }
                        Key.DirectionDown -> { index = (index + 1).coerceAtMost(options.lastIndex.coerceAtLeast(0)); true }
                        Key.DirectionUp -> { index = (index - 1).coerceAtLeast(0); true }
                        Key.Enter, Key.NumPadEnter -> { if (index in options.indices) choose(options[index]); open = false; true }
                        else -> false
                    }
                }.focusable().padding(4.dp), state = list) {
                itemsIndexed(options) { i, item ->
                    Box(Modifier.fillMaxWidth().background(if (i == index) DesktopBootstrapColors.accent else Color.Transparent)
                        .clickable { choose(item); open = false }.padding(8.dp)) { StatusText("${if (item == selected) "✓ " else ""}${text(item)}") }
                }
            }
        }
    }
}

internal fun studioSliderValue(fraction: Float, min: Float, max: Float, step: Float): Float =
    (min.toDouble() + (((max - min) * fraction.coerceIn(0f, 1f)) / step).roundToInt() * step.toString().toDouble()).toFloat().coerceIn(min, max)

internal fun studioNumericText(value: Float, minimumDecimals: Int): String = if (!value.isFinite()) value.toString() else
    value.toString().toBigDecimal().let { it.setScale(maxOf(it.scale(), minimumDecimals)).toPlainString() }

internal fun studioUsesSearchDialog(optionCount: Int): Boolean = optionCount > 12

internal object StudioPopupPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - popupContentSize.height
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

@Composable
internal fun StudioSlider(label: String, value: Float, min: Float, max: Float, step: Float, decimals: Int, change: (Float) -> Unit) {
    val update by rememberUpdatedState(change)
    val current by rememberUpdatedState(value)
    var focused by remember { mutableStateOf(false) }
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { StatusText(label) }
            StudioNumericInput(label, studioNumericText(value, decimals), Modifier.width(72.dp)) { raw ->
                raw.toFloatOrNull()?.takeIf { it.isFinite() && it in min..max && (decimals != 0 || it == it.roundToInt().toFloat()) }?.let(update)
            }
        }
        Box(Modifier.fillMaxWidth().height(24.dp).onFocusChanged { focused = it.isFocused }
            .border(1.dp, if (focused) DesktopBootstrapColors.primary else Color.Transparent, RoundedCornerShape(4.dp))
            .semantics { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(value, min..max)
                setProgress { update(it.coerceIn(min, max)); true } }
            .onKeyEvent { event -> if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft, Key.DirectionDown -> { update(studioSliderValue((current - step - min) / (max - min), min, max, step)); true }
                Key.DirectionRight, Key.DirectionUp -> { update(studioSliderValue((current + step - min) / (max - min), min, max, step)); true }
                Key.MoveHome -> { update(min); true }; Key.MoveEnd -> { update(max); true }; else -> false
            } }.focusable()
            .pointerInput(min, max, step) { detectTapGestures { update(studioSliderValue(it.x / size.width, min, max, step)) } }
            .pointerInput(min, max, step) { detectDragGestures { pointer, _ -> pointer.consume(); update(studioSliderValue(pointer.position.x / size.width, min, max, step)) } }) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).background(DesktopBootstrapColors.muted, RoundedCornerShape(2.dp)))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction).height(4.dp).background(DesktopBootstrapColors.primary, RoundedCornerShape(2.dp)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(Modifier.offset(x = (maxWidth - 12.dp) * fraction).align(Alignment.CenterStart).size(12.dp).background(DesktopBootstrapColors.primary, RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
internal fun StudioNumericInput(label: String, value: String, modifier: Modifier = Modifier, change: (String) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused || draft.toDoubleOrNull() != value.toDoubleOrNull()) draft = value }
    BasicTextField(draft, { draft = it; change(it) }, modifier
        .semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused; if (!focused) draft = value }
        .border(1.dp, if (focused) DesktopBootstrapColors.primary else DesktopBootstrapColors.border, RoundedCornerShape(4.dp))
        .background(DesktopBootstrapColors.input).padding(6.dp), singleLine = true,
        textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 13.sp))
}

internal fun studioAspectOptions(settings: NovelAiGenerationSettings) = NovelAiAspectRatio.entries.filterNot {
    settings.sizeTier == NovelAiSizeTier.WALLPAPER && it == NovelAiAspectRatio.SQUARE
}
internal fun studioAdvancedSummary(s: NovelAiGenerationSettings): String =
    "${s.steps} Steps · CFG ${studioNumericText(s.guidance, 1)} / ${studioNumericText(s.cfgRescale, 2)} · ${s.sampler.displayName} · ${if (s.seedMode == NovelAiSeedMode.RANDOM) "Random Seed" else "Seed ${s.seed}"}"

@Composable
internal fun StudioGenerationSettings(draft: NovelAiStudioDraft, edit: ((NovelAiStudioDraft) -> NovelAiStudioDraft) -> Unit) {
    val s = draft.activeSettings
    fun change(transform: (NovelAiGenerationSettings) -> NovelAiGenerationSettings) = edit { it.withActiveSettings(transform(it.activeSettings)) }
    var customSize by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card, RoundedCornerShape(8.dp))
        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    CompactChoice("模型", listOf<NovelAiImageModel?>(null) + NovelAiImageModel.entries,
        if (draft.followDefaultNovelAiImageModel) null else draft.selectedModel, { it?.displayName ?: "跟随角色卡 / 全局" }) { model ->
        edit { if (model == null) it.copy(followDefaultNovelAiImageModel = true) else it.copy(selectedModel = model, followDefaultNovelAiImageModel = false) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val size: @Composable () -> Unit = {
            StudioChips("尺寸", NovelAiSizeTier.entries, s.sizeTier.takeUnless { s.usesCustomSize }, { it.displayName }) { value -> change { it.copy(sizeTier = value, customWidth = null, customHeight = null) } }
        }
        val count: @Composable () -> Unit = { StudioChips("数量", (1..4).toList(), s.count, { it.toString() }) { value -> change { it.copy(count = value) } } }
        if (maxWidth >= 460.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { size() }; count()
        } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { size(); count() }
    }
    val pixels = runCatching { s.imageSize().let { "${it.width} × ${it.height}" } }.getOrDefault("尺寸待修正")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { StatusText("分辨率 · $pixels${if (s.usesCustomSize) " · 自定义" else ""}") }
        StudioAction("编辑尺寸", icon = DesktopAppIcons.Edit) { customSize = true }
    }
    StudioChips("", studioAspectOptions(s), s.normalized().aspectRatio.takeUnless { s.usesCustomSize }, { it.displayName }) { value -> change { it.copy(aspectRatio = value, customWidth = null, customHeight = null) } }
    StudioDisclosure("高级设置", studioAdvancedSummary(s)) {
        StudioAdvancedSettings(s, ::change)
    }
    StudioSwitch("连续生成", draft.continuousModeEnabled) { edit { it.copy(continuousModeEnabled = !it.continuousModeEnabled) } }
    if (draft.continuousModeEnabled) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("目标图片数")
        StudioNumericInput("目标图片数", draft.continuousTargetCount.toString(), Modifier.width(80.dp)) { raw -> raw.toIntOrNull()?.takeIf { it > 0 }?.let { value -> edit { it.copy(continuousTargetCount = value) } } }
    }
    s.validationError(draft.characters.size)?.let { StatusText(it, DesktopBootstrapColors.warning) }
    }
    if (customSize) StudioCustomSizeDialog(s, onClose = { customSize = false }) { width, height ->
        change { it.copy(customWidth = width, customHeight = height) }; customSize = false
    }
}

@Composable
internal fun StudioAdvancedSettings(s: NovelAiGenerationSettings, change: ((NovelAiGenerationSettings) -> NovelAiGenerationSettings) -> Unit) {
    StudioSlider("Steps", s.steps.toFloat(), 1f, 50f, 1f, 0) { value -> change { it.copy(steps = value.toInt()) } }
    StudioSlider("CFG Scale", s.guidance, 1f, 10f, .1f, 1) { value -> change { it.copy(guidance = value) } }
    StudioSlider("CFG Rescale", s.cfgRescale, 0f, 1f, .05f, 2) { value -> change { it.copy(cfgRescale = value) } }
    CompactChoice("Sampler", NovelAiSampler.entries, s.sampler, { it.displayName }) { value -> change { it.copy(sampler = value) } }
    StudioSwitch("Random Seed", s.seedMode == NovelAiSeedMode.RANDOM) { change { it.copy(seedMode = if (it.seedMode == NovelAiSeedMode.RANDOM) NovelAiSeedMode.FIXED else NovelAiSeedMode.RANDOM) } }
    if (s.seedMode == NovelAiSeedMode.FIXED) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("Seed")
        StudioNumericInput("固定 Seed", s.seed.toString(), Modifier.width(160.dp)) { raw -> raw.toLongOrNull()?.let { value -> change { it.copy(seed = value) } } }
    }
}

@Composable
internal fun StudioCustomSizeDialog(settings: NovelAiGenerationSettings, onClose: () -> Unit, apply: (Int?, Int?) -> Unit) {
    DialogWindow(onCloseRequest = onClose, title = "自定义分辨率", state = rememberDialogState(width = 420.dp, height = 300.dp)) {
        StudioCustomSizeEditor(settings, onClose, apply)
    }
}

@Composable
internal fun StudioCustomSizeEditor(settings: NovelAiGenerationSettings, onClose: () -> Unit, apply: (Int?, Int?) -> Unit) {
    val pixels = runCatching { settings.imageSize() }.getOrNull()
    var width by remember { mutableStateOf((settings.customWidth ?: pixels?.width)?.toString().orEmpty()) }
    var height by remember { mutableStateOf((settings.customHeight ?: pixels?.height)?.toString().orEmpty()) }
    val error = NovelAiStudioSizePolicy.validationError(width.toIntOrNull(), height.toIntOrNull())
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusText("自定义分辨率")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { StatusText("宽度"); StudioNumericInput("宽度", width, Modifier.fillMaxWidth()) { width = it } }
                Column(Modifier.weight(1f)) { StatusText("高度"); StudioNumericInput("高度", height, Modifier.fillMaxWidth()) { height = it } }
            }
            error?.let { StatusText(it, DesktopBootstrapColors.warning) }
            StudioActions {
                StudioAction("应用", enabled = error == null) { apply(width.toIntOrNull(), height.toIntOrNull()) }
                StudioAction("恢复预设") { apply(null, null) }
                StudioAction("取消", onClick = onClose)
            }
        }
}
