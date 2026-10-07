package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.example.chatbar.domain.image.*
import kotlin.math.roundToInt

internal data class StudioAnnotationSlot(val line: Int, val left: Float, val right: Float, val baseline: Float)
internal data class StudioAnnotationPlacement(val annotation: NovelAiPromptAnnotation, val slots: List<StudioAnnotationSlot>)

/** Match current text before using parser offsets; async results never paint onto edited text. */
internal fun studioAnnotationPlacements(layout: TextLayoutResult, annotations: List<NovelAiPromptAnnotation>): List<StudioAnnotationPlacement> {
    val text = layout.layoutInput.text.text
    return annotations.filter { it.start >= 0 && it.end <= text.length && it.start < it.end &&
        text.substring(it.start, it.end) == it.source && it.translation.isNotBlank() }.map { annotation ->
        val first = layout.getLineForOffset(annotation.start)
        val last = layout.getLineForOffset(annotation.end - 1)
        StudioAnnotationPlacement(annotation, (first..last).map { line ->
            StudioAnnotationSlot(line,
                if (line == first) layout.getBoundingBox(annotation.start).left else layout.getLineLeft(line),
                if (line == last) layout.getBoundingBox(annotation.end - 1).right else layout.getLineRight(line), layout.getLineBaseline(line))
        })
    }
}

@Composable
internal fun StudioPromptAnnotationOverlay(text: String, layout: TextLayoutResult?, annotations: List<NovelAiPromptAnnotation>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 10.sp)
    val draws = remember(text, layout, annotations, measurer, style) {
        if (layout == null || layout.layoutInput.text.text != text) emptyList() else
            studioAnnotationPlacements(layout, annotations).flatMap { placement ->
                var remaining = placement.annotation.translation
                placement.slots.mapIndexedNotNull { index, slot ->
                    if (remaining.isEmpty()) null else {
                        val last = index == placement.slots.lastIndex
                        val shaped = measurer.measure(AnnotatedString(remaining), style, maxLines = 1,
                            overflow = if (last) TextOverflow.Ellipsis else TextOverflow.Clip,
                            constraints = Constraints(maxWidth = (slot.right - slot.left - 1f).roundToInt().coerceAtLeast(1)))
                        val consumed = if (last) remaining.length else shaped.getLineEnd(0, visibleEnd = true).coerceIn(1, remaining.length)
                        remaining = remaining.substring(consumed)
                        slot to shaped
                    }
                }
            }
    }
    Canvas(modifier) { draws.forEach { (slot, shaped) -> drawText(shaped, topLeft = Offset(slot.left, slot.baseline + 4.dp.toPx())) } }
}

internal fun studioCanAcceptSuggestion(input: TextFieldValue, candidateCount: Int): Boolean =
    input.composition == null && input.selection.collapsed && candidateCount > 0

/** The same raw-text editor and glyph overlay are used inline and inside the fullscreen session. */
@Composable
internal fun StudioPromptTextEditor(input: TextFieldValue, onChange: (TextFieldValue) -> Unit, translated: Boolean,
    annotations: List<NovelAiPromptAnnotation>, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicTextField(input, onChange, modifier, textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp,
        lineHeight = if (translated) 34.sp else 22.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None)),
        onTextLayout = { layout = it }, decorationBox = { inner ->
            Box { inner(); if (translated) StudioPromptAnnotationOverlay(input.text, layout, annotations, Modifier.matchParentSize()) }
        })
}

@Composable
internal fun StudioPromptField(label: String, value: String, infrastructure: DesktopNovelAiInfrastructure,
    translated: Boolean, onChange: (String) -> Unit) = StudioPromptField(label, value,
    infrastructure.suggestions, infrastructure.translations, translated, onChange)

@Composable
internal fun StudioPromptField(label: String, value: String, suggestionService: NovelAiTagSuggestionService,
    translationService: NovelAiPromptTranslationService, translated: Boolean, onChange: (String) -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var openingValue by remember { mutableStateOf(value) }
    var openingInput by remember { mutableStateOf(input) }
    var dismissedQuery by remember { mutableStateOf<String?>(null) }
    var suggestionIndex by remember { mutableStateOf(0) }
    var inspecting by remember { mutableStateOf(true) }
    val inspected = NovelAiTagCompletion.inspectedTag(input.text, input.selection.end)
    val fragment = if (input.composition != null || !input.selection.collapsed) null else
        if (inspecting) inspected else NovelAiTagCompletion.activeFragment(input.text, input.selection.end)
            ?.takeIf { NovelAiPromptTranslationParser.activeSegment(input.text, input.selection.end, false)?.kind == NovelAiPromptTranslationSegmentKind.TAG }
    LaunchedEffect(value) {
        if (!fullscreen) input = desktopComposerDraftEcho(input, value)
        else if (value != openingValue) { fullscreen = false; input = TextFieldValue(value) }
    }
    LaunchedEffect(fragment?.query) { suggestionIndex = 0; dismissedQuery = null }
    val suggestionResult by produceState<Pair<String, TagSuggestionUpdate>?>(null, focused, fragment?.query) {
        this.value = null
        if (focused && fragment != null) suggestionService.observe(fragment.query).collect { this@produceState.value = fragment.query to it }
    }
    val suggestions = suggestionResult?.takeIf { focused && it.first == fragment?.query }?.second ?: TagSuggestionUpdate(loading = false)
    val source = input.text
    val annotations by produceState<Pair<String, NovelAiPromptTranslationResult>?>(null, source, translated) {
        this.value = null
        if (translated) this.value = source to try {
            translationService.resolve(NovelAiPromptTranslationParser.parse(source, false))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { NovelAiPromptTranslationResult(emptyList(), warning = "中文注释读取失败；Prompt 原文已保留，可继续编辑") }
    }
    val currentAnnotations = annotations?.takeIf { translated && it.first == input.text }?.second
    val candidates = suggestions.candidates.takeIf { suggestionService.isCurrent(suggestions) }.orEmpty()
        .let { values -> if (inspecting) values.sortedBy { !it.name.equals(fragment?.query, ignoreCase = true) } else values }
    val showSuggestions = focused && fragment != null && dismissedQuery != fragment.query && (candidates.isNotEmpty() || suggestions.loading || suggestions.error != null)
    fun accept(index: Int) {
        if (!studioCanAcceptSuggestion(input, candidates.size) || !suggestionService.isCurrent(suggestions)) return
        candidates.getOrNull(index)?.let { tag ->
            val target = inspected ?: fragment ?: return
            val result = NovelAiTagCompletion.replaceTag(input.text, target, tag.name)
            input = TextFieldValue(result.text, TextRange(result.cursor))
            if (!fullscreen) onChange(result.text)
        }
    }
    fun cancel() { fullscreen = false; input = if (value == openingValue) openingInput else TextFieldValue(value) }
    @Composable fun Editor() {
        val focus = remember { FocusRequester() }
        if (fullscreen) LaunchedEffect(Unit) { focus.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusText(label) }
                if (!fullscreen) StudioAction("展开", icon = DesktopAppIcons.ExpandComposer) {
                    openingValue = value; openingInput = input; fullscreen = true
                }
            }
            Box {
                Box(Modifier.fillMaxWidth().heightIn(max = if (fullscreen) 450.dp else 220.dp)
                    .border(1.dp, if (focused) DesktopBootstrapColors.primary else DesktopBootstrapColors.border)
                    .background(DesktopBootstrapColors.input).verticalScroll(rememberScrollState()).padding(9.dp)) {
                    StudioPromptTextEditor(input, { next -> inspecting = next.text == input.text; input = next; if (!fullscreen) onChange(next.text) }, translated, currentAnnotations?.annotations.orEmpty(), Modifier.fillMaxWidth()
                        .heightIn(min = if (fullscreen) 320.dp else 70.dp).focusRequester(focus)
                        .semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (!showSuggestions || event.type != KeyEventType.KeyDown || input.composition != null) false else when (event.key) {
                                Key.Escape -> { dismissedQuery = fragment?.query; true }
                                Key.DirectionDown -> { suggestionIndex = (suggestionIndex + 1).coerceAtMost(candidates.lastIndex.coerceAtLeast(0)); true }
                                Key.DirectionUp -> { suggestionIndex = (suggestionIndex - 1).coerceAtLeast(0); true }
                                Key.Enter, Key.NumPadEnter, Key.Tab -> if (studioCanAcceptSuggestion(input, candidates.size) && !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed) { accept(suggestionIndex); true } else false
                                else -> false
                            }
                        })
                }
                if (showSuggestions) Popup(popupPositionProvider = StudioPopupPosition, onDismissRequest = { dismissedQuery = fragment?.query },
                    properties = PopupProperties(focusable = false)) {
                    val list = rememberLazyListState()
                    LaunchedEffect(suggestionIndex, candidates.size) { if (candidates.isNotEmpty()) list.scrollToItem(suggestionIndex.coerceIn(candidates.indices)) }
                    Column(Modifier.widthIn(min = 260.dp, max = 400.dp).background(DesktopBootstrapColors.card).border(1.dp, DesktopBootstrapColors.border).padding(4.dp)) {
                        StatusText(if (inspecting) "查看完整 Tag · 选择才替换" else "Tag 补全")
                        if (suggestions.loading) StatusText("查询本地词库…")
                        suggestions.error?.let { StatusText(it, DesktopBootstrapColors.warning) }
                        LazyColumn(Modifier.heightIn(max = 160.dp), state = list) { itemsIndexed(candidates, key = { _, tag -> tag.name }) { index, tag ->
                            Column(Modifier.fillMaxWidth().background(if (index == suggestionIndex) DesktopBootstrapColors.accent else Color.Transparent)
                                .clickable { accept(index) }.padding(7.dp)) {
                                StatusText(tag.name + if (inspecting && tag.name.equals(fragment?.query, true)) " · 精确匹配" else "")
                                StatusText(tag.translatedName.orEmpty(), DesktopBootstrapColors.mutedForeground)
                                StatusText(if (tag.fromDictionary) "本地词典" else "${tag.category} · ${tag.count} 张", DesktopBootstrapColors.mutedForeground)
                            }
                        } }
                    }
                }
            }
            currentAnnotations?.warning?.let { StatusText(it, DesktopBootstrapColors.warning) }
        }
    }
    if (!fullscreen) Editor() else DialogWindow(onCloseRequest = ::cancel, title = label,
        state = rememberDialogState(width = 850.dp, height = 650.dp)) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { Editor() }
            StudioActions {
                StudioAction("确认") { if (value == openingValue) onChange(input.text); fullscreen = false }
                StudioAction("取消", onClick = ::cancel)
            }
        }
    }
}
