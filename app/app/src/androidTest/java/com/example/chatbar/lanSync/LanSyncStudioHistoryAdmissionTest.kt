package com.example.chatbar.lanSync

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioRepository
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.lanSync.LanSyncOperationGate
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Isolated Android files only. No generation, network, app profile or provider credentials. */
class LanSyncStudioHistoryAdmissionTest {
    @Test fun queuedImageDeletionWaitsOutsideHistoryMutexAndRetainsTheLatestRecipeAndOtherImage() = runBlocking {
        withTimeout(10000) {
            val app = ApplicationProvider.getApplicationContext<android.content.Context>()
            val root = Files.createTempDirectory(app.cacheDir.canonicalFile.toPath(), "studio-history-admission-").toRealPath()
            val context = object : ContextWrapper(app) { override fun getFilesDir() = root.toFile() }
            val images = NovelAiImageStorage(context)
            val first = images.save("synthetic-batch", byteArrayOf(1, 2, 3))
            val second = images.save("synthetic-batch", byteArrayOf(4, 5, 6))
            val gate = LanSyncOperationGate()
            val storage = JsonFileStorage(root, gate)
            val repository = NovelAiStudioRepository(storage, images)
            val original = NovelAiGenerationHistoryEntry("synthetic-batch", listOf(NovelAiGenerationHistoryImage(first, 1),
                NovelAiGenerationHistoryImage(second, 2)), NovelAiGenerationRecipe(basePrompt = "synthetic-original"), createdAt = 1)
            repository.saveHistory(original)
            val latest = original.copy(recipe = original.recipe.copy(basePrompt = "synthetic-current"))
            val clients = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var deletion: Deferred<NovelAiHistoryImageDeleteResult>? = null
            try {
                gate.exclusive {
                    // This client has its own scope, so it cannot inherit the exclusive admission.
                    deletion = clients.async(start = CoroutineStart.UNDISPATCHED) {
                        repository.deleteHistoryImages(listOf(NovelAiHistoryImageSelection(original.id, first)))
                    }
                    assertFalse(requireNotNull(deletion).isCompleted)
                    // A client that locked history before waiting at the data gate deadlocks here.
                    repository.saveHistory(latest)
                    assertTrue(Files.exists(java.nio.file.Paths.get(first)))
                }
                val result = requireNotNull(deletion).await()
                assertEquals(1, result.deletedCount); assertEquals(0, result.cleanupFailureCount)
                val retained = repository.history.first().single()
                assertEquals(latest.recipe, retained.recipe)
                assertEquals(listOf(NovelAiGenerationHistoryImage(second, 2)), retained.images)
                assertFalse(Files.exists(java.nio.file.Paths.get(first)))
                assertTrue(Files.exists(java.nio.file.Paths.get(second)))
            } finally { withContext(NonCancellable) { clients.cancel(); clients.coroutineContext[Job]!!.join() } }
        }
    }
}
