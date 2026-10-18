package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.chatbar.domain.voice.*

private enum class FishTools { ADVANCED, GENERATE }

/** Presentation selection is NOT a CharacterInfo binding or a preview request.
 * All request/binding clicks revalidate the original catalog snapshot, including after resize. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopFishPickerContent(controller: DesktopFishPickerController, configured: Boolean,
    onClose: () -> Unit, preview: DesktopFishPreviewRuntime.Session? = null, onUse: (FishAudioModel) -> Unit) {
    val state by controller.state.collectAsState()
    val query = state.query
    val audio = preview?.state?.collectAsState()?.value ?: DesktopFishPreviewState()
    var target by remember(controller) { mutableStateOf<FishAudioModel?>(null) }
    var panel by remember(controller) { mutableStateOf<FishTools?>(null) }
    var text by remember(controller) { mutableStateOf("") }
    val close by rememberUpdatedState(onClose)
    val fallbackEscape = remember { DesktopFishPickerEscape { close() } }
    val escape = LocalDesktopFishPickerEscape.current ?: fallbackEscape
    val list = rememberLazyListState()
    var pageInput by remember(controller) { mutableStateOf("") }
    val pageSize = query.pageSize.coerceAtLeast(1)
    val pageCount = state.page.total.takeIf { it > 0 }?.let { (it.toLong() + pageSize - 1) / pageSize }
    val requestedPage = pageInput.toIntOrNull()?.takeIf { it > 0 && (pageCount == null || it.toLong() <= pageCount) }
    fun jump() { if (configured && !state.loading && requestedPage != null) controller.search(query.copy(pageNumber = requestedPage)) }
    LaunchedEffect(query.pageNumber) { list.scrollToItem(0) }
    DisposableEffect(escape, panel) {
        val dismiss: (() -> Unit)? = if (panel == null) null else ({ panel = null })
        escape.dismissPanel = dismiss
        onDispose { if (escape.dismissPanel === dismiss) escape.dismissPanel = null }
    }
    val current = target?.takeIf { configured && controller.selectable(it.id) == it }

    @Composable fun previewControls() {
        if (preview != null && (audio.busy || audio.playing || audio.message != null)) {
            Row(Modifier.fillMaxWidth().background(DesktopBootstrapColors.muted, RoundedCornerShape(6.dp))
                .padding(6.dp).semantics { contentDescription = "Fish 预览状态" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    FishCaption(when {
                        audio.playing -> "正在播放预览"
                        audio.busy && audio.kind == DesktopFishPreviewKind.OFFICIAL_SAMPLE -> "正在下载官方样本…"
                        audio.busy -> "正在生成免费预览…"
                        else -> "预览已停止"
                    }, maxLines = 1)
                    audio.message?.let { FishCaption(it, maxLines = 2) }
                }
                if (audio.busy || audio.playing) StudioAction("停止预览", icon = DesktopAppIcons.Stop,
                    style = StudioActionStyle.PRIMARY) { preview.stop() }
            }
        }
    }

    @Composable fun search() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { DesktopFishField("音色标题", query.title) {
                controller.invalidate(query.copy(title = it, pageNumber = 1))
            } }
            FishAction("查询 / 重试", "查询 / 重试", icon = DesktopAppIcons.Refresh,
                enabled = configured, style = StudioActionStyle.TERTIARY) { controller.search() }
        }
    }
    @Composable fun tools() {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioAction("高级筛选", enabled = configured, selected = panel == FishTools.ADVANCED,
                icon = if (panel == FishTools.ADVANCED) DesktopAppIcons.DetailsOpen else DesktopAppIcons.DetailsClosed,
                style = StudioActionStyle.GHOST) { panel = if (panel == FishTools.ADVANCED) null else FishTools.ADVANCED }
            if (preview != null) StudioAction("免费文本生成", selected = panel == FishTools.GENERATE,
                icon = if (panel == FishTools.GENERATE) DesktopAppIcons.DetailsOpen else DesktopAppIcons.DetailsClosed,
                style = StudioActionStyle.GHOST) { panel = if (panel == FishTools.GENERATE) null else FishTools.GENERATE }
        }
    }
    @Composable fun toolBody() {
        when (panel) {
            FishTools.ADVANCED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopFishField("标签（逗号分隔）", query.tags.joinToString(",")) {
                    controller.invalidate(query.copy(tags = it.split(',').map(String::trim), pageNumber = 1))
                }
                DesktopFishField("语言（逗号分隔）", query.languages.joinToString(",")) {
                    controller.invalidate(query.copy(languages = it.split(',').map(String::trim), pageNumber = 1))
                }
                CompactChoice("排序", FishAudioModelSort.entries, query.sort, {
                    when (it) { FishAudioModelSort.RECOMMENDED -> "推荐"; FishAudioModelSort.POPULAR -> "热度"; FishAudioModelSort.LATEST -> "最新" }
                }) { controller.invalidate(query.copy(sort = it, pageNumber = 1)) }
            }
            FishTools.GENERATE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FishCaption(current?.let { "预览目标 · ${it.title}" } ?: "先点击音色卡，设为预览目标")
                DesktopFishField("免费预览文本（1–120 字）", text) { text = it }
                FishCaption("仅 s2.1-pro-free；点击生成即授权本次免费请求。首次结果在本音色库内复用，修改文字不会重新生成。", maxLines = 5)
                FishAction("生成 / 重播免费预览", "生成 / 重播免费预览", icon = DesktopAppIcons.Play,
                    enabled = preview != null && current != null && text.isNotBlank() &&
                        text.codePointCount(0, text.length) <= 120 && !audio.busy && !audio.playing) {
                    val submitted = current
                    if (configured && submitted != null && controller.selectable(submitted.id) == submitted)
                        preview?.generate(submitted.id, text)
                }
            }
            null -> Unit
        }
    }
    @Composable fun results(modifier: Modifier) {
        Column(modifier.semantics { contentDescription = "Fish 音色结果" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loading) FishCaption("正在查询…")
            else if (state.error != null) StatusText(state.error!!, DesktopBootstrapColors.destructive)
            else if (state.page.items.isEmpty()) FishCaption("暂无音色；请查询或调整筛选")
            else FishCaption("${state.page.total} 个音色 · 点击卡片设为预览目标")
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 14.dp).clipToBounds()
                .semantics { contentDescription = "Fish 音色列表" }, state = list,
                contentPadding = PaddingValues(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.page.items, key = { it.id }) { model ->
                    FishVoiceCard(model, selected = current == model,
                        enabled = configured && controller.selectable(model.id) == model,
                        hasRuntime = preview != null,
                        onTarget = { if (configured && controller.selectable(model.id) == model) target = model },
                        onSample = { sample ->
                            if (configured && controller.selectable(model.id) == model) {
                                target = model
                                preview?.officialSample(model.id, sample.previewUrl)
                            }
                        }, onUse = {
                            if (configured && controller.selectable(model.id) == model) onUse(model)
                        })
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                .semantics { contentDescription = "音色列表滚动条" },
                style = LocalScrollbarStyle.current.copy(unhoverColor = DesktopBootstrapColors.mutedForeground,
                    hoverColor = DesktopBootstrapColors.foreground))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioAction("上一页", enabled = configured && !state.loading && query.pageNumber > 1,
                    icon = DesktopAppIcons.Previous, style = StudioActionStyle.TERTIARY) {
                    controller.search(query.copy(pageNumber = query.pageNumber - 1))
                }
                FishCaption("第 ${query.pageNumber} 页" + (pageCount?.let { " / 共 $it 页" } ?: ""), Modifier.weight(1f))
                StudioAction("下一页", enabled = configured && !state.loading && state.hasNext && query.pageNumber < Int.MAX_VALUE,
                    icon = DesktopAppIcons.Next, style = StudioActionStyle.TERTIARY) {
                    if (query.pageNumber < Int.MAX_VALUE) controller.search(query.copy(pageNumber = query.pageNumber + 1))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(88.dp).onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) { jump(); true } else false
                }) { DesktopFishField("跳转页码", pageInput) { pageInput = it } }
                StudioAction("跳转", enabled = configured && !state.loading && requestedPage != null,
                    style = StudioActionStyle.TERTIARY) { jump() }
                if (pageInput.isNotBlank() && requestedPage == null) FishCaption("请输入有效页码", Modifier.weight(1f))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onPreviewKeyEvent(escape::handle)) {
        val wide = maxWidth >= 800.dp
        val toolsWidth = (maxWidth - 24.dp).coerceIn(0.dp, 360.dp)
        val toolsHeight = (maxHeight - 24.dp).coerceAtLeast(80.dp)
        Column(Modifier.fillMaxSize().semantics { contentDescription = if (wide) "Fish 宽屏分栏" else "Fish 紧凑单列" },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FishAudioLibrary.entries.forEach { library ->
                        StudioAction(if (library == FishAudioLibrary.MINE) "我的音色" else "社区音色",
                            enabled = configured, selected = query.library == library, style = StudioActionStyle.CHIP) {
                            controller.search(query.copy(library = library, pageNumber = 1))
                        }
                    }
                }
                FishAction("关闭", "取消 / 关闭音色库", icon = DesktopAppIcons.Close,
                    style = StudioActionStyle.GHOST, onClick = onClose)
            }
            if (wide || panel == null) previewControls()
            if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(288.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                    .semantics { contentDescription = "Fish 搜索设置" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    search(); tools(); toolBody()
                }
                results(Modifier.weight(1f).fillMaxHeight())
            } else {
                search(); tools()
                results(Modifier.weight(1f).fillMaxWidth())
            }
        }
        // Compact settings overlay the list instead of consuming its remaining height.
        // Draft text/target/panel live above the responsive branch, so resizing never sends work.
        if (!wide && panel != null) Popup(alignment = Alignment.Center, onDismissRequest = { panel = null },
            properties = PopupProperties(focusable = true, dismissOnBackPress = false), onPreviewKeyEvent = escape::handle) {
            Column(Modifier.width(toolsWidth).heightIn(max = toolsHeight)
                .background(DesktopBootstrapColors.card, RoundedCornerShape(8.dp))
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(12.dp)
                .semantics { contentDescription = "Fish 设置面板" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioAction("返回音色列表", icon = DesktopAppIcons.Previous, style = StudioActionStyle.GHOST) { panel = null }
                previewControls()
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .semantics { contentDescription = "Fish 折叠设置内容" }) { toolBody() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FishVoiceCard(model: FishAudioModel, selected: Boolean, enabled: Boolean, hasRuntime: Boolean,
    onTarget: () -> Unit, onSample: (FishAudioSample) -> Unit, onUse: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sample = model.samples.firstOrNull { it.previewUrl.isNotBlank() }
    val approved = sample?.let { runCatching { DesktopFishSamplePolicy.approve(it.previewUrl) }.isSuccess } == true
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().background(if (selected) DesktopBootstrapColors.accent else DesktopBootstrapColors.card, shape)
        .border(1.dp, if (selected || focused) DesktopBootstrapColors.primary else DesktopBootstrapColors.border, shape)
        .semantics { contentDescription = "预览目标 · ${model.title}" }
        .onFocusChanged { focused = it.isFocused }
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onTarget).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(model.title, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        FishCaption(listOf(model.author?.nickname ?: "未知作者", model.visibility ?: "未知权限",
            model.languages.joinToString("/"), model.tags.joinToString(", ")).filter(String::isNotBlank).joinToString(" · "))
        if (selected) FishCaption("已设为预览目标；尚未绑定")
        sample?.text?.takeIf(String::isNotBlank)?.let { FishCaption("官方示例文字 · $it") }
        if (sample == null) FishCaption("暂无官方样本")
        else if (!approved) FishCaption("官方样本来源或格式暂不受支持；不会下载或发送凭据")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FishAction("试听", "试听官方样本 · ${model.title}", enabled = enabled && hasRuntime && approved,
                icon = DesktopAppIcons.Play) { if (sample != null) onSample(sample) }
            FishAction("使用音色", "使用音色 · ${model.title}", enabled = enabled,
                icon = DesktopAppIcons.Check, style = StudioActionStyle.PRIMARY, onClick = onUse)
        }
    }
}

@Composable
private fun FishAction(label: String, description: String, enabled: Boolean = true,
    icon: ImageVector? = null, style: StudioActionStyle = StudioActionStyle.SECONDARY, onClick: () -> Unit) {
    StudioAction(label, enabled = enabled, icon = icon, style = style,
        accessibilityLabel = description, onClick = onClick)
}

@Composable
private fun FishCaption(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    BasicText(text, modifier, style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 12.sp),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
