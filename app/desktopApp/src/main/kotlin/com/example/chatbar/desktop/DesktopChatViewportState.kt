package com.example.chatbar.desktop

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** A session-owned UI lifetime; no background persistence scope survives the composition. */
internal class DesktopChatViewportState(val sessionId: String?, initialMapping: DesktopChatTimelineMapping) {
    val list = LazyListState()
    var ready by mutableStateOf(false)
    var restoring by mutableStateOf(false)
    var followingBottom by mutableStateOf(false)
    var mapping by mutableStateOf(initialMapping)
    fun items() = list.layoutInfo.visibleItemsInfo.map {
        DesktopVisibleTimelineItem(it.index, it.key.toString(), it.offset, it.size)
    }
    fun matches() = mapping.matches(items(), list.layoutInfo.totalItemsCount)
    fun atBottom() = mapping.isAtBottom(items(), list.layoutInfo.viewportEndOffset)
    fun canEarlier() = mapping.canJumpEarlier(items(), list.layoutInfo.viewportStartOffset)
    suspend fun awaitLayout() { snapshotFlow { matches() }.first { it } }

    suspend fun bottom(animated: Boolean) {
        awaitLayout()
        val last = mapping.keys.lastIndex
        if (last < 0) return
        if (animated) list.animateScrollToItem(last) else list.scrollToItem(last)
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
                    bottom(animated = true)
                    followingBottom = true
                }
                DesktopChatJump.FIRST -> {
                    if (mapping.hasOlder) {
                        controller.loadFirstMessageWindow(id) ?: return
                        snapshotFlow { !mapping.hasOlder }.first { it }
                    }
                    awaitLayout()
                    mapping.renderedMessageIds.firstOrNull()?.let { list.animateScrollToItem(mapping.keys.indexOf(it)) }
                    followingBottom = atBottom()
                }
                DesktopChatJump.PREVIOUS -> {
                    val first = mapping.firstReal(items())
                    val firstId = mapping.renderedMessageIds.firstOrNull()
                    if (mapping.hasOlder && (first == null || first.key == firstId)) {
                        controller.loadOlder()
                        if (controller.state.value.error != null) return
                        val expectedIds = controller.state.value.messages.map { it.id }
                        // Wait for the controller result to enter composition, not an arbitrary delay.
                        snapshotFlow { mapping.messageIds == expectedIds }.first { it }
                        awaitLayout()
                        val index = mapping.keys.indexOf(firstId)
                        list.animateScrollToItem((index - 1).coerceAtLeast(if (mapping.hasOlder) 1 else 0))
                    } else mapping.previousTarget(items())?.let { list.animateScrollToItem(it) }
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
    LaunchedEffect(viewport) {
        viewport.restoring = true
        try {
            viewport.awaitLayout()
            val target = viewport.mapping.initialTarget(state.readingPosition)
            if (state.readingPosition == null) viewport.bottom(animated = false)
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
            val id = viewport.sessionId
            if (id == null || !viewport.matches()) null else viewport.mapping.captureStable(
                id, viewport.items(), viewport.list.layoutInfo.viewportStartOffset,
                viewport.ready, viewport.restoring, viewport.list.isScrollInProgress,
            )
        }.distinctUntilChanged().collect { snapshot -> snapshot?.let { controller.persistReadingPosition(it) } }
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
