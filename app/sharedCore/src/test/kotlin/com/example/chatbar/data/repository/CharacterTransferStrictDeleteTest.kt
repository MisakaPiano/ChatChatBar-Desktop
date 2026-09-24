package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharacterTransferStrictDeleteTest {
    private lateinit var root: Path
    private lateinit var storage: JsonFileStorage

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("character-transfer-strict-delete-")
        storage = JsonFileStorage(root)
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `Character transfer delete does not commit while durable target remains`() = runTest {
        val repository = CharacterRepository(storage)
        val card = CharacterCard(id = "character", name = "Character", createdAt = 1L, updatedAt = 1L)
        repository.save(card)
        val target = obstructEntityDelete("character_cards", card.id)
        var committed = false

        assertFailsWith<IOException> {
            repository.deleteForTransfer(card.id) { committed = true }
        }

        assertFalse(committed)
        assertTrue(Files.isDirectory(target))
        assertTrue(Files.exists(target.resolve("blocker")))
    }

    @Test
    fun `WorldBook transfer rollback reports failure while durable target remains`() = runTest {
        val repository = WorldBookRepository(storage)
        val book = WorldBook(id = "world", name = "World", createdAt = 1L, updatedAt = 1L)
        repository.save(book)
        val target = obstructEntityDelete("world_books", book.id)

        assertFailsWith<IOException> { repository.deleteForTransferRollback(book.id) }

        assertTrue(Files.isDirectory(target))
        assertTrue(Files.exists(target.resolve("blocker")))
    }

    @Test
    fun `FormatCard transfer rollback reports failure while durable target remains`() = runTest {
        val repository = FormatCardRepository(storage)
        val card = FormatCard(id = "format", name = "Format", content = "content", createdAt = 1L)
        repository.save(card)
        val target = obstructEntityDelete("format_cards", card.id)

        assertFailsWith<IOException> { repository.deleteForTransferRollback(card.id) }

        assertTrue(Files.isDirectory(target))
        assertTrue(Files.exists(target.resolve("blocker")))
    }

    @Test
    fun `strict transfer deletion stays idempotent for already absent targets`() = runTest {
        var characterCommitted = false

        CharacterRepository(storage).deleteForTransfer("absent") { characterCommitted = true }
        WorldBookRepository(storage).deleteForTransferRollback("absent")
        FormatCardRepository(storage).deleteForTransferRollback("absent")

        assertTrue(characterCommitted)
    }

    private fun obstructEntityDelete(entityType: String, id: String): Path {
        val target = root.resolve("entities").resolve(entityType).resolve("$id.json")
        Files.delete(target)
        Files.createDirectory(target)
        Files.writeString(target.resolve("blocker"), "keep target non-empty")
        return target
    }
}
