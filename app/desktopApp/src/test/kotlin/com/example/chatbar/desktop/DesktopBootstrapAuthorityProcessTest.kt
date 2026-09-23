package com.example.chatbar.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.NonCancellable

class DesktopBootstrapAuthorityProcessTest {
    @Test
    fun `child-held authority lock rejects second process`() = windowsOnly {
        withBootstrapPath { bootstrapPath ->
            AuthorityChildProcess.start(bootstrapPath).use { child ->
                val busy = assertIs<AuthorityLockAcquisition.Busy>(
                    DesktopBootstrapAuthorityTransaction.acquireLock(bootstrapPath),
                )

                assertFalse(busy.sameJvm)
                child.closeNormally()
            }
        }
    }

    @Test
    fun `normal child release allows authority transaction reacquire`() = windowsOnly {
        withBootstrapPath { bootstrapPath ->
            AuthorityChildProcess.start(bootstrapPath).use(AuthorityChildProcess::closeNormally)

            acquireAfterProcessExit(bootstrapPath).close()
        }
    }

    @Test
    fun `forced child termination leaves stale file and releases authority lock`() = windowsOnly {
        withBootstrapPath { bootstrapPath ->
            AuthorityChildProcess.start(bootstrapPath).use(AuthorityChildProcess::terminateForcibly)

            val lockPath = bootstrapPath.resolveSibling(
                DesktopBootstrapAuthorityTransaction.AUTHORITY_LOCK_FILE_NAME,
            )
            assertTrue(Files.exists(lockPath))
            assertEquals(0L, Files.size(lockPath))
            acquireAfterProcessExit(bootstrapPath).close()
        }
    }

    private fun acquireAfterProcessExit(bootstrapPath: Path): BootstrapAuthorityLock {
        val startedAt = System.nanoTime()
        val deadline = startedAt + TimeUnit.SECONDS.toNanos(REACQUIRE_TIMEOUT_SECONDS)
        var lastBusy: AuthorityLockAcquisition.Busy? = null
        while (System.nanoTime() - deadline < 0L) {
            when (val result = DesktopBootstrapAuthorityTransaction.acquireLock(bootstrapPath)) {
                is AuthorityLockAcquisition.Acquired -> return result.lock
                is AuthorityLockAcquisition.Busy -> {
                    if (result.sameJvm) {
                        throw AssertionError(
                            "Same-JVM authority lock remained after child termination: $result",
                        )
                    }
                    lastBusy = result
                    Thread.yield()
                }

                is AuthorityLockAcquisition.Failure ->
                    throw AssertionError("Authority-lock reacquisition failed: $result")
            }
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        throw AssertionError(
            "OS authority lock remained unavailable after child termination; " +
                "bootstrapPath=$bootstrapPath, elapsedMillis=$elapsedMillis, " +
                "deadlineSeconds=$REACQUIRE_TIMEOUT_SECONDS, lastBusy=$lastBusy",
        )
    }

    private fun windowsOnly(block: () -> Unit) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            println("WINDOWS_CHILD_JVM_AUTHORITY_TEST_SKIPPED")
            return
        }
        block()
    }

    private fun withBootstrapPath(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("desktop-bootstrap-authority-process-")
        try {
            block(directory.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class AuthorityChildProcess private constructor(
        private val process: Process,
        private val input: java.io.BufferedReader,
        private val output: java.io.BufferedWriter,
    ) : AutoCloseable {
        private var completed = false

        fun closeNormally() {
            check(!completed) { "Child process already completed" }
            output.write(DesktopBootstrapAuthorityProcessChild.CLOSE)
            output.newLine()
            output.flush()
            output.close()
            assertTrue(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val remainingOutput = input.readText()
            assertEquals(0, process.exitValue(), remainingOutput)
            assertTrue(
                remainingOutput.lineSequence().any {
                    it == DesktopBootstrapAuthorityProcessChild.CLOSED
                },
                remainingOutput,
            )
            completed = true
        }

        fun terminateForcibly() {
            check(!completed) { "Child process already completed" }
            process.destroyForcibly()
            assertTrue(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            completed = true
        }

        override fun close() {
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            runCatching { output.close() }
            runCatching { input.close() }
        }

        companion object {
            fun start(bootstrapPath: Path): AuthorityChildProcess {
                val javaExecutable = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    if (System.getProperty("os.name").startsWith("Windows", true)) {
                        "java.exe"
                    } else {
                        "java"
                    },
                )
                check(Files.isRegularFile(javaExecutable)) {
                    "Child JVM executable is missing: $javaExecutable"
                }
                val process = ProcessBuilder(
                    javaExecutable.toString(),
                    "-cp",
                    childClasspath(),
                    DesktopBootstrapAuthorityProcessChild::class.java.name,
                    bootstrapPath.toString(),
                )
                    .redirectErrorStream(true)
                    .start()
                val input = process.inputStream.bufferedReader()
                val output = process.outputStream.bufferedWriter()
                val readyExecutor = Executors.newSingleThreadExecutor()
                val readyLine = try {
                    readyExecutor.submit<String?> { input.readLine() }
                        .get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (error: Exception) {
                    process.destroyForcibly()
                    process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    throw AssertionError("Child did not report authority-lock readiness", error)
                } finally {
                    readyExecutor.shutdownNow()
                }
                if (readyLine != DesktopBootstrapAuthorityProcessChild.READY) {
                    process.destroyForcibly()
                    process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val diagnostics = buildString {
                        appendLine("Expected READY, received: $readyLine")
                        append(input.readText())
                    }
                    throw AssertionError(diagnostics)
                }
                return AuthorityChildProcess(process, input, output)
            }

            private fun childClasspath(): String = listOf(
                DesktopBootstrapAuthorityProcessChild::class.java,
                DesktopBootstrapAuthorityTransaction::class.java,
                DesktopBootstrapSettingsStore::class.java,
                DesktopPortableRootResolver::class.java,
                NonCancellable::class.java,
                kotlin.Unit::class.java,
            )
                .map { type ->
                    Path.of(type.protectionDomain.codeSource.location.toURI())
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                }
                .distinct()
                .joinToString(File.pathSeparator)
        }
    }

    companion object {
        private const val PROCESS_TIMEOUT_SECONDS = 20L
        private const val REACQUIRE_TIMEOUT_SECONDS = 5L
    }
}
