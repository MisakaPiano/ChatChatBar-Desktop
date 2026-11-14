package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/** The section extends through the detail; its independently scrolling list stays inside that section. */
@Composable
internal fun DesktopPersonSectionLayout(
    viewportTop: Float, viewportHeight: Int,
    master: @Composable () -> Unit, detail: @Composable () -> Unit,
) {
    var sectionTop by remember { mutableStateOf(0f) }
    Layout(content = { Box { master() }; Box { detail() } },
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { sectionTop = it.positionInRoot().y }) { children, constraints ->
        val gap = 20.dp.roundToPx()
        val masterWidth = 320.dp.roundToPx().coerceAtMost((constraints.maxWidth - gap).coerceAtLeast(0))
        val detailWidth = (constraints.maxWidth - masterWidth - gap).coerceAtLeast(0)
        val right = children[1].measure(Constraints(minWidth = detailWidth, maxWidth = detailWidth))
        val available = viewportHeight.takeIf { it > 0 } ?: 420.dp.roundToPx()
        val height = max(right.height, available)
        val left = children[0].measure(Constraints.fixed(masterWidth, available.coerceAtMost(height)))
        val top = (viewportTop - sectionTop).roundToInt().coerceIn(0, height - left.height)
        layout(constraints.maxWidth, height) {
            left.placeRelative(0, top)
            right.placeRelative(masterWidth + gap, 0)
        }
    }
}
