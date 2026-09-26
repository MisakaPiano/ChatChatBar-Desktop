package com.example.chatbar.domain.chat

private data class VisibleRoleplaySource(
    val text: String,
    val rawIndexes: IntArray,
)

private data class StatusStripRange(
    val start: Int,
    val endExclusive: Int,
)

private data class StatusStripSegment(
    val start: Int,
    val endExclusive: Int,
    val status: Boolean,
)

/** 移除状态栏与横线包裹的选项块，保留叙事、对白、心理和人物标记。 */
fun stripRoleplayStatusSegments(content: String): String {
    val excludedRanges = excludedRoleplayStatusRanges(content)
    if (excludedRanges.isEmpty()) return content
    return excludedRanges
        .sortedByDescending(StatusStripRange::start)
        .fold(content) { text, range -> text.removeRange(range.start, range.endExclusive) }
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun excludedRoleplayStatusRanges(content: String): List<StatusStripRange> {
    if (content.isBlank()) return emptyList()
    val visible = visibleRoleplaySource(content)
    val statusRanges = splitCodeFenceSegments(visible.text)
        .flatMap { segment ->
            if (segment.status) listOf(segment) else splitDashFenceSegments(visible.text, segment)
        }
        .filter(StatusStripSegment::status)
        .mapNotNull { segment -> visible.toRawRange(segment.start, segment.endExclusive) }
        .toMutableList()
    findLongDashWrappedRanges(visible.text, 0, visible.text.length).forEach { range ->
        visible.toRawRange(range.start, range.endExclusive)?.let(statusRanges::add)
    }
    return mergeStatusStripRanges(statusRanges)
}

private fun splitCodeFenceSegments(text: String): List<StatusStripSegment> {
    if (text.isEmpty()) return emptyList()
    val marker = "```"
    val segments = mutableListOf<StatusStripSegment>()
    var cursor = 0
    while (cursor < text.length) {
        val open = text.indexOf(marker, cursor)
        if (open < 0) {
            segments += StatusStripSegment(cursor, text.length, status = false)
            break
        }
        if (open > cursor) segments += StatusStripSegment(cursor, open, status = false)
        val headerEnd = text.indexOf('\n', open + marker.length)
        val bodyStart = if (headerEnd >= 0) headerEnd + 1 else open + marker.length
        val close = text.indexOf(marker, bodyStart)
        if (close < 0) {
            segments += StatusStripSegment(open, text.length, status = true)
            break
        }
        segments += StatusStripSegment(open, close + marker.length, status = true)
        cursor = close + marker.length
    }
    return segments
}

private val statusDashFencePattern = Regex("(?m)^[ \t]*---[ \t]*$")

private fun splitDashFenceSegments(
    text: String,
    segment: StatusStripSegment,
): List<StatusStripSegment> {
    val segmentText = text.substring(segment.start, segment.endExclusive)
    val matches = statusDashFencePattern.findAll(segmentText).toList()
    if (matches.size < 2) return listOf(segment)

    val segments = mutableListOf<StatusStripSegment>()
    var cursor = 0
    var index = 0
    while (index < matches.size) {
        val fence = matches[index]
        if (index % 2 == 0) {
            if (fence.range.first > cursor) {
                segments += StatusStripSegment(
                    segment.start + cursor,
                    segment.start + fence.range.first,
                    status = false,
                )
            }
            cursor = fence.range.last + 1
        } else {
            segments += StatusStripSegment(
                segment.start + matches[index - 1].range.first,
                segment.start + fence.range.last + 1,
                status = true,
            )
            cursor = fence.range.last + 1
        }
        index++
    }
    if (cursor < segmentText.length) {
        segments += if (matches.size % 2 == 1) {
            StatusStripSegment(
                segment.start + matches.last().range.first,
                segment.endExclusive,
                status = true,
            )
        } else {
            StatusStripSegment(
                segment.start + cursor,
                segment.endExclusive,
                status = false,
            )
        }
    }
    return segments.ifEmpty { listOf(segment) }
}

private fun visibleRoleplaySource(content: String): VisibleRoleplaySource {
    val result = StringBuilder(content.length)
    val indexes = mutableListOf<Int>()
    var cursor = 0
    while (cursor < content.length) {
        val open = content.indexOf(HIDDEN_COMMENT_OPEN, cursor)
        if (open < 0) {
            appendVisibleRange(content, cursor, content.length, result, indexes)
            break
        }

        var depth = 1
        var search = open + HIDDEN_COMMENT_OPEN.length
        var closedAt = -1
        while (search < content.length) {
            val nextOpen = content.indexOf(HIDDEN_COMMENT_OPEN, search)
            val nextClose = content.indexOf(HIDDEN_COMMENT_CLOSE, search)
            if (nextClose < 0) break
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++
                search = nextOpen + HIDDEN_COMMENT_OPEN.length
            } else {
                depth--
                search = nextClose + HIDDEN_COMMENT_CLOSE.length
                if (depth == 0) {
                    closedAt = search
                    break
                }
            }
        }

        if (closedAt < 0) {
            appendVisibleRange(content, cursor, content.length, result, indexes)
            break
        }

        val lineStart = content.lastIndexOf('\n', open - 1).let { if (it < 0) 0 else it + 1 }
        val openerStartsLine = content.substring(lineStart, open).all(::isHorizontalWhitespace)
        var afterCloseLineEnd = closedAt
        while (afterCloseLineEnd < content.length && isHorizontalWhitespace(content[afterCloseLineEnd])) {
            afterCloseLineEnd++
        }
        val closesLine = afterCloseLineEnd >= content.length || content[afterCloseLineEnd] == '\n'
        val standaloneBlock = openerStartsLine && closesLine
        appendVisibleRange(content, cursor, if (standaloneBlock) lineStart else open, result, indexes)
        cursor = if (standaloneBlock && afterCloseLineEnd < content.length) {
            afterCloseLineEnd + 1
        } else {
            closedAt
        }
    }
    return VisibleRoleplaySource(result.toString(), indexes.toIntArray())
}

private fun appendVisibleRange(
    content: String,
    start: Int,
    endExclusive: Int,
    result: StringBuilder,
    indexes: MutableList<Int>,
) {
    for (index in start until endExclusive) {
        result.append(content[index])
        indexes += index
    }
}

private fun VisibleRoleplaySource.toRawRange(start: Int, endExclusive: Int): StatusStripRange? {
    if (endExclusive <= start) return null
    val rawStart = rawIndexes.getOrNull(start) ?: return null
    val rawEnd = (rawIndexes.getOrNull(endExclusive - 1) ?: return null) + 1
    return StatusStripRange(rawStart, rawEnd)
}

private fun mergeStatusStripRanges(ranges: List<StatusStripRange>): List<StatusStripRange> {
    if (ranges.isEmpty()) return emptyList()
    val merged = mutableListOf<StatusStripRange>()
    ranges.sortedBy(StatusStripRange::start).forEach { range ->
        val previous = merged.lastOrNull()
        if (previous == null || range.start > previous.endExclusive) {
            merged += range
        } else {
            merged[merged.lastIndex] = StatusStripRange(
                start = previous.start,
                endExclusive = maxOf(previous.endExclusive, range.endExclusive),
            )
        }
    }
    return merged
}

private fun findLongDashWrappedRanges(text: String, start: Int, end: Int): List<StatusStripRange> {
    val ranges = mutableListOf<StatusStripRange>()
    var lineStart = start
    while (lineStart < end) {
        val lineEnd = text.indexOf('\n', lineStart).takeIf { it >= 0 && it < end } ?: end
        val sameLineRanges = findSameLineLongDashWrappedRanges(text, lineStart, lineEnd)
        if (sameLineRanges.isNotEmpty()) {
            ranges += sameLineRanges
            lineStart = nextLineStart(lineEnd, end)
            continue
        }

        val lineOnlyOpen = lineOnlyLongDashRun(text, lineStart, lineEnd)
        val openLine = lineOnlyOpen ?: trailingLongDashRun(text, lineStart, lineEnd)
        if (openLine != null) {
            val blockStart = if (lineOnlyOpen != null) lineStart else openLine.start
            var searchLineStart = nextLineStart(lineEnd, end)
            var foundClose = false
            while (searchLineStart < end) {
                val searchLineEnd = text.indexOf('\n', searchLineStart)
                    .takeIf { it >= 0 && it < end } ?: end
                val closeLine = lineOnlyLongDashRun(text, searchLineStart, searchLineEnd)
                if (closeLine != null && hasLongDashWrappedBody(text, lineEnd, searchLineStart)) {
                    ranges += StatusStripRange(blockStart, searchLineEnd)
                    lineStart = nextLineStart(searchLineEnd, end)
                    foundClose = true
                    break
                }
                searchLineStart = nextLineStart(searchLineEnd, end)
            }
            if (foundClose) continue
            if (hasLongDashWrappedBody(text, lineEnd, end)) {
                ranges += StatusStripRange(blockStart, end)
                lineStart = end
                continue
            }
        }
        lineStart = nextLineStart(lineEnd, end)
    }
    return ranges
}

private fun findSameLineLongDashWrappedRanges(
    text: String,
    lineStart: Int,
    lineEnd: Int,
): List<StatusStripRange> {
    val ranges = mutableListOf<StatusStripRange>()
    var cursor = lineStart
    while (cursor < lineEnd) {
        val open = nextLongDashRun(text, cursor, lineEnd) ?: break
        var close = nextLongDashRun(text, open.endExclusive, lineEnd)
        var found = false
        while (close != null) {
            if (hasLongDashWrappedBody(text, open.endExclusive, close.start)) {
                ranges += StatusStripRange(open.start, close.endExclusive)
                cursor = close.endExclusive
                found = true
                break
            }
            close = nextLongDashRun(text, close.endExclusive, lineEnd)
        }
        if (!found) cursor = open.endExclusive
    }
    return ranges
}

private fun lineOnlyLongDashRun(text: String, lineStart: Int, lineEnd: Int): StatusStripRange? {
    var start = lineStart
    while (start < lineEnd && isHorizontalWhitespace(text[start])) start++
    var end = lineEnd
    while (end > start && isHorizontalWhitespace(text[end - 1])) end--
    val run = nextLongDashRun(text, start, end) ?: return null
    return if (run.start == start && run.endExclusive == end) run else null
}

private fun trailingLongDashRun(text: String, lineStart: Int, lineEnd: Int): StatusStripRange? {
    var end = lineEnd
    while (end > lineStart && isHorizontalWhitespace(text[end - 1])) end--
    if (end <= lineStart || !isLongWrapperDash(text[end - 1])) return null
    var start = end - 1
    while (start > lineStart && isLongWrapperDash(text[start - 1])) start--
    return if (end - start >= minLongWrapperDashCount(text[start])) {
        StatusStripRange(start, end)
    } else {
        null
    }
}

private fun nextLineStart(lineEnd: Int, end: Int): Int = if (lineEnd < end) lineEnd + 1 else end

private fun nextLongDashRun(text: String, start: Int, end: Int): StatusStripRange? {
    var cursor = start
    while (cursor < end) {
        if (!isLongWrapperDash(text[cursor])) {
            cursor++
            continue
        }
        val runStart = cursor
        while (cursor < end && isLongWrapperDash(text[cursor])) cursor++
        if (cursor - runStart >= minLongWrapperDashCount(text[runStart])) {
            return StatusStripRange(runStart, cursor)
        }
    }
    return null
}

private fun hasLongDashWrappedBody(text: String, start: Int, end: Int): Boolean =
    end > start && text.substring(start, end).any { !it.isWhitespace() && !isLongWrapperDash(it) }

private fun isHorizontalWhitespace(char: Char): Boolean =
    char == ' ' || char == '\t' || char == '\r'

private fun isLongWrapperDash(char: Char): Boolean =
    char == '-' || char == '\u2014' || char == '\u2015' || char == '\u2500' || char == '\uFF0D'

private fun minLongWrapperDashCount(char: Char): Int = if (char == '-') 6 else 4

private const val HIDDEN_COMMENT_OPEN = "<!--"
private const val HIDDEN_COMMENT_CLOSE = "-->"
