package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.example.chatbar.data.local.entity.ChatMessage

/** Owned by the screenshot presentation module; absent until that module is installed. */
internal val LocalDesktopChatScreenshot = staticCompositionLocalOf<((ChatMessage, String?) -> Unit)?> { null }

internal data class DesktopChatMenuEntry(val label: String, val icon: ImageVector, val enabled: Boolean,
    val destructive: Boolean = false, val invoke: () -> Unit)

internal class DesktopChatMenuPosition(private val anchor: Rect, private val hostSize: IntSize? = null) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val bounds = hostSize ?: windowSize
        val x = anchor.left.toInt().coerceIn(0, (bounds.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchor.bottom.toInt()
        val y = (if (below + popupContentSize.height <= bounds.height) below else anchor.top.toInt() - popupContentSize.height)
            .coerceIn(0, (bounds.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

@Composable
internal fun DesktopChatContextPopup(anchor: Rect, rows: List<Pair<String, List<DesktopChatMenuEntry>>>, onClose: () -> Unit) {
    val viewport = LocalWindowInfo.current.containerSize
    val width = with(LocalDensity.current) { viewport.width.coerceAtLeast(1).toDp() }.coerceAtMost(380.dp)
    val height = with(LocalDensity.current) { viewport.height.coerceAtLeast(1).toDp() }
    Popup(popupPositionProvider = remember(anchor, viewport) { DesktopChatMenuPosition(anchor, viewport) }, onDismissRequest = onClose,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        Box(Modifier.width(width).heightIn(max = height).verticalScroll(rememberScrollState())) { DesktopChatContextMenu(rows, onClose) }
    }
}

/** Each scope owns a non-wrapping row. A narrow viewport scrolls rows instead of changing targets. */
@Composable
internal fun DesktopChatContextMenu(rows: List<Pair<String, List<DesktopChatMenuEntry>>>, onClose: () -> Unit) {
    val focus=remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box {
        Column(Modifier.onPreviewKeyEvent { event -> if(event.key==Key.Escape && event.type==KeyEventType.KeyDown) { onClose();true } else false }
            .focusRequester(focus).focusable().widthIn(max = 380.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(10.dp))
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(10.dp)).padding(6.dp)) {
            rows.forEach { (scope, entries) -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val entryWidth = if (entries.size == 2) ((maxWidth - 32.dp) / 2).coerceAtLeast(62.dp) else 62.dp
                Row(Modifier.fillMaxWidth().testTag("chat-context-$scope").horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicText(scope, Modifier.width(32.dp), style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 11.sp))
                    entries.forEach { entry ->
                        var focused by remember(scope,entry.label) { mutableStateOf(false) }
                        val tint = if (entry.destructive) DesktopBootstrapColors.destructive else DesktopBootstrapColors.foreground
                        Column(Modifier.width(entryWidth).height(54.dp)
                            .background(if(focused) DesktopBootstrapColors.muted else androidx.compose.ui.graphics.Color.Transparent)
                            .onFocusChanged { focused=it.isFocused }.semantics { contentDescription = "$scope · ${entry.label}" }
                            .clickable(enabled = entry.enabled, role = Role.Button) { onClose(); entry.invoke() }.padding(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Image(rememberVectorPainter(entry.icon), null, Modifier.size(19.dp),
                                colorFilter = ColorFilter.tint(tint.copy(alpha = if (entry.enabled) 1f else .35f)))
                            BasicText(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = tint.copy(alpha = if (entry.enabled) 1f else .35f), fontSize = 11.sp))
                        }
                    }
                }
            }
            }
        }
    }
}
