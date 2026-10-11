package com.example.chatbar.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A double click suppresses the pending single click (selection/album opening). */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.desktopViewerEntry(enabled: Boolean = true, onClick: () -> Unit,
    onOpen: () -> Unit = onClick): Modifier = combinedClickable(
    enabled = enabled, onClick = onClick, onDoubleClick = onOpen)

/** Overlay targets occupy only the side controls; the underlying image keeps zoom/pan input. */
@Composable
internal fun DesktopViewerImageRegion(index: Int, count: Int, onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier.hoverable(interaction).semantics { contentDescription = "Viewer 图片区域" }) {
        content()
        if (count > 1 && hovered) {
            if (index > 0) Box(Modifier.align(Alignment.CenterStart).padding(8.dp)
                .background(DesktopBootstrapColors.card.copy(alpha = .9f))) {
                DesktopChatIconAction("预览上一张", DesktopAppIcons.Previous, targetDp = 44) { onNavigate(index - 1) }
            }
            if (index < count - 1) Box(Modifier.align(Alignment.CenterEnd).padding(8.dp)
                .background(DesktopBootstrapColors.card.copy(alpha = .9f))) {
                DesktopChatIconAction("预览下一张", DesktopAppIcons.Next, targetDp = 44) { onNavigate(index + 1) }
            }
        }
    }
}
