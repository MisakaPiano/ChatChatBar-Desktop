package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.chat.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopFormatRepairDerivedSafetyTest {
    private suspend fun fixture(block: suspend (DesktopMessageFormatRepairTest.Fixture) -> Unit) {
        val f = DesktopMessageFormatRepairTest.Fixture()
        var ownership: DesktopDataRootOwnership? = null
        try {
            f.prepare()
            ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(f.container.resolvedRoot)).ownership
            withTimeout(20_000) { block(f) }
        } finally {
            try { f.container.close() } finally {
                ownership?.close()
                f.root.toFile().deleteRecursively()
            }
        }
    }

    // Frozen legacy absence-proof subset; production now uses coordinated source mutation.
    private fun production(f: DesktopMessageFormatRepairTest.Fixture) = DesktopMessageFormatRepairController(
        f.chats, f.container.settingsRepository, f.container.characterRepository, f.container.formatCardRepository,
        f.container.effectiveModelResolver, f.runtime, safety(f), { f.requests.incrementAndGet(); f.response(it) })
    private suspend fun run(f: DesktopMessageFormatRepairTest.Fixture, c: DesktopMessageFormatRepairController,
        restore: Boolean = false) = f.await(if (restore) c.restore("s", "m") else c.repair("s", "m"), c)
    private fun hash(path: java.nio.file.Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList()
    private fun directory(f: DesktopMessageFormatRepairTest.Fixture, type: String) = Files.createDirectories(f.dataRoot.resolve("entities/$type"))
    private fun raw(f: DesktopMessageFormatRepairTest.Fixture, type: String, id: String, text: String) =
        directory(f, type).resolve("$id.json").also { Files.writeString(it, text) }
    private fun safety(f: DesktopMessageFormatRepairTest.Fixture) = DesktopFormatRepairDerivedSafety(f.dataRoot, f.storage,
        f.container.dataOperationCoordinator, f.runtime)

    @Test fun `production assembly admits empty derived state applies and restores across reopen`() = runBlocking { fixture { f ->
        val probe = CompletableDeferred<DesktopRepairSafety>()
        val probeTask = f.runtime.launchFormatRepair("s", "m") { _, _ ->
            try { probe.complete(safety(f).inspect(f.chats.getSession("s")!!, f.original)) }
            catch (error: Throwable) { probe.completeExceptionally(error) }
            DesktopRepairStatus.REJECTED
        }
        assertEquals(DesktopRepairSafety.VERIFIED_SAFE, probe.await())
        f.runtime.repairTasks.first { it[probeTask] == DesktopRepairStatus.REJECTED }
        val prepared = CompletableDeferred<DesktopRepairSafety>()
        f.response = { flow {
            try { prepared.complete(safety(f).inspect(f.chats.getSession("s")!!, f.saved())) }
            catch (error: Throwable) { prepared.completeExceptionally(error) }
            emit(StreamEvent.Delta("fixed")); emit(StreamEvent.Done)
        } }
        val c = production(f)
        val image = java.nio.file.Path.of(f.original.images.single()); val before = hash(image)
        val result = run(f, c)
        assertEquals(DesktopRepairSafety.VERIFIED_SAFE, prepared.await())
        assertEquals(DesktopRepairStatus.APPLIED, result.status)
        val saved = f.reopened()
        assertEquals("fixed", saved.displayContent)
        assertEquals(f.original.alternativeVersionIds, saved.alternativeVersionIds)
        assertEquals(f.original.images, saved.images); assertEquals(f.original.reasoningContent, saved.reasoningContent)
        assertEquals(DesktopRepairStatus.RESTORED, run(f, c, true).status)
        assertEquals(f.original.displayContent, f.reopened().displayContent)
        assertEquals(before, hash(image)); assertNull(f.reopened().formatRepairNotice)
    } }

    @Test fun `document completed moment and immutable image or backup are preserved`() = runBlocking { fixture { f ->
        val card = f.container.characterRepository.getById(f.chats.getSession("s")!!.characterCardId)!!
        val sourceFile = f.root.resolve("synthetic.txt"); Files.writeString(sourceFile, "independent document")
        f.container.characterRepository.save(card.copy(customDocuments = listOf(DocumentInfo("source-doc", "synthetic.txt", sourceFile.toString(), "txt", 1))))
        val doc = VectorChunk("doc", ChunkSourceType.DOCUMENT, card.id,
            content = "independent document", embedding = listOf(.25f),
            metadata = mapOf("originalDocId" to "source-doc", "documentId" to "source-doc", "sourceLabel" to "synthetic.txt",
                "contentHash" to "hash", "embeddingKey" to "embedding"), createdAt = 1)
        f.storage.saveEntity("vector_chunks", doc.id, doc, VectorChunk.serializer())
        val post = raw(f, "moment_posts", "post", """{"id":"post","characterCardId":"${doc.sourceId}","sessionId":"s","senderName":"Bot","text":"historic post","scheduledAt":1,"generatedAt":1}""")
        val task = raw(f, "moment_tasks", "task", """{"id":"task","characterCardId":"${doc.sourceId}","sessionId":"s","scheduledAt":1,"status":"COMPLETED","postId":"post"}""")
        val backup = Files.createDirectories(f.dataRoot.resolve("backups/historical")).resolve("opaque.cbbackup")
        Files.write(backup, byteArrayOf(3, 4, 5))
        val docPath = f.dataRoot.resolve("entities/vector_chunks/doc.json")
        val paths = listOf(post, task, backup, docPath, sourceFile, java.nio.file.Path.of(f.original.images.single()))
        val before = paths.map(::hash)
        assertEquals(DesktopRepairStatus.APPLIED, run(f, production(f)).status)
        assertEquals(before, paths.map(::hash))
    } }

    @Test fun `each mutable derived table rejects and preserves original files`() = runBlocking { fixture { f ->
        val records = mapOf(
            "vector_chunks" to """{"id":"x","sourceType":"CHAT_MEMORY","sourceId":"s","messageId":"m","content":"old","embedding":[],"createdAt":1}""",
            "memory_states" to """{"sessionId":"s"}""",
            "memory_nodes" to """{"id":"x","sessionId":"s","tier":"EPISODE"}""",
            "memory_tier_revisions" to """{"id":"x","sessionId":"s","tier":"EPISODE","operation":"MIGRATE","author":"AI"}""",
            "memory_compression_transactions" to """{"id":"x","sessionId":"s","kind":"EPISODE_TO_ARC","sourceTier":"EPISODE","targetTier":"ARC","consumedNodeIds":[],"resultNodeId":"n","sourceRevisionId":"a","targetRevisionId":"b"}""",
            "memory_commits" to """{"id":"x","sessionId":"s"}""",
            "memory_commit_journals" to """{"id":"x","sessionId":"s","expectedStateRevision":0,"nextState":{"sessionId":"s"}}""",
            "generated_voice_messages" to """{"id":"x","sessionId":"s","messageId":"m","messageVersionId":"b","sourceOrder":1,"sourceSegmentKind":"DIALOGUE","sourceSpeakerName":"Bot","sourceText":"old","taggedText":"old","characterId":"bot","characterName":"Bot","voice":{"referenceId":"voice","title":"Synthetic"},"fishModelId":"fake","audioPath":"synthetic.mp3","durationMs":1,"byteLength":1,"createdAt":1,"updatedAt":1}""",
            "voice_anchor_states" to """{"messageId":"m","sessionId":"s","displayContentSnapshot":"original"}""",
            "save_slots" to """{"id":"x","sessionId":"s","messages":[]}""",
        )
        val c = production(f)
        for ((type, text) in records) {
            val path = raw(f, type, "x", text); val before = hash(path)
            assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status, type)
            assertEquals(before, hash(path)); assertEquals(f.original, f.saved())
            Files.delete(path); Files.delete(path.parent)
        }
        assertEquals(0, f.requests.get())
    } }

    @Test fun `corruption unknown fields conflicting ids orphan records and interrupted entries refuse before request`() = runBlocking { fixture { f ->
        val card = f.chats.getSession("s")!!.characterCardId
        val owner = f.container.characterRepository.getById(card)!!
        f.container.characterRepository.save(owner.copy(customDocuments = listOf(DocumentInfo("d", "synthetic", "synthetic", "txt", 1))))
        val valid = """{"id":"x","sourceType":"DOCUMENT","sourceId":"$card","content":"doc","embedding":[],"metadata":{"originalDocId":"d","contentHash":"h","embeddingKey":"e"},"createdAt":1}"""
        val c = production(f)
        for (text in listOf("{bad", valid.dropLast(1) + ",\"unknown\":true}", valid.replace("\"id\":\"x\"", "\"id\":\"other\""),
            valid.replace(card, "orphan"), valid.replace("\"embeddingKey\":\"e\"", "\"sourceTurnId\":\"turn\""))) {
            val path = raw(f, "vector_chunks", "x", text); val before = hash(path)
            assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status)
            assertEquals(before, hash(path)); Files.delete(path)
        }
        val side = directory(f, "vector_chunks").resolve(".unfinished.tmp"); Files.writeString(side, "keep")
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status); assertEquals("keep", Files.readString(side))
        Files.delete(side)
        val odd = Files.createDirectory(directory(f, "vector_chunks").resolve("directory.json"))
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status); assertTrue(Files.isDirectory(odd))
        assertEquals(0, f.requests.get()); assertEquals(f.original, f.saved())
    } }

    @Test fun `late derived records invalidate preflight including stopped and restored submissions`() = runBlocking { fixture { f ->
        val c = production(f)
        assertEquals(DesktopRepairStatus.APPLIED, run(f, c).status)
        val prior = f.saved()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.response = { flow { emit(StreamEvent.Delta("late")); entered.complete(Unit); release.await(); emit(StreamEvent.Done) } }
        val id = c.repair("s", "m"); entered.await()
        val path = raw(f, "memory_states", "s", """{"sessionId":"s"}""")
        release.complete(Unit)
        assertEquals(DesktopRepairStatus.REJECTED, f.await(id, c).status)
        assertEquals(prior, f.saved())
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c, true).status)
        Files.delete(path); Files.delete(path.parent)
        val stopping = CompletableDeferred<Unit>()
        f.response = { flow { emit(StreamEvent.Delta("🦊")); stopping.complete(Unit); awaitCancellation() } }
        val stopId = c.repair("s", "m"); stopping.await()
        raw(f, "voice_anchor_states", "m", """{"messageId":"m","sessionId":"s","displayContentSnapshot":"old"}""")
        assertTrue(f.runtime.requestUserStop(stopId)); assertEquals(DesktopRepairStatus.REJECTED, f.await(stopId, c).status)
        assertEquals(prior, f.saved())
    } }

    @Test fun `final proof and cas share exclusive scope blocking a concurrent normal writer`() = runBlocking { fixture { f ->
        val gate = safety(f); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val scoped = object : DesktopRepairSafetyGate {
            override suspend fun check(session: ChatSession, message: ChatMessage) = gate.check(session, message)
            override suspend fun submit(session: ChatSession, message: ChatMessage, write: suspend () -> ConditionalMessageUpdateResult) =
                gate.submit(session, message) { entered.complete(Unit); release.await(); write() }
        }
        val c = DesktopMessageFormatRepairController(f.chats, f.container.settingsRepository, f.container.characterRepository,
            f.container.formatCardRepository, f.container.effectiveModelResolver, f.runtime, scoped, { f.response(it) })
        val id = c.repair("s", "m"); entered.await()
        assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, f.container.dataOperationCoordinator.state)
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            f.storage.saveEntity("memory_states", "s", MemorySessionState("s"), MemorySessionState.serializer())
        }
        assertFalse(writer.isCompleted)
        release.complete(Unit)
        assertEquals(DesktopRepairStatus.APPLIED, f.await(id, c).status)
        writer.await()
        assertEquals("fixed", f.reopened().displayContent)
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c, true).status)
    } }

    @Test fun `in flight and retained source task block repair and late task cannot start during stream`() = runBlocking { fixture { f ->
        val oldRead = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = f.runtime.launchNovelAi("synthetic source writer", "s", "m", retryable = true) {
            assertEquals(f.original, f.saved()); oldRead.complete(Unit); release.await(); throw java.io.IOException("synthetic")
        }
        oldRead.await(); val c = production(f)
        assertFailsWith<DesktopTaskAdmissionException> { c.repair("s", "m") }
        release.complete(Unit)
        f.runtime.tasks.first { it.any { task -> task.taskId == old && task.status != DesktopTaskStatus.RUNNING } }
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status)
        assertTrue(f.runtime.dismissImageTask(old))
        val entered = CompletableDeferred<Unit>()
        f.response = { flow { entered.complete(Unit); awaitCancellation() } }
        val id = c.repair("s", "m"); entered.await()
        assertFailsWith<DesktopTaskAdmissionException> { f.runtime.launchNovelAi("late writer", "s", "m") {} }
        f.runtime.requestUserStop(id); assertEquals(DesktopRepairStatus.STOPPED, f.await(id, c).status)
    } }

    @Test fun `unchanged errors stopped unicode and lifecycle cancellation retain b3b contracts`() = runBlocking { fixture { f ->
        val c = production(f)
        f.response = { flowOf(StreamEvent.Delta("[[CHATBAR_FORMAT_OK]]"), StreamEvent.Done) }
        assertEquals(DesktopRepairStatus.UNCHANGED, run(f, c).status); assertEquals(f.original, f.saved())
        f.response = { flowOf(StreamEvent.Error("synthetic failure")) }
        assertEquals(DesktopRepairStatus.ERROR, run(f, c).status)
        assertEquals(MessageFormatRepairNoticeKind.ERROR, f.reopened().formatRepairNotice?.kind)
        f.response = { flowOf(StreamEvent.Delta("fixed"), StreamEvent.Done) }
        assertEquals(DesktopRepairStatus.APPLIED, run(f, c).status)
        val applied = f.saved()
        f.response = { flowOf(StreamEvent.Error("synthetic failure")) }
        assertEquals(DesktopRepairStatus.ERROR, run(f, c).status); assertEquals(applied, f.saved())
        val entered = CompletableDeferred<Unit>()
        f.response = { flow { emit(StreamEvent.Delta("🦊")); entered.complete(Unit); awaitCancellation() } }
        val id = c.repair("s", "m"); entered.await(); f.runtime.requestUserStop(id)
        assertEquals(DesktopRepairStatus.STOPPED, f.await(id, c).status)
        assertEquals(MessageFormatRepairPolicy.progressiveOverlay(applied.displayContent, "🦊"), f.reopened().displayContent)
        assertEquals(DesktopRepairStatus.RESTORED, run(f, c, true).status)
        val beforeClose = f.saved(); val closing = CompletableDeferred<Unit>()
        f.response = { flow { emit(StreamEvent.Delta("discard")); closing.complete(Unit); awaitCancellation() } }
        val closingId = c.repair("s", "m"); closing.await(); f.runtime.closeAndDrain()
        assertEquals(DesktopRepairStatus.CANCELLED, c.states.value[closingId]?.status)
        assertEquals(beforeClose, f.reopened())
    } }

    @Test fun `missing application ownership refuses production assembly`() = runBlocking {
        val f = DesktopMessageFormatRepairTest.Fixture()
        try {
            f.prepare(); assertEquals(DesktopRepairStatus.REJECTED, run(f, production(f)).status)
            assertEquals(0, f.requests.get()); assertEquals(f.original, f.saved())
        } finally { f.close() }
    }

    @Test fun `completed task foreign post and document pointing at live message are contradictory sources`() = runBlocking { fixture { f ->
        val session = f.chats.getSession("s")!!
        f.chats.createSession(session.copy(id = "other"))
        val post = raw(f, "moment_posts", "post-other", """{"id":"post-other","characterCardId":"${session.characterCardId}","sessionId":"other","senderName":"Bot","text":"historical","scheduledAt":1,"generatedAt":1}""")
        val task = raw(f, "moment_tasks", "task", """{"id":"task","characterCardId":"${session.characterCardId}","sessionId":"s","scheduledAt":1,"status":"COMPLETED","postId":"post-other"}""")
        val c = production(f)
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status)
        assertEquals(f.original, f.saved()); Files.delete(task); Files.delete(post)
        val card = f.container.characterRepository.getById(session.characterCardId)!!
        val source = f.dataRoot.resolve("entities/chat_messages/s_m.json")
        f.container.characterRepository.save(card.copy(customDocuments = listOf(DocumentInfo("doc", "message.json", source.toString(), "json", 1))))
        f.storage.saveEntity("vector_chunks", "x", VectorChunk("x", ChunkSourceType.DOCUMENT, card.id,
            content = "old", embedding = listOf(.1f), metadata = mapOf("originalDocId" to "doc", "contentHash" to "h", "embeddingKey" to "e"), createdAt = 1), VectorChunk.serializer())
        val before = hash(source)
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status)
        assertEquals(before, hash(source)); assertEquals(0, f.requests.get())
    } }

    @Test fun `unknown namespace legacy mirrors and resumable moments fail closed without mutation`() = runBlocking { fixture { f ->
        val c = production(f)
        val unknown = directory(f, "future_source_cache")
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status); Files.delete(unknown)
        val originalSession = f.chats.getSession("s")!!
        for (session in listOf(originalSession.copy(longTermMemory = "legacy memory"),
            originalSession.copy(longTermMemoryUpdatedThroughMessageId = "m"), originalSession.copy(memoryHeadCommitId = "legacy"))) {
            f.chats.updateSession(session)
            assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status)
            assertEquals(session.longTermMemory, f.chats.getSession("s")!!.longTermMemory)
        }
        f.chats.updateSession(originalSession)
        f.chats.createSession(ChatSession("legacy-other", originalSession.characterCardId, "Synthetic other",
            longTermMemory = "unexplained source", createdAt = 1, updatedAt = 1))
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status)
        assertEquals("unexplained source", f.chats.getSession("legacy-other")!!.longTermMemory)
        f.chats.deleteSession("legacy-other")
        val card = originalSession.characterCardId
        val pending = raw(f, "moment_tasks", "pending", """{"id":"pending","sessionId":"s","characterCardId":"$card","scheduledAt":1,"status":"PENDING"}""")
        val before = hash(pending)
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status); assertEquals(before, hash(pending))
        Files.delete(pending)
        raw(f, "moment_posts", "post", """{"id":"post","sessionId":"s","characterCardId":"$card","senderName":"Bot","text":"old","scheduledAt":1,"generatedAt":1,"generationCheckpoint":"synthetic checkpoint"}""")
        assertEquals(DesktopRepairStatus.REJECTED, run(f, c).status)
        assertEquals(0, f.requests.get()); assertEquals(f.original, f.saved())
    } }

    @Test fun `production submission preserves every repository result without flattening commit evidence`() = runBlocking { fixture { f ->
        val gate = safety(f)
        val cause = java.io.IOException("synthetic index failure")
        val durable = JsonFileStorage.EntityReadResult.Valid(f.original)
        val result = ConditionalMessageUpdateResult.CommitUncertain(f.original.copy(content = "attempted"),
            MessageUpdateStage.WRITE_INDEX, cause, durable)
        val id = f.runtime.launchFormatRepair("s", "m") { _, _ ->
            assertSame(result, gate.submit(f.chats.getSession("s")!!, f.original) { result })
            val failure = ConditionalMessageUpdateResult.StorageFailure(MessageUpdateStage.WRITE_MESSAGE, cause)
            assertSame(failure, gate.submit(f.chats.getSession("s")!!, f.original) { failure })
            DesktopRepairStatus.COMMIT_UNCERTAIN
        }
        withTimeout(8_000) { f.runtime.repairTasks.first { it[id] == DesktopRepairStatus.COMMIT_UNCERTAIN } }
        assertEquals(f.original, f.saved()); assertSame(cause, result.cause); assertSame(durable, result.durableMessage)
    } }

    @Test fun `Windows junction is refused without following or changing foreign content`() = runBlocking { fixture { f ->
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        val foreign = Files.createTempDirectory("derived-foreign-")
        val marker = foreign.resolve("protected.json"); Files.writeString(marker, "protected")
        val link = f.dataRoot.resolve("entities/vector_chunks")
        try {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), foreign.toString())
                .redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            assertEquals(0, process.waitFor())
            assertEquals(DesktopRepairStatus.REJECTED, run(f, production(f)).status)
            assertEquals("protected", Files.readString(marker)); assertEquals(f.original, f.saved())
        } finally {
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
            foreign.toFile().deleteRecursively()
        }
    } }

    /** Fault decorator only: same root/coordinator, a dedicated storage/repository to inject failures.
     * The normal production-assembly tests above still use the container's single storage instance.
     */
    private suspend fun faultController(f: DesktopMessageFormatRepairTest.Fixture,
        fault: suspend (Int) -> Unit): DesktopMessageFormatRepairController {
        var armed = false
        var calls = 0
        val storage = JsonFileStorage(f.dataRoot, object : com.example.chatbar.data.operation.AppDataOperationGate {
            override suspend fun <T> withNormalOperation(operation: suspend () -> T): T =
                f.container.dataOperationCoordinator.withNormalOperation {
                    val call = if (armed) ++calls else 0
                    operation().also { if (armed) fault(call) }
                }
        })
        val chats = ChatRepository(storage)
        // The read path deliberately need not retain an index cache. Prime via the ordinary
        // fixture update before arming, so calls 3/4/6 are actual message/index/preview writes.
        chats.updateMessage(f.original)
        val gate = DesktopFormatRepairDerivedSafety(f.dataRoot, storage, f.container.dataOperationCoordinator, f.runtime)
        val scoped = object : DesktopRepairSafetyGate {
            override suspend fun check(session: ChatSession, message: ChatMessage) = gate.check(session, message)
            override suspend fun submit(session: ChatSession, message: ChatMessage, write: suspend () -> ConditionalMessageUpdateResult) =
                gate.submit(session, message) { armed = true; write() }
        }
        return DesktopMessageFormatRepairController(chats, f.container.settingsRepository, f.container.characterRepository,
            f.container.formatCardRepository, f.container.effectiveModelResolver, f.runtime, scoped, { f.response(it) })
    }

    @Test fun `actual message index and preview failures preserve uncertain evidence inside exclusive scope`() = runBlocking {
        for ((call, stage) in listOf(3 to MessageUpdateStage.WRITE_MESSAGE, 4 to MessageUpdateStage.WRITE_INDEX,
            6 to MessageUpdateStage.WRITE_PREVIEW)) fixture { f ->
            val failure = java.io.IOException("synthetic post-write failure")
            val c = faultController(f) { if (it == call) {
                assertEquals("fixed", assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(
                    f.chats.readMessageDurable("m", "s")).value.displayContent)
                throw failure
            } }
            val state = run(f, c)
            assertEquals(DesktopRepairStatus.COMMIT_UNCERTAIN, state.status)
            val result = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(state.commit)
            assertEquals(stage, result.stage); assertSame(failure, result.cause)
            assertEquals("fixed", result.attempted.displayContent)
            assertEquals("fixed", assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(result.durableMessage).value.displayContent)
            assertEquals("fixed", f.reopened().displayContent)
            assertEquals(1, c.states.value.size)
        }
    }

    @Test fun `actual shutdown after durable write retains uncertain result across exclusive unwinding`() = runBlocking { fixture { f ->
        val written = CompletableDeferred<Unit>()
        val c = faultController(f) { if (it == 3) {
            assertEquals("fixed", assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(
                f.chats.readMessageDurable("m", "s")).value.displayContent)
            written.complete(Unit); awaitCancellation()
        } }
        val id = c.repair("s", "m"); written.await()
        assertEquals(DesktopDataOperationCoordinatorState.EXCLUSIVE, f.container.dataOperationCoordinator.state)
        f.runtime.closeAndDrain()
        val state = assertNotNull(c.states.value[id])
        assertEquals(DesktopRepairStatus.COMMIT_UNCERTAIN, state.status)
        val result = assertIs<ConditionalMessageUpdateResult.CommitUncertain>(state.commit)
        assertEquals(MessageUpdateStage.WRITE_MESSAGE, result.stage)
        assertIs<CancellationException>(result.cause)
        assertEquals("fixed", assertIs<JsonFileStorage.EntityReadResult.Valid<ChatMessage>>(result.durableMessage).value.displayContent)
        assertEquals("fixed", f.reopened().displayContent)
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.container.dataOperationCoordinator.state)
    } }
}
