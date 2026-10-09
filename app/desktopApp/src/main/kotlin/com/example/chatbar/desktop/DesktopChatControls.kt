package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Chat-only pointer density; navigation and management controls retain their own sizing. */
internal object DesktopChatControlDensity {
    const val TARGET_DP = 30
    const val ICON_DP = 16
    const val GAP_DP = 2
}

@Composable
private fun chatControlModifier(enabled: Boolean, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val colors = DesktopBootstrapColors
    return Modifier.alpha(if (enabled) 1f else 0.4f)
        .background(if (enabled && (hovered || focused || pressed)) colors.muted else Color.Transparent, RoundedCornerShape(6.dp))
        .then(if (focused) Modifier.border(1.dp, colors.border, RoundedCornerShape(6.dp)) else Modifier)
        .hoverable(interaction, enabled)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun DesktopChatIconAction(
    label: String, icon: ImageVector, enabled: Boolean = true,
    destructive: Boolean = false, targetDp: Int = DesktopChatControlDensity.TARGET_DP, onClick: () -> Unit,
) {
    val colors = DesktopBootstrapColors
    TooltipArea(tooltip = {
        Box(Modifier.background(colors.card, RoundedCornerShape(6.dp)).padding(8.dp)) { StatusText(label) }
    }) {
        Box(Modifier.size(targetDp.dp)
            .semantics { contentDescription = label }
            .then(chatControlModifier(enabled, onClick)), contentAlignment = Alignment.Center) {
            Image(rememberVectorPainter(icon), null, Modifier.size(DesktopChatControlDensity.ICON_DP.dp),
                colorFilter = ColorFilter.tint(if (destructive) colors.destructive else colors.mutedForeground))
        }
    }
}

@Composable
internal fun DesktopChatNavigationSurface(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = DesktopBootstrapColors
    Row(modifier.background(colors.card.copy(alpha = 0.48f), RoundedCornerShape(8.dp))
        .border(1.dp, colors.border.copy(alpha = 0.46f), RoundedCornerShape(8.dp)).padding(1.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
internal fun DesktopChatNavigationGroup(
    actions: List<DesktopChatJump>, external: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onNavigate: (DesktopChatJump) -> Unit,
) {
    if (actions.isEmpty()) return
    val t = LocalDesktopUiStrings.current
    val buttons: @Composable () -> Unit = {
        actions.forEach { action ->
            val (label, icon) = when (action) {
                DesktopChatJump.PREVIOUS -> DesktopUiText.PREVIOUS_MESSAGE to DesktopAppIcons.MessagePrevious
                DesktopChatJump.FIRST -> DesktopUiText.FIRST_MESSAGE to DesktopAppIcons.MessageFirst
                DesktopChatJump.NEXT -> DesktopUiText.NEXT_MESSAGE to DesktopAppIcons.MessageNext
                DesktopChatJump.BOTTOM -> DesktopUiText.JUMP_BOTTOM to DesktopAppIcons.JumpBottom
            }
            DesktopChatIconAction(t(label), icon, enabled = enabled, targetDp = 48) { onNavigate(action) }
        }
    }
    DesktopChatNavigationSurface(modifier) {
        if (external) Column { buttons() } else Row { buttons() }
    }
}

@Composable
internal fun DesktopComposerResizeHandle(onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    val latestDrag by rememberUpdatedState(onDrag)
    val t = LocalDesktopUiStrings.current
    Box(Modifier.fillMaxWidth().height(12.dp)
        .semantics { contentDescription = t(DesktopUiText.RESIZE_COMPOSER) }
        .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)))
        .pointerInput(density) {
            detectVerticalDragGestures { change, amount ->
                change.consume()
                latestDrag(amount / density.density)
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 3.dp).background(DesktopBootstrapColors.mutedForeground.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
    }
}

@Composable
internal fun DesktopChatDisclosure(label: String, expansion: DesktopPresentationExpansion) {
    Row(Modifier.height(DesktopChatControlDensity.TARGET_DP.dp)
        .then(chatControlModifier(true, expansion::toggle)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(rememberVectorPainter(if (expansion.expanded) DesktopAppIcons.DetailsOpen else DesktopAppIcons.DetailsClosed),
            null, Modifier.size(DesktopChatControlDensity.ICON_DP.dp),
            colorFilter = ColorFilter.tint(DesktopBootstrapColors.mutedForeground))
        BasicText(label, style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 12.sp))
    }
}

@Composable
internal fun DesktopChatAlternativeControls(navigation: DesktopAlternativeNavigation, onSelect: (Int) -> Unit) {
    val t = LocalDesktopUiStrings.current
    Row(Modifier.background(DesktopBootstrapColors.muted, RoundedCornerShape(6.dp)), verticalAlignment = Alignment.CenterVertically) {
        DesktopChatIconAction(t(DesktopUiText.PREVIOUS_ALTERNATIVE), DesktopAppIcons.Previous, navigation.canPrevious) { onSelect(-1) }
        BasicText("${navigation.current} / ${navigation.total}",
            style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 12.sp))
        DesktopChatIconAction(t(DesktopUiText.NEXT_ALTERNATIVE), DesktopAppIcons.Next, navigation.canNext) { onSelect(1) }
    }
}

@Composable
internal fun DesktopChatMessageToolbar(actions: List<DesktopMessageAction>, onAction: (DesktopMessageAction) -> Unit) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    val overflow = desktopOverflowMessageActions(actions)
    var open by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopChatControlDensity.GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        desktopFooterMessageActions(actions).forEach { action ->
            DesktopChatIconAction(t(action.label), if (action == DesktopMessageAction.COPY) DesktopAppIcons.Copy else DesktopAppIcons.Refresh) { onAction(action) }
        }
        if (overflow.isNotEmpty()) Box {
            DesktopChatIconAction(t(DesktopUiText.MORE_MESSAGE_ACTIONS), DesktopAppIcons.More) { open = !open }
            if (open) Popup(alignment = Alignment.BottomStart, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(Modifier.background(colors.card, RoundedCornerShape(6.dp)).border(1.dp, colors.border, RoundedCornerShape(6.dp)).padding(4.dp)) {
                    overflow.forEach { action ->
                        Row(Modifier.height(DesktopChatControlDensity.TARGET_DP.dp).then(chatControlModifier(true) {
                            open = false
                            onAction(action)
                        }).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val tint = if (action == DesktopMessageAction.DELETE) colors.destructive else colors.mutedForeground
                            Image(rememberVectorPainter(if (action == DesktopMessageAction.DELETE) DesktopAppIcons.Delete else DesktopAppIcons.Edit),
                                null, Modifier.size(DesktopChatControlDensity.ICON_DP.dp), colorFilter = ColorFilter.tint(tint))
                            BasicText(t(action.label), style = TextStyle(color = tint, fontSize = 13.sp))
                        }
                    }
                }
            }
        }
    }
}

/** Only the visible speaker header is a message-menu hit target; image hit targets remain separate. */
@Composable
internal fun DesktopImageOnlyMessageContextMenu(actions: List<DesktopMessageAction>,
    onAction: (DesktopMessageAction) -> Unit, content: @Composable () -> Unit) {
    if (actions.isEmpty()) { content(); return }
    val t = LocalDesktopUiStrings.current
    val items = actions.map { action -> ContextMenuItem(t(action.label)) { onAction(action) } }
    ContextMenuArea(items = { items }) { content() }
}
