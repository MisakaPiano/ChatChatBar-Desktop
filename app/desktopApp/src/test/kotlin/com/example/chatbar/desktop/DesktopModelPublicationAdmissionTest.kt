package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ModelCredentialPersistencePolicy
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.desktop.security.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopModelPublicationAdmissionTest {
    private fun model(name: String = "Original", key: String = "fake-original") =
        ModelConfig("model/id", name, "https://fixture.invalid/v1", key, "fixture-model", createdAt = 1)

    @Test fun `cancelled committed model publishes under its admitted lease before maintenance and later mutation`() = runBlocking {
        val root = Files.createTempDirectory("desktop-model-publication-")
        val jobs = mutableListOf<Job>()
        try {
            withTimeout(10000) {
                val gate = DesktopDataOperationCoordinator()
                val store = InMemoryDesktopSecretStore()
                val protectedPolicy = DesktopModelCredentialPersistencePolicy(store, gate)
                val written = CompletableDeferred<Unit>()
                val releaseExclusive = CompletableDeferred<Unit>()
                val exclusiveEntered = CompletableDeferred<Unit>()
                val policy = object : ModelCredentialPersistencePolicy by protectedPolicy {
                    override suspend fun persist(runtime: ModelConfig, persistEntity: suspend (ModelConfig) -> Unit): ModelConfig {
                        val saved = protectedPolicy.persist(runtime, persistEntity)
                        if (runtime.displayName == "Committed") { written.complete(Unit); awaitCancellation() }
                        return saved
                    }
                }
                val repo = ModelRepository(JsonFileStorage(root, gate), WindowsSafeModelStorageKeyPolicy, policy)
                repo.saveModel(model())
                val save = launch { repo.saveModel(model("Committed", "fake-committed")) }.also(jobs::add)
                written.await()
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    gate.withExclusiveMaintenance {
                        assertEquals("Committed", repo.models.first().single().displayName)
                        assertEquals("fake-committed", repo.models.first().single().apiKey)
                        exclusiveEntered.complete(Unit)
                        releaseExclusive.await()
                    }
                }.also(jobs::add)
                assertEquals(DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING, gate.state)
                val late = async(start = CoroutineStart.UNDISPATCHED) { repo.saveModel(model("Later", "fake-later")) }.also(jobs::add)
                assertFalse(late.isCompleted)
                save.cancelAndJoin()
                exclusiveEntered.await()
                assertFalse(late.isCompleted)
                val path = root.resolve("entities/model_configs/" + WindowsSafeModelStorageKeyPolicy.storageKey("model/id") + ".json")
                val text = Files.readString(path)
                assertTrue(text.contains("Committed"))
                assertFalse(text.contains("fake-committed"))
                releaseExclusive.complete(Unit)
                exclusive.join(); late.await()
                assertEquals("Later", repo.models.first().single().displayName)
                assertEquals("fake-later", store.load(DesktopCredentialKey.ModelApiKey("model/id")))
            }
        } finally {
            withContext(NonCancellable) { jobs.forEach { it.cancel() }; jobs.joinAll() }
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `observed write failure retains protected rollback and publishes the actual durable model`() = runBlocking {
        val root = Files.createTempDirectory("desktop-model-rollback-publication-")
        try {
            val store = InMemoryDesktopSecretStore()
            val repo = ModelRepository(JsonFileStorage(root), WindowsSafeModelStorageKeyPolicy,
                DesktopModelCredentialPersistencePolicy(store))
            repo.saveModel(model())
            val failure = IOException("synthetic observed boundary failure")
            assertSame(failure, assertFailsWith<IOException> {
                repo.saveModelObserved(model("Committed", "fake-replaced")) { throw failure }
            })
            assertEquals("fake-original", store.load(DesktopCredentialKey.ModelApiKey("model/id")))
            val published = repo.models.first().single()
            assertEquals("Committed", published.displayName)
            assertEquals("fake-original", published.apiKey)
            assertEquals(published, repo.getModel("model/id"))
            val text = Files.readString(root.resolve("entities/model_configs/" + WindowsSafeModelStorageKeyPolicy.storageKey("model/id") + ".json"))
            assertFalse(text.contains("fake-original")); assertFalse(text.contains("fake-replaced"))
        } finally { root.toFile().deleteRecursively() }
    }
}
