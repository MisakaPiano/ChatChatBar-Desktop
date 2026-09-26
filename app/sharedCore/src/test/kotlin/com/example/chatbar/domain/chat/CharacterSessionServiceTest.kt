package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class CharacterSessionServiceTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        roots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        roots.clear()
    }

    @Test
    fun `legacy character JSON has no format binding`() {
        val card = Json.decodeFromString(
            CharacterCard.serializer(),
            """{"id":"old","name":"角色","createdAt":1,"updatedAt":1}""",
        )

        assertNull(card.defaultFormatCardId)
    }

    @Test
    fun `missing character fails without creating a session`() = runTest {
        val fixture = fixture()

        val error = assertFailsWith<IllegalArgumentException> {
            fixture.service.createSessionForCharacter("missing")
        }

        assertEquals("角色卡不存在", error.message)
        assertEquals(emptyList(), fixture.chats.getAllSessions())
    }

    @Test
    fun `session title id and opening greeting come from current character`() = runTest {
        val fixture = fixture()
        val card = CharacterCard.create("Current Name", greeting = "Opening greeting")
        fixture.characters.save(card)

        val sessionId = fixture.service.createSessionForCharacter(card.id)

        val session = requireNotNull(fixture.chats.getSession(sessionId))
        assertEquals(card.id, session.characterCardId)
        assertEquals("Current Name", session.title)
        val opening = fixture.chats.getMessages(sessionId).single()
        assertEquals(MessageRole.ASSISTANT, opening.role)
        assertEquals("Opening greeting", opening.content)
    }

    @Test
    fun `valid default format binding is copied into new session`() = runTest {
        val fixture = fixture()
        val format = FormatCard.create("Bound", "content")
        fixture.formats.save(format)
        val card = CharacterCard.create("Character").copy(defaultFormatCardId = format.id)
        fixture.characters.save(card)

        val sessionId = fixture.service.createSessionForCharacter(card.id)

        assertEquals(format.id, fixture.chats.getSession(sessionId)?.formatCardId)
        assertEquals(emptyList(), fixture.warnings)
    }

    @Test
    fun `blank default format binding becomes null without warning`() = runTest {
        val fixture = fixture()
        val card = CharacterCard.create("Character").copy(defaultFormatCardId = "  ")
        fixture.characters.save(card)

        val sessionId = fixture.service.createSessionForCharacter(card.id)

        assertNull(fixture.chats.getSession(sessionId)?.formatCardId)
        assertEquals(emptyList(), fixture.warnings)
    }

    @Test
    fun `stale default format binding becomes null and reports warning`() = runTest {
        val fixture = fixture()
        val card = CharacterCard.create("Character").copy(defaultFormatCardId = "stale-format")
        fixture.characters.save(card)

        val sessionId = fixture.service.createSessionForCharacter(card.id)

        assertNull(fixture.chats.getSession(sessionId)?.formatCardId)
        assertEquals(
            listOf("角色卡 ${card.id} 绑定的格式卡 stale-format 已不存在，新会话沿用全局默认"),
            fixture.warnings,
        )
    }

    @Test
    fun `existing session keeps binding after character default changes`() = runTest {
        val fixture = fixture()
        val first = FormatCard.create("First", "one")
        val second = FormatCard.create("Second", "two")
        fixture.formats.save(first)
        fixture.formats.save(second)
        val card = CharacterCard.create("Character").copy(defaultFormatCardId = first.id)
        fixture.characters.save(card)
        val existingSessionId = fixture.service.createSessionForCharacter(card.id)

        fixture.characters.save(card.copy(defaultFormatCardId = second.id))

        assertEquals(first.id, fixture.chats.getSession(existingSessionId)?.formatCardId)
        val newSessionId = fixture.service.createSessionForCharacter(card.id)
        assertEquals(second.id, fixture.chats.getSession(newSessionId)?.formatCardId)
    }

    @Test
    fun `blank greeting still creates exactly one blank assistant message`() = runTest {
        val fixture = fixture()
        val card = CharacterCard.create("Character", greeting = "")
        fixture.characters.save(card)

        val sessionId = fixture.service.createSessionForCharacter(card.id)

        val opening = fixture.chats.getMessages(sessionId).single()
        assertEquals(MessageRole.ASSISTANT, opening.role)
        assertEquals("", opening.content)
    }

    private fun fixture(): Fixture {
        val storage = JsonFileStorage(newRoot())
        val characters = CharacterRepository(storage)
        val chats = ChatRepository(storage)
        val formats = FormatCardRepository(storage)
        val warnings = mutableListOf<String>()
        return Fixture(
            characters = characters,
            chats = chats,
            formats = formats,
            warnings = warnings,
            service = CharacterSessionService(characters, chats, formats, warnings::add),
        )
    }

    private fun newRoot(): Path = Files.createTempDirectory("character-session-service-").also(roots::add)

    private data class Fixture(
        val characters: CharacterRepository,
        val chats: ChatRepository,
        val formats: FormatCardRepository,
        val warnings: MutableList<String>,
        val service: CharacterSessionService,
    )
}
