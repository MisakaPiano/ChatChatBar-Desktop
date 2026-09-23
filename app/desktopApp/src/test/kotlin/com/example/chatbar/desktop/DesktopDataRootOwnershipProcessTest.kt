package com.example.chatbar.desktop

import com.example.chatbar.data.root.AppDataRootInfrastructure
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

class DesktopDataRootOwnershipProcessTest {
    @Test
    fun `child ownership rejects a second process for the same root`() = windowsOnly {
        withTempDirectory { root ->
            OwnedChildProcess.start(root).use { child ->
                val blocked = assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(root))

                assertFalse(blocked.sameJvm)
                child.closeNormally()
            }
        }
    }

    @Test
    fun `different roots remain independently ownable across processes`() = windowsOnly {
        withTempDirectory { parent ->
            val rootA = Files.createDirectory(parent.resolve("a"))
            val rootB = Files.createDirectory(parent.resolve("b"))
            OwnedChildProcess.start(rootA).use { child ->
                val ownershipB = assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(rootB)).ownership

                ownershipB.close()
                child.closeNormally()
            }
        }
    }

    @Test
    fun `normal child release allows another process to reacquire`() = windowsOnly {
        withTempDirectory { root ->
            OwnedChildProcess.start(root).use(OwnedChildProcess::closeNormally)

            assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(root)).ownership.close()
        }
    }

    @Test
    fun `forced child termination releases lock while stale file remains`() = windowsOnly {
        withTempDirectory { root ->
            OwnedChildProcess.start(root).use(OwnedChildProcess::terminateForcibly)

            val lockPath = root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME)
            assertTrue(Files.exists(lockPath))
            acquireAfterProcessExit(root).ownership.close()
        }
    }

    private fun acquire(root: Path): DesktopDataRootOwnershipResult =
        DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = root.resolveSibling("parent-bootstrap.json"),
            ),
        )

    private fun acquireAfterProcessExit(root: Path): DesktopDataRootOwnershipResult.Acquired {
        repeat(MAX_REACQUIRE_ATTEMPTS) {
            when (val result = acquire(root)) {
                is DesktopDataRootOwnershipResult.Acquired -> return result
                is DesktopDataRootOwnershipResult.AlreadyInUse -> {
                    assertFalse(result.sameJvm)
                    Thread.yield()
                }

                is DesktopDataRootOwnershipResult.Failure ->
                    throw AssertionError("Reacquisition failed after child exit: $result")
            }
        }
        throw AssertionError("OS lock remained held after child process termination")
    }

    private fun windowsOnly(block: () -> Unit) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            println("WINDOWS_CHILD_JVM_OWNERSHIP_TEST_SKIPPED")
            return
        }
        block()
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("desktop-data-root-process-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class OwnedChildProcess private constructor(
        private val process: Process,
        private val input: java.io.BufferedReader,
        private val output: java.io.BufferedWriter,
    ) : AutoCloseable {
        private var completed = false

        fun closeNormally() {
            check(!completed) { "Child process already completed" }
            output.write(DesktopDataRootOwnershipProcessChild.CLOSE)
            output.newLine()
            output.flush()
            output.close()
            assertTrue(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val remainingOutput = input.readText()
            assertEquals(0, process.exitValue(), remainingOutput)
            assertTrue(
                remainingOutput.lineSequence().any {
                    it == DesktopDataRootOwnershipProcessChild.CLOSED
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
            fun start(root: Path): OwnedChildProcess {
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
                    DesktopDataRootOwnershipProcessChild::class.java.name,
                    root.toString(),
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
                    throw AssertionError("Child did not report ownership readiness", error)
                } finally {
                    readyExecutor.shutdownNow()
                }
                if (readyLine != DesktopDataRootOwnershipProcessChild.READY) {
                    process.destroyForcibly()
                    process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val diagnostics = buildString {
                        appendLine("Expected READY, received: $readyLine")
                        append(input.readText())
                    }
                    throw AssertionError(diagnostics)
                }
                return OwnedChildProcess(process, input, output)
            }

            private fun childClasspath(): String = listOf(
                DesktopDataRootOwnershipProcessChild::class.java,
                DesktopDataRootOwnership::class.java,
                AppDataRootInfrastructure::class.java,
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
        private const val MAX_REACQUIRE_ATTEMPTS = 100
    }
}
