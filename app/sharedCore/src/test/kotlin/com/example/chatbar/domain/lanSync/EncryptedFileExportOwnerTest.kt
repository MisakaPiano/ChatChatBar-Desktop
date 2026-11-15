package com.example.chatbar.domain.lanSync

import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real encrypted packages and borrowed streams; no OS picker or platform acceptance is inferred. */
class EncryptedFileExportOwnerTest {
    private suspend fun fixture(work: suspend (EncryptedFilePackageTest.Fixture) -> Unit) {
        val root = Files.createTempDirectory("group-export-owner-").toRealPath()
        try { work(EncryptedFilePackageTest.Fixture(root)) }
        finally { check(root.toFile().deleteRecursively()) }
    }

    private fun owner(f: EncryptedFilePackageTest.Fixture, preparations: AtomicInteger = AtomicInteger()) =
        LanSyncGroupEncryptedFileExportOwner(prepare = { active ->
            preparations.incrementAndGet()
            LanSyncGroupEncryptedFilePackage.prepare(f.plan, f.store, f.trust, f.cipher, f.work, active)
        })

    private suspend fun prepared(owner: LanSyncGroupEncryptedFileExportOwner): String {
        owner.prepare()
        withTimeout(10_000) { owner.drain() }
        val state = owner.state.value
        assertFalse(state.busy)
        assertNull(state.error)
        return assertNotNull(state.preparedBinding)
    }

    private fun children(path: Path): Set<String> = Files.list(path).use { files ->
        files.map { it.fileName.toString() }.toList().toSet()
    }

    @Test fun preparationIsExplicitAndActualExternalReadbackOwnsOnlyItsInput() = runBlocking {
        fixture { f ->
            val preparations = AtomicInteger()
            val owner = owner(f, preparations)
            try {
                assertEquals(0, preparations.get())
                owner.open()
                assertTrue(owner.state.value.visible)
                assertEquals(0, preparations.get())
                val binding = prepared(owner)
                val bytes = ByteArrayOutputStream()
                var outputClosed = false
                var inputClosed = false
                val borrowed = object : OutputStream() {
                    override fun write(value: Int) = bytes.write(value)
                    override fun write(buffer: ByteArray, offset: Int, count: Int) = bytes.write(buffer, offset, count)
                    override fun close() { outputClosed = true }
                }
                owner.write(binding, writer = { body ->
                    body(borrowed)
                    assertFalse(outputClosed, "The platform still owns the borrowed output")
                    borrowed.close()
                }, readback = {
                    object : ByteArrayInputStream(bytes.toByteArray()) {
                        override fun close() { inputClosed = true; super.close() }
                    }
                })
                withTimeout(10_000) { owner.drain() }
                val state = owner.state.value
                assertNull(state.error)
                assertTrue(state.writeCompleted)
                assertTrue(state.readbackVerified)
                assertEquals(bytes.size().toLong(), state.bytes)
                assertTrue(outputClosed)
                assertTrue(inputClosed)
                assertEquals(1, preparations.get())
                LanSyncGroupEncryptedFilePackage.stage(bytes.toByteArray().inputStream(), f.work).use { staged ->
                    val verified = staged.verifyInventory(f.cipher, f.trust, f.checkpoint)
                    assertEquals(f.plan.versions, verified.plan.versions)
                }
            } finally { owner.closeAndDrain() }
        }
    }

    @Test fun failedWriterAndFailedReadbackRetainOnePackageForExplicitRetries() = runBlocking {
        fixture { f ->
            val preparations = AtomicInteger()
            val writerCalls = AtomicInteger()
            val owner = owner(f, preparations)
            try {
                val binding = prepared(owner)
                owner.write(binding, writer = { body ->
                    writerCalls.incrementAndGet()
                    body(object : OutputStream() {
                        override fun write(value: Int) { throw IOException("secret endpoint password code") }
                        override fun write(buffer: ByteArray, offset: Int, count: Int) { throw IOException("secret endpoint password code") }
                    })
                })
                owner.drain()
                assertEquals(1, writerCalls.get())
                assertEquals(binding, owner.state.value.preparedBinding)
                assertFalse(owner.state.value.writeCompleted)
                assertNotNull(owner.state.value.error)
                assertFalse(owner.state.value.error.orEmpty().contains("secret"))

                val bytes = ByteArrayOutputStream()
                val inputClosed = AtomicBoolean()
                owner.write(binding, writer = { body -> writerCalls.incrementAndGet(); body(bytes) }, readback = {
                    object : ByteArrayInputStream(bytes.toByteArray().dropLast(1).toByteArray()) {
                        override fun close() { inputClosed.set(true); super.close() }
                    }
                })
                owner.drain()
                assertEquals(2, writerCalls.get())
                assertTrue(inputClosed.get())
                assertTrue(owner.state.value.writeCompleted)
                assertFalse(owner.state.value.readbackVerified)
                assertNotNull(owner.state.value.error)
                assertEquals(binding, owner.state.value.preparedBinding)

                bytes.reset()
                owner.write(binding, writer = { body -> writerCalls.incrementAndGet(); body(bytes) },
                    readback = { bytes.toByteArray().inputStream() })
                owner.drain()
                assertEquals(3, writerCalls.get())
                assertEquals(1, preparations.get())
                assertTrue(owner.state.value.readbackVerified)
                assertNull(owner.state.value.error)
                assertFalse(owner.toString().contains(f.root.toString()))
                assertEquals("LanSyncGroupEncryptedFileExportState(REDACTED)", owner.state.value.toString())
            } finally { owner.closeAndDrain() }
        }
    }

    @Test fun aWriteWithoutReadbackDoesNotClaimVerificationAndUsesTheSameAuthenticatedBytes() = runBlocking {
        fixture { f ->
            val owner = owner(f)
            try {
                val binding = prepared(owner)
                val first = ByteArrayOutputStream()
                owner.write(binding, writer = { it(first) })
                owner.drain()
                assertTrue(owner.state.value.writeCompleted)
                assertFalse(owner.state.value.readbackVerified)
                val second = ByteArrayOutputStream()
                owner.write(binding, writer = { it(second) })
                owner.drain()
                assertContentEquals(first.toByteArray(), second.toByteArray())
                assertEquals(binding, owner.state.value.preparedBinding)
            } finally { owner.closeAndDrain() }
        }
    }

    @Test fun preparedCiphertextSurvivesPickerHideAndDeniedWorkWaitsForAnExplicitRetry() = runBlocking {
        fixture { f ->
            val allowed = AtomicBoolean(true)
            val preparations = AtomicInteger()
            val writes = AtomicInteger()
            val owner = LanSyncGroupEncryptedFileExportOwner(prepare = { active ->
                preparations.incrementAndGet()
                LanSyncGroupEncryptedFilePackage.prepare(f.plan, f.store, f.trust, f.cipher, f.work, active)
            }, dataWork = { work -> if (allowed.get()) work() else false })
            try {
                val binding = prepared(owner)
                owner.hide()
                assertFalse(owner.state.value.visible)
                assertEquals(binding, owner.state.value.preparedBinding)
                val privateFiles = children(f.work)
                allowed.set(false)
                val bytes = ByteArrayOutputStream()
                owner.write(binding, writer = { body -> writes.incrementAndGet(); body(bytes) },
                    readback = { bytes.toByteArray().inputStream() })
                owner.drain()
                assertEquals(0, writes.get())
                assertFalse(owner.state.value.writeCompleted)
                assertFalse(owner.state.value.readbackVerified)
                assertEquals(binding, owner.state.value.preparedBinding)
                assertEquals(privateFiles, children(f.work))
                assertNotNull(owner.state.value.error)
                allowed.set(true)
                owner.write(binding, writer = { body -> writes.incrementAndGet(); body(bytes) },
                    readback = { bytes.toByteArray().inputStream() })
                owner.drain()
                assertEquals(1, writes.get())
                assertEquals(1, preparations.get())
                assertTrue(owner.state.value.readbackVerified)
                assertFalse(owner.state.value.visible)
                assertEquals(binding, owner.state.value.preparedBinding)
            } finally { owner.closeAndDrain() }
        }
    }

    @Test fun delayedOrRepeatedBorrowingCannotWriteAndOldPickerBindingsCannotSelectANewPackage() = runBlocking {
        fixture { f ->
            val owner = owner(f)
            try {
                val old = prepared(owner)
                var delayed: ((OutputStream) -> Unit)? = null
                owner.write(old, writer = { delayed = it })
                owner.drain()
                assertFalse(owner.state.value.writeCompleted)
                val untouched = ByteArrayOutputStream()
                assertFails { requireNotNull(delayed)(untouched) }
                assertEquals(0, untouched.size())

                val current = prepared(owner)
                assertNotEquals(old, current)
                val staleCalls = AtomicInteger()
                owner.write(old, writer = { staleCalls.incrementAndGet(); it(untouched) })
                owner.drain()
                assertEquals(0, staleCalls.get())
                assertEquals(0, untouched.size())
                assertEquals(current, owner.state.value.preparedBinding)

                val bodyCalls = AtomicInteger()
                owner.write(current, writer = { body ->
                    bodyCalls.incrementAndGet(); body(untouched)
                    assertFails { body(untouched) }
                })
                owner.drain()
                assertEquals(1, bodyCalls.get())
                assertFalse(owner.state.value.writeCompleted, "Swallowing the second-body error cannot turn a bad writer into success")
                assertNotNull(owner.state.value.error)
                owner.closeAndDrain()
                assertFails { requireNotNull(delayed)(ByteArrayOutputStream()) }
                val before = children(f.work)
                owner.open(); owner.prepare()
                owner.write(current, writer = { error("Closed owner must not invoke a picker") })
                owner.drain()
                assertFalse(owner.state.value.visible)
                assertFalse(owner.state.value.busy)
                assertEquals(before, children(f.work))
            } finally { owner.closeAndDrain() }
        }
    }

    @Test fun hideKeepsOnlyTheAlreadyAdmittedFiniteWriteAliveWithoutReopeningItsForm() = runBlocking {
        fixture { f ->
            val owner = owner(f)
            val release = CountDownLatch(1)
            val entered = CompletableDeferred<Unit>()
            val output = ByteArrayOutputStream()
            try {
                val binding = prepared(owner)
                owner.write(binding, writer = { body ->
                    body(object : OutputStream() {
                        override fun write(value: Int) { output.write(value) }
                        override fun write(buffer: ByteArray, offset: Int, count: Int) {
                            entered.complete(Unit)
                            release.await()
                            output.write(buffer, offset, count)
                        }
                    })
                })
                withTimeout(10_000) { entered.await() }
                owner.hide()
                assertFalse(owner.state.value.visible)
                assertTrue(owner.state.value.busy)
                release.countDown()
                withTimeout(10_000) { owner.drain() }
                assertFalse(owner.state.value.visible)
                assertFalse(owner.state.value.busy)
                assertTrue(owner.state.value.writeCompleted)
                assertFalse(owner.state.value.readbackVerified)
                assertTrue(output.size() > 0)
            } finally { release.countDown(); owner.closeAndDrain() }
        }
    }

    @Test fun cancelDrainWaitsForActualBorrowedSinkAndThenCleansOnlyOwnedCiphertext() = runBlocking {
        fixture { f ->
            Files.writeString(f.work.resolve("caller-owned.keep"), "retain")
            val baseline = children(f.work)
            val owner = owner(f)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val exited = AtomicBoolean()
            try {
                val binding = prepared(owner)
                assertNotEquals(baseline, children(f.work))
                owner.write(binding, writer = { body ->
                    try {
                        body(object : OutputStream() {
                            override fun write(value: Int) = error("Bulk writer expected")
                            override fun write(buffer: ByteArray, offset: Int, count: Int) {
                                entered.complete(Unit)
                                while (true) {
                                    try { release.await(); break } catch (_: InterruptedException) { /* Simulate a slow OS sink. */ }
                                }
                            }
                        })
                    } finally { exited.set(true) }
                })
                withTimeout(10_000) { entered.await() }
                owner.lock()
                val draining = async(start = CoroutineStart.UNDISPATCHED) { owner.drain() }
                assertFalse(draining.isCompleted)
                assertFalse(exited.get())
                assertNotEquals(baseline, children(f.work), "The private input remains owned until its writer really exits")
                release.countDown()
                withTimeout(10_000) { draining.await() }
                assertTrue(exited.get())
                assertFalse(owner.state.value.visible)
                assertFalse(owner.state.value.busy)
                assertNull(owner.state.value.preparedBinding)
                assertEquals(baseline, children(f.work))
                assertEquals("retain", Files.readString(f.work.resolve("caller-owned.keep")))
                assertEquals(f.resource.descriptor, f.store.metadata(f.resource.key)?.descriptor)
            } finally { release.countDown(); owner.closeAndDrain() }
        }
    }

    @Test fun deniedDataWorkCannotPrepareOrInventSuccessAndLatePrivateResultIsDisposed() = runBlocking {
        fixture { f ->
            val preparations = AtomicInteger()
            val denied = LanSyncGroupEncryptedFileExportOwner(prepare = {
                preparations.incrementAndGet(); f.prepare()
            }, dataWork = { false })
            try {
                denied.prepare(); denied.drain()
                assertEquals(0, preparations.get())
                assertNull(denied.state.value.preparedBinding)
                assertNotNull(denied.state.value.error)
            } finally { denied.closeAndDrain() }

            val baseline = children(f.work)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val late = LanSyncGroupEncryptedFileExportOwner(prepare = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    f.prepare()
                }
            })
            try {
                late.prepare()
                withTimeout(10_000) { entered.await() }
                late.lock()
                release.complete(Unit)
                withTimeout(10_000) { late.drain() }
                assertEquals(baseline, children(f.work))
                assertNull(late.state.value.preparedBinding)
                assertFalse(late.state.value.visible)
                assertFalse(late.state.value.busy)
            } finally { release.complete(Unit); late.closeAndDrain() }
        }
    }

    @Test fun ownedPreparationCancelsItsActualBlockedProducerBeforeAnyPackageExists() = runBlocking {
        fixture { f ->
            val baseline = children(f.work)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val interrupted = AtomicBoolean()
            val exited = AtomicBoolean()
            val owner = LanSyncGroupEncryptedFileExportOwner(preparer = LanSyncGroupFilePackagePreparer { _, _ ->
                runInterruptible(Dispatchers.IO) {
                    entered.complete(Unit)
                    try { check(release.await(10, TimeUnit.SECONDS)) }
                    catch (failure: InterruptedException) { interrupted.set(true); throw failure }
                    finally { exited.set(true) }
                }
            })
            try {
                owner.prepare()
                withTimeout(10_000) { entered.await() }
                owner.lock()
                withTimeout(10_000) { owner.drain() }
                assertTrue(interrupted.get(), "Production preparation runs in the cancellable job")
                assertTrue(exited.get())
                assertEquals(baseline, children(f.work))
                assertNull(owner.state.value.preparedBinding)
                assertFalse(owner.state.value.busy)
            } finally { release.countDown(); owner.closeAndDrain() }
        }
    }

    @Test fun ownedHandoffSurvivesCancellationUntilTheActualProducerHasExited() = runBlocking {
        fixture { f ->
            val baseline = children(f.work)
            val transferred = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val exited = AtomicBoolean()
            val owner = LanSyncGroupEncryptedFileExportOwner(preparer = LanSyncGroupFilePackagePreparer { active, receive ->
                runInterruptible(Dispatchers.IO) {
                    val prepared = LanSyncGroupEncryptedFilePackage.prepare(f.plan, f.store, f.trust, f.cipher, f.work, active)
                    receive(prepared)
                    transferred.complete(Unit)
                    try { awaitIgnoringInterrupts(release) } finally { exited.set(true) }
                }
            })
            try {
                owner.prepare()
                withTimeout(10_000) { transferred.await() }
                owner.lock()
                val draining = async(start = CoroutineStart.UNDISPATCHED) { owner.drain() }
                assertFalse(draining.isCompleted)
                assertFalse(exited.get())
                assertNotEquals(baseline, children(f.work), "Handoff ownership remains tracked while its producer is active")
                release.countDown()
                withTimeout(10_000) { draining.await() }
                assertTrue(exited.get())
                assertEquals(baseline, children(f.work))
                assertNull(owner.state.value.preparedBinding)
                assertFalse(owner.state.value.visible)
                assertFalse(owner.state.value.busy)
            } finally { release.countDown(); owner.closeAndDrain() }
        }
    }

    @Test fun aRetiredProducerCanStillTransferItsAlreadyCreatedPrivatePackageForDrainedDisposal() = runBlocking {
        fixture { f ->
            val baseline = children(f.work)
            val created = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val received = AtomicBoolean()
            val owner = LanSyncGroupEncryptedFileExportOwner(preparer = LanSyncGroupFilePackagePreparer { active, receive ->
                runInterruptible(Dispatchers.IO) {
                    val prepared = LanSyncGroupEncryptedFilePackage.prepare(f.plan, f.store, f.trust, f.cipher, f.work, active)
                    created.complete(Unit)
                    awaitIgnoringInterrupts(release)
                    receive(prepared)
                    received.set(true)
                }
            })
            try {
                owner.prepare()
                withTimeout(10_000) { created.await() }
                owner.lock()
                release.countDown()
                withTimeout(10_000) { owner.drain() }
                assertTrue(received.get(), "The receive callback accepts ownership before the producer returns Unit")
                assertEquals(baseline, children(f.work))
                assertNull(owner.state.value.preparedBinding)
                assertFalse(owner.state.value.busy)
                assertFalse(owner.state.value.visible)
            } finally { release.countDown(); owner.closeAndDrain() }
        }
    }

    @Test fun anEscapedReceiveCannotReplaceThePackageAndDisposesItsLateResultAfterClose() = runBlocking {
        fixture { f ->
            val baseline = children(f.work)
            var escaped: ((LanSyncGroupEncryptedFilePackage.Prepared) -> Unit)? = null
            val owner = LanSyncGroupEncryptedFileExportOwner(preparer = LanSyncGroupFilePackagePreparer { active, receive ->
                escaped = receive
                runInterruptible(Dispatchers.IO) {
                    receive(LanSyncGroupEncryptedFilePackage.prepare(f.plan, f.store, f.trust, f.cipher, f.work, active))
                }
            })
            try {
                val binding = prepared(owner)
                val held = children(f.work)
                val late = f.prepare()
                assertNotEquals(held, children(f.work))
                assertFails { requireNotNull(escaped)(late) }
                assertEquals(held, children(f.work))
                assertEquals(binding, owner.state.value.preparedBinding)
                assertNull(owner.state.value.error)
                val bytes = ByteArrayOutputStream()
                owner.write(binding, writer = { it(bytes) }, readback = { bytes.toByteArray().inputStream() })
                owner.drain()
                assertTrue(owner.state.value.readbackVerified)
                owner.closeAndDrain()
                assertEquals(baseline, children(f.work))
                val afterClose = f.prepare()
                assertFails { requireNotNull(escaped)(afterClose) }
                assertEquals(baseline, children(f.work))
                assertFalse(owner.state.value.visible)
                assertFalse(owner.state.value.busy)
                assertNull(owner.state.value.preparedBinding)
            } finally { owner.closeAndDrain() }
        }
    }

    private fun awaitIgnoringInterrupts(release: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "SYNTHETIC_PRODUCER_RELEASE_TIMEOUT" }
            try { check(release.await(remaining, TimeUnit.NANOSECONDS)); return }
            catch (_: InterruptedException) { /* A producer may finish its handoff before observing cancellation. */ }
        }
    }
}
