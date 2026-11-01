package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopStudioBatchDeleteTest {
    private val key = DesktopNovelAiGenerationStore.KEY
    private val serializer = NovelAiGenerationHistoryEntry.serializer()
    private fun png() = DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))
    private suspend fun fixture(block: suspend CoroutineScope.(DesktopAppContainer) -> Unit) = coroutineScope {
        val root = Files.createTempDirectory("studio-batch-delete-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        try { block(app) } finally { app.close(); root.toFile().deleteRecursively() }
    }
    private fun asset(app: DesktopAppContainer, name: String): String = app.characterResourceStore.materializeImage(
        PackagedImage("image.png", Base64.getEncoder().encodeToString(png())), 0, name)
    private suspend fun seed(app: DesktopAppContainer, id: String, paths: List<String>): NovelAiGenerationHistoryEntry =
        NovelAiGenerationHistoryEntry(id = id, images = paths.map { NovelAiGenerationHistoryImage(it, 1) }, createdAt = 0)
            .also { app.jsonFileStorage.saveEntity(key, id, it, serializer) }

    @Test fun `actual controller refuses corrupt later entry without partially deleting first or its files`() = runBlocking {
        fixture { app ->
            val firstPath = asset(app, "first"); val otherPath = asset(app, "other")
            val first = seed(app, "first", listOf(firstPath, otherPath))
            seed(app, "second", listOf(otherPath))
            val corrupt = app.appDataRoot.resolve("entities/$key/second.json")
            Files.writeString(corrupt, "{bad")
            val controller = app.novelAiStudioController
            val selected = linkedSetOf(NovelAiHistoryImageSelection("first", firstPath), NovelAiHistoryImageSelection("second", otherPath))
            controller.setHistorySelection(selected)
            assertFalse(controller.deleteSelected())
            assertEquals(first, (app.jsonFileStorage.readEntityStrict(key, "first", serializer) as JsonFileStorage.EntityReadResult.Valid).value)
            assertEquals("{bad", Files.readString(corrupt))
            assertTrue(Files.isRegularFile(app.characterResourceStore.resolveOwnedReference(firstPath)))
            assertTrue(Files.isRegularFile(app.characterResourceStore.resolveOwnedReference(otherPath)))
            assertEquals(selected, controller.state.value.selected)
            assertEquals(first, controller.history.first().first { it.id == "first" })
        }
    }

    @Test fun `actual controller commits multi entry selection and retains media referenced by untouched history`() = runBlocking {
        fixture { app ->
            val shared = asset(app, "shared"); val kept = asset(app, "kept"); val removed = asset(app, "removed")
            val first = seed(app, "first", listOf(shared, kept)); seed(app, "second", listOf(removed))
            val untouched = seed(app, "third", listOf(shared))
            val controller = app.novelAiStudioController
            controller.setHistorySelection(linkedSetOf(NovelAiHistoryImageSelection("first", shared), NovelAiHistoryImageSelection("second", removed)))
            assertTrue(controller.deleteSelected())
            assertEquals(first.copy(images = first.images.drop(1)), (app.jsonFileStorage.readEntityStrict(key, "first", serializer) as JsonFileStorage.EntityReadResult.Valid).value)
            assertEquals(JsonFileStorage.EntityReadResult.Missing, app.jsonFileStorage.readEntityStrict(key, "second", serializer))
            assertEquals(untouched, (app.jsonFileStorage.readEntityStrict(key, "third", serializer) as JsonFileStorage.EntityReadResult.Valid).value)
            assertTrue(Files.isRegularFile(app.characterResourceStore.resolveOwnedReference(shared)))
            assertTrue(Files.isRegularFile(app.characterResourceStore.resolveOwnedReference(kept)))
            assertFalse(Files.exists(app.characterResourceStore.resolveOwnedReference(removed)))
            assertTrue(controller.state.value.selected.isEmpty())
        }
    }

    @Test fun `real generation persistence waits for batch writer and its new entry is retained`() = runBlocking {
        fixture { app ->
            val old = seed(app, "old", listOf(asset(app, "old")))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val batch = async(Dispatchers.IO) {
                app.jsonFileStorage.mutateEntitiesAtomically(key, setOf(old.id), serializer) {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); mapOf(old.id to null)
                }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                val starting = CompletableDeferred<Unit>()
                val generation = async(Dispatchers.IO) {
                    starting.complete(Unit)
                    DesktopNovelAiGenerationStore(app.jsonFileStorage, app.characterResourceStore, app.dataOperationCoordinator)
                        .persist(listOf(png()), NovelAiGenerationRecipe())
                }
                starting.await(); delay(50); assertFalse(generation.isCompleted)
                release.countDown(); batch.await()
                val saved = withTimeout(10_000) { generation.await() }
                assertEquals(JsonFileStorage.EntityReadResult.Missing, app.jsonFileStorage.readEntityStrict(key, old.id, serializer))
                assertEquals(saved, (app.jsonFileStorage.readEntityStrict(key, saved.id, serializer) as JsonFileStorage.EntityReadResult.Valid).value)
                assertEquals(listOf(saved), app.jsonFileStorage.observeAll(key, serializer).first())
                assertTrue(saved.images.all { Files.isRegularFile(app.characterResourceStore.resolveOwnedReference(it.path)) })
            } finally { release.countDown(); batch.join() }
        }
    }
}
