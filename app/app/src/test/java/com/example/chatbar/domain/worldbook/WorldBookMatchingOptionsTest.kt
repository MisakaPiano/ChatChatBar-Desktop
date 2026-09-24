package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.WorldBookTransferService
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import com.example.chatbar.domain.draft.materialize
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorldBookMatchingOptionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val engine = WorldBookEngine()
    private val json = Json { ignoreUnknownKeys = true }
    private fun message(text: String) = ChatMessage(id = "m", sessionId = "s", role = MessageRole.USER,
        content = text, createdAt = 0, updatedAt = 0)

    @Test fun entryCaseOverrideAndBookDefaultRemainDistinct() {
        val inherited = WorldBookEntry(id = "inherit", keys = listOf("Alice"))
        val insensitive = inherited.copy(id = "off", caseSensitive = false)
        val sensitive = inherited.copy(id = "on", caseSensitive = true)
        val book = WorldBook(id = "b", name = "book", caseSensitive = true, entries = listOf(inherited, insensitive, sensitive))
        assertEquals(listOf("off"), engine.evaluate(book, listOf(message("alice"))).map { it.entry.id })
        assertEquals(listOf("inherit", "off"), engine.evaluate(book.copy(caseSensitive = false), listOf(message("alice"))).map { it.entry.id })
    }

    @Test fun oldFalseIsPreservedButMissingAndNullInherit() {
        assertEquals(false, json.decodeFromString<WorldBookEntry>("""{"id":"old","caseSensitive":false}""").caseSensitive)
        assertNull(json.decodeFromString<WorldBookEntry>("""{"id":"new"}""").caseSensitive)
        assertNull(json.decodeFromString<WorldBookEntry>("""{"id":"new","caseSensitive":null}""").caseSensitive)
    }

    @Test fun everyExtraSourceIsOptInEvenWithZeroHistoryDepth() {
        val flags: List<(WorldBookEntry) -> WorldBookEntry> = listOf(
            { it.copy(matchCharacterDescription = true) }, { it.copy(matchCharacterPersonality = true) },
            { it.copy(matchScenario = true) }, { it.copy(matchCreatorNotes = true) }, { it.copy(matchPersonaDescription = true) }
        )
        // Partial matching is intentional; source markers must not contain one another.
        val sources = listOf("appearance-match", "temperament-match", "setting-match", "author-match", "player-match")
        val context = WorldBookScanContext(sources[0], sources[1], sources[2], sources[3], sources[4])
        sources.forEachIndexed { index, key ->
            val base = WorldBookEntry(id = key, keys = listOf(key), scanDepth = 0)
            flags.forEachIndexed { flagIndex, flag ->
                val book = WorldBook(id = "b", name = "book", entries = listOf(flag(base)))
                assertEquals(index == flagIndex, engine.evaluate(book, emptyList(), scanContext = context).isNotEmpty())
            }
        }
    }

    @Test fun extraSourcesParticipateInSecondaryConditionsWithoutBeingInjected() {
        val entry = WorldBookEntry(id = "e", keys = listOf("primary"), secondaryKeys = listOf("secondary"),
            selective = true, matchPersonaDescription = true, content = "only lore")
        val book = WorldBook(id = "b", name = "book", entries = listOf(entry))
        assertTrue(engine.evaluate(book, listOf(message("primary"))).isEmpty())
        val hits = engine.evaluate(book, listOf(message("primary")), scanContext = WorldBookScanContext(personaDescription = "secondary"))
        assertEquals("only lore", engine.buildWorldBookPrompt(hits, "bot", "player"))
    }

    @Test fun currentFreeformSectionsStaySeparatedAndPlaceholdersRender() {
        val card = CharacterCard(id = "c", name = "bot", editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = "【人物描述】\ndescription\n【性格特点】\npersonality\n【背景场景】\nscenario\n【对话示例】\nnot-scanned",
            creatorNotes = "notes", createdAt = 0, updatedAt = 0)
        val context = WorldBookScanContext.fromCard(card, "{{user}} persona", "Alice")
        assertEquals("description", context.characterDescription)
        assertEquals("personality", context.characterPersonality)
        assertEquals("scenario", context.scenario)
        assertEquals("notes", context.creatorNotes)
        assertEquals("Alice persona", context.personaDescription)
        assertEquals("changed", WorldBookScanContext.fromCard(card.copy(freeformCharacterText = "changed"), "", "Alice").characterDescription)
    }

    @Test fun importExportAndEditorPreserveMatchingOptions() = runTest {
        val repository = WorldBookRepository(JsonFileStorage(temp.newFolder().toPath()))
        val transfer = WorldBookTransferService(repository, json)
        val book = transfer.decodeCharacterBook("""{"name":"book","case_sensitive":true,"entries":[{"keys":["key"],"content":"lore","extensions":{"case_sensitive":false,"matchCharacterDescription":true,"matchCharacterPersonality":true,"matchScenario":true,"matchCreatorNotes":true,"matchPersonaDescription":true}}]}""", "book")
        val entry = book.entries.single()
        assertTrue(book.caseSensitive)
        assertEquals(false, entry.caseSensitive)
        assertTrue(entry.matchCharacterDescription && entry.matchCharacterPersonality && entry.matchScenario && entry.matchCreatorNotes && entry.matchPersonaDescription)
        repository.save(book)
        val roundTrip = transfer.decode(transfer.exportSillyTavernJson(book.id)).book.entries.single()
        assertEquals(entry.copy(id = roundTrip.id, extensions = roundTrip.extensions, originalPosition = roundTrip.originalPosition), roundTrip)
        val edited = WorldBookEntryModalState.from(0, entry.copy(caseSensitive = null, matchWholeWords = null)).materialize(entry)
        assertNull(edited.caseSensitive)
        assertNull(edited.matchWholeWords)
        assertTrue(edited.matchScenario && edited.matchPersonaDescription)
    }
}
