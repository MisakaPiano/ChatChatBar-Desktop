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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.example.chatbar.data.local.entity.ChatMessage

/** Owned by the screenshot presentation module; absent until that module is installed. */
internal val LocalDesktopChatScreenshot = staticCompositionLocalOf<((ChatMessage, String?) -> Unit)?> { null }

internal data class DesktopChatMenuEntry(val label: String, val icon: ImageVector, val enabled: Boolean,
    val destructive: Boolean = false, val invoke: () -> Unit)

internal class DesktopChatMenuPosition(private val anchor: Rect) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = anchor.left.toInt().coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchor.bottom.toInt()
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchor.top.toInt() - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
internal fun DesktopChatContextPopup(anchor: Rect, rows: List<Pair<String, List<DesktopChatMenuEntry>>>, onClose: () -> Unit) {
    Popup(popupPositionProvider = remember(anchor) { DesktopChatMenuPosition(anchor) }, onDismissRequest = onClose,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        DesktopChatContextMenu(rows, onClose)
    }
}

/** Each scope owns a non-wrapping row. A narrow viewport scrolls rows instead of changing targets. */
@Composable
internal fun DesktopChatContextMenu(rows: List<Pair<String, List<DesktopChatMenuEntry>>>, onClose: () -> Unit) {
    BoxWithConstraints {
        Column(Modifier.widthIn(max = 380.dp).background(DesktopBootstrapColors.card, RoundedCornerShape(10.dp))
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(10.dp)).padding(6.dp)) {
            rows.forEach { (scope, entries) ->
                Row(Modifier.fillMaxWidth().testTag("chat-context-$scope").horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicText(scope, Modifier.width(32.dp), style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 11.sp))
                    entries.forEach { entry ->
                        val tint = if (entry.destructive) DesktopBootstrapColors.destructive else DesktopBootstrapColors.foreground
                        Column(Modifier.width(62.dp).height(54.dp).semantics { contentDescription = "$scope · ${entry.label}" }
                            .clickable(enabled = entry.enabled, role = Role.Button) { onClose(); entry.invoke() }.padding(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Image(rememberVectorPainter(entry.icon), null, Modifier.size(19.dp),
                                colorFilter = ColorFilter.tint(tint.copy(alpha = if (entry.enabled) 1f else .35f)))
                            BasicText(entry.label, maxLines = 1, style = TextStyle(color = tint.copy(alpha = if (entry.enabled) 1f else .35f), fontSize = 11.sp))
                        }
                    }
                }
            }
        }
    }
}
