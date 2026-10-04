package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.ChatScrollPosition
import com.example.chatbar.domain.chat.ChatScrollPositionPolicy

internal data class DesktopVisibleTimelineItem(val index: Int, val key: String, val offset: Int, val size: Int)
internal data class DesktopScrollTarget(val index: Int, val offset: Int = 0)

internal fun desktopChatTimelineMapping(state: DesktopPrimaryChatState, running: DesktopTaskEntry?) =
    DesktopChatTimelineMapping(state.messages.map { it.id }, desktopVisibleMessages(state.messages, running).map { it.id },
        state.hasOlderMessages, state.hasNewerMessages, running?.let { "stream:${it.taskId}" })

/** Synthetic rows and hidden regeneration targets never share the message-index namespace. */
internal class DesktopChatTimelineMapping(
    val messageIds: List<String>,
    val renderedMessageIds: List<String> = messageIds,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val streamKey: String? = null,
) {
    val keys = buildList {
        if (hasOlder) add("older")
        addAll(renderedMessageIds)
        if (hasNewer) add("newer")
        streamKey?.let(::add)
    }
    private val firstMessageItem = if (hasOlder) 1 else 0
    fun messageToLazy(index: Int): Int? = messageIds.getOrNull(index)?.let { id ->
        renderedMessageIds.indexOf(id).takeIf { it >= 0 }?.plus(firstMessageItem)
    }
    fun lazyToMessage(index: Int): Int? = renderedMessageIds.getOrNull(index - firstMessageItem)
        ?.let { messageIds.indexOf(it).takeIf { found -> found >= 0 } }

    fun matches(items: List<DesktopVisibleTimelineItem>, totalItems: Int): Boolean =
        totalItems == keys.size && (keys.isEmpty() || items.isNotEmpty()) &&
            items.all { keys.getOrNull(it.index) == it.key }

    fun firstReal(items: List<DesktopVisibleTimelineItem>): DesktopVisibleTimelineItem? =
        items.firstOrNull { lazyToMessage(it.index) != null && keys.getOrNull(it.index) == it.key }

    fun captureStable(
        sessionId: String, items: List<DesktopVisibleTimelineItem>, viewportStart: Int,
        initialScrollDone: Boolean, restoring: Boolean, scrolling: Boolean,
    ): ChatScrollPosition? {
        if (!initialScrollDone || restoring || scrolling) return null
        val item = firstReal(items) ?: return null
        return ChatScrollPositionPolicy.capture(sessionId, messageIds, lazyToMessage(item.index)!!,
            (viewportStart - item.offset).coerceAtLeast(0), capturedAt = 0)
    }

    fun initialTarget(position: ChatScrollPosition?): DesktopScrollTarget? {
        if (position == null) return keys.lastIndex.takeIf { it >= 0 }?.let(::DesktopScrollTarget)
        val messageIndex = ChatScrollPositionPolicy.restoreMessageIndex(position, messageIds) ?: return null
        val exact = messageToLazy(messageIndex)
        // A temporarily hidden regeneration target falls back to a nearby real message, never a task ID.
        val available = exact ?: (messageIndex..messageIds.lastIndex).firstNotNullOfOrNull(::messageToLazy)
            ?: (messageIndex downTo 0).firstNotNullOfOrNull(::messageToLazy)
        return available?.let { DesktopScrollTarget(it, position.scrollOffset.coerceAtLeast(0)) }
    }

    fun previousTarget(items: List<DesktopVisibleTimelineItem>): Int? {
        if (renderedMessageIds.isEmpty()) return null
        val first = firstReal(items)?.index
            ?: return if (items.any { it.key == streamKey }) firstMessageItem + renderedMessageIds.lastIndex else firstMessageItem
        return (first - 1).coerceAtLeast(firstMessageItem)
    }

    fun canJumpEarlier(items: List<DesktopVisibleTimelineItem>, viewportStart: Int): Boolean {
        if (renderedMessageIds.isEmpty()) return false
        val first = firstReal(items)
        return hasOlder || (first != null && (first.index > firstMessageItem || first.offset < viewportStart)) ||
            items.any { it.key == streamKey }
    }

    fun isAtBottom(items: List<DesktopVisibleTimelineItem>, viewportEnd: Int): Boolean {
        if (hasNewer) return false
        if (keys.isEmpty()) return true
        val last = items.lastOrNull() ?: return false
        return last.index == keys.lastIndex && last.key == keys.last() && last.offset + last.size <= viewportEnd + 16
    }
}

internal fun desktopShouldFollowBottom(
    initialScrollDone: Boolean, restoring: Boolean, followingBottom: Boolean,
    scrolling: Boolean, hasNewer: Boolean,
): Boolean = initialScrollDone && !restoring && followingBottom && !scrolling && !hasNewer
