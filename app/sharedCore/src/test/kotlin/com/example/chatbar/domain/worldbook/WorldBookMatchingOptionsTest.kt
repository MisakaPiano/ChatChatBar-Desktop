package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class WorldBookMatchingOptionsTest {
    private val engine = WorldBookEngine()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `entry case override and book default remain distinct`() {
        val inherited = WorldBookEntry(id = "inherit", keys = listOf("Alice"))
        val insensitive = inherited.copy(id = "off", caseSensitive = false)
        val sensitive = inherited.copy(id = "on", caseSensitive = true)
        val book = WorldBook(
            id = "b",
            name = "book",
            caseSensitive = true,
            entries = listOf(inherited, insensitive, sensitive),
        )

        assertEquals(listOf("off"), engine.evaluate(book, listOf(message("alice"))).map { it.entry.id })
        assertEquals(
            listOf("inherit", "off"),
            engine.evaluate(book.copy(caseSensitive = false), listOf(message("alice"))).map { it.entry.id },
        )
    }

    @Test
    fun `legacy false is preserved while missing and null inherit`() {
        assertEquals(
            false,
            json.decodeFromString<WorldBookEntry>("""{"id":"old","caseSensitive":false}""").caseSensitive,
        )
        assertNull(json.decodeFromString<WorldBookEntry>("""{"id":"new"}""").caseSensitive)
        assertNull(
            json.decodeFromString<WorldBookEntry>("""{"id":"new","caseSensitive":null}""").caseSensitive
        )
    }

    @Test
    fun `every extra matching source remains opt in with zero history depth`() {
        val flags: List<(WorldBookEntry) -> WorldBookEntry> = listOf(
            { it.copy(matchCharacterDescription = true) },
            { it.copy(matchCharacterPersonality = true) },
            { it.copy(matchScenario = true) },
            { it.copy(matchCreatorNotes = true) },
            { it.copy(matchPersonaDescription = true) },
        )
        val sources = listOf(
            "appearance-match",
            "temperament-match",
            "setting-match",
            "author-match",
            "player-match",
        )
        val context = WorldBookScanContext(sources[0], sources[1], sources[2], sources[3], sources[4])

        sources.forEachIndexed { index, key ->
            val base = WorldBookEntry(id = key, keys = listOf(key), scanDepth = 0)
            flags.forEachIndexed { flagIndex, flag ->
                val book = WorldBook(id = "b", name = "book", entries = listOf(flag(base)))
                assertEquals(
                    index == flagIndex,
                    engine.evaluate(book, emptyList(), scanContext = context).isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `extra sources participate in secondary conditions without being injected`() {
        val entry = WorldBookEntry(
            id = "e",
            keys = listOf("primary"),
            secondaryKeys = listOf("secondary"),
            selective = true,
            matchPersonaDescription = true,
            content = "only lore",
        )
        val book = WorldBook(id = "b", name = "book", entries = listOf(entry))

        assertTrue(engine.evaluate(book, listOf(message("primary"))).isEmpty())
        val hits = engine.evaluate(
            book,
            listOf(message("primary")),
            scanContext = WorldBookScanContext(personaDescription = "secondary"),
        )
        assertEquals("only lore", engine.buildWorldBookPrompt(hits, "bot", "player"))
    }

    @Test
    fun `freeform sections stay separated examples stay excluded and placeholders render`() {
        val card = CharacterCard(
            id = "c",
            name = "bot",
            editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = "【人物描述】\ndescription\n【性格特点】\npersonality\n【背景场景】\nscenario\n【对话示例】\nnot-scanned",
            creatorNotes = "notes",
            createdAt = 0,
            updatedAt = 0,
        )

        val context = WorldBookScanContext.fromCard(card, "{{user}} persona", "Alice")

        assertEquals("description", context.characterDescription)
        assertEquals("personality", context.characterPersonality)
        assertEquals("scenario", context.scenario)
        assertEquals("notes", context.creatorNotes)
        assertEquals("Alice persona", context.personaDescription)
        assertFalse(
            listOf(
                context.characterDescription,
                context.characterPersonality,
                context.scenario,
                context.creatorNotes,
                context.personaDescription,
            ).any { "not-scanned" in it }
        )
        assertEquals(
            "changed",
            WorldBookScanContext.fromCard(
                card.copy(freeformCharacterText = "changed"),
                playerSetting = "",
                playerName = "Alice",
            ).characterDescription,
        )
    }

    @Test
    fun `structured card fields remain separated matching sources`() {
        val character = CharacterInfo(
            id = "character",
            name = "Name",
            profile = "Profile",
            appearance = "Appearance",
            clothing = "Clothing",
            abilities = "Abilities",
            habits = "Habits",
            background = "Background",
            relationships = "Relationships",
            speakingStyle = "Speaking",
        )
        val card = CharacterCard(
            id = "card",
            name = "Bot",
            basicSetting = "Scenario",
            creatorNotes = "Creator",
            characters = listOf(character),
            createdAt = 0,
            updatedAt = 0,
        )

        val context = WorldBookScanContext.fromCard(card, "Player", "Alice")

        assertEquals(
            listOf("Name", "Profile", "Appearance", "Clothing", "Abilities", "Background", "Relationships"),
            context.characterDescription.lines(),
        )
        assertEquals(listOf("Habits", "Speaking"), context.characterPersonality.lines())
        assertEquals("Scenario", context.scenario)
        assertEquals("Creator", context.creatorNotes)
        assertEquals("Player", context.personaDescription)
    }

    private fun message(text: String) = ChatMessage(
        id = "m",
        sessionId = "s",
        role = MessageRole.USER,
        content = text,
        createdAt = 0,
        updatedAt = 0,
    )
}
