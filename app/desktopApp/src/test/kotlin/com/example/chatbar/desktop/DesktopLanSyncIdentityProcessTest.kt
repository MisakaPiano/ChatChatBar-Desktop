package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.WindowsDpapiSecretProtector
import com.example.chatbar.domain.lanSync.*
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopLanSyncIdentityProcessTest {
    @Test fun `native DPAPI identity and trusted peer survive independent JVM reopen`() {
        assertTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("ccb-lan-dpapi-").toRealPath()
        val classpath = requireNotNull(System.getProperty("ccb.readonly.classpath"))
        for (mode in listOf("write", "read")) {
            val log = root.resolve("$mode.log")
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                "-Dfile.encoding=UTF-8", "-cp", classpath, DesktopLanSyncIdentityChild::class.java.name, root.toString(), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            process.outputStream.close()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "synthetic child timeout")
                assertEquals(0, process.exitValue(), Files.readString(log).take(1000))
                assertTrue(Files.readString(log).contains("LAN_IDENTITY_${mode.uppercase()}_PASS"))
            } finally { if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        }
    }
}

object DesktopLanSyncIdentityChild {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0]).toRealPath()
        check(root.fileName.toString().startsWith("ccb-lan-dpapi-"))
        val protector = WindowsDpapiSecretProtector()
        val store = LanSyncStateStore(root.resolve("state"), protector::protect, protector::unprotect)
        val identity = store.identity()
        if (args[1] == "write") {
            Files.writeString(root.resolve("fingerprint.txt"), identity.fingerprint, StandardOpenOption.CREATE_NEW)
            store.trust(LanSyncPeer("f".repeat(64), "synthetic trusted peer"))
            val plaintext = "invented-lan-private-protection-probe".toByteArray()
            val protected = protector.protect(plaintext)
            check(!protected.toString(Charsets.ISO_8859_1).contains(plaintext.toString(Charsets.US_ASCII)))
            Files.write(root.resolve("probe.protected"), protected, StandardOpenOption.CREATE_NEW)
        } else {
            check(args[1] == "read")
            check(identity.fingerprint == Files.readString(root.resolve("fingerprint.txt")))
            check(store.peers().single().fingerprint == "f".repeat(64))
            check(protector.unprotect(Files.readAllBytes(root.resolve("probe.protected"))).contentEquals("invented-lan-private-protection-probe".toByteArray()))
        }
        val ciphertext = Files.readAllBytes(root.resolve("state/identity.protected"))
        check(runCatching { LanSyncTlsIdentity.decode(ciphertext) }.isFailure)
        check(LanSyncTlsIdentity.decode(protector.unprotect(ciphertext)).fingerprint == identity.fingerprint)
        println("LAN_IDENTITY_${args[1].uppercase()}_PASS")
    }
}
