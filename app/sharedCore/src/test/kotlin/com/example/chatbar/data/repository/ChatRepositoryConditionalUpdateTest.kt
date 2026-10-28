package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryConditionalUpdateTest {
    private val roots = mutableListOf<Path>()
    private class Gate : AppDataOperationGate {
        val count = AtomicInteger()
        var hook: suspend (Int, Boolean) -> Unit = { _, _ -> }
        override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
            val n = count.incrementAndGet()
            hook(n, false)
            return operation().also { hook(n, true) }
        }
        fun arm(block: suspend (Int, Boolean) -> Unit = { _, _ -> }) { count.set(0); hook = block }
    }
    private data class Fixture(val root: Path, val gate: Gate, val storage: JsonFileStorage, val repo: ChatRepository, val expected: ChatMessage) {
        val file get() = root.resolve("entities/chat_messages/s_m.json")
        suspend fun repair(text: String = "fixed", snapshot: ChatMessage = expected) = repo.updateMessageForFormatRepair(
            "s", "m", snapshot, text, MessageFormatRepairNotice(MessageFormatRepairNoticeKind.APPLIED, text, snapshot.displayContent),
        )
    }
    private suspend fun fixture(role: MessageRole = MessageRole.ASSISTANT, readbackMillis: Long? = null): Fixture {
        val root = Files.createTempDirectory("conditional-message-").also(roots::add)
        val gate = Gate()
        val storage = JsonFileStorage(root, gate)
        val repo = if (readbackMillis == null) ChatRepository(storage) else ChatRepository(storage, readbackMillis)
        repo.createSession(ChatSession("s", "card", "title", createdAt = 1, updatedAt = 1))
        val owned = root.resolve("owned-image.png").also { Files.write(it, byteArrayOf(1, 3, 5)) }
        val message = ChatMessage("m", "s", role, "middle",
            alternatives = listOf("first", "middle", "last"), alternativeVersionIds = listOf("v1", "v2", "v3"),
            currentAlternativeIndex = 1, currentAlternativeVersionId = "v2", reasoningContent = "reasoning",
            images = listOf(owned.toString()), generatedFromMessageId = "origin", createdAt = 7, updatedAt = 8,
            sourceTurnId = "turn", sourceTurnOrder = 2, timelineTurn = 2,
            generatedImageMetadata = listOf(GeneratedImageMetadata(owned.toString(), "caption", negativePrompt = "negative",
                sizePreset = "fixture", width = 1, height = 1)),
        )
        val saved = repo.addMessage(message)
        gate.arm()
        return Fixture(root, gate, storage, repo, saved)
    }
    private fun snapshot(root: Path): Map<String, Pair<List<Byte>, java.nio.file.attribute.FileTime>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            root.relativize(it).toString() to (Files.readAllBytes(it).toList() to Files.getLastModifiedTime(it))
        }
    }
    @AfterTest fun cleanup() {
        roots.forEach { root -> Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun `ordinary update receipt is the exact durable snapshot including storage owned fields`() = runTest {
        val f = fixture()
        val edited = f.expected.copy(updatedAt = 1, orderKey = -100,
            content = "new selected body", alternatives = listOf("first", "new selected body", "last"))
        val saved = f.repo.updateMessageReturningSnapshot(edited)
        assertEquals(f.expected.orderKey, saved.orderKey)
        assertTrue(saved.updatedAt > edited.updatedAt)
        assertEquals(saved, assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(f.repo.readMessageDurable("m", "s")).value)
        assertEquals(edited.alternativeVersionIds, saved.alternativeVersionIds)
        assertEquals(edited.sourceTurnId, saved.sourceTurnId)
        assertEquals("new selected body", f.repo.getSession("s")!!.lastMessagePreview)
    }

    @Test fun `notice only preserves divergent base content candidates and metadata with no-op and stale protection`() = runTest {
        val f = fixture()
        f.repo.updateMessage(f.expected.copy(content = "base distinct from selected candidate"))
        val legacy = assertNotNull(f.repo.getMessage("m", "s"))
        val notice = MessageFormatRepairNotice(MessageFormatRepairNoticeKind.ERROR, legacy.displayContent, errorMessage = "SYNTHETIC")
        val result = assertIs<ConditionalMessageUpdateResult.Success>(
            f.repo.updateMessageFormatRepairNotice("s", "m", legacy, notice))
        assertEquals(legacy.copy(formatRepairNotice = notice, updatedAt = result.message.updatedAt), result.message)
        assertEquals(result.message, ChatRepository(JsonFileStorage(f.root)).getMessage("m", "s"))
        val before = snapshot(f.root)
        assertFalse(assertIs<ConditionalMessageUpdateResult.Success>(
            f.repo.updateMessageFormatRepairNotice("s", "m", result.message, notice)).changed)
        assertIs<ConditionalMessageUpdateResult.Conflict>(f.repo.updateMessageFormatRepairNotice("s", "m", legacy, null))
        assertEquals(before, snapshot(f.root))
    }

    @Test fun `notice only cancellation at write and index retains bounded uncertain readback evidence`() = runTest {
        for (call in listOf(3, 4)) {
            val f = fixture(readbackMillis = 50)
            f.repo.updateMessage(f.expected.copy(content = "legacy base differs"))
            val expected = assertNotNull(f.repo.getMessage("m", "s"))
            val notice = MessageFormatRepairNotice(MessageFormatRepairNoticeKind.ERROR, expected.displayContent, errorMessage = "INVALID_MODEL")
            val writing = CompletableDeferred<Unit>(); val reading = CompletableDeferred<Unit>()
            val readReleased = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<ConditionalMessageUpdateResult>()
            f.gate.arm { n, after ->
                if (n == call && after) { writing.complete(Unit); awaitCancellation() }
                if (n == call + 1 && !after) {
                    reading.complete(Unit)
                    try { awaitCancellation() } finally { readReleased.complete(Unit) }
                }
            }
            val job = launch { outcome.complete(f.repo.updateMessageFormatRepairNotice("s", "m", expected, notice)) }
            writing.await()
            val cancellation = CancellationException("synthetic notice lifecycle cancellation")
            job.cancel(cancellation)
            reading.await()
            job.cancel(CancellationException("second cancellation"))
            advanceTimeBy(50); runCurrent()
            val result = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(outcome.await())
            job.join()
            assertTrue(readReleased.isCompleted)
            assertOriginalError(cancellation, result.cause)
            assertEquals(if (call == 3) MessageUpdateStage.WRITE_MESSAGE else MessageUpdateStage.WRITE_INDEX, result.stage)
            assertEquals(expected.copy(formatRepairNotice = notice, updatedAt = result.attempted.updatedAt), result.attempted)
            assertIs<TimeoutCancellationException>(assertIs<JsonFileStorage.EntityReadResult.ReadError>(result.durableMessage).cause)
            assertEquals(call + 1, f.gate.count.get())
            assertIndexCacheInvalidated(f)
            assertEquals(result.attempted, assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(
                f.repo.readMessageDurable("m", "s")).value)
            assertFalse(assertIs<ConditionalMessageUpdateResult.Success>(
                f.repo.updateMessageFormatRepairNotice("s", "m", result.attempted, notice)).changed)
        }
    }

    @Test fun `matching snapshot commits only repair fields and reopens with index and preview`() = runTest {
        val f = fixture()
        val result = assertIs<ConditionalMessageUpdateResult.Success>(f.repair())
        assertTrue(result.changed)
        val expected = f.expected.copy(content = "fixed", alternatives = listOf("first", "fixed", "last"),
            formatRepairNotice = MessageFormatRepairNotice(MessageFormatRepairNoticeKind.APPLIED, "fixed", "middle"),
            updatedAt = result.message.updatedAt)
        assertEquals(expected, result.message)
        val storage = JsonFileStorage(f.root)
        val reopened = ChatRepository(storage)
        assertEquals(expected, reopened.getMessage("m", "s"))
        assertEquals(listOf(expected), reopened.getMessages("s"))
        assertEquals("fixed", reopened.getSession("s")?.lastMessagePreview)
        assertEquals(expected.createdAt, reopened.getSession("s")?.lastMessageTime)
        val index = assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessageIndex>>(
            storage.readEntityStrict("chat_message_indexes", "s", ChatMessageIndex.serializer())).value
        assertEquals(listOf(expected.toIndexEntry()), index.entries)
        assertEquals(listOf<Byte>(1, 3, 5), Files.readAllBytes(f.root.resolve("owned-image.png")).toList())
    }

    private suspend fun assertNonAssistantRejected(role: MessageRole) {
        val f = fixture(role)
        assertEquals(f.expected, f.repo.getMessage("m", "s"))
        val before = snapshot(f.root)
        for (text in listOf("fixed", f.expected.displayContent)) {
            val result = f.repo.updateMessageForFormatRepair("s", "m", f.expected, text, f.expected.formatRepairNotice)
            assertIs<ConditionalMessageUpdateResult.Conflict>(result)
            assertEquals(before, snapshot(f.root))
        }
    }

    @Test fun `matching USER snapshot rejects repair and no-op without any file changes`() = runTest {
        assertNonAssistantRejected(MessageRole.USER)
    }

    @Test fun `matching SYSTEM snapshot rejects repair and no-op without any file changes`() = runTest {
        assertNonAssistantRejected(MessageRole.SYSTEM)
    }

    @Test fun `repairing earlier assistant preserves timeline order and latest preview after reopen`() = runTest {
        val f = fixture()
        f.repo.addMessage(ChatMessage("next-user", "s", MessageRole.USER, "question", createdAt = 9, updatedAt = 9))
        f.repo.addMessage(ChatMessage("last-assistant", "s", MessageRole.ASSISTANT, "latest reply", createdAt = 10, updatedAt = 10))
        val messagesBefore = f.repo.getMessages("s")
        assertEquals(listOf("m", "next-user", "last-assistant"), messagesBefore.map { it.id })
        val sessionBefore = f.repo.getSession("s")
        assertEquals("latest reply", sessionBefore?.lastMessagePreview)
        val filesBefore = snapshot(f.root)
        val saved = assertIs<ConditionalMessageUpdateResult.Success>(f.repair())
        assertTrue(saved.changed)
        val expectedMessages = messagesBefore.map { if (it.id == "m") saved.message else it }
        val filesAfter = snapshot(f.root)
        assertEquals(filesBefore.keys, filesAfter.keys)
        for (path in filesBefore.keys.filterNot {
            it == "entities\\chat_messages\\s_m.json" || it == "entities\\chat_message_indexes\\s.json" ||
                it == "entities/chat_messages/s_m.json" || it == "entities/chat_message_indexes/s.json"
        }) assertEquals(filesBefore[path], filesAfter[path], path)
        val storage = JsonFileStorage(f.root)
        val reopened = ChatRepository(storage)
        assertEquals(expectedMessages, f.repo.getMessages("s"))
        assertEquals(expectedMessages, reopened.getMessages("s"))
        assertEquals(sessionBefore, f.repo.getSession("s"))
        assertEquals(sessionBefore, reopened.getSession("s"))
        val index = assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessageIndex>>(
            storage.readEntityStrict("chat_message_indexes", "s", ChatMessageIndex.serializer())).value
        assertEquals(expectedMessages.map { it.toIndexEntry() }, index.entries)
    }

    @Test fun `stale user edit candidate switch regeneration notice and metadata all conflict without writes`() = runTest {
        val changes: List<(ChatMessage) -> ChatMessage> = listOf(
            { it.copy(content = "edited", alternatives = listOf("first", "edited", "last")) },
            { MessageAlternativeVersionPolicy.select(it, 2) },
            { MessageAlternativeVersionPolicy.append(it, "again", "v4") },
            { it.copy(alternativeVersionIds = listOf("v1", "new-id", "v3"), currentAlternativeVersionId = "new-id") },
            { it.copy(formatRepairNotice = MessageFormatRepairNotice(MessageFormatRepairNoticeKind.ERROR, "middle", errorMessage = "fixture")) },
            { it.copy(reasoningContent = "new reasoning") },
            { it.copy(images = emptyList()) },
        )
        for (change in changes) {
            val f = fixture()
            f.repo.updateMessage(change(f.expected))
            val before = snapshot(f.root)
            assertIs<ConditionalMessageUpdateResult.Conflict>(f.repair())
            assertEquals(before, snapshot(f.root))
        }
    }

    @Test fun `deletion and missing or corrupt session never recreate records`() = runTest {
        val f = fixture()
        f.repo.deleteMessage("m", "s")
        val before = snapshot(f.root)
        assertIs<ConditionalMessageUpdateResult.Missing>(f.repair())
        assertEquals(before, snapshot(f.root))
        for (corrupt in listOf(false, true)) {
            val other = fixture()
            val session = other.root.resolve("entities/chat_sessions/s.json")
            if (corrupt) Files.writeString(session, "{broken") else Files.delete(session)
            val untouched = snapshot(other.root)
            if (corrupt) assertIs<ConditionalMessageUpdateResult.Corrupt>(other.repair())
            else assertIs<ConditionalMessageUpdateResult.Missing>(other.repair())
            assertEquals(untouched, snapshot(other.root))
        }
    }

    @Test fun `same text and timestamp cannot conceal different version notice or metadata`() = runTest {
        val f = fixture()
        val changes = listOf(
            f.expected.copy(alternativeVersionIds = listOf("v1", "other", "v3"), currentAlternativeVersionId = "other"),
            f.expected.copy(formatRepairNotice = MessageFormatRepairNotice(MessageFormatRepairNoticeKind.APPLIED, "middle", "original")),
            f.expected.copy(reasoningContent = "changed reasoning"),
            f.expected.copy(orderKey = f.expected.orderKey + 1),
        )
        for (changed in changes) {
            assertEquals(f.expected.updatedAt, changed.updatedAt)
            assertEquals(f.expected.displayContent, changed.displayContent)
            f.storage.saveEntityUncached("chat_messages", "s_m", changed, ChatMessage.serializer())
            val before = snapshot(f.root)
            assertIs<ConditionalMessageUpdateResult.Conflict>(f.repair())
            assertEquals(before, snapshot(f.root))
        }
    }

    @Test fun `corrupt and unreadable messages preserve original data without preview writes`() = runTest {
        for (directory in listOf(false, true)) {
            val f = fixture()
            if (directory) {
                Files.delete(f.file); Files.createDirectory(f.file); Files.writeString(f.file.resolve("unknown"), "do not touch")
            } else Files.writeString(f.file, "{truncated")
            val before = snapshot(f.root)
            if (directory) assertIs<ConditionalMessageUpdateResult.StorageFailure>(f.repair())
            else assertIs<ConditionalMessageUpdateResult.Corrupt>(f.repair())
            assertEquals(before, snapshot(f.root))
        }
    }

    @Test fun `legacy absent IDs remain absent and invalid identities are rejected without migration`() = runTest {
        val f = fixture()
        val legacy = f.expected.copy(alternativeVersionIds = emptyList(), currentAlternativeVersionId = null)
        f.storage.saveEntityUncached("chat_messages", "s_m", legacy, ChatMessage.serializer())
        val saved = assertIs<ConditionalMessageUpdateResult.Success>(f.repair(snapshot = legacy)).message
        assertTrue(saved.alternativeVersionIds.isEmpty()); assertNull(saved.currentAlternativeVersionId)
        val invalids = listOf(legacy.copy(currentAlternativeIndex = 99),
            legacy.copy(alternativeVersionIds = listOf("a")),
            legacy.copy(alternativeVersionIds = listOf("a", "a", "b")),
            legacy.copy(currentAlternativeVersionId = ""),
            f.expected.copy(currentAlternativeVersionId = "other"))
        for (invalid in invalids) {
            f.storage.saveEntityUncached("chat_messages", "s_m", invalid, ChatMessage.serializer())
            val before = snapshot(f.root)
            assertIs<ConditionalMessageUpdateResult.Conflict>(f.repair(snapshot = invalid))
            assertEquals(before, snapshot(f.root))
        }
    }

    @Test fun `plain legacy message and exact no-op preserve IDs and avoid all writes`() = runTest {
        val f = fixture()
        val plain = f.expected.copy(alternatives = emptyList(), alternativeVersionIds = emptyList(), currentAlternativeIndex = 0,
            currentAlternativeVersionId = null)
        f.storage.saveEntityUncached("chat_messages", "s_m", plain, ChatMessage.serializer())
        val before = snapshot(f.root)
        val noop = assertIs<ConditionalMessageUpdateResult.Success>(f.repo.updateMessageForFormatRepair("s", "m", plain, "middle", null))
        assertFalse(noop.changed); assertEquals(before, snapshot(f.root))
        val saved = assertIs<ConditionalMessageUpdateResult.Success>(f.repair(snapshot = plain)).message
        assertTrue(saved.alternatives.isEmpty()); assertNull(saved.currentAlternativeVersionId)
    }

    @Test fun `two concurrent repairs serialize and at most one commits`() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.gate.arm { n, after -> if (n == 3 && !after) { entered.complete(Unit); release.await() } }
        val first = async { f.repair("first repair") }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { f.repair("second repair") }
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertIs<ConditionalMessageUpdateResult.Success>(first.await())
        assertIs<ConditionalMessageUpdateResult.Conflict>(second.await())
        assertEquals("first repair", f.repo.getMessage("m", "s")?.displayContent)
    }

    @Test fun `ordinary edit holding message mutex completes before old repair validation`() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.gate.arm { n, after -> if (n == 2 && !after) { entered.complete(Unit); release.await() } }
        val edit = async { f.repo.updateMessage(f.expected.copy(content = "user edit", alternatives = listOf("first", "user edit", "last"))) }
        entered.await()
        val repair = async(start = CoroutineStart.UNDISPATCHED) { f.repair() }
        assertFalse(repair.isCompleted)
        release.complete(Unit); edit.await()
        assertIs<ConditionalMessageUpdateResult.Conflict>(repair.await())
        assertEquals("user edit", f.repo.getMessage("m", "s")?.displayContent)
    }

    @Test fun `write failure before commit is distinct from post-write index and preview failures`() = runTest {
        for ((call, after) in listOf(3 to false, 3 to true, 4 to false, 5 to false, 6 to false)) {
            val f = fixture(); val before = snapshot(f.root)
            f.gate.arm { n, done -> if (n == call && done == after) throw IOException("synthetic operation failure") }
            val result = withContext(Dispatchers.Default) { f.repair() }
            f.gate.arm()
            if (call == 3 && !after) {
                assertIs<ConditionalMessageUpdateResult.StorageFailure>(result)
                assertEquals(before, snapshot(f.root))
            } else {
                val uncertain = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(result)
                assertIs<IOException>(uncertain.cause)
                assertEquals(when(call) { 3 -> MessageUpdateStage.WRITE_MESSAGE; 4 -> MessageUpdateStage.WRITE_INDEX; else -> MessageUpdateStage.WRITE_PREVIEW }, uncertain.stage)
                assertEquals("fixed", assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(uncertain.durableMessage).value.displayContent)
                assertEquals("fixed", ChatRepository(JsonFileStorage(f.root)).getMessage("m", "s")?.displayContent)
                assertEquals("middle", f.repo.getSession("s")?.lastMessagePreview)
            }
            assertEquals(listOf<Byte>(1, 3, 5), Files.readAllBytes(f.root.resolve("owned-image.png")).toList())
        }
    }

    @Test fun `unreadable readback after attempted write is uncertain not rollback`() = runTest {
        val f = fixture()
        f.gate.arm { n, after ->
            if ((n == 3 && after) || (n == 4 && !after)) throw IOException("synthetic readback failure")
        }
        val result = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(f.repair())
        assertIs<JsonFileStorage.EntityReadResult.ReadError>(result.durableMessage)
    }

    private suspend fun assertIndexCacheInvalidated(f: Fixture) {
        val probe = IOException("synthetic index cache probe")
        f.gate.arm { _, after -> if (!after) throw probe }
        assertSame(probe, assertFailsWith<IOException> { f.repo.getMessageIds("s") })
        f.gate.arm()
    }

    private fun assertOriginalError(original: Exception, evidence: Exception) {
        // Coroutine stack-trace recovery can copy an exception, retaining the original as its cause.
        assertEquals(original::class, evidence::class)
        assertEquals(original.message, evidence.message)
        assertTrue(generateSequence<Throwable>(evidence) { it.cause }.take(8).any { it === original })
    }

    @Test fun `budgeted failure readback preserves confirmed old and committed classifications`() = runTest {
        for (afterWrite in listOf(false, true)) {
            val f = fixture(readbackMillis = 1_000)
            val failure = IOException("synthetic write failure")
            f.gate.arm { n, after -> if (n == 3 && after == afterWrite) throw failure }
            // Real IO uses a real dispatcher here; do not auto-advance a virtual deadline past it.
            val result = withContext(Dispatchers.Default) { f.repair() }
            if (afterWrite) {
                val uncertain = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(result)
                assertSame(failure, uncertain.cause)
                assertEquals(MessageUpdateStage.WRITE_MESSAGE, uncertain.stage)
                assertEquals("fixed", assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(uncertain.durableMessage).value.displayContent)
            } else {
                val failed = assertIs<ConditionalMessageUpdateResult.StorageFailure>(result)
                assertSame(failure, failed.cause)
                assertEquals(MessageUpdateStage.WRITE_MESSAGE, failed.stage)
            }
            assertIndexCacheInvalidated(f)
        }
    }

    @Test fun `budgeted failure readback times out suspended gate and releases message mutex`() = runTest {
        for (afterWrite in listOf(false, true)) {
            val f = fixture(readbackMillis = 50)
            val failure = IOException("synthetic write failure")
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val maintenance = CompletableDeferred<Unit>()
            f.gate.arm { n, after ->
                if (n == 3 && after == afterWrite) throw failure
                if (n == 4 && !after) {
                    entered.complete(Unit)
                    try { maintenance.await() } finally { released.complete(Unit) }
                }
            }
            val repair = async { f.repair() }
            entered.await()
            advanceTimeBy(49); runCurrent()
            assertFalse(repair.isCompleted)
            advanceTimeBy(1); runCurrent()
            val uncertain = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(repair.await())
            assertTrue(released.isCompleted)
            assertFalse(maintenance.isCompleted)
            assertSame(failure, uncertain.cause)
            assertEquals(MessageUpdateStage.WRITE_MESSAGE, uncertain.stage)
            assertEquals("fixed", uncertain.attempted.displayContent)
            assertIs<TimeoutCancellationException>(assertIs<JsonFileStorage.EntityReadResult.ReadError>(uncertain.durableMessage).cause)
            assertEquals(4, f.gate.count.get()) // No retry or second write.
            assertIndexCacheInvalidated(f)
            val durable = assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(f.repo.readMessageDurable("m", "s")).value
            assertEquals(if (afterWrite) "fixed" else "middle", durable.displayContent)
            val noop = withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    f.repo.updateMessageForFormatRepair("s", "m", durable, durable.displayContent, durable.formatRepairNotice)
                }
            }
            assertFalse(assertIs<ConditionalMessageUpdateResult.Success>(noop).changed)
            assertEquals(durable, ChatRepository(JsonFileStorage(f.root)).readMessageDurable("m", "s").let {
                assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(it).value
            })
        }
    }

    @Test fun `budgeted failure readback cancellation retains bounded evidence without duplicate writes`() = runTest {
        val f = fixture(readbackMillis = 50)
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<ConditionalMessageUpdateResult>()
        val writeFinished = CompletableDeferred<Unit>()
        f.gate.arm { n, after ->
            if (n == 3 && after) { writeFinished.complete(Unit); awaitCancellation() }
            if (n == 4 && !after) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { released.complete(Unit) }
            }
        }
        val job = launch { outcome.complete(f.repair()) }
        writeFinished.await()
        val cancellation = CancellationException("synthetic lifecycle cancellation")
        job.cancel(cancellation)
        entered.await()
        job.cancel(CancellationException("second cancellation while reading"))
        advanceTimeBy(50); runCurrent()
        val uncertain = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(outcome.await())
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(released.isCompleted)
        assertOriginalError(cancellation, uncertain.cause)
        assertEquals(MessageUpdateStage.WRITE_MESSAGE, uncertain.stage)
        assertIs<TimeoutCancellationException>(assertIs<JsonFileStorage.EntityReadResult.ReadError>(uncertain.durableMessage).cause)
        assertEquals(4, f.gate.count.get())
        assertIndexCacheInvalidated(f)
        val durable = assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(f.repo.readMessageDurable("m", "s")).value
        assertEquals(uncertain.attempted, durable)
        assertFalse(assertIs<ConditionalMessageUpdateResult.Success>(withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                f.repo.updateMessageForFormatRepair("s", "m", durable, durable.displayContent, durable.formatRepairNotice)
            }
        }).changed)
    }

    @Test fun `budgeted failure readback rejected admission preserves both errors and durable content`() = runTest {
        val f = fixture(readbackMillis = 50)
        val failure = IOException("synthetic post-write failure")
        val rejected = IllegalStateException("synthetic maintenance admission rejected")
        f.gate.arm { n, after ->
            if (n == 3 && after) throw failure
            if (n == 4 && !after) throw rejected
        }
        val uncertain = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(f.repair())
        assertSame(failure, uncertain.cause)
        assertOriginalError(rejected, assertIs<JsonFileStorage.EntityReadResult.ReadError>(uncertain.durableMessage).cause)
        assertEquals(MessageUpdateStage.WRITE_MESSAGE, uncertain.stage)
        assertEquals(4, f.gate.count.get())
        assertIndexCacheInvalidated(f)
        assertEquals(uncertain.attempted, assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(
            f.repo.readMessageDurable("m", "s")).value)
    }

    @Test fun `budgeted failure readback does not impose a deadline on normal message writes`() = runTest {
        val f = fixture(readbackMillis = 50)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.gate.arm { n, after -> if (n == 3 && !after) { entered.complete(Unit); release.await() } }
        val repair = async { f.repair() }
        entered.await()
        advanceTimeBy(500); runCurrent()
        assertFalse(repair.isCompleted)
        release.complete(Unit)
        assertTrue(assertIs<ConditionalMessageUpdateResult.Success>(repair.await()).changed)
        assertEquals("fixed", f.repo.getMessage("m", "s")?.displayContent)
    }

    @Test fun `old JSON with missing optional fields edits without version migration`() = runTest {
        val f = fixture()
        Files.writeString(f.file, """{"id":"m","sessionId":"s","role":"ASSISTANT","content":"old","createdAt":1,"updatedAt":1}""")
        val expected = assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(f.repo.readMessageDurable("m", "s")).value
        val result = assertIs<ConditionalMessageUpdateResult.Success>(f.repair(snapshot = expected))
        assertNull(result.message.currentAlternativeVersionId)
        assertTrue(result.message.alternativeVersionIds.isEmpty())
        assertNull(result.message.sourceTurnId)
        assertEquals(expected.copy(content = "fixed", formatRepairNotice = result.message.formatRepairNotice,
            updatedAt = result.message.updatedAt), result.message)
    }

    @Test fun `batch deletion waits for conditional write and cannot leave a resurrected message`() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.gate.arm { n, after -> if (n == 3 && !after) { entered.complete(Unit); release.await() } }
        val repair = async { f.repair() }; entered.await()
        val deletion = async(start = CoroutineStart.UNDISPATCHED) { f.repo.deleteSession("s") }
        assertFalse(deletion.isCompleted)
        release.complete(Unit)
        assertIs<ConditionalMessageUpdateResult.Success>(repair.await()); deletion.await()
        assertNull(f.repo.getMessage("m", "s")); assertNull(f.repo.getSession("s"))
    }
}
