package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual synthetic filesystem mutations; no production runtime wiring. */
class DesktopDocumentIndexTransactionPrototypeTest {
    internal class Fixture {
        val root = Files.createTempDirectory("p9-s2b-synthetic-")
        val coordinator = DesktopDataOperationCoordinator()
        val storage = JsonFileStorage(root, coordinator)
        lateinit var owner: DesktopDataRootOwnership
        suspend fun seed() { SyntheticIndexTxnData.seed(root); owner = SyntheticIndexTxnData.acquire(root) }
        fun prototype(cut: suspend (IndexTxnCut) -> Unit = {}) = DesktopDocumentIndexTransactionPrototype(root, storage, coordinator, cut)
        fun pending(): Path = Files.newDirectoryStream(root.resolve("entities/prototype_index_transactions/pending")).use { it.single() }
        fun fullHashes() = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() != ".ccb-desktop.lock" }.toList()
            .associate { root.relativize(it).toString() to DesktopDocumentIndexTransactionPrototype.digest(Files.readAllBytes(it)) } }
        suspend fun close() { coordinator.closeAndDrain(); owner.close(); root.toFile().deleteRecursively() }
    }
    private class Injected(val point: IndexTxnCut) : java.io.IOException("Synthetic transaction cut: $point")
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.seed(); withTimeout(60_000) { block(f) } } finally { f.close() }
    }
    private suspend fun stop(f: Fixture, point: IndexTxnCut) {
        val failure = assertFailsWith<Injected> { f.prototype { if (it == point) throw Injected(it) }.publish() }
        assertEquals(point, failure.point)
    }
    private fun committed(point: IndexTxnCut) = point in setOf(IndexTxnCut.AFTER_MARKER, IndexTxnCut.DURING_CLEANUP, IndexTxnCut.AFTER_CLEANUP)
    private fun unsealed(point: IndexTxnCut) = point in setOf(IndexTxnCut.AFTER_STAGE, IndexTxnCut.BEFORE_JOURNAL, IndexTxnCut.INTENT_WITHOUT_SEAL)

    @Test fun `actual file fault matrix never opens a mixed or unproven generation`() = runBlocking {
        for (point in IndexTxnCut.entries.takeWhile { it != IndexTxnCut.RECOVERY_FIRST_NEW_REMOVED }) fixture { f ->
            val old = SyntheticIndexTxnData.payloadHashes(f.root); val other = SyntheticIndexTxnData.protectedHashes(f.root)
            stop(f, point)
            if (point != IndexTxnCut.BEFORE_STAGE) assertFalse(f.prototype().businessOpen())
            val receipt = f.prototype().recover()
            val expected = when { unsealed(point) -> IndexTxnOutcome.COMMIT_UNCERTAIN
                committed(point) -> IndexTxnOutcome.COMMITTED; else -> IndexTxnOutcome.NOT_COMMITTED }
            assertEquals(expected, receipt.outcome, point.name)
            assertEquals(!unsealed(point), receipt.businessOpen, point.name)
            assertEquals(other, SyntheticIndexTxnData.protectedHashes(f.root))
            if (!committed(point)) assertEquals(old, SyntheticIndexTxnData.payloadHashes(f.root))
            if (receipt.businessOpen) {
                val beforeAgain = f.fullHashes(); assertEquals(receipt, f.prototype().recover()); assertEquals(beforeAgain, f.fullHashes())
                assertConsistentReader(f)
            }
            IndexTxnTestEvidence.record("fault-matrix", point.name, receipt.outcome.name, receipt.businessOpen)
        }
    }

    @Test fun `recovery can itself stop after removal or restore and then converge idempotently`() = runBlocking {
        for (cut in listOf(IndexTxnCut.RECOVERY_FIRST_NEW_REMOVED, IndexTxnCut.RECOVERY_FIRST_OLD_RESTORED)) fixture { f ->
            val old = SyntheticIndexTxnData.payloadHashes(f.root)
            stop(f, IndexTxnCut.AFTER_CARD)
            // Recovery treats a synthetic IOException as an uncertain result; it does not retry.
            val partial = f.prototype { if (it == cut) throw Injected(it) }.recover()
            assertEquals(IndexTxnOutcome.COMMIT_UNCERTAIN, partial.outcome); assertFalse(partial.businessOpen)
            val finished = f.prototype().recover()
            assertEquals(IndexTxnOutcome.NOT_COMMITTED, finished.outcome); assertTrue(finished.businessOpen)
            assertEquals(old, SyntheticIndexTxnData.payloadHashes(f.root))
            val full = f.fullHashes(); assertEquals(finished, f.prototype().recover()); assertEquals(full, f.fullHashes())
            IndexTxnTestEvidence.record("recovery-fault", cut.name, finished.outcome.name, true)
        }
    }

    @Test fun `journal old new and marker corruption retain all evidence and refuse recovery writes`() = runBlocking {
        for (kind in listOf("intent", "old-backup", "old-live", "new-stage", "new-live", "card", "marker", "stray")) fixture { f ->
            stop(f, if (kind == "marker") IndexTxnCut.AFTER_MARKER else IndexTxnCut.FIRST_NEW_INSTALLED)
            val tx = f.pending()
            val path = when (kind) {
                "intent" -> tx.resolve("intent.json")
                "old-backup" -> tx.resolve("backup/vectors/old_0.json")
                "old-live" -> tx.resolve("moved/old_0.json")
                "new-stage" -> Files.newDirectoryStream(tx.resolve("stage/vectors")).use { it.first() }
                "new-live" -> Files.newDirectoryStream(f.root.resolve("entities/vector_chunks")).use { paths -> paths.first { it.fileName.toString().startsWith("txn_") } }
                "card" -> f.root.resolve("entities/character_cards/card.json")
                "marker" -> tx.resolve("commit.marker")
                else -> tx.resolve("unknown-side-file")
            }
            Files.writeString(path, "Synthetic corrupt bytes")
            val before = f.fullHashes(); val result = f.prototype().recover()
            assertEquals(IndexTxnOutcome.COMMIT_UNCERTAIN, result.outcome, kind); assertFalse(result.businessOpen)
            assertEquals(before, f.fullHashes(), kind)
            IndexTxnTestEvidence.record("corruption", kind, result.outcome.name, false)
        }
    }

    @Test fun `source edits deletion replacement and config changes block before any index installation`() = runBlocking {
        for (kind in listOf("document", "card-delete", "card-edit", "config")) fixture { f ->
            val oldVectors = SyntheticIndexTxnData.payloadHashes(f.root).filterKeys { it.startsWith("entities/vector_chunks/") }
            var expected: Map<String, String>? = null
            val task = f.prototype { step -> if (step == IndexTxnCut.AFTER_STAGE) {
                f.coordinator.withNormalOperation {
                    when (kind) {
                        "document" -> Files.writeString(f.root.resolve("documents/doc.txt"), "Synthetic edited source")
                        "card-delete" -> f.storage.deleteEntity<CharacterCard>("character_cards", "card")
                        "card-edit" -> {
                            val card = f.storage.loadEntity("character_cards", "card", CharacterCard.serializer())!!
                            f.storage.saveEntity("character_cards", "card", card.copy(name = "Synthetic replacement"), CharacterCard.serializer())
                        }
                        else -> {
                            val config = f.storage.loadEntity("embedding_configs", "embedding", EmbeddingConfig.serializer())!!
                            f.storage.saveEntity("embedding_configs", "embedding", config.copy(modelName = "changed"), EmbeddingConfig.serializer())
                        }
                    }
                }
                expected = SyntheticIndexTxnData.payloadHashes(f.root)
            } }
            assertFailsWith<IllegalStateException> { task.publish() }
            assertEquals(expected, SyntheticIndexTxnData.payloadHashes(f.root))
            assertEquals(oldVectors, SyntheticIndexTxnData.payloadHashes(f.root).filterKeys { it.startsWith("entities/vector_chunks/") })
            val beforeRecovery = f.fullHashes(); assertEquals(IndexTxnOutcome.COMMIT_UNCERTAIN, f.prototype().recover().outcome)
            assertEquals(beforeRecovery, f.fullHashes())
        }
    }

    @Test fun `manual orphan unknown and damaged vector records are never swept or overwritten`() = runBlocking {
        for (kind in listOf("manual", "orphan", "legacy", "unknown-field", "malformed", "wrong-id")) fixture { f ->
            val old = f.storage.loadEntity("vector_chunks", "old_0", VectorChunk.serializer())!!
            val file = f.root.resolve("entities/vector_chunks/protected.json")
            when (kind) {
                "manual" -> f.storage.saveEntity("vector_chunks", "protected", old.copy(id = "protected", metadata = emptyMap()), VectorChunk.serializer())
                "orphan" -> f.storage.saveEntity("vector_chunks", "protected", old.copy(id = "protected", metadata = old.metadata + ("originalDocId" to "missing")), VectorChunk.serializer())
                "legacy" -> f.storage.saveEntity("vector_chunks", "protected", old.copy(id = "protected", sourceType = ChunkSourceType.CHARACTER_SETTING), VectorChunk.serializer())
                "wrong-id" -> f.storage.saveEntity("vector_chunks", "protected", old.copy(id = "other-id"), VectorChunk.serializer())
                "unknown-field" -> Files.writeString(file, Json.encodeToString(VectorChunk.serializer(), old.copy(id = "protected")).replaceFirst("{", "{\"future\":1,"))
                else -> Files.writeString(file, "{broken")
            }
            val before = f.fullHashes(); assertFails { f.prototype().publish() }; assertEquals(before, f.fullHashes())
            assertFalse(Files.exists(f.root.resolve("entities/prototype_index_transactions")))
        }
    }

    @Test fun `cancellation leaves a recoverable closed gate and no detached writer`() = runBlocking { fixture { f -> coroutineScope {
        val old = SyntheticIndexTxnData.payloadHashes(f.root)
        val reached = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>()
        val job = async { f.prototype { if (it == IndexTxnCut.FIRST_NEW_INSTALLED) { reached.complete(Unit); wait.await() } }.publish() }
        reached.await(); assertFalse(f.prototype().businessOpen()); job.cancelAndJoin()
        assertEquals(DesktopDataOperationCoordinatorState.OPEN, f.coordinator.state)
        assertEquals(IndexTxnOutcome.NOT_COMMITTED, f.prototype().recover().outcome)
        assertEquals(old, SyntheticIndexTxnData.payloadHashes(f.root))
    } } }

    @Test fun `actual filesystem write error cannot be mislabeled as complete rollback or success`() = runBlocking { fixture { f ->
        val old = SyntheticIndexTxnData.payloadHashes(f.root)
        val prototype = f.prototype { if (it == IndexTxnCut.BEFORE_JOURNAL) {
            val bad = Files.createDirectory(f.pending().resolve("intent.json")); Files.writeString(bad.resolve("foreign"), "synthetic")
        } }
        assertFailsWith<java.io.IOException> { prototype.publish() }
        assertEquals(old, SyntheticIndexTxnData.payloadHashes(f.root))
        val before = f.fullHashes(); val recovery = f.prototype().recover()
        assertEquals(IndexTxnOutcome.COMMIT_UNCERTAIN, recovery.outcome); assertEquals(before, f.fullHashes())
    } }

    @Test fun `real repository and storage caches refresh only after a verified decision`() = runBlocking {
        fixture { f ->
            val p = f.prototype(); assertEquals(1L, p.characters.getAll().single { it.id == "card" }.customDocuments.single().ragIndexedAt)
            f.storage.loadAll("vector_chunks", VectorChunk.serializer())
            stop(f, IndexTxnCut.AFTER_CARD)
            assertEquals(1L, p.characters.getAll().single { it.id == "card" }.customDocuments.single().ragIndexedAt)
            assertFalse(p.businessOpen())
            assertEquals(IndexTxnOutcome.NOT_COMMITTED, p.recover().outcome)
            assertTrue(f.storage.observeAll("vector_chunks", VectorChunk.serializer()).first().none { it.id.startsWith("txn_") })
            assertConsistentReader(f)
        }
        // A completed rollback also consumes this one-transaction protocol. Use a fresh
        // synthetic root for the independent successful cache-refresh case.
        fixture { f ->
            val p = f.prototype(); assertEquals(1L, p.characters.getAll().single { it.id == "card" }.customDocuments.single().ragIndexedAt)
            f.storage.loadAll("vector_chunks", VectorChunk.serializer())
            val next = p.publish(); assertEquals(IndexTxnOutcome.COMMITTED, next.outcome)
            assertEquals(2L, p.characters.getAll().single { it.id == "card" }.customDocuments.single().ragIndexedAt)
            assertTrue(f.storage.observeAll("vector_chunks", VectorChunk.serializer()).first().none { it.id.startsWith("old_") })
            assertConsistentReader(f)
        }
    }

    @Test fun `completed receipt revalidates live files and source without recovery writes`() = runBlocking {
        for (kind in listOf("vector-delete", "vector-corrupt", "card-corrupt", "source-edit", "source-delete", "config-edit", "unrelated-vector")) fixture { f ->
            assertEquals(IndexTxnReceipt(IndexTxnOutcome.COMMITTED, true), f.prototype().publish())
            SyntheticIndexTxnData.tamperCompleted(f.root, kind)
            val before = f.fullHashes()
            // New repositories/storage must not rely on the publishing instance's caches.
            val reopened = DesktopDocumentIndexTransactionPrototype(f.root, JsonFileStorage(f.root, f.coordinator), f.coordinator)
            assertFalse(reopened.businessOpen(), kind)
            val expected = IndexTxnReceipt(IndexTxnOutcome.COMMIT_UNCERTAIN, false)
            assertEquals(expected, reopened.recover(), kind)
            assertEquals(before, f.fullHashes(), kind)
            assertEquals(expected, reopened.recover(), kind)
            assertEquals(before, f.fullHashes(), "No rollback, cleanup, receipt or payload rewrite: $kind")
            IndexTxnTestEvidence.record("completed-tamper", kind, expected.outcome.name, false,
                buildJsonObject { put("allFilesByteIdenticalAfterRefusal", true) })
        }
    }

    @Test fun `completed receipt permits normal restart but rejects second publish before all side effects`() = runBlocking {
        for (committed in listOf(true, false)) fixture { f ->
            val first = if (committed) f.prototype().publish() else {
                stop(f, IndexTxnCut.AFTER_CARD); f.prototype().recover()
            }
            val before = f.fullHashes()
            val reopened = DesktopDocumentIndexTransactionPrototype(f.root, JsonFileStorage(f.root, f.coordinator), f.coordinator) {
                fail("Second publish must reject before reaching any transaction cut")
            }
            assertTrue(reopened.businessOpen()); assertEquals(first, reopened.recover())
            assertEquals(before, f.fullHashes())
            val failure = assertFailsWith<IllegalStateException> { reopened.publish() }
            assertEquals("Test protocol permits only one completed transaction", failure.message)
            assertEquals(before, f.fullHashes())
            assertTrue(reopened.businessOpen()); assertEquals(first, reopened.recover())
            assertEquals(before, f.fullHashes()); assertConsistentReader(f)
            IndexTxnTestEvidence.record("second-publish", first.outcome.name, "REJECTED", true,
                buildJsonObject { put("allFilesByteIdentical", true) })
        }
    }

    @Test fun `unknown prototype journal namespace makes existing production F4 safety reject`() = runBlocking { fixture { f ->
        stop(f, IndexTxnCut.AFTER_JOURNAL)
        val container = DesktopAppContainer(SyntheticIndexTxnData.resolved(f.root),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            val session = ChatSession.create("card", "Synthetic").copy(id = "s")
            val message = ChatMessage("m", "s", MessageRole.ASSISTANT, "Synthetic", createdAt = 1, updatedAt = 1)
            val result = CompletableDeferred<DesktopRepairSafety>()
            val gate = DesktopFormatRepairDerivedSafety(f.root, container.jsonFileStorage, container.dataOperationCoordinator, container.taskRuntime)
            container.taskRuntime.launchFormatRepair("s", "m") { _, _ -> result.complete(gate.check(session, message)); DesktopRepairStatus.UNCHANGED }
            assertEquals(DesktopRepairSafety.UNKNOWN, result.await())
        } finally { container.close() }
    } }

    private suspend fun assertConsistentReader(f: Fixture) {
        val card = f.storage.loadEntity("character_cards", "card", CharacterCard.serializer())!!
        val config = f.storage.loadEntity("embedding_configs", "embedding", EmbeddingConfig.serializer())!!
        val reader = DesktopDocumentRagReader(f.root, f.storage, f.coordinator)
        val value = assertIs<DesktopDocumentRagResult.Available>(reader.retrieve(card, listOf(1f, 0f), DesktopRagEmbeddingIdentity.from(config)))
        assertTrue(value.matches.all { it.chunkId.startsWith("old_") } || value.matches.all { it.chunkId.startsWith("txn_") })
        assertEquals(2, value.matches.size)
    }
}

/** Fixed enum/count/hash diagnostics only; optional destination is the explicit gitignored test gate. */
internal object IndexTxnTestEvidence {
    @Synchronized fun record(kind: String, stage: String, result: String, open: Boolean, extra: JsonObject = buildJsonObject {}) {
        val row = buildJsonObject { put("kind", kind); put("stage", stage); put("result", result); put("businessOpen", open); put("extra", extra) }
        System.getenv("CCB_P9S2B_EVIDENCE")?.let { path ->
            Files.writeString(Path.of(path).resolve("transaction-events.jsonl"), row.toString() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
