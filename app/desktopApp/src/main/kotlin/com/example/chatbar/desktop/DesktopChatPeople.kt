@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class DesktopChatPeopleActions(
    val people: List<DesktopChatPerson>, val inspect: (DesktopChatPerson) -> Unit,
    val edit: (String, String) -> Unit, val previewEnabled: Boolean = true, val toggleEnabled: Boolean = true,
    val setEnabled: ((DesktopChatPerson, Boolean) -> Unit)? = null,
    val pinned: Set<String> = emptySet(), val pin: (DesktopChatPerson, Boolean) -> Unit = { _, _ -> },
    val preview: DesktopPeoplePreviewState = DesktopPeoplePreviewState(),
)
internal val LocalDesktopChatPeople = staticCompositionLocalOf<DesktopChatPeopleActions?> { null }

internal class DesktopPeoplePreviewState {
    var personId by mutableStateOf<String?>(null)
    var anchor by mutableStateOf(Rect.Zero)
    var held by mutableStateOf(false)
    var anchorHovered by mutableStateOf(false)
    var popupHovered by mutableStateOf(false)
    var anchorOwner: Any? = null
    fun show(person: DesktopChatPerson, bounds: Rect, hold: Boolean = false, owner: Any? = null) {
        if (!hold && held) return
        personId = person.character.id; anchor = bounds; held = hold; anchorHovered = true; anchorOwner = owner
    }
    fun close() { personId = null; held = false; anchorHovered = false; popupHovered = false; anchorOwner = null }
}

/** Stable partitions preserve card order within each group. Pinning never changes participation. */
internal fun desktopOrderedPeople(people: List<DesktopChatPerson>, pinned: Set<String>) =
    people.withIndex().sortedWith(compareBy<IndexedValue<DesktopChatPerson>>(
        { if (it.value.character.id in pinned) 0 else if (it.value.active) 1 else 2 }, { it.index })).map { it.value }
internal val DesktopChatPerson.active get() = enabled && resident && status != null
internal fun desktopVisiblePeople(people: List<DesktopChatPerson>, pinned: Set<String>, all: Boolean) =
    desktopOrderedPeople(people, pinned).filter { all || it.enabled }

internal class DesktopPersonPopupPosition(private val anchor: Rect) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val right = anchor.right.toInt() + 6
        val x = if (right + popupContentSize.width <= windowSize.width) right else anchor.left.toInt() - popupContentSize.width - 6
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            anchor.top.toInt().coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

@Composable internal fun DesktopPeoplePreview(actions: DesktopChatPeopleActions) {
    val preview = actions.preview
    val person = actions.people.firstOrNull { it.character.id == preview.personId } ?: return
    if (!actions.previewEnabled) return
    LaunchedEffect(preview.personId, preview.anchorHovered, preview.popupHovered, preview.held) {
        if (!preview.held && !preview.anchorHovered && !preview.popupHovered) { delay(220); preview.close() }
    }
    val viewport = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val width = with(density) { viewport.width.toDp() }.coerceAtMost(380.dp)
    val height = with(density) { viewport.height.toDp() }.coerceAtMost(480.dp)
    Popup(popupPositionProvider = remember(preview.anchor) { DesktopPersonPopupPosition(preview.anchor) },
        onDismissRequest = preview::close, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        Column(Modifier.width(width).heightIn(max = height).testTag("person-state-preview")
            .onPointerEvent(PointerEventType.Enter) { preview.popupHovered = true }
            .onPointerEvent(PointerEventType.Exit) { preview.popupHovered = false }
            .background(DesktopBootstrapColors.card, RoundedCornerShape(10.dp))
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopPersonStateHeading(person) { preview.close(); actions.inspect(person) }
            SelectionContainer { Box(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) { DesktopPersonStateText(person) } }
        }
    }
}

@Composable private fun DesktopPersonStateHeading(person: DesktopChatPerson, manage: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(person.character.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
        DesktopChatIconAction("管理人物 · ${person.character.name}", DesktopAppIcons.Settings, onClick = manage)
    }
}
@Composable private fun DesktopPersonStateText(person: DesktopChatPerson) {
    BasicText(person.status?.text ?: LocalDesktopUiStrings.current(DesktopUiText.PERSON_STATUS_UNKNOWN),
        style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 13.sp, lineHeight = 19.sp))
}

@Composable internal fun DesktopChatPersonTarget(person: DesktopChatPerson?, onOwnContext: () -> Unit = {},
    onClick: (() -> Unit)? = null, allowHover: Boolean = true, content: @Composable () -> Unit) {
    val actions = LocalDesktopChatPeople.current
    if (person == null || actions == null) { content(); return }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val token = remember { Any() }
    var menu by remember(person.cardId, person.character.id) { mutableStateOf(false) }
    if (menu) DesktopChatContextPopup(bounds, listOf("人物" to listOf(
        DesktopChatMenuEntry(if (person.character.id in actions.pinned) "取消置顶" else "固定置顶", DesktopAppIcons.Pin, true) {
            actions.pin(person, person.character.id !in actions.pinned)
        },
        DesktopChatMenuEntry("管理人物", DesktopAppIcons.Settings, person.identityUnique) { actions.inspect(person) },
    ))) { menu = false }
    Box(Modifier.onGloballyPositioned {
        bounds = it.boundsInWindow()
        if (actions.preview.anchorOwner === token) actions.preview.anchor = bounds
    }.semantics { contentDescription = "人物状态 · ${person.character.name}" }
        .onPointerEvent(PointerEventType.Enter) {
            if (allowHover && actions.previewEnabled && !menu) actions.preview.show(person, bounds, owner = token)
        }.onPointerEvent(PointerEventType.Exit) {
            if (actions.preview.anchorOwner === token) actions.preview.anchorHovered = false
        }.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (event.buttons.isSecondaryPressed && person.identityUnique) {
                onOwnContext(); actions.preview.close(); menu = true; event.changes.forEach { it.consume() }
            }
        }.clickable(enabled = person.identityUnique, role = Role.Button) {
            if (onClick != null) { actions.preview.close(); onClick() } else actions.preview.show(person, bounds, true, token)
        }) { content() }
}

@Composable private fun DesktopPersonAvatar(person: DesktopChatPerson, controller: DesktopPrimaryChatController, pinned: Boolean) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        PrimaryAvatar(person.character.appearanceImage, person.character.name, controller, 38.dp)
        if (person.active || !person.enabled) Box(Modifier.align(Alignment.BottomEnd).size(13.dp)
            .testTag(if (!person.enabled) "person-disabled-${person.character.id}" else "person-active-${person.character.id}")
            .semantics { contentDescription = if (!person.enabled) "不可用" else "活跃" }
            .background(DesktopBootstrapColors.background, CircleShape).padding(2.dp)
            .background(if (!person.enabled) DesktopBootstrapColors.destructive else DesktopBootstrapColors.success, CircleShape), contentAlignment = Alignment.Center) {
            if (!person.enabled) Box(Modifier.width(6.dp).height(2.dp).background(Color.White))
        }
        if (pinned) Box(Modifier.align(Alignment.TopStart).size(14.dp).background(DesktopBootstrapColors.card, CircleShape), contentAlignment = Alignment.Center) {
            Image(androidx.compose.ui.graphics.vector.rememberVectorPainter(DesktopAppIcons.Pin), "已固定置顶", Modifier.size(10.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(DesktopBootstrapColors.foreground))
        }
    }
}

@Composable internal fun DesktopChatPeopleRail(people: List<DesktopChatPerson>, controller: DesktopPrimaryChatController,
    widthDp: Float = 60f, names: Boolean = false, inlineStatuses: Boolean = false,
    all: Boolean = true, onAll: (Boolean) -> Unit = {}, onCollapse: (() -> Unit)? = null,
    onInlineStatuses: (Boolean) -> Unit = {}, onManage: () -> Unit = {},
    multipleInlineStatuses: Boolean = false, onMultipleInlineStatuses: (Boolean) -> Unit = {}) {
    val actions = LocalDesktopChatPeople.current ?: return
    val scroll = rememberScrollState()
    var hovered by remember { mutableStateOf(false) }
    var expanded by remember(people.firstOrNull()?.cardId) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(multipleInlineStatuses) {
        if (!multipleInlineStatuses && expanded.size > 1) expanded = expanded.takeLast(1)
    }
    var settings by remember { mutableStateOf(false) }
    Column(Modifier.width(widthDp.dp).fillMaxHeight().testTag("chat-people-column")) {
        Row(Modifier.fillMaxWidth().height(40.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(6.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DesktopPeopleFilter(people, all, !names, onAll) }
            Box {
                DesktopChatIconAction("角色栏设置", DesktopAppIcons.Settings, targetDp = 26) { settings = !settings }
                if (settings) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 40), onDismissRequest = { settings = false },
                    properties = PopupProperties(focusable = true)) {
                    Column(Modifier.width(260.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(8.dp))
                        .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusText("角色栏显示")
                        Row { StudioAction("活跃", selected = !all) { onAll(false) }; StudioAction("启用", selected = all) { onAll(true) } }
                        StudioSwitch("原地展开状态", inlineStatuses) { onInlineStatuses(!inlineStatuses) }
                        if (inlineStatuses) Box(Modifier.padding(start = 12.dp)) {
                            StudioSwitch("允许同时展开多个状态", multipleInlineStatuses) { onMultipleInlineStatuses(!multipleInlineStatuses) }
                        }
                        BootstrapButton("卡内人物", secondary = true) { settings = false; actions.preview.close(); onManage() }
                        BootstrapButton("关闭设置", secondary = true) { settings = false }
                    }
                }
            }
        }
    Box(Modifier.fillMaxWidth().weight(1f).testTag("chat-people-rail")
        .onPointerEvent(PointerEventType.Enter) { hovered = true }.onPointerEvent(PointerEventType.Exit) { hovered = false }) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            desktopVisiblePeople(people, actions.pinned, all).forEachIndexed { index, person -> key(person.cardId, person.character.id, if (person.identityUnique) null else index) {
                val inline = names && inlineStatuses
                Column(Modifier.fillMaxWidth()) {
                    DesktopChatPersonTarget(person, onClick = if (inline) ({
                        val id = person.character.id
                        expanded = if (id in expanded) expanded - id else if (multipleInlineStatuses) expanded + id else listOf(id)
                    }) else null, allowHover = !inline) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                            DesktopPersonAvatar(person, controller, person.character.id in actions.pinned)
                            if (names) {
                                BasicText(person.character.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 13.sp))
                                if (person.character.id in expanded && inline) DesktopChatIconAction("管理人物 · ${person.character.name}", DesktopAppIcons.Settings) { actions.inspect(person) }
                            }
                        }
                    }
                    if (inline && person.character.id in expanded) Box(Modifier.fillMaxWidth().padding(8.dp)
                        .testTag("inline-person-status-${person.character.id}")) { SelectionContainer { DesktopPersonStateText(person) } }
                }
            } }
        }
        if (hovered || scroll.isScrollInProgress) VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("people-scrollbar"))
    }
    }
}

@Composable private fun DesktopPeopleFilter(people: List<DesktopChatPerson>, all: Boolean, compact: Boolean,
    onAll: (Boolean) -> Unit) {
    val count = if (all) people.count { it.enabled } else people.count { it.active }
    val label = "${if (all) "启用" else "活跃"} $count/${people.size}"
    Box(Modifier.semantics { contentDescription = "$label；切换查看${if (all) "启用角色" else "全部角色"}" }) {
        Box(Modifier.fillMaxWidth().height(40.dp)
            .background(DesktopBootstrapColors.card, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button) { onAll(!all) }, contentAlignment = Alignment.Center) {
            BasicText(if (compact) "${if (all) "○" else "●"} $count" else label, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 13.sp))
        }
    }
}

@Composable internal fun DesktopPeopleResizeHandle(widthDp: Float, onDrag: (Float) -> Unit, onEnd: () -> Unit,
    onCollapse: () -> Unit, collapsed: Boolean = false, onExpand: () -> Unit = {}, onAutomatic: () -> Unit = {}) {
    val density = LocalDensity.current.density
    var current by remember { mutableStateOf(widthDp) }
    val latestWidth by rememberUpdatedState(widthDp)
    val drag by rememberUpdatedState(onDrag)
    val finish by rememberUpdatedState(onEnd)
    val collapse by rememberUpdatedState(onCollapse)
    val isCollapsed by rememberUpdatedState(collapsed)
    val expand by rememberUpdatedState(onExpand)
    Box(Modifier.width(DesktopAutomaticChatColumns.DIVIDER_DP.dp).fillMaxHeight().testTag("people-resize")
        .semantics { contentDescription = "拖动调整角色栏宽度；双击恢复自动宽度，左右方向键调整，Home收起" }
        .combinedClickable(onClick = {}, onDoubleClick = onAutomatic)
        .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR)))
        .onKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionLeft -> { if (widthDp <= 64f || collapsed) onCollapse() else { onDrag(widthDp - 20); onEnd() }; true }
                Key.DirectionRight -> { if (collapsed) onExpand() else { onDrag(widthDp + 20); onEnd() }; true }
                Key.MoveHome -> { onCollapse(); true }
                Key.MoveEnd -> { onAutomatic(); true }
                else -> false
            }
        }.focusable().pointerInput(density) {
            detectDragGestures(onDragStart = { current = latestWidth.coerceAtLeast(64f) }, onDragEnd = { finish() }, onDragCancel = { finish() }) { change, delta ->
                change.consume(); current += delta.x / density
                if (current < 44f) collapse() else { if (isCollapsed) expand(); drag(current) }
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(DesktopBootstrapColors.border))
        DesktopChatIconAction(if (collapsed) "展开角色栏" else "收起角色栏",
            if (collapsed) DesktopAppIcons.Expand else DesktopAppIcons.Collapse, targetDp = 24,
            onClick = if (collapsed) onExpand else onCollapse)
    }
}

@Composable internal fun DesktopChatPeopleHeader(people: List<DesktopChatPerson>, controller: DesktopPrimaryChatController,
    inlineStatuses: Boolean, onInlineStatuses: (Boolean) -> Unit, onManage: () -> Unit,
    allPeople: Boolean = false, onAllPeople: (Boolean) -> Unit = {}, onRestore: (() -> Unit)? = null,
    multipleInlineStatuses: Boolean = false, onMultipleInlineStatuses: (Boolean) -> Unit = {}) {
    val actions = LocalDesktopChatPeople.current ?: return
    var open by remember { mutableStateOf(false) }
    val all = allPeople
    val viewport = LocalWindowInfo.current.containerSize
    val height = with(LocalDensity.current) { viewport.height.toDp() }.coerceAtMost(560.dp)
    Box {
        BootstrapButton("${if (all) "启用" else "活跃"} ${people.count { if (all) it.enabled else it.active }}/${people.size}", secondary = true) { open = !open }
        if (open) Popup(popupPositionProvider = StudioPopupPosition, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            Column(Modifier.width(280.dp).heightIn(max = height).background(DesktopBootstrapColors.card, RoundedCornerShape(10.dp))
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(10.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { StudioAction("活跃", selected = !all) { onAllPeople(false) }; StudioAction("启用", selected = all) { onAllPeople(true) }
                    DesktopChatIconAction("关闭活跃列表", DesktopAppIcons.Close) { open = false } }
                Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) {
                    val visible = desktopVisiblePeople(people, actions.pinned, all)
                    if (visible.isEmpty()) StatusText("暂无活跃状态")
                    visible.forEach { person -> key(person.cardId, person.character.id) {
                        DesktopChatPersonTarget(person) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DesktopPersonAvatar(person, controller, person.character.id in actions.pinned); StatusText(person.character.name)
                        } }
                    } }
                }
                if (onRestore != null) BootstrapButton("展开角色栏", secondary = true) { open = false; onRestore() }
                StudioSwitch("宽栏原地展开状态", inlineStatuses) { onInlineStatuses(!inlineStatuses) }
                if (inlineStatuses) Box(Modifier.padding(start = 12.dp)) {
                    StudioSwitch("允许同时展开多个状态", multipleInlineStatuses) { onMultipleInlineStatuses(!multipleInlineStatuses) }
                }
                BootstrapButton("卡内人物", secondary = true) { open = false; actions.preview.close(); onManage() }
            }
        }
    }
}

@Composable internal fun DesktopChatPeopleDialog(people: List<DesktopChatPerson>, selectedId: String?,
    onSelect: (String) -> Unit, onEdit: (String, String) -> Unit,
    onSetEnabled: ((DesktopChatPerson, Boolean) -> Unit)? = null, toggleEnabled: Boolean = true,
    participationAvailable: Boolean = true, pinned: Set<String> = emptySet(),
    onPin: (DesktopChatPerson, Boolean) -> Unit = { _, _ -> }, onClose: () -> Unit) {
    val t = LocalDesktopUiStrings.current; val colors = DesktopBootstrapColors
    val selected = people.firstOrNull { it.character.id == selectedId }
    DesktopModalSurface { Box(Modifier.fillMaxSize().background(colors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 700.dp)
            .background(colors.card, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusText(selected?.character?.name ?: t(DesktopUiText.CARD_PEOPLE)) }
                BootstrapButton(t(DesktopUiText.CLOSE), secondary = true, onClick = onClose)
            }
            Column(Modifier.weight(1f, false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusText(t(if (participationAvailable) DesktopUiText.PERSON_PARTICIPATION_SCOPE else DesktopUiText.PERSON_TOGGLE_FREEFORM))
                if (selected == null) people.forEach { person -> BootstrapButton(person.character.name, secondary = true, enabled = person.identityUnique) { onSelect(person.character.id) } }
                else {
                    StatusText(if (selected.active) "活跃" else "非活跃 · 可查看最近一次明确归属的状态")
                    if (!selected.cardEnabled) StatusText(t(DesktopUiText.CARD_PERSON_DISABLED))
                    if (!selected.enabled) StatusText(t(DesktopUiText.PERSON_DISABLED_HISTORY))
                    selected.status?.let { status ->
                        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(status.source.createdAt))
                        StatusText("${t(DesktopUiText.PERSON_STATUS_SOURCE)} $time · ${status.source.currentAlternativeIndex + 1}")
                    }
                    SelectionContainer { DesktopPersonStateText(selected) }
                    StudioSwitch("固定置顶", selected.character.id in pinned) { onPin(selected, selected.character.id !in pinned) }
                    if (onSetEnabled != null) StudioSwitch("参与当前会话", selected.enabled, enabled = toggleEnabled && selected.identityUnique && selected.cardEnabled) { onSetEnabled(selected, !selected.enabled) }
                    BootstrapButton(t(DesktopUiText.EDIT_PERSON), secondary = true, enabled = selected.identityUnique) { onEdit(selected.cardId, selected.character.id) }
                }
            }
        }
    } }
}
