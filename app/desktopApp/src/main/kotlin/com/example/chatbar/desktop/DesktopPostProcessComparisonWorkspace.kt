package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** One comparison viewport at a time, sharing only its transient view across inline/window hosts. */
@Composable
internal fun DesktopPostProcessComparisonWorkspace(identity: String, original: ByteArray, result: ByteArray, sourceSize: IntSize,
    onResultDimensions: ((IntSize) -> Unit)? = null) {
    val view = remember(identity) { DesktopPostProcessComparisonState() }
    var expanded by remember(identity) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StudioAction(if (expanded) "返回面板对比" else "展开大图对比") { expanded = !expanded }
        if (expanded) StatusText("对比已在独立窗口展开")
        else DesktopPostProcessComparison(identity, original, result, sourceSize, Modifier.fillMaxWidth().height(365.dp), view, onResultDimensions)
    }
    if (expanded) DesktopImageToolWindow("原图与处理结果对比", { expanded = false }, width = 1080.dp, height = 800.dp) {
        StudioAction("返回面板对比") { expanded = false }
        DesktopPostProcessComparison(identity, original, result, sourceSize, Modifier.fillMaxWidth().weight(1f), view, onResultDimensions)
    }
}
