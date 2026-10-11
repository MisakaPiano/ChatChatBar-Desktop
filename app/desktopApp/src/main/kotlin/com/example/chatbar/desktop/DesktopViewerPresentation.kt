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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX

/** Navigation-only snapshot for one opening. Refresh/reorder/removal cannot retarget a page
 * to another occurrence; a subsequently removed resource still uses the normal read error UI. */
internal class DesktopViewerSelection(references: List<String>, initialIndex: Int) {
    val references: List<String> = references.toList()
    var index by mutableIntStateOf(initialIndex.coerceIn(0, (references.size - 1).coerceAtLeast(0)))
        private set
    val reference: String? get() = references.getOrNull(index)
    fun navigate(next: Int) { index = next.coerceIn(0, (references.size - 1).coerceAtLeast(0)) }
    // Read at execution time: repeated callbacks may run before the next composition.
    fun previous() = navigate(index - 1)
    fun next() = navigate(index + 1)
}

internal fun desktopViewerKeyHandler(selection: DesktopViewerSelection, onClose: () -> Unit): (KeyEvent) -> Boolean = { event ->
    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
        Key.DirectionLeft -> { selection.previous(); true }
        Key.DirectionRight -> { selection.next(); true }
        Key.Escape -> { onClose(); true }
        else -> false
    }
}

@Composable
internal fun DesktopViewerPageButtons(selection: DesktopViewerSelection) {
    if (selection.references.size > 1) {
        BootstrapButton("上一张", enabled = selection.index > 0, onClick = selection::previous)
        BootstrapButton("下一张", enabled = selection.index < selection.references.lastIndex, onClick = selection::next)
    }
}

/** Resolve the clicked message/image position, never the first matching resource path. */
internal fun desktopChatViewerEntry(messages: List<ChatMessage>, messageId: String,
    imageIndex: Int, reference: String): Pair<List<String>, Int>? {
    val paths = mutableListOf<String>()
    var selected = -1
    messages.forEach { message -> message.images.forEachIndexed { index, path ->
        if (!path.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) {
            if (message.id == messageId && index == imageIndex && path == reference) selected = paths.size
            paths.add(path)
        }
    } }
    return if (selected >= 0) paths.toList() to selected else null
}

/** A double click suppresses the pending single click (selection/album opening). */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.desktopViewerEntry(enabled: Boolean = true, onClick: () -> Unit,
    onOpen: () -> Unit = onClick): Modifier = combinedClickable(
    enabled = enabled, onClick = onClick, onDoubleClick = onOpen)

/** Overlay targets occupy only the side controls; the underlying image keeps zoom/pan input. */
@Composable
internal fun DesktopViewerImageRegion(index: Int, count: Int, onPrevious: () -> Unit, onNext: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier.hoverable(interaction).semantics { contentDescription = "Viewer 图片区域" }) {
        content()
        if (count > 1 && hovered) {
            if (index > 0) Box(Modifier.align(Alignment.CenterStart).padding(8.dp)
                .background(DesktopBootstrapColors.card.copy(alpha = .9f))) {
                DesktopChatIconAction("预览上一张", DesktopAppIcons.Previous, targetDp = 44, onClick = onPrevious)
            }
            if (index < count - 1) Box(Modifier.align(Alignment.CenterEnd).padding(8.dp)
                .background(DesktopBootstrapColors.card.copy(alpha = .9f))) {
                DesktopChatIconAction("预览下一张", DesktopAppIcons.Next, targetDp = 44, onClick = onNext)
            }
        }
    }
}
