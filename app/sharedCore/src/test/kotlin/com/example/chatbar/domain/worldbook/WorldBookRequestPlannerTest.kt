package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.TimedEffectState
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.WorldBookRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WorldBookRequestPlannerTest {
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
    fun `sources resolve in embedded bound card and session order`() = runTest {
        val fixture = fixture()
        val embedded = book("embedded", "embedded")
        val bound = book("bound", "bound")
        val cardBook = book("card", "card")
        val sessionBook = book("session", "session")
        fixture.save(bound, cardBook, sessionBook)
        val card = card().copy(
            characterBook = embedded,
            boundWorldBookId = bound.id,
            worldBookIds = listOf(cardBook.id),
        )
        val session = session().copy(extraWorldBookIds = listOf(sessionBook.id))

        val plan = fixture.planner.plan(card, session)

        assertEquals("embedded\n\nbound\n\ncard\n\nsession", plan.prompt)
    }

    @Test
    fun `duplicate id uses latest repository object at first occurrence position`() = runTest {
        val fixture = fixture()
        val embedded = book("duplicate", "embedded-old")
        val repositoryCopy = book("duplicate", "repository-new")
        val tail = book("tail", "tail")
        fixture.save(repositoryCopy, tail)
        val card = card().copy(
            characterBook = embedded,
            worldBookIds = listOf(repositoryCopy.id, tail.id),
        )

        val plan = fixture.planner.plan(card, session())

        assertEquals("repository-new\n\ntail", plan.prompt)
    }

    @Test
    fun `missing references are skipped`() = runTest {
        val fixture = fixture()
        val existing = book("existing", "existing")
        fixture.save(existing)
        val card = card().copy(
            boundWorldBookId = "missing-bound",
            worldBookIds = listOf("missing-card", existing.id),
        )
        val session = session().copy(extraWorldBookIds = listOf("missing-session"))

        assertEquals("existing", fixture.planner.plan(card, session).prompt)
    }

    @Test
    fun `effective scan depth is maximum enabled book and entry depth`() = runTest {
        val fixture = fixture()
        val book = WorldBook(
            id = "book",
            name = "book",
            scanDepth = -2,
            entries = listOf(
                entry("enabled", "", "enabled").copy(scanDepth = 7),
                entry("disabled", "", "disabled").copy(enabled = false, scanDepth = 12),
            ),
        )
        fixture.save(book)
        val logs = mutableListOf<String>()

        fixture.planner.plan(card().copy(worldBookIds = listOf(book.id)), session(), debugLog = logs::add)

        assertTrue(logs.any { it.contains("扫描最近 7 条") })
    }

    @Test
    fun `transient current user scans after stored messages and increments timed count once`() = runTest {
        val fixture = fixture()
        val book = WorldBook(
            id = "book",
            name = "book",
            entries = listOf(entry("entry", "transient-key", "hit").copy(sticky = 2)),
        )
        fixture.save(book)
        val logs = mutableListOf<String>()

        val plan = fixture.planner.plan(
            card = card().copy(worldBookIds = listOf(book.id)),
            session = session(),
            transientUserMessage = message("transient", "transient-key"),
            debugLog = logs::add,
        )

        assertEquals("hit", plan.prompt)
        assertEquals(3, plan.timedWorldInfo.getValue("book::entry").stickyUntil)
        assertTrue(logs.any { it.contains("实际载入 1 条，计时消息数 1") })
    }

    @Test
    fun `excluded regeneration target is absent from scan and indexed count`() = runTest {
        val fixture = fixture()
        val session = session()
        fixture.chats.createSession(session)
        fixture.chats.addMessage(message("target", "target-key"))
        fixture.chats.addMessage(message("other", "other"))
        val book = WorldBook(
            id = "book",
            name = "book",
            scanDepth = 10,
            entries = listOf(entry("entry", "target-key", "wrong")),
        )
        fixture.save(book)
        val logs = mutableListOf<String>()

        val plan = fixture.planner.plan(
            card = card().copy(worldBookIds = listOf(book.id)),
            session = session,
            excludedMessageId = "target",
            debugLog = logs::add,
        )

        assertNull(plan.prompt)
        assertTrue(logs.any { it.contains("实际载入 1 条，计时消息数 1") })
    }

    @Test
    fun `structured character names participate in character filters`() = runTest {
        val fixture = fixture()
        val book = filteredBook("Alice")
        fixture.save(book)
        val character = CharacterInfo.create("Alice")

        val plan = fixture.planner.plan(
            card().copy(characters = listOf(character), worldBookIds = listOf(book.id)),
            session(),
        )

        assertEquals("filtered", plan.prompt)
    }

    @Test
    fun `freeform character name tokens participate in character filters`() = runTest {
        val fixture = fixture()
        val book = filteredBook("alice")
        fixture.save(book)
        val card = card().copy(
            editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = "【角色名称】\nAlice\n【人物描述】\nDescription",
            worldBookIds = listOf(book.id),
        )

        assertEquals("filtered", fixture.planner.plan(card, session()).prompt)
    }

    @Test
    fun `composite timed key takes precedence over legacy key`() = runTest {
        val fixture = fixture()
        val book = timedBook()
        fixture.save(book)
        val previous = mapOf(
            "book::entry" to TimedEffectState("entry", stickyUntil = 5),
            "entry" to TimedEffectState("entry", stickyUntil = 9),
        )

        val plan = fixture.planner.plan(
            card().copy(worldBookIds = listOf(book.id)),
            session(),
            previousTimed = previous,
        )

        assertEquals(5, plan.timedWorldInfo.getValue("book::entry").stickyUntil)
    }

    @Test
    fun `legacy timed key remains readable and emits composite key`() = runTest {
        val fixture = fixture()
        val book = timedBook()
        fixture.save(book)

        val plan = fixture.planner.plan(
            card().copy(worldBookIds = listOf(book.id)),
            session(),
            previousTimed = mapOf("entry" to TimedEffectState("entry", stickyUntil = 9)),
        )

        assertEquals(setOf("book::entry"), plan.timedWorldInfo.keys)
        assertEquals(9, plan.timedWorldInfo.getValue("book::entry").stickyUntil)
    }

    @Test
    fun `before and after entries form one ordered prompt block`() = runTest {
        val fixture = fixture()
        val book = WorldBook(
            id = "book",
            name = "book",
            entries = listOf(
                entry("after", "", "after", WorldBookPosition.AFTER_CHAR, order = 1).copy(constant = true),
                entry("before", "", "before", WorldBookPosition.BEFORE_CHAR, order = 2).copy(constant = true),
            ),
        )
        fixture.save(book)

        assertEquals(
            "before\n\nafter",
            fixture.planner.plan(card().copy(worldBookIds = listOf(book.id)), session()).prompt,
        )
    }

    @Test
    fun `outlet is excluded from prompt and returned separately`() = runTest {
        val fixture = fixture()
        val book = WorldBook(
            id = "book",
            name = "book",
            entries = listOf(
                entry("normal", "", "normal").copy(constant = true),
                entry("outlet", "", "outlet-value", WorldBookPosition.OUTLET)
                    .copy(constant = true, outletName = "memo"),
            ),
        )
        fixture.save(book)

        val plan = fixture.planner.plan(card().copy(worldBookIds = listOf(book.id)), session())

        assertEquals("normal", plan.prompt)
        assertEquals(mapOf("memo" to "outlet-value"), plan.outlets)
    }

    @Test
    fun `resolved player name renders prompt placeholders`() = runTest {
        val fixture = fixture()
        val book = book("book", "{{user}} meets {{char}}")
        fixture.save(book)
        val card = card().copy(botName = "Bot", worldBookIds = listOf(book.id))

        assertEquals("Alice meets Bot", fixture.planner.plan(card, session(), playerName = "Alice").prompt)
    }

    @Test
    fun `no resolved books returns null and empty results with diagnostic`() = runTest {
        val fixture = fixture()
        val logs = mutableListOf<String>()

        val plan = fixture.planner.plan(
            card = card().copy(worldBookIds = listOf("missing")),
            session = session(),
            previousTimed = mapOf("legacy" to TimedEffectState("legacy", stickyUntil = 9)),
            debugLog = logs::add,
        )

        assertNull(plan.prompt)
        assertTrue(plan.outlets.isEmpty())
        assertTrue(plan.timedWorldInfo.isEmpty())
        assertEquals(listOf("世界书：当前角色和会话未绑定世界书。"), logs)
    }

    private fun fixture(): Fixture {
        val storage = JsonFileStorage(Files.createTempDirectory("world-book-request-planner-").also(roots::add))
        val chats = ChatRepository(storage)
        val books = WorldBookRepository(storage)
        return Fixture(chats, books, WorldBookRequestPlanner(chats, books))
    }

    private fun card() = CharacterCard(
        id = "card",
        name = "Card",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun session() = ChatSession(
        id = "session",
        characterCardId = "card",
        title = "Session",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun book(id: String, content: String) = WorldBook(
        id = id,
        name = id,
        entries = listOf(entry("$id-entry", "", content).copy(constant = true)),
    )

    private fun filteredBook(filter: String) = WorldBook(
        id = "book",
        name = "book",
        entries = listOf(
            entry("entry", "", "filtered").copy(
                constant = true,
                characterFilter = listOf(filter),
            )
        ),
    )

    private fun timedBook() = WorldBook(
        id = "book",
        name = "book",
        entries = listOf(entry("entry", "", "timed")),
    )

    private fun entry(
        id: String,
        key: String,
        content: String,
        position: WorldBookPosition = WorldBookPosition.BEFORE_CHAR,
        order: Int = 100,
    ) = WorldBookEntry(
        id = id,
        keys = listOf(key),
        content = content,
        position = position,
        insertionOrder = order,
    )

    private fun message(id: String, content: String) = ChatMessage(
        id = id,
        sessionId = "session",
        role = MessageRole.USER,
        content = content,
        createdAt = id.hashCode().toLong(),
        updatedAt = id.hashCode().toLong(),
    )

    private data class Fixture(
        val chats: ChatRepository,
        val books: WorldBookRepository,
        val planner: WorldBookRequestPlanner,
    ) {
        suspend fun save(vararg worldBooks: WorldBook) {
            worldBooks.forEach { books.save(it) }
        }
    }
}
