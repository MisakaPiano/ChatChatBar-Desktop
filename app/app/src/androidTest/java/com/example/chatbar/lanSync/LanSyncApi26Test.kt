package com.example.chatbar.lanSync

import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.domain.lanSync.*
import java.net.*
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import org.junit.Assert.*
import org.junit.Test

/** Runs only in an isolated cloud emulator; all keys, roots and bytes here are invented. */
class LanSyncApi26Test {
    @Test fun keystoreWrappedIdentitySurvivesReopenAndRejectsTampering() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "lan-key-")
        val protection = AndroidLanSyncKeyProtection("synthetic-lan-${UUID.randomUUID()}")
        val first = LanSyncStateStore(root, protection::protect, protection::unprotect).identity()
        val reopened = LanSyncStateStore(root, protection::protect, protection::unprotect).identity()
        assertEquals(first.fingerprint, reopened.fingerprint)
        val bytes = protection.protect("synthetic-private-value".toByteArray())
        assertArrayEquals("synthetic-private-value".toByteArray(), protection.unprotect(bytes))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { protection.unprotect(bytes) }.isFailure)
    }
    @Test fun actualAndroidMutualTlsAndBoundedBlobReadWorkOnMinimumApi() {
        val serverId = LanSyncTlsIdentity.create(); val clientId = LanSyncTlsIdentity.create()
        val server = serverId.context { it == clientId.fingerprint }.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.soTimeout = 15_000; server.needClientAuth = true
        // Android may choose ::1 for getLoopbackAddress; match the numeric IPv4 client below.
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val response = pool.submit<Boolean> {
                (server.accept() as SSLSocket).use { socket ->
                    LanSyncTls.configure(socket); socket.soTimeout = 15_000; socket.startHandshake()
                    LanSyncWire(socket).use { wire ->
                        assertEquals("synthetic", wire.text()); wire.sendText("verified")
                        val bytes = ByteArray(65_536) { (it % 251).toByte() }
                        wire.sendBlob(bytes.inputStream(), bytes.size.toLong())
                    }
                }
                true
            }
            (clientId.context { it == serverId.fingerprint }.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { socket ->
                LanSyncTls.configure(socket); socket.soTimeout = 15_000; socket.startHandshake()
                LanSyncWire(socket).use { wire ->
                    wire.sendText("synthetic"); assertEquals("verified", wire.text())
                    val expected = ByteArray(65_536) { (it % 251).toByte() }
                    val result = java.io.ByteArrayOutputStream()
                    wire.receiveBlob(result, expected.size.toLong(), lanSyncHash(expected))
                    assertArrayEquals(expected, result.toByteArray())
                }
            }
            assertTrue(response.get(20, TimeUnit.SECONDS))
        } finally { server.close(); pool.shutdownNow() }
    }
    @Test fun trustedContextAliasIsCanonicalizedWhileInternalLinksStillRefuse() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "lan-root-").toRealPath()
        val state = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "lan-state-").toRealPath()
        LanSyncWorkspace(root, state, { Paths.get(it) }).capture("synthetic").use { assertTrue(it.snapshot.records.isEmpty()) }
        val foreign = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "lan-foreign-").toRealPath()
        Files.createSymbolicLink(root.resolve("entities"), foreign)
        assertTrue(runCatching { LanSyncWorkspace(root, state, { Paths.get(it) }).capture("synthetic") }.isFailure)
    }
}
