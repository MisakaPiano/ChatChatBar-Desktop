package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.moment.MomentGenerationResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopImageWorkQueueTest {
    @Test fun `new Moment placeholder is reserved with its card before another post action can be admitted`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture(); val entered = CompletableDeferred<Unit>()
        var owner: DesktopMomentsController? = null
        try {
            f.initialize(); val c = f.c
            c.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            val store = DesktopMomentStore(c.jsonFileStorage, c.dataOperationCoordinator)
            val calls = AtomicInteger()
            val controller = DesktopMomentsController(store, c.settingsRepository, c.characterRepository, c.chatRepository,
                c.dataOperationCoordinator, c.taskRuntime,
                DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> calls.incrementAndGet(); entered.complete(Unit); awaitCancellation() },
                imageGenerator = DesktopMomentImageGenerator { _, _, _, _, _, _, _ -> error("Duplicate post image must not run") })
            owner = controller
            withTimeout(5000) { controller.state.first { it.enabled } }
            controller.generate("card"); withTimeout(5000) { entered.await() }
            val placeholder = store.posts().single()
            assertTrue("card:card" in controller.state.value.busyTargets)
            assertTrue("post:${placeholder.id}" in controller.state.value.busyTargets)
            controller.retry(placeholder); controller.generateImage(placeholder)
            assertEquals(1, controller.workRows.value.count { it.active })
            val other = CharacterCard.create("Queued card").copy(id = "queued-card")
            c.characterRepository.save(other)
            c.chatRepository.createSession(ChatSession("queued-session", other.id, "Queued", createdAt = 1, updatedAt = 1))
            controller.generate(other.id)
            controller.pause()
            withTimeout(5000) { controller.workRows.first { it.none { row -> row.active } } }
            assertEquals(MomentTaskStatus.FAILED, store.tasks().single().status)
            assertEquals(1, calls.get())
            assertTrue(controller.state.value.busyTargets.isEmpty())
        } finally { owner?.closeAndDrain(); f.close() }
    }
    @Test fun `different source queues and queued cancellation retry cannot overtake running work or release its target`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture()
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        try {
            f.initialize(); val runtime = f.c.taskRuntime; val calls = CopyOnWriteArrayList<String>()
            val first = runtime.launchNovelAi("held", "session", "first", queueIfBusy = true) {
                entered.complete(Unit); release.await(); calls += "first"
            }
            withTimeout(5000) { entered.await() }
            assertFailsWith<DesktopTaskAdmissionException> { runtime.launchNovelAi("duplicate", "session", "first", queueIfBusy = true) {} }
            val second = runtime.launchNovelAi("second", "session", "second", retryable = true, queueIfBusy = true) { calls += "second" }
            val third = runtime.launchNovelAi("third", "session", "third", queueIfBusy = true) { calls += "third" }
            assertNull(runtime.tasks.value.single { it.taskId == second }.startedAt)
            assertTrue(runtime.requestUserStop(second)); assertEquals(DesktopTaskStatus.USER_STOPPED, f.terminal(second).status)
            val retry = assertNotNull(runtime.retryImageTask(second))
            assertNull(runtime.tasks.value.single { it.taskId == retry }.startedAt)
            runtime.requestUserStop(retry); f.terminal(retry)
            assertTrue(calls.isEmpty()); assertTrue(runtime.hasActiveImageSourceMutation("session", "first"))
            release.complete(Unit); assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(first).status)
            assertEquals(DesktopTaskStatus.COMPLETED, f.terminal(third).status)
            assertEquals(listOf("first", "third"), calls.toList())
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun `maintenance drains running and queued image work without invoking queued bodies`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture(); val entered = CompletableDeferred<Unit>()
        try {
            f.initialize(); val runtime = f.c.taskRuntime; var queuedCalls = 0
            val first = runtime.launchNovelAi("held", "session", "first", queueIfBusy = true) { entered.complete(Unit); awaitCancellation() }
            withTimeout(5000) { entered.await() }
            val second = runtime.launchNovelAi("queued", "session", "second", queueIfBusy = true) { queuedCalls++ }
            runtime.withMaintenance {
                assertTrue(runtime.tasks.value.none { it.status == DesktopTaskStatus.RUNNING })
                assertFailsWith<DesktopTaskAdmissionException> { runtime.launchNovelAi("blocked") {} }
            }
            assertEquals(0, queuedCalls)
            assertEquals(DesktopTaskStatus.CANCELLED, f.terminal(first).status)
            assertEquals(DesktopTaskStatus.CANCELLED, f.terminal(second).status)
        } finally { f.close() }
    }

    @Test fun `Moments different cards and posts queue in the image lane with exact target stop and original post ownership`() = runBlocking {
        val f = DesktopAssistantImageActionTest.Fixture(); val release = CompletableDeferred<Unit>()
        var owner: DesktopMomentsController? = null
        try {
            f.initialize(); val c = f.c; val entered = CompletableDeferred<Unit>()
            val hold = c.taskRuntime.launchNovelAi("held", "session", "held", queueIfBusy = true) { entered.complete(Unit); release.await() }
            withTimeout(5000) { entered.await() }
            val card2 = CharacterCard.create("Second synthetic card").copy(id = "card2")
            c.characterRepository.save(card2)
            c.chatRepository.createSession(ChatSession("session2", card2.id, "Second", createdAt = 1, updatedAt = 1))
            c.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            val store = DesktopMomentStore(c.jsonFileStorage, c.dataOperationCoordinator)
            val posts = (1..2).map { MomentPost("post$it", "card", "session", senderName = "Synthetic", text = "Original $it", scheduledAt = 1, generatedAt = 1) }
            for (post in posts) c.jsonFileStorage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
            val calls = CopyOnWriteArrayList<String>(); val active = AtomicInteger(); val maximum = AtomicInteger()
            suspend fun record(id: String) {
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try { calls += id; delay(15) } finally { active.decrementAndGet() }
            }
            val controller = DesktopMomentsController(store, c.settingsRepository, c.characterRepository, c.chatRepository,
                c.dataOperationCoordinator, c.taskRuntime,
                DesktopMomentGenerator { card, _, _, _, _, _, _, _, _, _ -> record(card.id); MomentGenerationResult.Skipped("Synthetic no publication") },
                imageGenerator = DesktopMomentImageGenerator { post, _, _, _, _, _, _ ->
                    record(post.id)
                    val path = "images/queue-${post.id}.png"
                    java.nio.file.Files.createDirectories(f.design.root.resolve("images"))
                    java.nio.file.Files.write(f.design.root.resolve(path), DesktopImageEditing.png(java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_RGB)))
                    DesktopMomentImageResult(path, GeneratedImageMetadata(path, "Synthetic", negativePrompt = "Negative", width = 16, height = 16, sizePreset = "SQUARE"), "Prompt ${post.id}", "Brief")
                })
            owner = controller
            withTimeout(5000) { controller.state.first { it.enabled && it.posts.size == 2 } }
            controller.generate("card"); controller.generate("card2")
            controller.generateImage(posts[0]); controller.generateImage(posts[1])
            controller.generateImage(posts[0])
            assertEquals(4, controller.workRows.value.count { it.active })
            assertEquals(setOf("card:card", "card:card2", "post:post1", "post:post2"), controller.state.value.busyTargets)
            assertTrue(calls.isEmpty())
            val cancelled = controller.workRows.value.first { it.active }.id
            assertTrue(controller.stopTask(cancelled))
            withTimeout(5000) { controller.workRows.first { rows -> rows.any { it.id == cancelled && !it.active } } }
            assertTrue(calls.isEmpty()); assertTrue(c.taskRuntime.tasks.value.single { it.taskId == hold }.status == DesktopTaskStatus.RUNNING)
            release.complete(Unit); f.terminal(hold)
            withTimeout(10000) { controller.workRows.first { it.none { row -> row.active } } }
            assertEquals(1, maximum.get()); assertEquals(setOf("card", "card2", "post1"), calls.toSet())
            val saved = store.posts().associateBy { it.id }
            assertEquals("images/queue-post1.png", saved.getValue("post1").imagePath)
            assertEquals(posts[0].text, saved.getValue("post1").text)
            assertEquals(posts[1], saved.getValue("post2"))
            assertTrue(controller.state.value.busyTargets.isEmpty()); assertFalse(controller.state.value.busy)
        } finally { release.complete(Unit); owner?.closeAndDrain(); f.close() }
    }
}
