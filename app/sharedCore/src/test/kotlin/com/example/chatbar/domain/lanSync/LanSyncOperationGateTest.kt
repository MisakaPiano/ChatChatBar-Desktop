package com.example.chatbar.domain.lanSync

import kotlinx.coroutines.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.domain.image.NovelAiStudioDraft
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LanSyncOperationGateTest {
    @Test fun studioUpdateCannotStageUnflushedTextBeforeStorageAdmissionAndKeepsNormalRepositoryBehavior() = runBlocking {
        withTimeout(5_000) {
            val root = Files.createTempDirectory("lan-live-studio-admission-").toRealPath()
            val gate = LanSyncOperationGate(); val storage = JsonFileStorage(root, gate)
            val repository = NovelAiStudioStateRepository(storage)
            repository.loadDraft()
            val original = repository.updateDraft { it.copy(basePrompt = "合成原文", negativePrompt = "合成负面") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                lateinit var queued: Deferred<NovelAiStudioDraft>
                gate.exclusive {
                    queued = scope.async(start = CoroutineStart.UNDISPATCHED) {
                        repository.updateDraft { it.copy(basePrompt = it.basePrompt + "，保留新编辑") }
                    }
                    assertFalse(queued.isCompleted)
                    assertEquals(original, repository.draft.value, "A blocked writer must not publish its memory stage before admission")
                    assertEquals(original, storage.loadSingleton("novelai_studio_draft", NovelAiStudioDraft.serializer()))
                }
                val changed = queued.await()
                assertEquals("合成原文，保留新编辑", changed.basePrompt)
                assertEquals(changed, repository.draft.value)
                assertEquals(changed, storage.loadSingleton("novelai_studio_draft", NovelAiStudioDraft.serializer()))
            } finally { withContext(NonCancellable) {
                requireNotNull(scope.coroutineContext[Job]).cancelAndJoin(); check(root.toFile().deleteRecursively())
            } }
        }
    }

    @Test fun immediateEditorStageDrainsBeforeExclusiveAndRefusalNeverRunsOrLosesTheCallerEdit() = runBlocking {
        withTimeout(10_000) {
            val gate = LanSyncOperationGate(); val staged = CountDownLatch(1); val finishStage = CountDownLatch(1)
            val exclusiveEntered = CompletableDeferred<Unit>(); val finishExclusive = CompletableDeferred<Unit>()
            var text = "合成原文"
            val editor = async(Dispatchers.Default) {
                assertTrue(gate.tryWithImmediateNormalOperation {
                    text += "，同步前编辑"; staged.countDown()
                    check(finishStage.await(5, TimeUnit.SECONDS))
                })
            }
            try {
                withContext(Dispatchers.IO) { check(staged.await(5, TimeUnit.SECONDS)) }
                val maintenance = async(start = CoroutineStart.UNDISPATCHED) {
                    gate.exclusive { exclusiveEntered.complete(Unit); finishExclusive.await() }
                }
                assertFalse(exclusiveEntered.isCompleted)
                val laterEdit = { text += "，同步后编辑" }
                assertFalse(gate.tryWithImmediateNormalOperation(laterEdit))
                assertEquals("合成原文，同步前编辑", text)
                val deferred = async(start = CoroutineStart.UNDISPATCHED) { gate.withNormalOperation { laterEdit() } }
                finishStage.countDown(); editor.await(); exclusiveEntered.await()
                assertFalse(deferred.isCompleted)
                assertEquals("合成原文，同步前编辑", text)
                finishExclusive.complete(Unit); maintenance.await(); deferred.await()
                assertEquals("合成原文，同步前编辑，同步后编辑", text)
                assertFailsWith<IllegalArgumentException> { gate.tryWithImmediateNormalOperation { throw IllegalArgumentException("synthetic") } }
                gate.exclusive { assertEquals("合成原文，同步前编辑，同步后编辑", text) }
            } finally { finishStage.countDown(); finishExclusive.complete(Unit) }
        }
    }

    @Test fun nestedAdmissionDrainsAndLockOrderViolationsFailBeforeWaiting() = runBlocking {
        withTimeout(5000) {
            val gate = LanSyncOperationGate()
            val entered = CompletableDeferred<Unit>()
            val attempt = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reader = async {
                gate.withNormalOperation {
                    gate.withNormalOperation { entered.complete(Unit) }
                    attempt.await()
                    assertEquals("SYNC_GATE_UPGRADE", assertFailsWith<IllegalArgumentException> {
                        gate.requireNoNormalAdmission()
                    }.message)
                    assertEquals("SYNC_GATE_UPGRADE", assertFailsWith<IllegalArgumentException> {
                        gate.exclusive { error("must refuse") }
                    }.message)
                    release.await()
                }
            }
            entered.await()
            val applied = CompletableDeferred<Unit>()
            val writer = async(start = CoroutineStart.UNDISPATCHED) { gate.exclusive { applied.complete(Unit) } }
            assertFalse(applied.isCompleted)
            attempt.complete(Unit)
            yield()
            assertFalse(applied.isCompleted)
            release.complete(Unit)
            reader.await(); writer.await()
            gate.requireNoNormalAdmission()
            gate.withNormalOperation { assertTrue(applied.isCompleted) }
        }
    }
}
