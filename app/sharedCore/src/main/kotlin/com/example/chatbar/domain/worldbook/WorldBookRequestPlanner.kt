package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.TimedEffectState
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.WorldBookRepository

data class WorldBookRequestPlan(
    val prompt: String?,
    val outlets: Map<String, String>,
    val timedWorldInfo: Map<String, TimedEffectState>,
)

/**
 * 单次聊天请求的 WorldBook authority。
 *
 * 只读取 repository 并返回 request-only prompt/outlets 与下一份 timed state；调用方负责解析
 * player/persona settings，也负责在需要时持久化 [WorldBookRequestPlan.timedWorldInfo]。
 */
class WorldBookRequestPlanner(
    private val chatRepository: ChatRepository,
    private val worldBookRepository: WorldBookRepository,
    private val engine: WorldBookEngine = WorldBookEngine(),
) {
    suspend fun plan(
        card: CharacterCard,
        session: ChatSession,
        previousTimed: Map<String, TimedEffectState> = session.timedWorldInfo,
        excludedMessageId: String? = null,
        transientUserMessage: ChatMessage? = null,
        scanContext: WorldBookScanContext = WorldBookScanContext(),
        playerName: String? = null,
        debugLog: (String) -> Unit = {},
        readOnlyRepositoryAccess: Boolean = false,
    ): WorldBookRequestPlan {
        val worldBooks = resolveWorldBooks(card, session)
        if (worldBooks.isEmpty()) {
            debugLog("世界书：当前角色和会话未绑定世界书。")
            return WorldBookRequestPlan(null, emptyMap(), emptyMap())
        }

        val scanDepth = worldBooks.maxOf { book ->
            maxOf(
                book.scanDepth,
                book.entries.filter { it.enabled }.maxOfOrNull { it.scanDepth ?: book.scanDepth } ?: 0,
            )
        }.coerceAtLeast(0)
        val (storedMessageCount, storedMessages) = if (readOnlyRepositoryAccess) {
            chatRepository.getWorldBookScanSnapshotReadOnly(
                sessionId = session.id,
                scanDepth = scanDepth,
                excludedMessageId = excludedMessageId,
            )
        } else {
            chatRepository.getWorldBookScanSnapshot(
                sessionId = session.id,
                scanDepth = scanDepth,
                excludedMessageId = excludedMessageId,
            )
        }
        val messages = storedMessages + listOfNotNull(transientUserMessage)
        val messageCount = storedMessageCount + if (transientUserMessage != null) 1 else 0
        debugLog("世界书：扫描最近 $scanDepth 条，实际载入 ${messages.size} 条，计时消息数 $messageCount。")
        worldBooks.forEach { book ->
            debugLog("世界书来源：${book.name} [${book.id}]，版本 ${book.updatedAt}，词条 ${book.entries.size}。")
        }

        val characterTokens = buildSet {
            add(card.name.lowercase())
            card.characters.mapTo(this) { it.name.lowercase() }
            if (card.editMode == CharacterEditMode.FREEFORM) {
                Regex("【角色名称】\\s*\\n?\\s*(\\S+)").findAll(card.freeformCharacterText)
                    .mapTo(this) { it.groupValues[1].lowercase().trim() }
            }
        }
        val timedStates = previousTimed.mapValues { (_, state) ->
            WorldBookEngine.TimedState(state.entryId, state.stickyUntil, state.cooldownUntil)
        }
        val bookTimedStates = worldBooks.associate { book ->
            book.id to book.entries.mapNotNull { entry ->
                (timedStates[timedKey(book.id, entry.id)] ?: timedStates[entry.id])
                    ?.let { entry.id to it }
            }.toMap()
        }
        val activated = engine.evaluateAll(
            books = worldBooks,
            messages = messages,
            timedStates = bookTimedStates,
            messageCount = messageCount,
            characterTokens = characterTokens,
            debugLog = debugLog,
            scanContext = scanContext,
        )
        val promptEntries = activated.filter { it.entry.position == WorldBookPosition.BEFORE_CHAR } +
            activated.filter { it.entry.position == WorldBookPosition.AFTER_CHAR }
        val prompt = if (promptEntries.isEmpty()) {
            null
        } else {
            engine.buildWorldBookPrompt(promptEntries, card.effectiveBotName, playerName)
        }
        val outlets = engine.collectOutlets(activated)
        val newTimed = worldBooks.flatMap { book ->
            engine.computeTimedStates(
                previousStates = bookTimedStates[book.id].orEmpty(),
                activatedIds = activated.filter { it.sourceBookId == book.id }.mapTo(mutableSetOf()) {
                    it.entry.id
                },
                entryMap = book.entries.associateBy { it.id },
                currentMessageCount = messageCount,
            ).map { (entryId, state) ->
                timedKey(book.id, entryId) to TimedEffectState(
                    entryId = entryId,
                    stickyUntil = state.stickyUntil,
                    cooldownUntil = state.cooldownUntil,
                )
            }
        }.toMap()

        return WorldBookRequestPlan(prompt, outlets, newTimed)
    }

    private suspend fun resolveWorldBooks(card: CharacterCard, session: ChatSession): List<WorldBook> {
        val resolved = mutableListOf<WorldBook>()
        card.characterBook?.let(resolved::add)
        card.boundWorldBookId?.let { id -> worldBookRepository.getById(id)?.let(resolved::add) }
        card.worldBookIds.forEach { id -> worldBookRepository.getById(id)?.let(resolved::add) }
        session.extraWorldBookIds.forEach { id -> worldBookRepository.getById(id)?.let(resolved::add) }

        // 后解析的 repository 对象覆盖同 ID 的旧副本，但首次出现位置不变。
        return resolved.map { book -> resolved.last { it.id == book.id } }.distinctBy(WorldBook::id)
    }

    private fun timedKey(bookId: String, entryId: String): String = "$bookId::$entryId"
}
