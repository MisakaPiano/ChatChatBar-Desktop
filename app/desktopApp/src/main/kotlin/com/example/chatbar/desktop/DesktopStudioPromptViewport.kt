package com.example.chatbar.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView

/** The inner scroll owns the long text; ancestors only need its bounded viewport/caret portion. */
internal fun Modifier.studioPromptViewportBoundary(): Modifier = this.then(StudioPromptViewportElement)

/** Focusable's full long-content bounds must not outrank the current source caret. */
internal fun Modifier.studioPromptCaretBoundary(caret: () -> Rect?): Modifier = then(StudioPromptCaretElement(caret))

private data class StudioPromptCaretElement(val caret: () -> Rect?) : ModifierNodeElement<StudioPromptCaretNode>() {
    override fun create() = StudioPromptCaretNode(caret)
    override fun update(node: StudioPromptCaretNode) { node.caret = caret }
    override fun InspectorInfo.inspectableProperties() { name = "studioPromptCaretBoundary" }
}

private class StudioPromptCaretNode(var caret: () -> Rect?) : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        bringIntoView {
            if (!isAttached || !childCoordinates.isAttached) null else caret() ?: boundsProvider()?.let {
                it.translate(requireLayoutCoordinates().localPositionOf(childCoordinates, Offset.Zero))
            }
        }
    }
}

private object StudioPromptViewportElement : ModifierNodeElement<StudioPromptViewportNode>() {
    override fun create() = StudioPromptViewportNode()
    override fun update(node: StudioPromptViewportNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "studioPromptViewportBoundary" }
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}

private class StudioPromptViewportNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        // Keep forwarding: an off-screen editor still has to become visible. The existing inner
        // responder independently scrolls selection/caret into its viewport before/while this runs.
        bringIntoView {
            if (!isAttached || !childCoordinates.isAttached) null else {
                val own = requireLayoutCoordinates()
                val requested = boundsProvider()
                if (requested == null || !own.isAttached) null else {
                    val viewport = Rect(0f, 0f, own.size.width.toFloat(), own.size.height.toFloat())
                    val local = requested.translate(own.localPositionOf(childCoordinates, Offset.Zero))
                    val visible = local.intersect(viewport)
                    if (visible.isEmpty) viewport else visible
                }
            }
        }
    }
}
