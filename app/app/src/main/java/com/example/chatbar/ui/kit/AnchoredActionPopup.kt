package com.example.chatbar.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

private data class ActionPopupPointer(val x: Float = 0f, val onTop: Boolean = true)

/** Non-dimming action menu anchored to a visible message in window coordinates. */
@Composable
fun CbAnchoredActionPopup(
    anchor: Rect,
    onDismissRequest: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val margin = with(density) { 12.dp.roundToPx() }
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val bottomInset = maxOf(WindowInsets.safeDrawing.getBottom(density), WindowInsets.ime.getBottom(density))
    val width = minOf(336.dp, (configuration.screenWidthDp.dp - 24.dp).coerceAtLeast(48.dp))
    val maxHeight = with(density) {
        (configuration.screenHeightDp.dp.toPx() - topInset - bottomInset - margin * 2)
            .coerceAtLeast(48.dp.toPx()).toDp()
    }
    var pointer by remember(anchor) { mutableStateOf(ActionPopupPointer()) }
    val positionProvider = remember(anchor, margin, topInset, bottomInset) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val minY = topInset + margin
                val maxY = (windowSize.height - bottomInset - margin - popupContentSize.height).coerceAtLeast(minY)
                val below = anchor.bottom.roundToInt()
                val above = anchor.top.roundToInt() - popupContentSize.height
                val onTop = below <= maxY || (above < minY && windowSize.height - bottomInset - anchor.bottom >= anchor.top - topInset)
                val x = (anchor.center.x - popupContentSize.width / 2f).roundToInt()
                    .coerceIn(margin, (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin))
                val y = (if (onTop) below else above).coerceIn(minY, maxY)
                pointer = ActionPopupPointer(
                    (anchor.center.x - x).coerceIn(margin * 2f, (popupContentSize.width - margin * 2f).coerceAtLeast(margin * 2f)),
                    onTop
                )
                return IntOffset(x, y)
            }
        }
    }
    val color = ChatBarTheme.colors.surfaceElevated
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Box(
            Modifier.width(width).heightIn(max = maxHeight)
                .semantics { paneTitle = title }
                .drawBehind {
                    val arrow = 8.dp.toPx()
                    val edgeY = if (pointer.onTop) arrow else size.height - arrow
                    val tipY = if (pointer.onTop) 0f else size.height
                    drawPath(Path().apply {
                        moveTo(pointer.x - arrow, edgeY)
                        lineTo(pointer.x, tipY)
                        lineTo(pointer.x + arrow, edgeY)
                        close()
                    }, color)
                }
                .padding(vertical = 8.dp)
        ) {
            CbSurface(color = color, elevation = ChatBarElevation.high) {
                Column(Modifier.padding(12.dp), content = content)
            }
        }
    }
}
