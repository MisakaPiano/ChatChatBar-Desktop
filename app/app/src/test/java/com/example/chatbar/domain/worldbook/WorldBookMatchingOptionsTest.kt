package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.WorldBookTransferService
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import com.example.chatbar.domain.draft.materialize
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorldBookMatchingOptionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun importExportAndEditorPreserveMatchingOptions() = runTest {
        val repository = WorldBookRepository(JsonFileStorage(temp.newFolder().toPath()))
        val transfer = WorldBookTransferService(repository, json)
        val book = transfer.decodeCharacterBook(
            """{"name":"book","case_sensitive":true,"entries":[{"keys":["key"],"content":"lore","extensions":{"case_sensitive":false,"matchCharacterDescription":true,"matchCharacterPersonality":true,"matchScenario":true,"matchCreatorNotes":true,"matchPersonaDescription":true}}]}""",
            "book",
        )
        val entry = book.entries.single()
        assertTrue(book.caseSensitive)
        assertEquals(false, entry.caseSensitive)
        assertTrue(
            entry.matchCharacterDescription &&
                entry.matchCharacterPersonality &&
                entry.matchScenario &&
                entry.matchCreatorNotes &&
                entry.matchPersonaDescription
        )
        repository.save(book)
        val roundTrip = transfer.decode(transfer.exportSillyTavernJson(book.id)).book.entries.single()
        assertEquals(
            entry.copy(
                id = roundTrip.id,
                extensions = roundTrip.extensions,
                originalPosition = roundTrip.originalPosition,
            ),
            roundTrip,
        )
        val edited = WorldBookEntryModalState.from(
            0,
            entry.copy(caseSensitive = null, matchWholeWords = null),
        ).materialize(entry)
        assertNull(edited.caseSensitive)
        assertNull(edited.matchWholeWords)
        assertTrue(edited.matchScenario && edited.matchPersonaDescription)
    }
}
