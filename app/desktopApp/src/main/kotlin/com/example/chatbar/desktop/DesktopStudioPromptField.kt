package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
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

/** Use the source font metrics, not the paragraph's extra annotation leading. */
internal fun studioPromptCaretRect(layout: TextLayoutResult, offset: Int, glyph: TextLayoutResult): Rect {
    val position = offset.coerceIn(0, layout.layoutInput.text.length)
    val cursor = layout.getCursorRect(position)
    val baseline = layout.getLineBaseline(layout.getLineForOffset(position))
    val top = baseline - glyph.getLineBaseline(0)
    return Rect(cursor.left, top, cursor.right, top + glyph.size.height)
}

/** The visible part of the source glyph band; annotation leading never participates. */
internal fun studioPromptSourceBand(layout: TextLayoutResult, offset: Int, glyph: TextLayoutResult): Rect {
    val rect = studioPromptCaretRect(layout, offset, glyph)
    val line = layout.getLineForOffset(offset.coerceIn(0, layout.layoutInput.text.length))
    val top = rect.top.coerceIn(layout.getLineTop(line), layout.getLineBottom(line))
    val bottom = rect.bottom.coerceIn(top, layout.getLineBottom(line))
    return Rect(rect.left, top, rect.right, bottom)
}

/** Raw source remains the editable/IME text; translations are only glyph overlays. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun StudioPromptTextEditor(input: TextFieldValue, onChange: (TextFieldValue) -> Unit, translated: Boolean,
    annotations: List<NovelAiPromptAnnotation>, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    var caretVisible by remember { mutableStateOf(true) }
    val caretColor = DesktopBootstrapColors.foreground
    val normalCaret = DesktopBootstrapColors.inputCaret
    val measurer = rememberTextMeasurer()
    val glyph = measurer.measure(AnnotatedString("M"), TextStyle(fontSize = 14.sp))
    val latestInput by rememberUpdatedState(input)
    val latestGlyph by rememberUpdatedState(glyph)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val caretRequester = remember { BringIntoViewRequester() }
    val sourceCaret = layout?.takeIf { it.layoutInput.text.text == input.text }
        ?.let { studioPromptSourceBand(it, input.selection.max, glyph) }
    val liveCaret by rememberUpdatedState(sourceCaret)
    LaunchedEffect(focused, sourceCaret) {
        if (focused && sourceCaret != null) caretRequester.bringIntoView(sourceCaret)
    }
    // The legacy adapter caches its field-to-root transform. Query live coordinates when the
    // platform requests the candidate rectangle, including after an ancestor scroll/move.
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(object : PlatformTextInputMethodRequest by request {
                override val focusedRectInRoot: () -> Rect? = {
                    val current = layout?.takeIf { it.layoutInput.text.text == latestInput.text }
                    val origin = coordinates?.takeIf { it.isAttached }?.localToRoot(Offset.Zero)
                    if (current != null && origin != null)
                        studioPromptSourceBand(current, latestInput.selection.max, latestGlyph).translate(origin)
                    else request.focusedRectInRoot()
                }
            })
        }
    }
    val selectionColors = LocalTextSelectionColors.current
    LaunchedEffect(focused, translated, input.text, input.selection) {
        caretVisible = true
        if (focused && translated && input.selection.collapsed) while (true) {
            kotlinx.coroutines.delay(500)
            caretVisible = !caretVisible
        }
    }
    InterceptPlatformTextInput(interceptor) {
    CompositionLocalProvider(LocalTextSelectionColors provides if (translated)
        TextSelectionColors(selectionColors.handleColor, Color.Transparent) else selectionColors) {
    BasicTextField(input, onChange, modifier.studioPromptCaretBoundary { liveCaret }
        .bringIntoViewRequester(caretRequester).onFocusChanged { focused = it.isFocused },
        cursorBrush = if (translated) SolidColor(Color.Transparent) else normalCaret,
        textStyle = TextStyle(color = caretColor, fontSize = 14.sp,
            lineHeight = if (translated) 34.sp else 22.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None)),
        onTextLayout = { layout = it }, decorationBox = { inner ->
            Box(Modifier.onGloballyPositioned { coordinates = it }) {
                if (translated && !input.selection.collapsed) {
                    val current = layout?.takeIf { it.layoutInput.text.text == input.text }
                    if (current != null) Canvas(Modifier.matchParentSize()) {
                        val path = current.getPathForRange(input.selection.min, input.selection.max)
                        for (line in 0 until current.lineCount) {
                            val band = studioPromptSourceBand(current, current.getLineStart(line), glyph)
                            clipRect(top = band.top, bottom = band.bottom) {
                                drawPath(path, selectionColors.backgroundColor)
                            }
                        }
                    }
                }
                inner()
                if (translated) {
                    StudioPromptAnnotationOverlay(input.text, layout, annotations, Modifier.matchParentSize())
                    val current = layout?.takeIf { it.layoutInput.text.text == input.text }
                    if (focused && input.selection.collapsed && caretVisible && current != null) {
                        val rect = studioPromptSourceBand(current, input.selection.end, glyph)
                        Canvas(Modifier.matchParentSize()) {
                            drawLine(caretColor, Offset(rect.left, rect.top), Offset(rect.left, rect.bottom), strokeWidth = 2.dp.toPx())
                        }
                    }
                }
            }
        })
    }
    }
}

internal val LocalDesktopPromptAnnotationRevision = staticCompositionLocalOf { 0L }

@Composable
internal fun StudioPromptField(label: String, value: String, infrastructure: DesktopNovelAiInfrastructure,
    translated: Boolean, focusRevision: Long? = null, onEditorFocusChanged: ((Boolean) -> Unit)? = null,
    onExpandedEditorChanged: ((Boolean) -> Unit)? = null,
    inlineHeader: (@Composable (onExpand: () -> Unit) -> Unit)? = null, compact: Boolean = false,
    onChange: (String) -> Unit) = StudioPromptField(label, value,
    infrastructure.suggestions, infrastructure.translations, translated, focusRevision, onEditorFocusChanged, onExpandedEditorChanged, inlineHeader, compact, onChange)

@Composable
internal fun StudioPromptField(label: String, value: String, suggestionService: NovelAiTagSuggestionService,
    translationService: NovelAiPromptTranslationService, translated: Boolean, focusRevision: Long? = null,
    onEditorFocusChanged: ((Boolean) -> Unit)? = null, onExpandedEditorChanged: ((Boolean) -> Unit)? = null,
    inlineHeader: (@Composable (onExpand: () -> Unit) -> Unit)? = null, compact: Boolean = false,
    onChange: (String) -> Unit) {
    val inlineHistory = rememberStudioTextHistory(label, value)
    var expandedHistory by remember { mutableStateOf<DesktopStudioTextHistory?>(null) }
    val editor = expandedHistory ?: inlineHistory
    val input = editor.value
    var focused by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var openingValue by remember { mutableStateOf(value) }
    var dismissedQuery by remember { mutableStateOf<String?>(null) }
    var suggestionIndex by remember { mutableStateOf(0) }
    var inspecting by remember { mutableStateOf(true) }
    val inspected = NovelAiTagCompletion.inspectedTag(input.text, input.selection.end)
    val fragment = if (input.composition != null || !input.selection.collapsed) null else
        if (inspecting) inspected else NovelAiTagCompletion.activeFragment(input.text, input.selection.end)
            ?.takeIf { NovelAiPromptTranslationParser.activeSegment(input.text, input.selection.end, false)?.kind == NovelAiPromptTranslationSegmentKind.TAG }
    LaunchedEffect(value) {
        if (fullscreen && value != openingValue) { fullscreen = false; expandedHistory = null; onExpandedEditorChanged?.invoke(false) }
    }
    LaunchedEffect(fragment?.query) { suggestionIndex = 0; dismissedQuery = null }
    val suggestionResult by produceState<Pair<String, TagSuggestionUpdate>?>(null, focused, fragment?.query) {
        this.value = null
        if (focused && fragment != null) suggestionService.observe(fragment.query).collect { this@produceState.value = fragment.query to it }
    }
    val suggestions = suggestionResult?.takeIf { focused && it.first == fragment?.query }?.second ?: TagSuggestionUpdate(loading = false)
    val source = input.text
    val authorityRevision = LocalDesktopPromptAnnotationRevision.current
    val replayRevision = LocalDesktopStudioTextHistories.current?.authorityRevision ?: 0L
    val annotations by produceState<Pair<String, NovelAiPromptTranslationResult>?>(null, source, translated, translationService, authorityRevision, replayRevision) {
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
            editor.edit(TextFieldValue(result.text, TextRange(result.cursor))) { if (!fullscreen) onChange(it) }
        }
    }
    fun cancel() { fullscreen = false; expandedHistory = null; onExpandedEditorChanged?.invoke(false) }
    @Composable fun Editor() {
        val focus = remember { FocusRequester() }
        LaunchedEffect(fullscreen, focusRevision) { if (fullscreen || focusRevision != null) focus.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fun expand() {
                openingValue = value; expandedHistory = inlineHistory.fork(); fullscreen = true; onExpandedEditorChanged?.invoke(true)
            }
            if (!fullscreen && inlineHeader != null) inlineHeader(::expand)
            else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusText(label) }
                if (!fullscreen) StudioAction("展开", icon = DesktopAppIcons.ExpandComposer, onClick = ::expand)
            }
            Box {
                Box(Modifier.fillMaxWidth().heightIn(max = if (fullscreen) 450.dp else 220.dp)
                    .border(1.dp, if (focused) DesktopBootstrapColors.primary else DesktopBootstrapColors.border)
                    .background(DesktopBootstrapColors.input).studioPromptViewportBoundary()
                    .verticalScroll(rememberScrollState()).padding(9.dp)) {
                    StudioPromptTextEditor(input, { next -> inspecting = next.text == input.text; editor.edit(next) { if (!fullscreen) onChange(it) } }, translated, currentAnnotations?.annotations.orEmpty(), Modifier.fillMaxWidth()
                        .heightIn(min = if (fullscreen) 320.dp else if (compact) 0.dp else 70.dp).focusRequester(focus)
                        .semantics { contentDescription = label }.studioTextHistoryKeys(editor) { if (!fullscreen) onChange(it) }.onFocusChanged {
                            focused = it.isFocused; onEditorFocusChanged?.invoke(it.isFocused)
                        }
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
    if (!fullscreen) Editor() else DesktopImageToolWindow(label, ::cancel, width = 850.dp, height = 650.dp,
        closeOnEscape = false) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).onKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { cancel(); true } else false
        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { Editor() }
            StudioActions {
                StudioAction("确认") { if (value == openingValue) inlineHistory.applyExpanded(input, onChange); fullscreen = false; expandedHistory = null; onExpandedEditorChanged?.invoke(false) }
                StudioAction("取消", onClick = ::cancel)
            }
        }
    }
}
