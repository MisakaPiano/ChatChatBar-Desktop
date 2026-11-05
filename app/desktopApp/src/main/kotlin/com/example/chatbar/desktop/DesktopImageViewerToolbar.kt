package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One compact, scrollable command strip; the image keeps the remaining window height. */
@Composable
internal fun DesktopImageViewerToolbar(zoom: DesktopImageZoomState, selection: DesktopViewerSelection? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().testTag("image-viewer-toolbar").background(DesktopBootstrapColors.card)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selection != null && selection.references.size > 1) {
                DesktopChatIconAction("上一张", DesktopAppIcons.Previous, enabled = selection.index > 0, onClick = selection::previous)
                StatusText("${selection.index + 1} / ${selection.references.size}")
                DesktopChatIconAction("下一张", DesktopAppIcons.Next, enabled = selection.index < selection.references.lastIndex, onClick = selection::next)
                ViewerToolbarSeparator()
            }
            DesktopChatIconAction("缩小", DesktopAppIcons.ZoomOut) { zoom.zoomBy(1f / 1.25f) }
            BasicText("${zoom.percent}%", Modifier.widthIn(min = 42.dp),
                style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 12.sp))
            DesktopChatIconAction("放大", DesktopAppIcons.ZoomIn) { zoom.zoomBy(1.25f) }
            StudioAction("适应窗口", icon = DesktopAppIcons.ExpandComposer, onClick = zoom::fit)
            StudioAction("100%", onClick = zoom::actualSize)
            actions()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
            val size = zoom.sourceSize
            StatusText(if (size.width > 0) "${size.width} × ${size.height} px · 滚轮缩放 · 拖动平移 · 双击切换原大" else "滚轮缩放 · 拖动平移")
        }
    }
}

@Composable
internal fun ViewerToolbarSeparator() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(DesktopBootstrapColors.border))
}
