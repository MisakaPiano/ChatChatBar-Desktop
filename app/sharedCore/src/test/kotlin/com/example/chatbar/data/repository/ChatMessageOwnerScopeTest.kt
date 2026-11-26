package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.operation.AppDataOperationGate
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class ChatMessageOwnerScopeTest {
    private val roots = mutableListOf<Path>()
    private val json = Json { encodeDefaults = true }
    @AfterTest fun cleanup() { roots.forEach { it.toFile().deleteRecursively() } }
    private fun root(): Path = Files.createTempDirectory("chat-owner-").also { roots.add(it) }
    private fun message(session: String, id: String, content: String = id) =
        ChatMessage(id, session, MessageRole.USER, content, createdAt = 1, updatedAt = 1)
    private fun path(root: Path, session: String, id: String) = root.resolve("entities/chat_messages/${session}_${id}.json")
    private fun seed(root: Path, vararg messages: ChatMessage) = runBlocking {
        val store = JsonFileStorage(root)
        messages.forEach { store.saveEntityUncached("chat_messages", "${it.sessionId}_${it.id}", it, ChatMessage.serializer()) }
    }

    @Test fun `clear parent session preserves decoded child owner bytes`() = runBlocking {
        val root = root(); seed(root, message("s", "a"), message("s_child", "m"))
        val foreign = Files.readAllBytes(path(root, "s_child", "m"))
        assertEquals(1, ChatRepository(JsonFileStorage(root)).deleteMessagesForSession("s"))
        assertContentEquals(foreign, Files.readAllBytes(path(root, "s_child", "m")), "Parent clear deleted another decoded owner")
        assertEquals(listOf("m"), ChatRepository(JsonFileStorage(root)).getMessages("s_child").map { it.id })
    }

    @Test fun `ordinary and read only parent indexes exclude prefix neighbour after cold reopen`() = runBlocking {
        val root = root(); seed(root, message("s", "a"), message("s_child", "m"))
        val repo = ChatRepository(JsonFileStorage(root))
        assertEquals(listOf("a"), repo.getMessagesReadOnly("s").map { it.id })
        assertEquals(listOf("a"), repo.getMessages("s").map { it.id })
        assertEquals(listOf("a"), ChatRepository(JsonFileStorage(root)).getMessages("s").map { it.id })
        assertEquals("s_child", assertNotNull(repo.getMessage("m", "s_child")).sessionId)
    }

    @Test fun `old polluted or unknown proof index rebuilds despite matching counts`() = runBlocking {
        for (version in listOf(0, 2)) {
            val root = root(); val own = message("s", "a"); val child = message("s_child", "m")
            seed(root, own, child)
            val storage = JsonFileStorage(root)
            storage.saveEntityUncached("chat_message_indexes", "s", ChatMessageIndex("s",
                listOf(own.toIndexEntry(), child.toIndexEntry()), fileCount = 2, fileFingerprint = 0,
                ownerScopeVersion = version), ChatMessageIndex.serializer())
            assertEquals(listOf("a"), ChatRepository(storage).getMessages("s").map { it.id })
            val rebuilt = assertNotNull(storage.loadEntity("chat_message_indexes", "s", ChatMessageIndex.serializer()))
            assertEquals(1, rebuilt.ownerScopeVersion)
            assertEquals(listOf("a"), rebuilt.entries.map { it.messageId })
        }
    }

    @Test fun `verified normal index cache remains byte unchanged on cold read`() = runBlocking {
        val root = root(); seed(root, message("s", "a"))
        ChatRepository(JsonFileStorage(root)).getMessages("s")
        val index = root.resolve("entities/chat_message_indexes/s.json")
        val before = Files.readAllBytes(index)
        assertEquals(listOf("a"), ChatRepository(JsonFileStorage(root)).getMessages("s").map { it.id })
        assertContentEquals(before, Files.readAllBytes(index))
    }

    @Test fun `canonical foreign key collision refuses ordinary write and delete`() = runBlocking {
        for (operation in listOf("add", "update", "delete", "read")) {
            val root = root(); val foreign = message("s_child", "m", "foreign original")
            seed(root, foreign); val file = path(root, "s_child", "m"); val bytes = Files.readAllBytes(file)
            val repo = ChatRepository(JsonFileStorage(root)); val colliding = message("s", "child_m", "replacement")
            val result = runCatching { when (operation) {
                "add" -> repo.addMessage(colliding)
                "update" -> repo.updateMessage(colliding)
                "delete" -> repo.deleteMessage(colliding.id, colliding.sessionId)
                else -> repo.getMessage(colliding.id, colliding.sessionId)
            } }
            assertTrue(result.isFailure, "Foreign physical key must be refused for $operation")
            assertContentEquals(bytes, Files.readAllBytes(file))
        }
    }

    @Test fun `inline and streaming replacement preserve foreign rows and refuse colliding targets`() = runBlocking {
        for (stream in listOf(false, true)) for (collision in listOf(false, true)) {
            val root = root(); seed(root, message("s", "old"), message("s_child", "m", "keep"))
            val foreign = path(root, "s_child", "m"); val bytes = Files.readAllBytes(foreign)
            val replacement = message("s", if (collision) "child_m" else "next")
            val repo = ChatRepository(JsonFileStorage(root))
            val result = runCatching {
                if (stream) repo.replaceMessagesForSessionStreaming("s") { emit -> emit(replacement) }
                else repo.replaceMessagesForSession("s", listOf(replacement))
            }
            assertEquals(collision, result.isFailure)
            assertContentEquals(bytes, Files.readAllBytes(foreign))
            assertEquals(listOf(if (collision) "old" else "next"), ChatRepository(JsonFileStorage(root)).getMessages("s").map { it.id })
        }
    }

    @Test fun `late producer failure does not publish or capture newly created child row`() = runBlocking {
        val root = root(); seed(root, message("s", "old"))
        val repo = ChatRepository(JsonFileStorage(root)); val foreign = message("s_child", "m")
        assertFails { repo.replaceMessagesForSessionStreaming("s") { emit ->
            emit(message("s", "next"))
            // Separate app-data writer publishes a different owner after preparation began.
            seed(root, foreign)
            error("synthetic producer failure")
        } }
        assertEquals(listOf("old"), ChatRepository(JsonFileStorage(root)).getMessages("s").map { it.id })
        assertEquals(foreign, JsonFileStorage(root).loadEntity("chat_messages", "s_child_m", ChatMessage.serializer()))
    }

    @Test fun `unreadable source refuses clearing before deleting readable own or foreign rows`() = runBlocking {
        val root = root(); seed(root, message("s", "old"), message("s_child", "m"))
        val own = Files.readAllBytes(path(root, "s", "old")); val foreign = Files.readAllBytes(path(root, "s_child", "m"))
        Files.writeString(path(root, "s", "broken"), "{broken")
        assertFails { ChatRepository(JsonFileStorage(root)).deleteMessagesForSession("s") }
        assertContentEquals(own, Files.readAllBytes(path(root, "s", "old")))
        assertContentEquals(foreign, Files.readAllBytes(path(root, "s_child", "m")))
    }

    @Test fun `zero ownership evidence is omitted even when defaults are encoded`() {
        val old = json.encodeToString(ChatMessageIndex.serializer(), ChatMessageIndex("s"))
        assertFalse("ownerScopeVersion" in json.parseToJsonElement(old).jsonObject)
        assertEquals(0, json.decodeFromString(ChatMessageIndex.serializer(), old).ownerScopeVersion)
        val verified = json.encodeToString(ChatMessageIndex.serializer(), ChatMessageIndex("s", ownerScopeVersion = 1))
        assertTrue("ownerScopeVersion" in json.parseToJsonElement(verified).jsonObject)
    }

    @Test fun `failed index publication after source replacement cannot reuse previous transcript`() = runBlocking {
        for (stream in listOf(false, true)) {
            val root = root(); seed(root, message("s", "old"), message("s_child", "m"))
            val index = root.resolve("entities/chat_message_indexes/s.json")
            val child = path(root, "s_child", "m"); val childBytes = Files.readAllBytes(child)
            var armed = false; var blocked = false
            val gate = object : AppDataOperationGate {
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                    if (armed && !blocked && Files.exists(path(root, "s", "next"))) {
                        blocked = true
                        // Fault the actual index destination after source publication, without a JVM/mock writer.
                        Files.createDirectory(index)
                        Files.writeString(index.resolve("hold"), "synthetic index fault")
                    }
                    return operation()
                }
            }
            val repo = ChatRepository(JsonFileStorage(root, gate))
            assertEquals(listOf("old"), repo.getMessages("s").map { it.id })
            armed = true
            val result = runCatching {
                if (stream) repo.replaceMessagesForSessionStreaming("s") { emit -> emit(message("s", "next")) }
                else repo.replaceMessagesForSession("s", listOf(message("s", "next")))
            }
            assertTrue(blocked); assertTrue(result.isFailure)
            armed = false; index.toFile().deleteRecursively()
            assertEquals(listOf("next"), repo.getMessages("s").map { it.id })
            assertEquals(listOf("next"), ChatRepository(JsonFileStorage(root)).getMessages("s").map { it.id })
            assertContentEquals(childBytes, Files.readAllBytes(child))
        }
    }
}
