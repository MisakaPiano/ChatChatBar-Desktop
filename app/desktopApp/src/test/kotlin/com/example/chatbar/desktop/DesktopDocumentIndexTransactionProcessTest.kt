package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.EmbeddingConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Process interruption, NOT a power-loss test. Only this test's exact Process is terminated. */
class DesktopDocumentIndexTransactionProcessTest {
    @Test fun `fresh child recovery classifies interrupted installs and is byte idempotent`() = runBlocking {
        check(System.getProperty("os.name").startsWith("Windows")) { "Windows crash evidence required" }
        for (stage in listOf(IndexTxnCut.BEFORE_STAGE, IndexTxnCut.AFTER_STAGE, IndexTxnCut.AFTER_JOURNAL,
            IndexTxnCut.FIRST_OLD_MOVED, IndexTxnCut.FIRST_NEW_INSTALLED, IndexTxnCut.AFTER_CARD,
            IndexTxnCut.AFTER_MARKER, IndexTxnCut.DURING_CLEANUP)) withRoot { root ->
            val old = SyntheticIndexTxnData.payloadHashes(root)
            val protected = SyntheticIndexTxnData.protectedHashes(root)
            Child.start(root, "PUBLISH", stage).use { it.interruptAt(stage) }
            // The exact child has exited; taking ownership must now succeed without clearing locks.
            reacquireAfterOwnChildExit(root).close()
            val expected = when (stage) {
                IndexTxnCut.AFTER_STAGE -> IndexTxnOutcome.COMMIT_UNCERTAIN
                IndexTxnCut.AFTER_MARKER, IndexTxnCut.DURING_CLEANUP -> IndexTxnOutcome.COMMITTED
                else -> IndexTxnOutcome.NOT_COMMITTED
            }
            val first = Child.start(root, "RECOVER").use { it.finish() }
            assertEquals(IndexTxnReceipt(expected, expected != IndexTxnOutcome.COMMIT_UNCERTAIN), first, stage.name)
            assertEquals(protected, SyntheticIndexTxnData.protectedHashes(root))
            if (expected != IndexTxnOutcome.COMMITTED) assertEquals(old, SyntheticIndexTxnData.payloadHashes(root))
            val once = allHashes(root)
            assertEquals(first, Child.start(root, "RECOVER").use { it.finish() })
            assertEquals(once, allHashes(root), "Fresh-process recovery must not rewrite evidence or payload")
            if (first.businessOpen) assertRealReader(root)
            IndexTxnTestEvidence.record("process-matrix", stage.name, first.outcome.name, first.businessOpen,
                buildJsonObject { put("protectedHashesEqual", true); put("secondRecoveryByteIdentical", true) })
        }
    }

    @Test fun `recovery itself can be killed and a third fresh JVM still restores old bytes`() = runBlocking {
        for (cut in listOf(IndexTxnCut.RECOVERY_FIRST_NEW_REMOVED, IndexTxnCut.RECOVERY_FIRST_OLD_RESTORED)) withRoot { root ->
            val old = SyntheticIndexTxnData.payloadHashes(root)
            Child.start(root, "PUBLISH", IndexTxnCut.AFTER_CARD).use { it.interruptAt(IndexTxnCut.AFTER_CARD) }
            Child.start(root, "RECOVER", cut).use { it.interruptAt(cut) }
            val outcome = Child.start(root, "RECOVER").use { it.finish() }
            assertEquals(IndexTxnReceipt(IndexTxnOutcome.NOT_COMMITTED, true), outcome)
            assertEquals(old, SyntheticIndexTxnData.payloadHashes(root))
            val once = allHashes(root)
            assertEquals(outcome, Child.start(root, "RECOVER").use { it.finish() })
            assertEquals(once, allHashes(root)); assertRealReader(root)
            IndexTxnTestEvidence.record("process-recovery-crash", cut.name, outcome.outcome.name, true)
        }
    }

    private suspend fun withRoot(block: suspend (Path) -> Unit) {
        val root = Files.createTempDirectory("p9-s2b-synthetic-")
        try { SyntheticIndexTxnData.seed(root); block(root) }
        finally {
            // Only our createTempDirectory root, after all child handles have synchronously exited.
            check(root.toAbsolutePath().normalize().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun assertRealReader(root: Path) {
        val owner = SyntheticIndexTxnData.acquire(root); val coordinator = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, coordinator)
            val prototype = DesktopDocumentIndexTransactionPrototype(root, storage, coordinator)
            assertTrue(prototype.businessOpen())
            val card = storage.loadEntity("character_cards", "card", CharacterCard.serializer())!!
            val model = storage.loadEntity("embedding_configs", "embedding", EmbeddingConfig.serializer())!!
            val result = assertIs<DesktopDocumentRagResult.Available>(DesktopDocumentRagReader(root, storage, coordinator)
                .retrieve(card, listOf(1f, 0f), DesktopRagEmbeddingIdentity.from(model)))
            assertEquals(2, result.matches.size)
            assertTrue(result.matches.all { it.chunkId.startsWith("old_") } || result.matches.all { it.chunkId.startsWith("txn_") })
        } finally { coordinator.closeAndDrain(); owner.close() }
    }

    // Match the existing ownership process test: Windows may briefly retain an OS lock
    // after waitFor reports exit. Never clear the lock file or turn a timeout into PASS.
    private fun reacquireAfterOwnChildExit(root: Path): DesktopDataRootOwnership {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            when (val result = DesktopDataRootOwnership.acquire(SyntheticIndexTxnData.resolved(root))) {
                is DesktopDataRootOwnershipResult.Acquired -> return result.ownership
                is DesktopDataRootOwnershipResult.AlreadyInUse -> {
                    check(!result.sameJvm && System.nanoTime() < deadline) { "Ownership not released after exact child exit" }
                    Thread.yield()
                }
                is DesktopDataRootOwnershipResult.Failure -> error("Ownership reacquire failed: ${result.kind}")
            }
        }
    }

    private fun allHashes(root: Path) = Files.walk(root).use { stream -> stream.filter {
        Files.isRegularFile(it) && it.fileName.toString() != DesktopDataRootOwnership.LOCK_FILE_NAME
    }.toList().associate { root.relativize(it).toString() to DesktopDocumentIndexTransactionPrototype.digest(Files.readAllBytes(it)) } }

    private class Child private constructor(private val process: Process, private val reader: java.io.BufferedReader,
        private val mode: String, private val stop: String) : AutoCloseable {
        private val pid = process.pid()
        private val created = process.info().startInstant().orElseThrow().toString()
        private var exited = false
        private fun line(): String? {
            val executor = Executors.newSingleThreadExecutor()
            return try { executor.submit<String?> { reader.readLine() }.get(40, TimeUnit.SECONDS) }
            finally { executor.shutdownNow() }
        }
        fun interruptAt(stage: IndexTxnCut) {
            assertEquals("READY ${stage.name} $pid", line())
            check(process.isAlive && process.pid() == pid && process.info().startInstant().orElseThrow().toString() == created)
            process.destroyForcibly() // Explicitly authorized kill of the one stored, self-started child.
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Own child failed to exit; stop further process work")
            exited = true
            IndexTxnTestEvidence.record("child-interrupted", stage.name, "EXITED", false, details(process.exitValue()))
        }
        fun finish(): IndexTxnReceipt {
            val result = line()?.split(' ') ?: error("Child exited without result")
            assertEquals("RESULT", result[0]); assertEquals(3, result.size)
            assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), reader.readText())
            exited = true
            val receipt = IndexTxnReceipt(IndexTxnOutcome.valueOf(result[1]), result[2].toBooleanStrict())
            IndexTxnTestEvidence.record("child-recovery", stop, receipt.outcome.name, receipt.businessOpen, details(0))
            return receipt
        }
        private fun details(exit: Int) = buildJsonObject {
            put("pid", pid); put("creationTime", created); put("mode", mode); put("exitCode", exit)
            put("aliveAfterWait", process.isAlive); put("heapMiB", 96)
        }
        override fun close() {
            if (!exited && process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(30, TimeUnit.SECONDS)) { "Own child remains alive: no subsequent child allowed" }
            }
            reader.close(); process.outputStream.close()
        }
        companion object {
            fun start(root: Path, mode: String, stop: IndexTxnCut? = null): Child {
                val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
                check(Files.isRegularFile(java))
                val process = ProcessBuilder(java.toString(), "-Xmx96m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
                    "-Dfile.encoding=UTF-8", "-cp", classpath(), DesktopDocumentIndexTransactionChild::class.java.name,
                    root.toString(), mode, stop?.name ?: "NONE").redirectErrorStream(true).start()
                return Child(process, process.inputStream.bufferedReader(), mode, stop?.name ?: "NONE")
            }
            private fun classpath(): String = listOf(
                DesktopDocumentIndexTransactionChild::class.java, DesktopDataRootOwnership::class.java,
                JsonFileStorage::class.java, Unit::class.java, kotlinx.coroutines.Job::class.java,
                Json::class.java, kotlinx.serialization.KSerializer::class.java,
                com.sun.jna.Native::class.java, com.sun.jna.platform.win32.Kernel32::class.java,
            ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        }
    }
}

/** No UI, task daemon, network, production root argument, or delayed background work. */
object DesktopDocumentIndexTransactionChild {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 3)
        val root = Path.of(args[0]); val owner = SyntheticIndexTxnData.acquire(root)
        val coordinator = DesktopDataOperationCoordinator()
        try {
            val prototype = DesktopDocumentIndexTransactionPrototype(root, JsonFileStorage(root, coordinator), coordinator) { step ->
                if (step.name == args[2]) {
                    println("READY ${step.name} ${ProcessHandle.current().pid()}"); System.out.flush()
                    check(System.`in`.read() >= 0) { "Parent pipe closed" }
                }
            }
            val receipt = when (args[1]) { "PUBLISH" -> prototype.publish(); "RECOVER" -> prototype.recover(); else -> error("Unknown test mode") }
            println("RESULT ${receipt.outcome} ${receipt.businessOpen}"); System.out.flush()
        } finally { coordinator.closeAndDrain(); owner.close() }
    }
}
