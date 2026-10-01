package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedEntityRepositoryTest {
    private lateinit var root: Path
    private lateinit var storage: JsonFileStorage

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("shared-entity-repository-")
        storage = JsonFileStorage(root)
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `character repository preserves persistence sorting search and delete semantics`() = runTest {
        val repository = CharacterRepository(storage)
        repository.save(CharacterCard(id = "old", name = "Older", characters = emptyList(), createdAt = 1, updatedAt = 1))
        repository.save(
            CharacterCard(
                id = "new",
                name = "Newer",
                characters = listOf(CharacterInfo(id = "alice", name = "Alice")),
                createdAt = 2,
                updatedAt = 2
            )
        )

        assertEquals(listOf("new", "old"), repository.getAll().map(CharacterCard::id))
        assertEquals("new", repository.search("alice").single().id)
        assertTrue(repository.exists("old"))

        repository.delete("old")
        assertFalse(repository.exists("old"))
        assertNull(repository.getById("old"))
    }

    @Test
    fun `format repository keeps single default and stable ordering semantics`() = runTest {
        val repository = FormatCardRepository(storage)
        repository.save(FormatCard(id = "b", name = "Beta", content = "B", isDefault = true, createdAt = 1))
        repository.save(FormatCard(id = "a", name = "Alpha", content = "A", isDefault = true, createdAt = 2))

        assertEquals("a", repository.getDefault()?.id)
        assertEquals(false, repository.getById("b")?.isDefault)
        assertEquals(listOf("a", "b"), repository.getAll().map(FormatCard::id))
    }

    @Test
    fun `world book repository persists updates and sorts by name`() = runTest {
        val repository = WorldBookRepository(storage)
        repository.save(WorldBook(id = "z", name = "Zulu", createdAt = 1, updatedAt = 1))
        repository.save(WorldBook(id = "a", name = "Alpha", createdAt = 2, updatedAt = 2))

        assertEquals(listOf("a", "z"), repository.getAll().map(WorldBook::id))
        assertTrue(requireNotNull(repository.getById("a")).updatedAt >= 2)

        repository.delete("a")
        assertNull(repository.getById("a"))
    }

    @Test
    fun `refresh from storage reconciles repository lists without writing entities`() = runTest {
        val characters = CharacterRepository(storage)
        val formats = FormatCardRepository(storage)
        val worlds = WorldBookRepository(storage)
        val originalCharacter = CharacterCard(id = "character", name = "Old", createdAt = 1, updatedAt = 1)
        val originalFormat = FormatCard(id = "format", name = "Old", content = "Body", isDefault = true,
            createdAt = 1)
        val originalWorld = WorldBook(id = "world", name = "Old")
        characters.save(originalCharacter)
        formats.save(originalFormat)
        worlds.save(originalWorld)
        characters.getAll()
        formats.getAll()
        worlds.getAll()
        val newCharacter = originalCharacter.copy(name = "New")
        val newFormat = originalFormat.copy(name = "New", isDefault = false)
        val newWorld = originalWorld.copy(name = "New")
        storage.saveEntity("character_cards", newCharacter.id, newCharacter, CharacterCard.serializer())
        storage.saveEntity("format_cards", newFormat.id, newFormat, FormatCard.serializer())
        storage.saveEntity("world_books", newWorld.id, newWorld, WorldBook.serializer())
        assertEquals("Old", characters.getAll().single().name)
        assertEquals("format", formats.getDefault()?.id)
        assertEquals("Old", worlds.getAll().single().name)
        val files = listOf("character_cards/character.json", "format_cards/format.json", "world_books/world.json")
            .map { root.resolve("entities/$it") }
        val bytes = files.map(Files::readAllBytes)
        characters.refreshFromStorage()
        formats.refreshFromStorage()
        worlds.refreshFromStorage()
        assertEquals(newCharacter, characters.getAll().single())
        assertEquals(newFormat, formats.getAll().single())
        assertNull(formats.getDefault())
        assertEquals(newWorld, worlds.getAll().single())
        files.zip(bytes).forEach { (file, original) -> assertTrue(original.contentEquals(Files.readAllBytes(file))) }
    }
}
