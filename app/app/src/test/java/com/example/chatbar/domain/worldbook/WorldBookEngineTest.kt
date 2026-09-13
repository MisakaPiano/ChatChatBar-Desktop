package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookEngineTest {
    private val engine = WorldBookEngine()

    @Test
    fun evaluateAllKeepsBookOrderAndEntryOrderInsideEachBook() {
        val roleBook = book("role", listOf(entry("r2", "role", "role late", order = 20), entry("r1", "role", "role early", order = 10)))
        val sessionBook = book("session", listOf(entry("s1", "session", "session lore", order = 5)))

        val activated = engine.evaluateAll(
            books = listOf(roleBook, sessionBook),
            messages = listOf(msg("role session"))
        )

        assertEquals(listOf("role early", "role late", "session lore"), activated.map { it.entry.content })
    }

    @Test
    fun entryScanDepthOverridesBookScanDepth() {
        val shallow = entry("shallow", "old", "miss")
        val deep = entry("deep", "old", "hit", scanDepth = 2)
        val book = book("book", listOf(shallow, deep), scanDepth = 1)

        val activated = engine.evaluate(book, listOf(msg("old clue"), msg("new text")))

        assertEquals(listOf("hit"), activated.map { it.entry.content })
    }

    @Test
    fun secondaryLogicWholeWordAndRegexWork() {
        val entries = listOf(
            entry("all", "alpha", "all", secondary = listOf("beta", "gamma"), logic = WorldBookSelectiveLogic.AND_ALL.value, selective = true),
            entry("notAny", "alpha", "not any", secondary = listOf("delta"), logic = WorldBookSelectiveLogic.NOT_ANY.value, selective = true),
            entry("whole", "cat", "whole", wholeWord = true),
            entry("regex", "/a.+a/", "regex", regex = true)
        )
        val book = book("book", entries)

        val activated = engine.evaluate(book, listOf(msg("alpha beta gamma catalog arena")))

        assertEquals(listOf("all", "not any", "regex"), activated.map { it.entry.content })
    }

    @Test
    fun outletsConcatenateByInsertionOrder() {
        val book = book(
            "book",
            listOf(
                entry("b", "key", "second", order = 20, position = WorldBookPosition.OUTLET, outlet = "memo"),
                entry("a", "key", "first", order = 10, position = WorldBookPosition.OUTLET, outlet = "memo")
            )
        )

        val activated = engine.evaluate(book, listOf(msg("key")))
        val outlets = engine.collectOutlets(activated)

        assertEquals("first\nsecond", outlets["memo"])
        assertEquals("A first\nsecond B", engine.expandOutlets("A {{outlet::memo}} B", outlets))
    }

    @Test
    fun stickyAndCooldownStatesAffectActivation() {
        val sticky = entry("sticky", "spark", "sticky", sticky = 2, cooldown = 3)
        val book = book("book", listOf(sticky))
        val first = engine.evaluate(book, listOf(msg("spark")), messageCount = 10)
        val states = engine.computeTimedStates(emptyMap(), first.map { it.entry.id }.toSet(), mapOf(sticky.id to sticky), 10)

        val stickyHit = engine.evaluate(book, listOf(msg("nothing")), states, messageCount = 11)
        val cooldownMiss = engine.evaluate(book, listOf(msg("spark")), states, messageCount = 13)

        assertEquals(listOf("sticky"), stickyHit.map { it.entry.content })
        assertTrue(cooldownMiss.isEmpty())
    }

    @Test fun stickyActivationDoesNotExtendItsOwnExpiry() {
        val entry = entry("sticky", "spark", "lore", sticky = 2, cooldown = 3)
        val book = book("book", listOf(entry))
        val initial = engine.computeTimedStates(emptyMap(), setOf(entry.id), mapOf(entry.id to entry), 10)
        val next = engine.computeTimedStates(initial, setOf(entry.id), mapOf(entry.id to entry), 12)
        assertEquals(initial, next)
        assertTrue(engine.evaluate(book, listOf(msg("spark")), next, 13).isEmpty())
        assertEquals(1, engine.evaluate(book, listOf(msg("spark")), next, 15).size)
    }

    @Test fun blankKeywordsNeverActivateUnrelatedContent() {
        val book = book("book", listOf(entry("empty", "", "wrong"), entry("space", "  ", "wrong")))
        assertTrue(engine.evaluate(book, listOf(msg("anything"))).isEmpty())
    }

    @Test fun wholeWordDefaultIsInheritedAndEntryCanOverride() {
        val book = book("book", listOf(entry("default", "cat", "no"), entry("override", "cat", "yes", wholeWord = false)))
            .copy(matchWholeWords = true)
        assertEquals(listOf("yes"), engine.evaluate(book, listOf(msg("catalog"))).map { it.entry.content })
    }

    @Test fun zeroProbabilityNeverActivatesAndDiagnosticExplainsBudgetRejection() {
        val logs = mutableListOf<String>()
        val book = book("book", listOf(entry("zero", "key", "no").copy(probability = 0), entry("budget", "key", "long lore")))
            .copy(tokenBudget = 0)
        assertTrue(engine.evaluateAll(listOf(book), listOf(msg("key")), debugLog = { logs.add(it) }).isEmpty())
        assertTrue(logs.any { it.contains("zero") && it.contains("0%") })
        assertTrue(logs.any { it.contains("budget") && it.contains("token") })
    }

    private fun book(id: String, entries: List<WorldBookEntry>, scanDepth: Int = 10) =
        WorldBook(id = id, name = id, entries = entries, scanDepth = scanDepth)

    private fun entry(
        id: String,
        key: String,
        content: String,
        order: Int = 100,
        secondary: List<String> = emptyList(),
        logic: Int = WorldBookSelectiveLogic.AND_ANY.value,
        selective: Boolean = false,
        wholeWord: Boolean? = null,
        regex: Boolean = false,
        scanDepth: Int? = null,
        position: WorldBookPosition = WorldBookPosition.BEFORE_CHAR,
        outlet: String = "",
        sticky: Int = 0,
        cooldown: Int = 0
    ) = WorldBookEntry(
        id = id,
        keys = listOf(key),
        content = content,
        insertionOrder = order,
        secondaryKeys = secondary,
        selectiveLogic = logic,
        selective = selective,
        matchWholeWords = wholeWord,
        useRegex = regex,
        scanDepth = scanDepth,
        position = position,
        outletName = outlet,
        sticky = sticky,
        cooldown = cooldown
    )

    private fun msg(content: String) =
        ChatMessage(id = content, sessionId = "s", role = MessageRole.USER, content = content, createdAt = 1L, updatedAt = 1L)
}
