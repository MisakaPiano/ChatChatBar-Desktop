package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import com.example.chatbar.domain.chat.PlaceholderRenderer

class WorldBookEngine {

    data class ActivatedEntry(
        val entry: WorldBookEntry,
        val sourceBookId: String,
        val sourceBookName: String
    )

    data class TimedState(
        val entryId: String,
        val stickyUntil: Int = 0,
        val cooldownUntil: Int = 0
    )

    data class ActivateResult(
        val activated: List<ActivatedEntry>,
        val outlets: Map<String, String>
    )

    fun evaluate(
        book: WorldBook,
        messages: List<ChatMessage>,
        timedStates: Map<String, TimedState> = emptyMap(),
        messageCount: Int = messages.size,
        filteredEntries: List<WorldBookEntry> = book.entries,
        debugLog: (String) -> Unit = {},
        scanContext: WorldBookScanContext = WorldBookScanContext()
    ): List<ActivatedEntry> {
        val effectiveEntries = filteredEntries.map { entry ->
            entry.copy(
                caseSensitive = entry.caseSensitive ?: book.caseSensitive,
                matchWholeWords = entry.matchWholeWords ?: book.matchWholeWords
            )
        }
        return evaluateInternal(book, effectiveEntries, messages, book.id, book.name, timedStates, messageCount,
            allowRecursion = { book.recursiveScanning }, debugLog = debugLog, scanContext = scanContext)
    }

    fun evaluateAll(
        books: List<WorldBook>,
        messages: List<ChatMessage>,
        timedStates: Map<String, Map<String, TimedState>> = emptyMap(),
        messageCount: Int = messages.size,
        characterTokens: Set<String> = emptySet(),
        debugLog: (String) -> Unit = {},
        scanContext: WorldBookScanContext = WorldBookScanContext()
    ): List<ActivatedEntry> {
        val results = mutableListOf<ActivatedEntry>()

        for (book in books) {
            val bookTimed = timedStates[book.id] ?: emptyMap()
            val filtered = filterEntriesByCharacter(book.entries, characterTokens)
            book.entries.filterNot { it in filtered }.forEach {
                debugLog("世界书[${book.name}/${it.name.ifBlank { it.id }}]：角色过滤未通过。")
            }
            results += evaluate(book, messages, bookTimed, messageCount, filtered, debugLog, scanContext)
        }

        val accepted = applyBudgetAndSort(results, books)
        results.filterNot { it in accepted }.forEach {
            debugLog("世界书[${it.sourceBookName}/${it.entry.name.ifBlank { it.entry.id }}]：命中但超出书的 token 预算。")
        }
        accepted.forEach {
            debugLog("世界书[${it.sourceBookName}/${it.entry.name.ifBlank { it.entry.id }}]：选入 ${it.entry.position}，outlet=${it.entry.outletName}。")
        }
        return accepted
    }

    fun filterEntriesByCharacter(entries: List<WorldBookEntry>, tokens: Set<String>): List<WorldBookEntry> {
        if (tokens.isEmpty()) return entries
        return entries.filter { entry ->
            if (entry.characterFilter.isEmpty()) return@filter true
            val inFilter = entry.characterFilter.any { filter ->
                tokens.any { token -> token == filter.lowercase() }
            }
            if (entry.characterFilterExclude) !inFilter else inFilter
        }
    }

    private fun evaluateInternal(
        book: WorldBook,
        entries: List<WorldBookEntry>,
        messages: List<ChatMessage>,
        bookId: String,
        bookName: String,
        timedStates: Map<String, TimedState>,
        messageCount: Int,
        allowRecursion: () -> Boolean,
        depth: Int = 0,
        maxDepth: Int = 5,
        alreadyActivated: MutableSet<String> = mutableSetOf(),
        debugLog: (String) -> Unit = {},
        scanContext: WorldBookScanContext = WorldBookScanContext()
    ): List<ActivatedEntry> {
        val activated = mutableListOf<ActivatedEntry>()

        for (entry in entries) {
            fun report(reason: String) = debugLog("世界书[$bookName/${entry.name.ifBlank { entry.id }}]：$reason（递归层 $depth）")
            if (!entry.enabled) {
                report("已禁用")
                continue
            }
            if (entry.id in alreadyActivated) continue

            val timed = timedStates[entry.id]
            val currentMsg = messageCount

            // Delay check
            if (entry.delay > 0 && currentMsg < entry.delay) {
                report("未到延迟消息数 ${entry.delay}")
                continue
            }
            if (depth == 0 && entry.delayUntilRecursion) {
                report("仅在递归阶段扫描")
                continue
            }
            if (depth > 0 && entry.excludeRecursion) continue
            entry.recursionLevel?.let { level ->
                if (depth > 0 && depth < level) continue
            }

            // Sticky check - auto-activate if sticky
            val isSticky = timed != null && timed.stickyUntil > 0 && currentMsg <= timed.stickyUntil
            if (!isSticky && timed != null && timed.cooldownUntil > 0 && currentMsg < timed.cooldownUntil) {
                report("冷却中，截止 ${timed.cooldownUntil}")
                continue
            }

            val shouldActivate = isSticky || entry.constant || matchesKeys(entry, messages, book.scanDepth, scanContext)

            if (shouldActivate) {
                // Probability check (only for non-constant, non-sticky)
                if (!isSticky && !entry.constant && entry.probability < 100) {
                    if (Math.random() * 100 >= entry.probability) {
                        report("概率筛选未通过 ${entry.probability}%")
                        continue
                    }
                }

                activated += ActivatedEntry(entry, bookId, bookName)
                alreadyActivated += entry.id
                report(if (isSticky) "黏附命中" else if (entry.constant) "常驻命中" else "关键词命中")
            } else {
                report("关键词条件未满足，扫描深度 ${entry.scanDepth ?: book.scanDepth}，主键 ${entry.keys}，辅助键 ${entry.secondaryKeys}")
            }
        }

        // Group competition: keep only one per group
        val groupWinners = mutableMapOf<String, ActivatedEntry>()
        for (act in activated) {
            val g = act.entry.group
            if (g.isBlank()) continue
            val existing = groupWinners[g]
            if (existing == null || act.entry.groupWeight > existing.entry.groupWeight) {
                groupWinners[g] = act
            }
        }
        val toRemove = activated.filter { it.entry.group.isNotBlank() && groupWinners[it.entry.group] != it }
        toRemove.forEach { debugLog("世界书[$bookName/${it.entry.name.ifBlank { it.entry.id }}]：同组竞争未选入 ${it.entry.group}。") }
        activated.removeAll(toRemove)

        // Recursive scanning
        if (allowRecursion() && depth < maxDepth) {
            val triggeredContent = activated
                .filterNot { it.entry.preventRecursion }
                .joinToString("\n") { it.entry.content }
            if (triggeredContent.isNotBlank()) {
                val now = System.currentTimeMillis()
                val recursiveMsg = listOf(
                    ChatMessage(id = "", sessionId = "", role = MessageRole.USER, content = triggeredContent, createdAt = now, updatedAt = now)
                )
                val recurse = evaluateInternal(
                    book, entries, recursiveMsg, bookId, bookName,
                    timedStates, messageCount, allowRecursion, depth + 1, maxDepth, alreadyActivated, debugLog, scanContext
                )
                activated += recurse
            }
        }

        return activated
    }

    fun applyBudgetAndSort(
        activated: List<ActivatedEntry>,
        books: List<WorldBook>
    ): List<ActivatedEntry> {
        val result = mutableListOf<ActivatedEntry>()

        for (book in books) {
            val bookActivated = activated.filter { it.sourceBookId == book.id }
            val budget = book.tokenBudget ?: Int.MAX_VALUE
            var used = 0
            val accepted = mutableListOf<ActivatedEntry>()
            for (act in bookActivated.sortedByDescending { it.entry.insertionOrder }) {
                val tokenEstimate = estimateTokens(act.entry.content)
                if (act.entry.ignoreBudget || used + tokenEstimate <= budget) {
                    accepted += act
                    if (!act.entry.ignoreBudget) used += tokenEstimate
                }
            }
            result += accepted.sortedBy { it.entry.insertionOrder }
        }

        return result
    }

    private fun matchesKeys(entry: WorldBookEntry, messages: List<ChatMessage>, bookScanDepth: Int, scanContext: WorldBookScanContext): Boolean {
        if (entry.keys.isEmpty()) return false
        val depth = (entry.scanDepth ?: bookScanDepth).coerceAtLeast(0)
        val buffer = (messages.takeLast(depth).map { it.displayContent } + scanContext.selectedText(entry))
            .filter(String::isNotBlank).joinToString("\n")
        if (buffer.isBlank()) return false
        val effectiveBuffer = if (entry.caseSensitive == true) buffer else buffer.lowercase()

        var primaryMatched = false
        for (key in entry.keys) {
            val matched = matchKey(key, buffer, effectiveBuffer, entry)
            if (matched) {
                primaryMatched = true
                break
            }
        }
        if (!primaryMatched) return false
        if (!entry.selective || entry.secondaryKeys.isEmpty()) return true

        val secondaryMatches = entry.secondaryKeys.map { sk -> matchKey(sk, buffer, effectiveBuffer, entry) }
        val any = secondaryMatches.any { it }
        val all = secondaryMatches.all { it }
        return when (entry.selectiveLogic) {
            WorldBookSelectiveLogic.NOT_ALL.value -> !all
            WorldBookSelectiveLogic.NOT_ANY.value -> !any
            WorldBookSelectiveLogic.AND_ALL.value -> all
            else -> any
        }
    }

    private fun matchKey(
        key: String,
        buffer: String,
        effectiveBuffer: String,
        entry: WorldBookEntry
    ): Boolean {
        if (key.isBlank()) return false
        if (entry.useRegex) {
            // Check literal match first
            val literalKey = if (entry.caseSensitive == true) key else key.lowercase()
            if (effectiveBuffer.contains(literalKey)) return true
            // Then try regex match
            return tryRegexMatch(key, buffer, entry.caseSensitive == true)
        }
        val effectiveKey = if (entry.caseSensitive == true) key else key.lowercase()
        if (entry.matchWholeWords == true) {
            if (effectiveKey.contains(Regex("\\s"))) return effectiveBuffer.contains(effectiveKey)
            return Regex("(?:^|\\W)${Regex.escape(effectiveKey)}(?:$|\\W)")
                .containsMatchIn(effectiveBuffer)
        }
        return effectiveBuffer.contains(effectiveKey)
    }

    private fun tryRegexMatch(key: String, buffer: String, caseSensitive: Boolean): Boolean {
        return try {
            val (pattern, flags) = if (isRegexKey(key)) parseRegexKey(key) else key to ""
            val options = mutableSetOf<RegexOption>()
            if (!caseSensitive || flags.contains("i")) options += RegexOption.IGNORE_CASE
            Regex(pattern, options).containsMatchIn(buffer)
        } catch (_: Exception) { false }
    }

    private fun isRegexKey(key: String): Boolean =
        key.startsWith("/") && key.length > 2 && key.drop(1).contains("/")

    private fun parseRegexKey(key: String): Pair<String, String> {
        val lastSlash = key.lastIndexOf('/')
        if (lastSlash <= 1) return key.substring(1) to ""
        val pattern = key.substring(1, lastSlash)
        val flags = key.substring(lastSlash + 1)
        return pattern to flags
    }

    fun splitByPosition(activated: List<ActivatedEntry>): Pair<List<ActivatedEntry>, List<ActivatedEntry>> {
        val before = activated.filter { it.entry.position == WorldBookPosition.BEFORE_CHAR }
        val after = activated.filter { it.entry.position == WorldBookPosition.AFTER_CHAR }
        return before to after
    }

    fun collectOutlets(activated: List<ActivatedEntry>): Map<String, String> {
        val outlets = linkedMapOf<String, StringBuilder>()
        val outletEntries = activated.filter { it.entry.position == WorldBookPosition.OUTLET && it.entry.outletName.isNotBlank() }
            .sortedBy { it.entry.insertionOrder }
        for (act in outletEntries) {
            val sb = outlets.getOrPut(act.entry.outletName) { StringBuilder() }
            if (sb.isNotEmpty()) sb.appendLine()
            sb.append(PlaceholderRenderer.normalize(act.entry.content))
        }
        return outlets.mapValues { it.value.toString() }
    }

    fun expandOutlets(prompt: String, outlets: Map<String, String>): String {
        val regex = Regex("\\{\\{outlet::(\\w+)}}")
        return regex.replace(prompt) { mr ->
            outlets[mr.groupValues[1]] ?: mr.value
        }
    }

    fun isOutletEntry(entry: WorldBookEntry): Boolean =
        entry.position == WorldBookPosition.OUTLET && entry.outletName.isNotBlank()

    fun computeTimedStates(
        previousStates: Map<String, TimedState>,
        activatedIds: Set<String>,
        entryMap: Map<String, WorldBookEntry>,
        currentMessageCount: Int
    ): Map<String, TimedState> {
        val newStates = mutableMapOf<String, TimedState>()

        for ((id, state) in previousStates) {
            if (id !in entryMap) continue
            // Only keep states still relevant
            if (state.stickyUntil > currentMessageCount || state.cooldownUntil > currentMessageCount) {
                newStates[id] = state
            }
        }

        for (id in activatedIds) {
            val entry = entryMap[id] ?: continue
            val previous = previousStates[id]
            if (previous != null && previous.stickyUntil > 0 && currentMessageCount <= previous.stickyUntil) {
                newStates[id] = previous
                continue
            }
            var stickyUntil = 0
            var cooldownUntil = 0

            if (entry.sticky > 0) stickyUntil = currentMessageCount + entry.sticky
            if (entry.cooldown > 0) cooldownUntil = currentMessageCount + entry.sticky + entry.cooldown

            if (stickyUntil > 0 || cooldownUntil > 0) {
                newStates[id] = TimedState(id, stickyUntil, cooldownUntil)
            }
        }

        return newStates
    }

    fun buildWorldBookPrompt(
        activated: List<ActivatedEntry>,
        characterName: String,
        playerName: String?
    ): String {
        if (activated.isEmpty()) return ""

        val sb = StringBuilder()
        for (act in activated) {
            if (act.entry.content.isBlank()) continue
            val content = PlaceholderRenderer.render(act.entry.content, playerName, characterName)
            sb.appendLine(content)
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private fun estimateTokens(text: String): Int {
        var count = 0
        for (char in text) {
            count += if (char.code in 0x4E00..0x9FA5) 2 else 1
        }
        return (count * 0.4).toInt().coerceAtLeast(1)
    }

}
