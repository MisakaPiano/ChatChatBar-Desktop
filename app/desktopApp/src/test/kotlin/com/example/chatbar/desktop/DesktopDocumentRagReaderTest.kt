package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.rag.ChunkingEngine
import com.example.chatbar.domain.rag.VectorSearchEngine
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopDocumentRagReaderTest {
    private class Fixture {
        val root = Files.createTempDirectory("desktop-document-rag-")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        val storage get() = container.jsonFileStorage
        val reader get() = container.documentRagReader
        val config = EmbeddingConfig("embedding", "Synthetic", "https://invalid.example/v1/", "", "synthetic", 2)
        val identity = DesktopRagEmbeddingIdentity.from(config)
        var card = CharacterCard.create("Synthetic").copy(id = "card")
        lateinit var ownership: DesktopDataRootOwnership
        suspend fun prepare() {
            container.characterRepository.save(card)
            ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(container.resolvedRoot)).ownership
        }
        suspend fun document(id: String = "doc", count: Int = 8): List<VectorChunk> {
            val text = (0 until count).joinToString("\n\n") { "# Section $it\nSynthetic document body $it." }
            val path = Files.createDirectories(container.appDataRoot.resolve("documents")).resolve("$id.txt")
            Files.writeString(path, text)
            val hash = digest(text.toByteArray())
            val plan = ChunkingEngine().chunkDocument(text, id, "$id.txt")
            val info = DocumentInfo(id, "$id.txt", "documents/$id.txt", "txt", 1, hash, hash,
                DocumentRagStatus.INDEXED.name, plan.size, 2)
            card = card.copy(customDocuments = card.customDocuments + info)
            container.characterRepository.save(card)
            val chunks = plan.mapIndexed { index, (body, meta) ->
                VectorChunk("${id}_%05d".format(index), ChunkSourceType.DOCUMENT, card.id, content = body,
                    embedding = when (index % 4) { 0 -> listOf(1f, 0f); 1 -> listOf(1f, 1f); 2 -> listOf(0f, 1f); else -> listOf(1f, 0f) },
                    metadata = meta + mapOf("originalDocId" to id, "fileName" to "$id.txt", "contentHash" to hash,
                        "embeddingKey" to identity.key), createdAt = 1)
            }
            for (chunk in chunks) save(chunk)
            return chunks
        }
        suspend fun save(chunk: VectorChunk) = storage.saveEntity("vector_chunks", chunk.id, chunk, VectorChunk.serializer())
        fun path(id: String) = container.appDataRoot.resolve("entities/vector_chunks/$id.json")
        suspend fun read(topK: Int = 5, threshold: Float = .7f) = reader.retrieve(card, listOf(1f, 0f), identity, topK, threshold)
        fun snapshot(): Map<String, String> = Files.walk(container.appDataRoot).use { paths ->
            // Ownership lock is reserved infrastructure, not payload; Windows prohibits reading it.
            paths.filter { Files.isRegularFile(it) &&
                (it.startsWith(container.appDataRoot.resolve("entities")) || it.startsWith(container.appDataRoot.resolve("documents"))) }.toList().associate {
                container.appDataRoot.relativize(it).toString() to digest(Files.readAllBytes(it))
            }
        }
        suspend fun close() {
            try { container.close() } finally { if (::ownership.isInitialized) ownership.close(); root.toFile().deleteRecursively() }
        }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.prepare(); withTimeout(60_000) { block(f) } } finally { f.close() }
    }
    private fun unavailable(result: DesktopDocumentRagResult) = assertIs<DesktopDocumentRagResult.Unavailable>(result)

    @Test fun `valid documents use official search scores threshold topK and canonical ties without writes`() = runBlocking { fixture { f ->
        val chunks = f.document()
        f.document("second", 3)
        val all = chunks + f.card.customDocuments.last().let {
            // Decode test fixtures only, never product presets or user data.
            (0..2).map { f.storage.loadEntity("vector_chunks", "second_%05d".format(it), VectorChunk.serializer())!! }
        }
        val before = f.snapshot()
        for (k in listOf(0, 1, 5, 100)) for (threshold in listOf(-1f, .7f, 1f)) {
            val actual = assertIs<DesktopDocumentRagResult.Available>(f.read(k, threshold))
            val expected = VectorSearchEngine().search(listOf(1f, 0f), all.sortedBy { it.id }, k, threshold)
            assertEquals(expected.map { it.id }, actual.matches.map { it.chunkId })
            assertEquals(expected.map { VectorSearchEngine().cosineSimilarity(listOf(1f, 0f), it.embedding) }, actual.matches.map { it.similarity })
            assertTrue(actual.peakRetainedCandidates <= k)
            assertTrue(actual.matches.all { it.characterCardId == "card" && it.embeddingKey == f.identity.key })
        }
        assertEquals(before, f.snapshot())
    } }

    @Test fun `other character and chat memory and character setting never enter document retrieval`() = runBlocking { fixture { f ->
        val original = f.document().first()
        f.save(original.copy(id = "other", sourceId = "other-card"))
        f.save(original.copy(id = "chat", sourceType = ChunkSourceType.CHAT_MEMORY, sourceId = "session", messageId = "m"))
        f.save(original.copy(id = "setting", sourceType = ChunkSourceType.CHARACTER_SETTING))
        val result = assertIs<DesktopDocumentRagResult.Available>(f.read(100, -1f))
        assertEquals(8, result.matches.size)
        assertTrue(result.matches.none { it.chunkId in setOf("other", "chat", "setting") })
    } }

    @Test fun `embedding key dimension finite and zero vector failures are explicit and read only`() = runBlocking { fixture { f ->
        val original = f.document().first()
        val variants = listOf(original.copy(metadata = original.metadata + ("embeddingKey" to "different")),
            original.copy(embedding = listOf(1f)), original.copy(embedding = listOf(0f, 0f)),
            original.copy(embedding = listOf(Float.MAX_VALUE, Float.MAX_VALUE)))
        for (variant in variants) {
            f.save(variant); val before = f.snapshot()
            assertEquals(DesktopDocumentRagResult.Reason.EMBEDDING, unavailable(f.read()).reason)
            assertEquals(before, f.snapshot())
        }
        f.save(original)
        assertEquals(DesktopDocumentRagResult.Reason.INVALID_QUERY,
            unavailable(f.reader.retrieve(f.card, listOf(Float.NaN, 0f), f.identity)).reason)
        val path = f.path(original.id)
        Files.writeString(path, Files.readString(path).replace("1.0", "NaN"))
        unavailable(f.read()) // JSON parser must reject non-finite persisted values, not skip them.
    } }

    @Test fun `missing document orphan and changed source hash reject without repair`() = runBlocking { fixture { f ->
        val chunk = f.document().first()
        f.save(chunk.copy(metadata = chunk.metadata + ("originalDocId" to "missing")))
        val orphan = f.snapshot(); unavailable(f.read()); assertEquals(orphan, f.snapshot())
        f.save(chunk)
        Files.writeString(f.container.appDataRoot.resolve("documents/doc.txt"), "Changed source")
        val changed = f.snapshot(); unavailable(f.read()); assertEquals(changed, f.snapshot())
        Files.delete(f.container.appDataRoot.resolve("documents/doc.txt"))
        unavailable(f.read())
    } }

    @Test fun `partial and duplicate index content cannot be accepted as a complete document`() = runBlocking { fixture { f ->
        val chunks = f.document()
        Files.delete(f.path(chunks.last().id))
        unavailable(f.read())
        f.save(chunks.first().copy(id = chunks.last().id)) // Same count, wrong multiset.
        unavailable(f.read())
        f.save(chunks.last())
        assertIs<DesktopDocumentRagResult.Available>(f.read())
    } }

    @Test fun `corrupt unknown fields illegal storage identity and interrupted files invalidate all results`() = runBlocking { fixture { f ->
        val chunk = f.document().first(); val path = f.path(chunk.id); val original = Files.readString(path)
        for (text in listOf("{", original.replaceFirst("{", "{\"futureField\":1,"),
            original.replace(chunk.id, "wrong-id"))) {
            Files.writeString(path, text); val before = f.snapshot()
            unavailable(f.read()); assertEquals(before, f.snapshot())
        }
        Files.writeString(path, original)
        val interrupted = path.parent.resolve("unknown.tmp"); Files.writeString(interrupted, "not a complete entity")
        unavailable(f.read()); assertTrue(Files.exists(interrupted))
        Files.delete(interrupted)
        Files.createDirectory(path.parent.resolve("unexpected-directory"))
        unavailable(f.read())
    } }

    @Test fun `source metadata and stale character snapshots fail closed`() = runBlocking { fixture { f ->
        val original = f.document().first()
        for (variant in listOf(original.copy(messageId = "m"), original.copy(metadata = original.metadata + ("future" to "x")),
            original.copy(content = "forged"), original.copy(metadata = original.metadata + ("documentId" to "wrong")))) {
            f.save(variant); unavailable(f.read())
        }
        f.save(original)
        f.container.characterRepository.save(f.card.copy(name = "Updated"))
        assertEquals(DesktopDocumentRagResult.Reason.CHARACTER_CHANGED, unavailable(f.read()).reason)
    } }

    @Test fun `absolute traversal and linked document references cannot escape root`() = runBlocking { fixture { f ->
        f.document(); val original = f.card
        for (reference in listOf("../doc.txt", f.root.resolve("outside.txt").toString(), "assets://doc.txt")) {
            f.card = original.copy(customDocuments = original.customDocuments.map { it.copy(filePath = reference) })
            f.container.characterRepository.save(f.card); unavailable(f.read())
        }
        f.card = original; f.container.characterRepository.save(original)
        val link = f.container.appDataRoot.resolve("documents/link")
        val target = Files.createDirectory(f.root.resolve("synthetic-link-target"))
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start()
            process.inputStream.readAllBytes() // Never log path/contents.
            assertEquals(0, process.waitFor())
        } else Files.createSymbolicLink(link, target)
        try {
            f.card = original.copy(customDocuments = original.customDocuments.map { it.copy(filePath = "documents/link/doc.txt") })
            f.container.characterRepository.save(f.card); unavailable(f.read())
        } finally { Files.delete(link) }
        f.card = original; f.container.characterRepository.save(original)
        val directory = f.container.appDataRoot.resolve("documents")
        val retained = f.root.resolve("retained-owned-documents")
        Files.move(directory, retained)
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", directory.toString(), retained.toString())
                .redirectErrorStream(true).start()
            process.inputStream.readAllBytes(); assertEquals(0, process.waitFor())
        } else Files.createSymbolicLink(directory, retained)
        try { unavailable(f.read()) } finally { Files.delete(directory); Files.move(retained, directory) }
    } }

    @Test fun `large document index retains only bounded candidates and one document plan`() = runBlocking { fixture { f ->
        val chunks = f.document(count = 1200)
        val result = assertIs<DesktopDocumentRagResult.Available>(f.read(7))
        assertEquals(1200, result.scannedFiles)
        assertEquals(7, result.peakRetainedCandidates)
        assertEquals(VectorSearchEngine().search(listOf(1f, 0f), chunks.sortedBy { it.id }, 7, .7f).map { it.id },
            result.matches.map { it.chunkId })
    } }

    @Test fun `cancellation while writer holds root releases queued reader and permits later retrieval`() = runBlocking { fixture { f ->
        f.document(); val before = f.snapshot()
        val acquired = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writer = launch { f.container.dataOperationCoordinator.withNormalOperation { acquired.complete(Unit); release.await() } }
        acquired.await()
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.read() }
        assertFalse(read.isCompleted)
        read.cancelAndJoin(); release.complete(Unit); writer.join()
        assertIs<DesktopDocumentRagResult.Available>(f.read())
        assertEquals(before, f.snapshot())
    } }

    @Test fun `exclusive read snapshot excludes cooperative late writer and never upgrades normal lease`() = runBlocking { fixture { f ->
        f.document()
        val acquired = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writer = launch { f.container.dataOperationCoordinator.withNormalOperation {
            acquired.complete(Unit); release.await()
            val doc = f.container.appDataRoot.resolve("documents/doc.txt"); Files.writeString(doc, "new content")
        } }
        acquired.await()
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.read() }
        assertFalse(read.isCompleted); release.complete(Unit); writer.join()
        unavailable(read.await()) // Reader proves the completed write, never returns stale matches.
        f.container.dataOperationCoordinator.withNormalOperation { unavailable(f.read()) }
    } }

    @Test fun `missing ownership closed coordinator and empty library have explicit outcomes`() = runBlocking { fixture { f ->
        assertEquals(emptyList(), assertIs<DesktopDocumentRagResult.Available>(f.read()).matches)
        f.ownership.close()
        assertEquals(DesktopDocumentRagResult.Reason.OWNERSHIP, unavailable(f.read()).reason)
        f.container.dataOperationCoordinator.closeAndDrain()
        unavailable(f.read())
    } }

    @Test fun `retrieval never changes serialized model messages or automatically injects document text`() = runBlocking { fixture { f ->
        f.document()
        f.container.chatRepository.createSession(ChatSession("s", f.card.id, "Synthetic", createdAt = 1, updatedAt = 1))
        val user = f.container.chatRepository.addMessage(ChatMessage.create("s", MessageRole.USER, "synthetic user"))
        val model = ModelConfig("fake", "Synthetic", "https://invalid.example", "fake-test", "synthetic", createdAt = 1)
        suspend fun request(): String {
            val plan = f.container.chatRequestPlanner.planPersistedUser("s", user,
                DesktopFakeChatInputs(effectiveContextWindowSize = 5), readOnlyRepositoryAccess = true)
            return OpenAiChatRequestSerializer.serialize(plan.assembly.messages, model, true)
        }
        val before = request()
        assertIs<DesktopDocumentRagResult.Available>(f.read())
        assertEquals(before, request())
        assertFalse(request().contains("Synthetic document body"))
    } }

    @Test fun `cancelling an active scan releases exclusive barrier and unblocks exactly one waiting writer`() = runBlocking { fixture { f ->
        f.document()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reader = DesktopDocumentRagReader(f.container.appDataRoot, f.storage,
            f.container.dataOperationCoordinator, onChunkRead = { entered.complete(Unit); release.await() })
        val read = async { reader.retrieve(f.card, listOf(1f, 0f), f.identity) }
        entered.await()
        val wrote = CompletableDeferred<Unit>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            f.container.dataOperationCoordinator.withNormalOperation { wrote.complete(Unit) }
        }
        assertFalse(wrote.isCompleted)
        val before = f.snapshot()
        read.cancelAndJoin(); writer.join()
        assertTrue(wrote.isCompleted)
        assertEquals(before, f.snapshot())
        assertIs<DesktopDocumentRagResult.Available>(f.read())
        assertTrue(DesktopDataRootOwnership.isHeld(f.container.appDataRoot))
    } }

    @Test fun `embedding identity matches independently fixed official base URL and model hashing rule`() {
        val config = EmbeddingConfig("id", "Synthetic", "https://invalid.example/v1///", "unused", "model", 2)
        val result = DesktopRagEmbeddingIdentity.from(config)
        assertEquals(digest("https://invalid.example/v1|model".toByteArray()), result.key)
        assertEquals(result.key, DesktopRagEmbeddingIdentity.from(config.copy(apiKey = "different", dimensions = 3)).key)
        assertEquals(2, result.dimensions)
    }

    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
