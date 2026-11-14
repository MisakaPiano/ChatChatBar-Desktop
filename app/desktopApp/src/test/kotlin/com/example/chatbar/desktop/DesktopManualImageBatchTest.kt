package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import com.example.chatbar.desktop.security.*
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopManualImageBatchTest {
    private val usage = NovelAiAccountUsage(1000000, 3, true, 100.0, false)
    private fun plan(total: Int = 3, budget: Long = 0) = ImageBatchRunPlan(1, total,
        ImageBatchPacing(ImageBatchInterval.NONE), budget, ImageBatchOrigin.STUDIO)

    @Test fun `preview cache is read only short lived credential scoped and explicitly refreshable`() = runTest {
        var time = 1L
        var queries = 0
        val preview = DesktopImageBatchAccountPreview { time }
        suspend fun read(key: String = "synthetic-one", refresh: Boolean = false) = preview.read(key, refresh) { queries++; usage }
        assertEquals(usage, read()); read(); assertEquals(1, queries)
        time += 30001; read(); assertEquals(2, queries)
        read(refresh = true); assertEquals(3, queries)
        read("synthetic-two"); assertEquals(4, queries)
        assertNull(preview.read("synthetic-failure") { queries++; error("synthetic") })
        assertNull(preview.read("synthetic-failure") { error("Must cache failure briefly") })
        assertEquals(5, queries)
    }

    @Test fun `unknown account never invokes generation or reserves a successful result`() = runTest {
        var requests = 0
        val states = mutableListOf<ImageBatchProgress>()
        val failure = runCatching {
            runDesktopManualImageBatch(plan(), ImageBatchControl(), UUID.randomUUID().toString(),
                account = { throw IllegalStateException("synthetic account failure") },
                estimate = { _, _ -> error("No quote without account") }, validateOwner = {},
                onProgress = { states += it }, generateAndSave = { _, _ -> requests++; 1 })
        }.exceptionOrNull()
        assertIs<ImageBatchStopped>(failure)
        assertEquals(0, requests)
        assertTrue(states.all { it.saved == 0 && it.estimatedSpent == 0L })
    }

    @Test fun `fresh higher cost stops next batch without discarding committed first output`() = runTest {
        var quotes = 0
        var requests = 0
        val states = mutableListOf<ImageBatchProgress>()
        val failure = runCatching {
            runDesktopManualImageBatch(plan(budget = 3), ImageBatchControl(), UUID.randomUUID().toString(),
                account = { usage }, estimate = { _, _ ->
                    NovelAiGenerationCost(NovelAiGenerationChargeKind.ANLAS, anlas = if (++quotes == 1) 2 else 4)
                }, validateOwner = {}, onProgress = { states += it }, generateAndSave = { _, _ -> requests++; 1 })
        }.exceptionOrNull()
        assertIs<ImageBatchStopped>(failure)
        assertEquals(2, quotes)
        assertEquals(1, requests)
        assertEquals(1, states.last().saved)
        assertEquals(2L, states.last().estimatedSpent)
    }

    @Test fun `pacing releases original TaskRuntime image lane and Stop retires only its original job`() = runTest {
        val root = Files.createTempDirectory("desktop-batch-lane-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val tasks = DesktopTaskRuntime(app.createRealChatRuntime(), dispatcher = StandardTestDispatcher(testScheduler))
        try {
            val waiting = CompletableDeferred<Unit>()
            val enteredOther = CompletableDeferred<Unit>()
            var completed = 0
            val run = tasks.launchNovelAi("synthetic batch", batchManagedWork = true, onFinished = { completed++ }) { _ ->
                runDesktopManualImageBatch(plan().copy(pacing = ImageBatchPacing(fixedSeconds = 1)), ImageBatchControl(),
                    UUID.randomUUID().toString(), account = { usage },
                    estimate = { _, _ -> NovelAiGenerationCost(NovelAiGenerationChargeKind.FREE, 0) }, validateOwner = {},
                    onProgress = {}, generateAndSave = { count, _ -> count },
                    runner = ImageBatchRunner(admission = tasks.imageBatchAdmission, sleep = { waiting.complete(Unit); awaitCancellation() }))
            }
            runCurrent(); assertTrue(waiting.isCompleted)
            val other = tasks.launchNovelAi("other synthetic request", batchManagedWork = true) { _ ->
                tasks.withNovelAiWork { enteredOther.complete(Unit) }
            }
            runCurrent(); assertTrue(enteredOther.isCompleted)
            assertEquals(DesktopTaskStatus.COMPLETED, tasks.tasks.value.single { it.taskId == other }.status)
            assertTrue(tasks.requestUserStop(run)); runCurrent()
            assertEquals(DesktopTaskStatus.CANCELLED, tasks.tasks.value.single { it.taskId == run }.status)
            assertEquals(1, completed)
        } finally { tasks.closeAndDrain(); app.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `Stop before dispatcher enters a managed task still invokes exact terminal cleanup`() = runTest {
        val root = Files.createTempDirectory("desktop-batch-stop-before-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        val tasks = DesktopTaskRuntime(app.createRealChatRuntime(), dispatcher = StandardTestDispatcher(testScheduler))
        try {
            var cleanup = 0
            var calls = 0
            val task = tasks.launchNovelAi("synthetic queued batch", batchManagedWork = true, onFinished = { cleanup++ }) { _ -> calls++ }
            tasks.requestUserStop(task); runCurrent()
            assertEquals(0, calls); assertEquals(1, cleanup)
            assertEquals(DesktopTaskStatus.CANCELLED, tasks.tasks.value.single { it.taskId == task }.status)
        } finally { tasks.closeAndDrain(); app.close(); root.toFile().deleteRecursively() }
    }
}
