package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryPublicationTest {
    private fun assertFailureOrigin(original: Throwable, evidence: Throwable) {
        // Coroutine stack-trace recovery may copy an exception across an IO boundary.
        // Keep the exact original identity assertion on the preserved cause chain.
        assertTrue(generateSequence(evidence) { it.cause }.take(8).any { it === original })
    }
    private class ModelPolicy : ModelCredentialPersistencePolicy by IdentityModelCredentialPersistencePolicy {
        var before: suspend (ModelConfig) -> Unit = {}
        var after: suspend (ModelConfig) -> Unit = {}
        var afterDelete: suspend () -> Unit = {}
        var hydrationFailure: Throwable? = null
        override suspend fun hydrate(persisted: ModelConfig): ModelConfig {
            hydrationFailure?.let { throw it }
            return persisted
        }
        override suspend fun persist(runtime: ModelConfig, persistEntity: suspend (ModelConfig) -> Unit): ModelConfig {
            before(runtime)
            persistEntity(runtime)
            after(runtime)
            return runtime
        }
        override suspend fun delete(logicalModelId: String, deleteEntity: suspend () -> Unit) {
            deleteEntity()
            afterDelete()
        }
    }

    private class EmbeddingPolicy : EmbeddingCredentialPersistencePolicy by IdentityEmbeddingCredentialPersistencePolicy {
        var after: suspend () -> Unit = {}
        override suspend fun persist(scope: EmbeddingCredentialScope, runtime: EmbeddingConfig,
            persistEntity: suspend (EmbeddingConfig) -> Unit): EmbeddingConfig {
            persistEntity(runtime)
            after()
            return runtime
        }
        override suspend fun delete(scope: EmbeddingCredentialScope, deleteEntity: suspend () -> Unit) {
            deleteEntity()
            after()
        }
    }

    private suspend fun fixture(test: suspend (ModelRepository, ModelPolicy, EmbeddingPolicy) -> Unit) {
        val root = Files.createTempDirectory("model-publication-")
        try {
            val model = ModelPolicy()
            val embedding = EmbeddingPolicy()
            test(ModelRepository(JsonFileStorage(root), WindowsSafeModelStorageKeyPolicy, model, embedding), model, embedding)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun model(name: String = "Original") = ModelConfig("model/id", name, "https://fixture.invalid/v1", "fake-key", "model", createdAt = 1)
    private fun embedding() = EmbeddingConfig("embedding", "Embedding", "https://fixture.invalid/v1", "fake-key", "embedding", 1024)

    @Test fun `cancellation before persistence does not commit a model`() = runTest {
        fixture { repo, policy, _ ->
            repo.saveModel(model())
            val entered = CompletableDeferred<Unit>()
            policy.before = { entered.complete(Unit); awaitCancellation() }
            val save = launch { repo.saveModel(model("Cancelled")) }
            entered.await()
            save.cancelAndJoin()
            assertEquals("Original", repo.getModel("model/id")?.displayName)
            assertEquals("Original", repo.models.first().single().displayName)
        }
    }

    @Test fun `cancellation after durable model write publishes that write before returning`() = runTest {
        fixture { repo, policy, _ ->
            repo.saveModel(model())
            val written = CompletableDeferred<Unit>()
            policy.after = { written.complete(Unit); awaitCancellation() }
            val save = launch { repo.saveModel(model("Committed")) }
            written.await()
            save.cancelAndJoin()
            assertTrue(save.isCancelled)
            assertEquals("Committed", repo.getModel("model/id")?.displayName)
            assertEquals("Committed", repo.models.first().single().displayName)
        }
    }

    @Test fun `observed commit callback failure still publishes committed data and preserves failure`() = runTest {
        fixture { repo, _, _ ->
            val failure = IOException("synthetic callback failure")
            assertFailureOrigin(failure, assertFailsWith<IOException> {
                repo.saveModelObserved(model("Observed")) { throw failure }
            })
            assertEquals("Observed", repo.models.first().single().displayName)
            assertEquals("Observed", repo.getModel("model/id")?.displayName)
        }
    }

    @Test fun `later mutation cannot overtake an earlier persistence and publication`() = runTest {
        fixture { repo, policy, _ ->
            repo.saveModel(model())
            val firstWritten = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            policy.before = { if (it.displayName == "Second") secondEntered.complete(Unit) }
            policy.after = { if (it.displayName == "First") { firstWritten.complete(Unit); release.await() } }
            val first = async { repo.saveModel(model("First")) }
            firstWritten.await()
            val second = async { repo.saveModel(model("Second")) }
            runCurrent()
            assertFalse(secondEntered.isCompleted)
            release.complete(Unit)
            first.await(); second.await()
            assertEquals("Second", repo.models.first().single().displayName)
            assertEquals("Second", repo.getModel("model/id")?.displayName)
        }
    }

    @Test fun `partial preset batch failure publishes only committed models and preserves failure`() = runTest {
        fixture { repo, policy, _ ->
            val failure = IOException("synthetic second item failure")
            policy.before = { if (it.sourcePresetKey == "second") throw failure }
            val catalog = PresetModelCatalog(baseUrl = "https://fixture.invalid/v1", chatModels = listOf(
                PresetChatModel("first", "First", "first"), PresetChatModel("second", "Second", "second")))
            assertSame(failure, assertFailsWith<IOException> { repo.restorePresetChatModels(catalog, 1) })
            assertEquals(listOf("First"), repo.models.first().map { it.displayName })
            assertEquals(listOf("First"), repo.getAllModels().map { it.displayName })
        }
    }

    @Test fun `concurrent duplicate naming uses the latest serialized repository state`() = runTest {
        fixture { repo, _, _ ->
            repo.saveModel(model())
            val copies = (1..3).map { async { repo.duplicateModel("model/id") } }.awaitAll()
            assertEquals(setOf("Original (2)", "Original (3)", "Original (4)"), copies.map { it.displayName }.toSet())
            assertEquals(4, repo.models.first().size)
            assertEquals(4, repo.getAllModels().map { it.id }.toSet().size)
        }
    }

    @Test fun `cancelled durable model deletion publishes absence`() = runTest {
        fixture { repo, policy, _ ->
            repo.saveModel(model())
            val deleted = CompletableDeferred<Unit>()
            policy.afterDelete = { deleted.complete(Unit); awaitCancellation() }
            val job = launch { repo.deleteModel("model/id") }
            deleted.await(); job.cancelAndJoin()
            assertTrue(repo.models.first().isEmpty())
            assertNull(repo.getModel("model/id"))
        }
    }

    @Test fun `publication failure remains attached to the original operation failure`() = runTest {
        fixture { repo, policy, _ ->
            repo.saveModel(model())
            val operationFailure = IOException("synthetic operation failure")
            val publicationFailure = IOException("synthetic hydration failure")
            policy.before = { policy.hydrationFailure = publicationFailure; throw operationFailure }
            val caught = assertFailsWith<IOException> { repo.saveModel(model("Failed")) }
            assertFailureOrigin(operationFailure, caught)
            val suppressed = generateSequence<Throwable>(caught) { it.cause }.take(8).flatMap { it.suppressed.asSequence() }.toList()
            assertEquals(1, suppressed.size)
            assertFailureOrigin(publicationFailure, suppressed.single())
            policy.hydrationFailure = null
            assertEquals("Original", repo.getModel("model/id")?.displayName)
            assertEquals("Original", repo.models.first().single().displayName)
        }
    }

    @Test fun `embedding list singleton and deletion publish durable outcomes despite postwrite cancellation`() = runTest {
        fixture { repo, _, policy ->
            suspend fun cancelledAfterWrite(operation: suspend () -> Unit) {
                val written = CompletableDeferred<Unit>()
                policy.after = { written.complete(Unit); awaitCancellation() }
                val job = launch { operation() }
                written.await(); job.cancelAndJoin()
                assertTrue(job.isCancelled)
                policy.after = {}
            }
            cancelledAfterWrite { repo.saveEmbedding(embedding()) }
            assertEquals(listOf(embedding()), repo.embeddings.first())
            cancelledAfterWrite { repo.saveEmbeddingModel(embedding()) }
            assertEquals(embedding().copy(id = "default"), repo.embeddingModel.first())
            cancelledAfterWrite { repo.deleteEmbedding("embedding") }
            assertTrue(repo.embeddings.first().isEmpty())
            cancelledAfterWrite { repo.deleteEmbeddingModel() }
            assertNull(repo.embeddingModel.first())
        }
    }
}
