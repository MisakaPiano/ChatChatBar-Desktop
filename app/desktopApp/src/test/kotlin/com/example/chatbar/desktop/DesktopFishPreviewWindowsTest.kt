package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopFishPreviewWindowsTest {
    @Test fun `real Windows MP3 device starts stops and releases file for exclusive reopen`() = runBlocking {
        requireWindowsAudioOutputForNativeTest()
        val file = Files.createTempFile("f1-native-", ".mp3")
        Files.write(file, fishPreviewMp3())
        val output = DesktopWindowsAudioOutput()
        try {
            DesktopMp3Validation.validate(file, 100_000)
            output.play(file)
            assertTrue(output.isPlaying())
            output.stopAndDrain(); assertFalse(output.isPlaying())
            output.closeAndDrain()
            java.io.RandomAccessFile(file.toFile(), "rw").use { it.channel.tryLock().use { lock -> assertNotNull(lock) } }
            Files.move(file, file.resolveSibling(file.fileName.toString() + ".released"))
            Files.delete(file.resolveSibling(file.fileName.toString() + ".released"))
        } finally { output.closeAndDrain() }
    }

    @Test fun `actual app container owns lazy preview runtime and closes it before root release`() = runBlocking {
        val root = Files.createTempDirectory("f1-container-")
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
        val c = DesktopAppContainer(resolved, secretStoreFactory = { InMemoryDesktopSecretStore() })
        val session = c.fishPreviewRuntime.openSession()
        assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolved))
        try { c.close() } finally { owner.close() }
        session.generate("fixture", "must not run")
        assertFalse(session.state.value.busy)
        assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership.close()
    }

    @Test fun `independent audio JVM exits then another JVM reacquires root`() {
        requireWindowsAudioOutputForNativeTest()
        val root = Files.createTempDirectory("f1-process-")
        val classpath = listOf(DesktopFishAudioChild::class.java, DesktopDataRootOwnership::class.java,
            com.example.chatbar.data.root.AppDataRootInfrastructure::class.java, Unit::class.java, Job::class.java,
            com.sun.jna.Native::class.java, com.sun.jna.platform.win32.Kernel32::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        for (mode in listOf("AUDIO", "LOCK")) {
            val log = root.resolve("$mode.log")
            val p = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "-Xmx128m", "-XX:+UseSerialGC", "-cp", classpath, DesktopFishAudioChild::class.java.name,
                root.toString(), mode).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            val started = p.info().startInstant().orElseThrow()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "Owned audio child did not exit; synthetic root retained")
            assertEquals(0, p.exitValue(), Files.readString(log))
            assertTrue(Files.readString(log).contains("F1_CHILD_$mode"))
            System.getenv("CCB_G2_INSPECTOR_EVIDENCE")?.let {
                Files.writeString(Path.of(it).resolve("audio-process.jsonl"),
                    "{\"pid\":${p.pid()},\"startedUtc\":\"$started\",\"mode\":\"$mode\",\"exitCode\":${p.exitValue()},\"alive\":${p.isAlive}}\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }
}

object DesktopFishAudioChild {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Path.of(args[0])
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json"))
        val owner = (DesktopDataRootOwnership.acquire(resolved) as DesktopDataRootOwnershipResult.Acquired).ownership
        try {
            if (args[1] == "AUDIO") {
                val file = root.resolve("synthetic.mp3"); Files.write(file, fishPreviewMp3())
                val output = DesktopWindowsAudioOutput()
                try { output.play(file); check(output.isPlaying()); output.stopAndDrain() }
                finally { output.closeAndDrain() }
                Files.delete(file)
            }
        } finally { owner.close() }
        println("F1_CHILD_${args[1]}")
    }
}
