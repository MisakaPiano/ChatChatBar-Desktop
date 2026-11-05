package com.example.chatbar.desktop

import com.example.chatbar.data.root.AppDataRootInfrastructure
import java.io.*
import java.net.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*

class DesktopExternalActivationTest {
    @Test fun `actual cold and warm process ingress preserves ordered unicode paths and one root writer`() = temporary { root ->
        val home = Files.createDirectory(root.resolve("程序 home"))
        val local = Files.createDirectory(root.resolve("local"))
        val data = Files.createDirectory(root.resolve("data"))
        val first = Files.writeString(root.resolve("第一 & file.json"), "synthetic-one")
        val second = Files.writeString(root.resolve("second file.cbsave"), "synthetic-two")
        val third = Files.writeString(root.resolve("第三.json"), "synthetic-three")
        Child.start(home, local, data, listOf(first)).use { owner ->
            assertEquals("OWNER", owner.line())
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(data))
            Child.start(home, local, data, listOf(second, third), raw = true).use { warm ->
                assertEquals("FORWARDED", warm.line()); warm.awaitExit()
            }
            assertEquals(listOf(first.toString(), second.toString(), third.toString()), owner.paths())
            Child.start(home, local, data, emptyList()).use { normal ->
                assertEquals("FORWARDED", normal.line()); normal.awaitExit()
            }
            assertEquals(listOf(first.toString(), second.toString(), third.toString()), owner.paths())
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(data))
            owner.stop()
        }
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(data)).ownership.close()
        assertEquals("synthetic-one", Files.readString(first)); assertEquals("synthetic-two", Files.readString(second))
    }

    @Test fun `pending attempted head survives normal restart and stale acknowledgements cannot skip it`() = temporary { root ->
        val file = root.resolve("pending.json")
        val a = root.resolve("a.json"); val b = root.resolve("b.json")
        val request = UUID.randomUUID().toString()
        val queue = DesktopExternalFileQueue(file)
        assertTrue(queue.submit(request, listOf(a, b)))
        val ids = queue.state.value.files.map { it.id }
        assertFalse(queue.acknowledge(ids[1]))
        queue.markAttempted(ids[0]); queue.stopAccepting()
        val reopened = DesktopExternalFileQueue(file)
        assertEquals(ids, reopened.state.value.files.map { it.id })
        assertTrue(reopened.state.value.files.first().attempted)
        assertFalse(reopened.state.value.files.last().attempted)
        assertTrue(reopened.submit(request, listOf(a, b)))
        assertEquals(2, reopened.state.value.files.size)
        assertTrue(reopened.acknowledge(ids[0])); assertEquals(b, reopened.state.value.files.single().path)
    }

    @Test fun `corrupt auxiliary queue preserves its bytes and permits ordinary production business ingress`() = temporary { root ->
        val home = Files.createDirectory(root.resolve("home")); val local = Files.createDirectory(root.resolve("local"))
        val directory = DesktopExternalActivation.directory(home, local); Files.createDirectories(directory)
        val journal = Files.writeString(directory.resolve("pending.json"), "{synthetic broken queue")
        val original = Files.readAllBytes(journal)
        var entered = false
        runDesktopExternalActivation(emptyArray(), home, local, report = { fail("Ordinary startup was refused: $it") }) { owner ->
            entered = true
            assertNotNull(owner.queue.state.value.error)
            assertFalse(owner.queue.submit(UUID.randomUUID().toString(), listOf(root.resolve("a.json"))))
        }
        assertTrue(entered); assertContentEquals(original, Files.readAllBytes(journal))
    }

    @Test fun `attempt marker publication failure does not change in-memory head admission proof`() = temporary { root ->
        val journal = root.resolve("pending.json")
        val queue = DesktopExternalFileQueue(journal)
        assertTrue(queue.submit(UUID.randomUUID().toString(), listOf(root.resolve("a.json"))))
        val head = queue.state.value.files.single()
        Files.move(journal, root.resolve("retained-original.json")); Files.createDirectory(journal)
        assertFails { queue.markAttempted(head.id) }
        assertEquals(head, queue.state.value.files.single()); assertFalse(queue.state.value.files.single().attempted)
    }

    @Test fun `lost delivery acknowledgement is not replayed and never starts a business writer`() = temporary { root ->
        val home = Files.createDirectory(root.resolve("home")); val local = Files.createDirectory(root.resolve("local"))
        val directory = DesktopExternalActivation.directory(home, local); Files.createDirectories(directory)
        val queue = DesktopExternalFileQueue()
        val dispatched = AtomicInteger()
        FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                    val token = "a".repeat(64)
                    Files.writeString(directory.resolve("endpoint"), "${server.localPort}\n$token\n")
                    val worker = Thread {
                        server.accept().use { connection ->
                            val input = DataInputStream(connection.getInputStream())
                            check(input.readInt() == 0x43434231)
                            fun text(): String = input.readNBytes(input.readInt()).toString(Charsets.UTF_8)
                            check(text() == token)
                            val request = text(); val count = input.readInt()
                            val paths = (0 until count).map { Path.of(text()) }
                            queue.submit(request, paths); dispatched.incrementAndGet()
                            // Accepted and published, then the connection fails before ACK delivery.
                        }
                    }.apply { isDaemon = true; start() }
                    var entered = false
                    var reported: DesktopExternalActivationFailure? = null
                    assertFailsWith<DesktopStartupBlockedException> {
                        runDesktopExternalActivation(arrayOf("--open-files", root.resolve("a.json").toString()), home, local,
                            report = { reported = it }) { entered = true }
                    }
                    worker.join(3000)
                    assertFalse(worker.isAlive); assertFalse(entered)
                    assertEquals(DesktopExternalActivationFailure.DELIVERY_UNCONFIRMED, reported)
                    assertEquals(1, dispatched.get()); assertEquals(1, queue.state.value.files.size)
                    server.soTimeout = 300
                    assertFailsWith<SocketTimeoutException> { server.accept() }
                }
            }
        }
    }

    private fun acquire(root: Path) = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, root.resolveSibling("synthetic-bootstrap.json")))
    private fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("external-activation-test-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private class Child(private val process: Process) : AutoCloseable {
        private val input = process.inputStream.bufferedReader(Charsets.UTF_8)
        private val output = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val reader = Executors.newSingleThreadExecutor()
        fun line(): String = requireNotNull(reader.submit<String?> { input.readLine() }.get(15, TimeUnit.SECONDS))
        fun paths(): List<String> {
            output.write("STATE\n"); output.flush()
            return Json.parseToJsonElement(Base64.getDecoder().decode(line()).toString(Charsets.UTF_8)).jsonArray.map { it.jsonPrimitive.content }
        }
        fun awaitExit() { assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()) }
        fun stop() { output.write("STOP\n"); output.flush(); awaitExit() }
        override fun close() {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS); reader.shutdownNow(); input.close(); output.close()
        }
        companion object {
            fun start(home: Path, local: Path, data: Path, paths: List<Path>, raw: Boolean = false): Child {
                val cp = listOf(DesktopExternalActivationProcessChild::class.java, DesktopExternalActivation::class.java,
                    AppDataRootInfrastructure::class.java, kotlin.Unit::class.java, kotlinx.coroutines.Job::class.java,
                    Json::class.java, kotlinx.serialization.KSerializer::class.java,
                    com.sun.jna.Native::class.java, com.sun.jna.platform.win32.Kernel32::class.java)
                    .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
                val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
                // Fixture control transport is ASCII because the raw JDK17 launcher can transcode argv.
                // Production ingress still receives/queues the exact Unicode strings; packaged OS argv is verified separately.
                val input = listOf(home.toString(), local.toString(), data.toString()) +
                    if (paths.isEmpty()) emptyList() else (if (raw) emptyList() else listOf("--open-files")) + paths.map(Path::toString)
                val arguments = listOf(java.toString(), "-Xmx96m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-Dfile.encoding=UTF-8",
                    "-cp", cp, DesktopExternalActivationProcessChild::class.java.name) + input.map { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                return Child(ProcessBuilder(arguments).redirectErrorStream(true).start())
            }
        }
    }
}

object DesktopExternalActivationProcessChild {
    @JvmStatic fun main(encoded: Array<String>) {
        val args = encoded.map { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }
        var entered = false
        runDesktopExternalActivation(args.drop(3).toTypedArray(), Path.of(args[0]), Path.of(args[1]), report = { error(it.name) }) { owner ->
            entered = true
            val root = Path.of(args[2])
            val result = DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
                DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, root.resolveSibling("synthetic-bootstrap.json")))
            check(result is DesktopDataRootOwnershipResult.Acquired)
            result.ownership.use {
                println("OWNER"); System.out.flush()
                val commands = System.`in`.bufferedReader(Charsets.UTF_8)
                while (true) when (commands.readLine()) {
                    "STATE" -> { println(Base64.getEncoder().encodeToString(JsonArray(owner.queue.state.value.files.map { JsonPrimitive(it.path.toString()) }).toString().toByteArray())); System.out.flush() }
                    "STOP", null -> break
                }
            }
        }
        if (!entered) { println("FORWARDED"); System.out.flush() }
    }
}
