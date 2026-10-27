package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.root.AppDataRootInfrastructure
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.rag.ChunkingEngine
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopDocumentRagIndexPreparationTest {
    private class Fixture {
        val root = Files.createTempDirectory("index-preparation-")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val storage get() = container.jsonFileStorage
        val coordinator get() = container.dataOperationCoordinator
        val tasks get() = container.taskRuntime
        var card = CharacterCard.create("Synthetic").copy(id = "card")
        var config = EmbeddingConfig("default", "Synthetic", "https://invalid.example/v1", "", "synthetic", 2)
        lateinit var ownership: DesktopDataRootOwnership
        var owns = false
        var closed = false
        suspend fun seed() {
            container.characterRepository.save(card)
            saveConfig()
            ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(container.resolvedRoot)).ownership
            owns = true
            document()
        }
        suspend fun saveConfig() = storage.saveEntity("embedding_model_config", "default", config, EmbeddingConfig.serializer())
        suspend fun document(text: String = "# Alpha\nSynthetic first body.\n\n# Beta\nSynthetic second body.",
            status: String = DocumentRagStatus.PENDING.name) {
            val file = Files.createDirectories(container.appDataRoot.resolve("documents")).resolve("doc.txt")
            Files.writeString(file, text)
            val info = DocumentInfo("doc", "doc.txt", "documents/doc.txt", "txt", 1, ragStatus = status)
            card = card.copy(customDocuments = listOf(info))
            container.characterRepository.save(card)
        }
        suspend fun saveCard(value: CharacterCard) { card = value; container.characterRepository.save(value) }
        fun file() = container.appDataRoot.resolve("documents/doc.txt")
        fun good(batch: DesktopIndexEmbeddingBatch) = batch.pieces.map { List(batch.identity.dimensions) { index -> if (index == 0) 1f else 0f } }
        fun service(gateway: DesktopIndexEmbeddingGateway = DesktopIndexEmbeddingGateway(::good)) = container.createDocumentIndexPreparation(gateway)
        fun snapshot(): Map<String, String> = Files.walk(container.appDataRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() != AppDataRootInfrastructure.OWNERSHIP_LOCK_FILE_NAME }
                .toList().associate { container.appDataRoot.relativize(it).toString() to digest(Files.readAllBytes(it)) }
        }
        suspend fun unchanged(block: suspend () -> DesktopIndexPreparationResult): DesktopIndexPreparationResult {
            val before = snapshot(); val result = block(); assertEquals(before, snapshot())
            assertFalse(result.indexed); assertFalse(result.committed)
            assertFalse(result.toString().contains("Synthetic first body"))
            return result
        }
        suspend fun close() {
            try { if (!closed) container.close() } finally {
                if (owns) ownership.close()
                root.toFile().deleteRecursively()
            }
        }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.seed(); withTimeout(60_000) { block(f) } } finally { f.close() }
    }
    private fun prepared(result: DesktopIndexPreparationResult): DesktopIndexPreparationSummary {
        assertEquals(DesktopIndexPreparationStatus.PREPARED_ONLY, result.status)
        return assertNotNull(result.summary)
    }
    private fun rejected(result: DesktopIndexPreparationResult, reason: DesktopIndexPreparationReason? = null) {
        assertEquals(DesktopIndexPreparationStatus.REJECTED, result.status)
        assertNull(result.summary)
        reason?.let { assertEquals(it, result.reason) }
    }

    @Test fun `pending source uses exact official ordered chunks and metadata without any persistence`() = runBlocking { fixture { f ->
        f.document((0 until 40).joinToString("\n") { "# Section $it\nSynthetic body $it." })
        val text = Files.readString(f.file()); val doc = f.card.customDocuments.single()
        val expected = ChunkingEngine().chunkDocument(text, doc.id, doc.fileName)
        val identity = DesktopRagEmbeddingIdentity.from(f.config)
        var seen = 0
        val service = f.service { batch ->
            assertEquals(identity, batch.identity); assertEquals(seen, batch.firstIndex)
            assertTrue(batch.pieces.size <= 16)
            for ((offset, piece) in batch.pieces.withIndex()) {
                assertEquals(expected[seen + offset].first, piece.first)
                assertEquals(expected[seen + offset].second + mapOf("originalDocId" to "doc", "fileName" to doc.fileName,
                    "contentHash" to digest(text.toByteArray()), "embeddingKey" to identity.key), piece.second)
            }
            seen += batch.pieces.size; f.good(batch)
        }
        val summary = prepared(f.unchanged { service.prepare("card", "doc") })
        assertEquals(40, seen); assertEquals(40, summary.chunkCount); assertEquals(3, summary.validatedBatches)
        assertEquals(16, summary.peakBatchVectors); assertNull(summary.recordedContentHashMatches)
        assertNull(summary.recordedIndexedHashMatches); assertEquals("PENDING", summary.sourceStatus)
        assertEquals(64, summary.orderedDigest.length)
        assertEquals(summary.orderedDigest, prepared(f.unchanged { f.service().prepare("card", "doc") }).orderedDigest)
        assertFalse(Files.exists(f.container.appDataRoot.resolve("entities/vector_chunks")))
    } }

    @Test fun `indexed source with stale recorded hashes keeps actual evidence and old vectors intact`() = runBlocking { fixture { f ->
        f.document(status = "INDEXED")
        f.saveCard(f.card.copy(customDocuments = f.card.customDocuments.map { it.copy(contentHash = "old", indexedHash = "old",
            ragChunkCount = 999, ragIndexedAt = 7, ragError = "retained") }))
        val old = VectorChunk("old", ChunkSourceType.DOCUMENT, "card", content = "Synthetic old index",
            embedding = listOf(1f, 0f), metadata = mapOf("originalDocId" to "doc"), createdAt = 1)
        f.storage.saveEntity("vector_chunks", old.id, old, VectorChunk.serializer())
        val summary = prepared(f.unchanged { f.service().prepare("card", "doc") })
        assertEquals(false, summary.recordedContentHashMatches); assertEquals(false, summary.recordedIndexedHashMatches)
        assertEquals(digest(Files.readAllBytes(f.file())), summary.contentHash)
        assertEquals("INDEXED", summary.sourceStatus)
        // Existing vectors are not used as publication proof; even unknown records are preserved.
        Files.writeString(f.container.appDataRoot.resolve("entities/vector_chunks/unknown.json"), "{broken")
        prepared(f.unchanged { f.service().prepare("card", "doc") })
    } }

    @Test fun `all source budgets reject before shared chunk allocation or embedding`() = runBlocking { fixture { f ->
        val cases = listOf("# " + "h".repeat(129) + "\nbody", "# S\nx\n".repeat(2049), "x".repeat(720_000),
            (1..6).joinToString("\n") { "#".repeat(it) + " " + "h".repeat(128) } + "\nbody")
        var plans = 0; var calls = 0
        val service = DesktopDocumentRagIndexPreparation(f.container.appDataRoot, f.storage, f.coordinator, f.tasks,
            DesktopIndexEmbeddingGateway { calls++; f.good(it) }, onPlanGeneration = { plans++ })
        for ((index, text) in cases.withIndex()) {
            f.document(text)
            if (index == 3) f.saveCard(f.card.copy(customDocuments = f.card.customDocuments.map { it.copy(fileName = "n".repeat(256)) }))
            rejected(f.unchanged { service.prepare("card", "doc") }, DesktopIndexPreparationReason.SOURCE)
        }
        f.document("body")
        f.saveCard(f.card.copy(customDocuments = f.card.customDocuments.map { it.copy(fileName = "n".repeat(257)) }))
        rejected(f.unchanged { service.prepare("card", "doc") }, DesktopIndexPreparationReason.SOURCE)
        f.document("x".repeat(1_048_577))
        rejected(f.unchanged { service.prepare("card", "doc") }, DesktopIndexPreparationReason.SOURCE)
        assertEquals(0, plans); assertEquals(0, calls)
    } }

    @Test fun `vector count dimension finite norm and overflow validation reject the entire preparation`() = runBlocking { fixture { f ->
        f.document("plain body")
        val bad = listOf<List<List<Float>>>(emptyList(), listOf(listOf(1f, 0f), listOf(1f, 0f)),
            listOf(listOf(1f)), listOf(listOf(Float.NaN, 0f)), listOf(listOf(Float.POSITIVE_INFINITY, 0f)),
            listOf(listOf(0f, 0f)), listOf(listOf(Float.MAX_VALUE, Float.MAX_VALUE)), listOf(listOf(Float.MIN_VALUE, 0f)))
        for (vectors in bad) rejected(f.unchanged { f.service { vectors }.prepare("card", "doc") }, DesktopIndexPreparationReason.EMBEDDING)
        assertFalse(Files.exists(f.container.appDataRoot.resolve("entities/vector_chunks")))
    } }

    @Test fun `second batch failure returns no partial prepared summary and no exception text`() = runBlocking { fixture { f ->
        f.document((0..20).joinToString("\n") { "# Part $it\nbody $it" })
        var calls = 0
        val result = f.unchanged { f.service { batch ->
            if (++calls == 2) error("Synthetic sensitive failure detail")
            f.good(batch)
        }.prepare("card", "doc") }
        assertEquals(2, calls); rejected(result, DesktopIndexPreparationReason.EMBEDDING)
        assertFalse(result.toString().contains("failure detail"))
    } }

    @Test fun `high dimension batch capacity is bounded independently of document chunk count`() = runBlocking { fixture { f ->
        f.config = f.config.copy(dimensions = 8192); f.saveConfig()
        f.document((0 until 20).joinToString("\n") { "# Part $it\nbody $it" })
        val summary = prepared(f.unchanged { f.service { batch ->
            assertTrue(batch.pieces.size <= 8); f.good(batch)
        }.prepare("card", "doc") })
        assertEquals(3, summary.validatedBatches); assertEquals(8, summary.peakBatchVectors)
        assertEquals(65536, summary.peakBatchFloats)
    } }

    private suspend fun sourceRace(change: suspend (Fixture) -> Unit) = fixture { f -> coroutineScope {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val job = async { f.service { batch -> entered.complete(Unit); release.await(); f.good(batch) }.prepare("card", "doc") }
        entered.await(); change(f)
        val afterWriter = f.snapshot(); release.complete(Unit)
        val result = job.await(); assertTrue(result.status in setOf(DesktopIndexPreparationStatus.STALE, DesktopIndexPreparationStatus.REJECTED))
        assertNull(result.summary); assertEquals(afterWriter, f.snapshot())
        assertFalse(f.tasks.hasDocumentPreparation("card", "doc"))
    } }

    @Test fun `body mutation during gateway is rejected by final source hash`() = runBlocking {
        sourceRace { f -> f.coordinator.withNormalOperation { Files.writeString(f.file(), "new synthetic body") } }
    }
    @Test fun `document removal during gateway rejects late vectors`() = runBlocking {
        sourceRace { f -> f.saveCard(f.card.copy(customDocuments = emptyList())) }
    }
    @Test fun `overwritten card identity snapshot is not silently accepted`() = runBlocking {
        sourceRace { f -> f.saveCard(f.card.copy(name = "Replaced synthetic card")) }
    }
    @Test fun `embedding config mutations cannot reuse an old plan identity`() = runBlocking {
        for (variant in 0..3) sourceRace { f ->
            f.config = when (variant) { 0 -> f.config.copy(modelName = "new"); 1 -> f.config.copy(dimensions = 3)
                2 -> f.config.copy(baseUrl = "https://other.invalid"); else -> f.config.copy(displayName = "Replaced") }
            f.saveConfig()
        }
    }
    @Test fun `source path rebinding cannot validate an earlier owned source`() = runBlocking {
        sourceRace { f ->
            f.coordinator.withNormalOperation { Files.writeString(f.file().resolveSibling("new.txt"), "other") }
            f.saveCard(f.card.copy(customDocuments = f.card.customDocuments.map { it.copy(filePath = "documents/new.txt") }))
        }
    }
    @Test fun `deleted card and config reject final proof without recreating entities`() = runBlocking {
        sourceRace { f -> f.storage.deleteEntity<CharacterCard>("character_cards", "card") }
        sourceRace { f -> f.storage.deleteEntity<EmbeddingConfig>("embedding_model_config", "default") }
    }

    @Test fun `duplicate pair admission is rejected without affecting chat image or repair admission`() = runBlocking { fixture { f -> coroutineScope {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val service = f.service { batch -> calls++; entered.complete(Unit); release.await(); f.good(batch) }
        val before = f.snapshot(); val first = async { service.prepare("card", "doc") }
        entered.await(); assertTrue(f.tasks.hasDocumentPreparation("card", "doc"))
        rejected(service.prepare("card", "doc"), DesktopIndexPreparationReason.ADMISSION); assertEquals(1, calls)
        assertFalse(f.tasks.hasActiveSessionTask("synthetic-session"))
        val repairId = f.tasks.launchFormatRepair("synthetic-session", "message") { _, _ -> DesktopRepairStatus.UNCHANGED }
        assertTrue(repairId.isNotBlank())
        release.complete(Unit); prepared(first.await()); assertFalse(f.tasks.hasDocumentPreparation("card", "doc"))
        assertEquals(before, f.snapshot())
        prepared(f.unchanged { f.service().prepare("card", "doc") })
    } } }

    @Test fun `caller cancellation joins its registered child and preserves source bytes`() = runBlocking { fixture { f -> coroutineScope {
        val entered = CompletableDeferred<Unit>(); val finalized = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        val before = f.snapshot()
        val job = async { f.service { batch ->
            try { entered.complete(Unit); hold.await(); f.good(batch) } finally { finalized.complete(Unit) }
        }.prepare("card", "doc") }
        entered.await(); job.cancelAndJoin(); finalized.await()
        assertFalse(f.tasks.hasDocumentPreparation("card", "doc")); assertEquals(before, f.snapshot())
        prepared(f.unchanged { f.service().prepare("card", "doc") })
    } } }

    @Test fun `same container shutdown cancels and drains preparation without publishing late state`() = runBlocking { fixture { f -> coroutineScope {
        val entered = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>(); val finalized = CompletableDeferred<Unit>()
        val before = f.snapshot()
        val result = async { f.service { batch ->
            try { entered.complete(Unit); hold.await(); f.good(batch) } finally { finalized.complete(Unit) }
        }.prepare("card", "doc") }
        entered.await(); f.container.close(); f.closed = true; finalized.await()
        assertEquals(DesktopIndexPreparationStatus.CANCELLED, result.await().status)
        assertFalse(f.tasks.hasDocumentPreparation("card", "doc"))
        rejected(f.service().prepare("card", "doc"), DesktopIndexPreparationReason.ADMISSION)
        assertEquals(before, f.snapshot())
    } } }

    @Test fun `exclusive source snapshot excludes a queued normal writer but gateway does not`() = runBlocking { fixture { f -> coroutineScope {
        val read = CompletableDeferred<Unit>(); val releaseRead = CompletableDeferred<Unit>()
        val embedding = CompletableDeferred<Unit>(); val releaseEmbedding = CompletableDeferred<Unit>(); var snapshots = 0
        val service = DesktopDocumentRagIndexPreparation(f.container.appDataRoot, f.storage, f.coordinator, f.tasks,
            DesktopIndexEmbeddingGateway { batch -> embedding.complete(Unit); releaseEmbedding.await(); f.good(batch) },
            onSnapshotRead = { if (++snapshots == 1) { read.complete(Unit); releaseRead.await() } })
        val job = async { service.prepare("card", "doc") }; read.await()
        val wrote = CompletableDeferred<Unit>()
        val writer = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.withNormalOperation {
            Files.writeString(f.file(), "new body"); wrote.complete(Unit)
        } }
        assertFalse(wrote.isCompleted); releaseRead.complete(Unit)
        embedding.await(); writer.await(); wrote.await() // Proves no exclusive held across gateway.
        val afterWriter = f.snapshot(); releaseEmbedding.complete(Unit)
        assertEquals(DesktopIndexPreparationStatus.STALE, job.await().status); assertEquals(afterWriter, f.snapshot())
    } } }

    @Test fun `final source proof shares exclusive barrier and never upgrades a normal operation`() = runBlocking { fixture { f -> coroutineScope {
        val finalRead = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var snapshots = 0
        val before = f.snapshot()
        val service = DesktopDocumentRagIndexPreparation(f.container.appDataRoot, f.storage, f.coordinator, f.tasks,
            DesktopIndexEmbeddingGateway(f::good), onSnapshotRead = {
                if (++snapshots == 2) { finalRead.complete(Unit); release.await() }
            })
        val job = async { service.prepare("card", "doc") }; finalRead.await()
        val admitted = CompletableDeferred<Unit>()
        val writer = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.withNormalOperation { admitted.complete(Unit) } }
        assertFalse(admitted.isCompleted); release.complete(Unit); prepared(job.await()); writer.await()
        assertEquals(before, f.snapshot())
        rejected(f.coordinator.withNormalOperation { f.service().prepare("card", "doc") }, DesktopIndexPreparationReason.LIFECYCLE)
    } } }

    @Test fun `ownership loss and restart required state block final preparation`() = runBlocking {
        sourceRace { f -> f.ownership.close(); f.owns = false }
        sourceRace { f -> f.coordinator.withExclusiveMaintenance { requireRestart() } }
    }

    @Test fun `strict inventory rejects malformed unknown and contradictory source records without writes`() = runBlocking { fixture { f ->
        val cardFile = f.container.appDataRoot.resolve("entities/character_cards/card.json")
        val configFile = f.container.appDataRoot.resolve("entities/embedding_model_config/default.json")
        for (file in listOf(cardFile, configFile)) {
            val original = Files.readString(file)
            for (bad in listOf("{broken", original.replaceFirst("{", "{\"futureUnknown\":true,"),
                original.replaceFirst(Regex("\"id\"\\s*:\\s*\"[^\"]*\""), "\"id\":\"wrong\""))) {
                Files.writeString(file, bad)
                rejected(f.unchanged { f.service().prepare("card", "doc") })
            }
            Files.writeString(file, original)
        }
        for (reference in listOf("../doc.txt", "documents/../doc.txt", "assets://doc.txt", f.file().toString())) {
            f.saveCard(f.card.copy(customDocuments = f.card.customDocuments.map { it.copy(filePath = reference) }))
            rejected(f.unchanged { f.service().prepare("card", "doc") }, DesktopIndexPreparationReason.SOURCE)
        }
    } }

    @Test fun `document directory junction cannot bypass shared source validation`() = runBlocking { fixture { f ->
        val directory = f.container.appDataRoot.resolve("documents"); val retained = f.root.resolve("retained")
        Files.move(directory, retained)
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", directory.toString(), retained.toString())
                .redirectErrorStream(true).start()
            process.inputStream.readAllBytes(); assertEquals(0, process.waitFor())
        } else Files.createSymbolicLink(directory, retained)
        try { rejected(f.unchanged { f.service().prepare("card", "doc") }) }
        finally { Files.delete(directory); Files.move(retained, directory) }
    } }

    @Test fun `legacy embedding namespace is explicit and invalid config never calls gateway`() = runBlocking { fixture { f ->
        val legacy = f.config.copy(id = "legacy")
        f.storage.saveEntity("embedding_configs", legacy.id, legacy, EmbeddingConfig.serializer())
        prepared(f.unchanged { f.service().prepare("card", "doc", DesktopIndexEmbeddingReference(DesktopIndexEmbeddingStore.LEGACY, "legacy")) })
        var calls = 0
        val service = f.service { batch -> calls++; f.good(batch) }
        for (dimension in listOf(0, 65537)) {
            f.config = f.config.copy(dimensions = dimension); f.saveConfig()
            rejected(f.unchanged { service.prepare("card", "doc") }, DesktopIndexPreparationReason.EMBEDDING)
        }
        assertEquals(0, calls)
        rejected(f.unchanged { service.prepare("card", "doc", DesktopIndexEmbeddingReference(id = "legacy")) })
    } }

    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
