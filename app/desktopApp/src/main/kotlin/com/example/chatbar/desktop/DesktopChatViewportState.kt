package com.example.chatbar.desktop

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import com.example.chatbar.data.local.entity.ChatScrollPosition

internal data class DesktopViewportObservation(
    val items: List<DesktopVisibleTimelineItem>, val totalItems: Int,
    val start: Int, val end: Int, val scrolling: Boolean,
)

/** A session-owned UI lifetime; no background persistence scope survives the composition. */
internal class DesktopChatViewportState(
    val sessionId: String?, initialMapping: DesktopChatTimelineMapping,
    private val observe: (() -> DesktopViewportObservation)? = null,
    private val scroll: (suspend (DesktopScrollTarget, Boolean) -> Unit)? = null,
) {
    val list = LazyListState()
    var ready by mutableStateOf(false)
    var restoring by mutableStateOf(false)
    var followingBottom by mutableStateOf(false)
    var mapping by mutableStateOf(initialMapping)
    fun observation() = observe?.invoke() ?: DesktopViewportObservation(list.layoutInfo.visibleItemsInfo.map {
        DesktopVisibleTimelineItem(it.index, it.key.toString(), it.offset, it.size)
    }, list.layoutInfo.totalItemsCount, list.layoutInfo.viewportStartOffset, list.layoutInfo.viewportEndOffset, list.isScrollInProgress)
    fun items() = observation().items
    fun matches() = observation().let { mapping.matches(it.items, it.totalItems) }
    fun atBottom() = observation().let { mapping.isAtBottom(it.items, it.end) }
    fun canEarlier() = observation().let { mapping.canJumpEarlier(it.items, it.start) }
    suspend fun awaitLayout() { snapshotFlow { matches() }.first { it } }

    fun capture(final: Boolean = false): ChatScrollPosition? {
        val id = sessionId ?: return null
        val view = observation()
        if (!mapping.matches(view.items, view.totalItems)) return null
        return mapping.captureStable(id, view.items, view.start, ready, restoring, !final && view.scrolling)
    }

    fun submitFinalViewport(submit: (ChatScrollPosition) -> Unit) { capture(final = true)?.let(submit) }

    private suspend fun scrollTo(target: DesktopScrollTarget, animated: Boolean = true) {
        if (scroll != null) scroll.invoke(target, animated)
        else if (animated) list.animateScrollToItem(target.index, target.offset)
        else list.scrollToItem(target.index, target.offset)
    }

    suspend fun loadAdjacent(controller: DesktopPrimaryChatController, older: Boolean) {
        val id = sessionId ?: return
        if (!ready || restoring) return
        val position = capture(final = true)
        position?.let { controller.updateMessageWindowAnchor(id, it.anchorMessageId) }
        restoring = true
        try {
            if (older) controller.loadOlder() else controller.loadNewer()
            if (controller.state.value.error != null) return
            val expected = controller.state.value.messages.map { it.id }
            snapshotFlow { mapping.messageIds == expected }.first { it }
            awaitLayout()
            if (position != null) mapping.initialTarget(position)?.let { scrollTo(it, animated = false) }
            followingBottom = atBottom()
        } finally { restoring = false }
    }

    suspend fun bottom(animated: Boolean) {
        awaitLayout()
        val last = mapping.keys.lastIndex
        if (last < 0) return
        scrollTo(DesktopScrollTarget(last), animated)
        val item = list.layoutInfo.visibleItemsInfo.lastOrNull() ?: return
        val remaining = item.offset + item.size - list.layoutInfo.viewportEndOffset
        if (remaining > 0) {
            if (animated) list.animateScrollBy(remaining.toFloat()) else list.scrollBy(remaining.toFloat())
        }
    }

    suspend fun navigate(controller: DesktopPrimaryChatController, action: DesktopChatJump) {
        val id = sessionId ?: return
        if (!ready || restoring) return
        restoring = true
        try {
            when (action) {
                DesktopChatJump.BOTTOM -> {
                    if (mapping.hasNewer) {
                        controller.loadLatestMessageWindow(id) ?: return
                        snapshotFlow { !mapping.hasNewer }.first { it }
                    }
                    controller.updateMessageWindowAnchor(id, mapping.messageIds.lastOrNull())
                    bottom(animated = true)
                    followingBottom = true
                }
                DesktopChatJump.FIRST -> {
                    if (mapping.hasOlder) {
                        controller.loadFirstMessageWindow(id) ?: return
                        snapshotFlow { !mapping.hasOlder }.first { it }
                    }
                    awaitLayout()
                    mapping.renderedMessageIds.firstOrNull()?.let {
                        controller.updateMessageWindowAnchor(id, it)
                        scrollTo(DesktopScrollTarget(mapping.keys.indexOf(it)))
                    }
                    followingBottom = atBottom()
                }
                DesktopChatJump.PREVIOUS -> {
                    val first = mapping.firstReal(items())
                    val firstId = mapping.renderedMessageIds.firstOrNull()
                    val streamVisible = items().any { it.key == mapping.streamKey }
                    if (mapping.hasOlder && (first?.key == firstId ||
                            (first == null && !streamVisible && items().any { it.key == "older" }))) {
                        controller.loadOlder()
                        if (controller.state.value.error != null) return
                        val expectedIds = controller.state.value.messages.map { it.id }
                        // Wait for the controller result to enter composition, not an arbitrary delay.
                        snapshotFlow { mapping.messageIds == expectedIds }.first { it }
                        awaitLayout()
                        val index = mapping.keys.indexOf(firstId)
                        val target = (index - 1).coerceAtLeast(if (mapping.hasOlder) 1 else 0)
                        controller.updateMessageWindowAnchor(id, mapping.keys.getOrNull(target))
                        scrollTo(DesktopScrollTarget(target))
                    } else mapping.previousTarget(items())?.let {
                        controller.updateMessageWindowAnchor(id, mapping.keys[it])
                        scrollTo(DesktopScrollTarget(it))
                    }
                    followingBottom = atBottom()
                }
            }
        } finally { restoring = false }
    }
}

internal enum class DesktopChatJump { PREVIOUS, FIRST, BOTTOM }

@Composable
internal fun rememberDesktopChatViewport(
    state: DesktopPrimaryChatState, mapping: DesktopChatTimelineMapping,
    controller: DesktopPrimaryChatController, streamRevision: Pair<String?, String?>,
): DesktopChatViewportState {
    val viewport = remember(state.selectedSession?.id) { DesktopChatViewportState(state.selectedSession?.id, mapping) }
    val latestStreamRevision by rememberUpdatedState(streamRevision)
    SideEffect { viewport.mapping = mapping }
    DisposableEffect(viewport, controller) {
        onDispose { viewport.submitFinalViewport { controller.submitReadingPosition(it) } }
    }
    LaunchedEffect(viewport) {
        viewport.restoring = true
        try {
            viewport.awaitLayout()
            val target = viewport.mapping.initialTarget(state.viewportRestorePosition)
            if (state.viewportRestorePosition == null) viewport.bottom(animated = false)
            else target?.let { viewport.list.scrollToItem(it.index, it.offset) }
            viewport.followingBottom = viewport.atBottom()
            viewport.ready = true
        } finally { viewport.restoring = false }
    }
    LaunchedEffect(viewport) {
        var userScrolling = false
        snapshotFlow { viewport.list.isScrollInProgress to viewport.restoring }.collect { (scrolling, restoring) ->
            if (restoring) userScrolling = false
            else if (scrolling) userScrolling = true
            else if (userScrolling) {
                viewport.followingBottom = viewport.atBottom()
                userScrolling = false
            }
        }
    }
    LaunchedEffect(viewport) {
        snapshotFlow {
            viewport.capture()
        }.distinctUntilChanged().collect { snapshot -> snapshot?.let { controller.submitReadingPosition(it) } }
    }
    LaunchedEffect(viewport) {
        snapshotFlow { Triple(viewport.mapping.keys, latestStreamRevision, viewport.ready) }.collectLatest {
            // Measure the new content first, then recheck user scrolling before taking scroll ownership.
            withFrameNanos { }
            if (desktopShouldFollowBottom(viewport.ready, viewport.restoring, viewport.followingBottom,
                    viewport.list.isScrollInProgress, viewport.mapping.hasNewer)) {
                viewport.restoring = true
                try { viewport.bottom(animated = false) } finally { viewport.restoring = false }
            }
        }
    }
    return viewport
}
