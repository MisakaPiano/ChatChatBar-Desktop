package com.example.chatbar.domain.lanSync

import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.SocketException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlin.test.*

class LanSyncTlsTest {
    @Test fun applicationOwnedSocketsCloseAStalledFirstHandshakeWithoutWaitingForItsLongReadTimeout() {
        val identity = LanSyncTlsIdentity.create()
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { it.soTimeout = 10_000 }
        val hello = CountDownLatch(1); val release = CountDownLatch(1)
        val owned = CopyOnWriteArrayList<Socket>()
        val executor = Executors.newFixedThreadPool(2)
        val peer = executor.submit {
            listener.accept().use { socket ->
                socket.soTimeout = 10_000
                check(socket.getInputStream().read() >= 0)
                hello.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val connecting = executor.submit<Exception> {
            try {
                LanSyncTls.connectOwnedWithDial(identity, "10.0.0.1", listener.localPort, "a".repeat(64), 120_000,
                    { owned += it }) { plain, _, port ->
                    assertTrue(plain in owned, "The application owns the TCP socket before connect starts")
                    assertFalse(plain.isConnected)
                    plain.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
                }.close()
                error("SYNTHETIC_STALLED_PEER_MUST_NOT_COMPLETE_TLS")
            } catch (failure: Exception) { failure }
        }
        try {
            assertTrue(hello.await(5, TimeUnit.SECONDS))
            assertEquals(2, owned.size)
            assertTrue(owned.any { it is SSLSocket })
            val closingAt = System.nanoTime()
            owned.forEach(Socket::close)
            val failure = connecting.get(5, TimeUnit.SECONDS)
            assertTrue(System.nanoTime() - closingAt < 5_000_000_000L)
            assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it is SocketException || it is SSLException })
            assertFalse(generateSequence(failure as Throwable?) { it.cause }.any { it is SocketTimeoutException })
            assertTrue(owned.all { it.isClosed })
        } finally {
            owned.forEach { runCatching { it.close() } }
            release.countDown(); listener.close()
            try { peer.get(5, TimeUnit.SECONDS) }
            finally { executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun refusingSslOwnershipBeforeHandshakeClosesBothTheTlsAndUnderlyingTcpSockets() {
        val identity = LanSyncTlsIdentity.create()
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { it.soTimeout = 5_000 }
        val owned = CopyOnWriteArrayList<Socket>()
        val refusal = IOException("synthetic application ownership refusal")
        val executor = Executors.newSingleThreadExecutor()
        val peer = executor.submit {
            listener.accept().use { socket ->
                socket.soTimeout = 5_000
                var count = 0
                try { while (socket.getInputStream().read() != -1) check(++count <= 1024) }
                catch (_: SocketException) { /* A reset also proves that the owned connection closed. */ }
            }
        }
        try {
            assertSame(refusal, assertFailsWith<IOException> {
                LanSyncTls.connectOwnedWithDial(identity, "10.0.0.1", listener.localPort, "a".repeat(64), 120_000,
                    { socket -> owned += socket; if (socket is SSLSocket) throw refusal }) { plain, _, port ->
                    assertTrue(plain in owned)
                    plain.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
                }
            })
            assertEquals(2, owned.size)
            assertTrue(owned.all { it.isClosed })
            peer.get(5, TimeUnit.SECONDS)
        } finally {
            owned.forEach { runCatching { it.close() } }
            listener.close(); executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun connectTimeoutAppliesBeforeTheFirstTlsHandshakeAndClosesTheOwnedTcpSocket() {
        val identity = LanSyncTlsIdentity.create(); val release = CountDownLatch(1)
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { it.soTimeout = 10_000 }
        val executor = Executors.newSingleThreadExecutor(); val dialed = AtomicReference<Socket>()
        val peer = executor.submit { listener.accept().use { socket ->
            socket.soTimeout = 10_000
            check(socket.getInputStream().read() >= 0)
            // A TCP peer that receives ClientHello but never starts TLS must hit the first-handshake timeout.
            release.await(3, TimeUnit.SECONDS)
        } }
        try {
            val failure = assertFails { LanSyncTls.connect(identity, "10.0.0.1", listener.localPort, "a".repeat(64), 250) { _, _ ->
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).also(dialed::set)
            } }
            assertTrue(generateSequence(failure) { it.cause }.any { it is SocketTimeoutException })
            assertTrue(requireNotNull(dialed.get()).isClosed)
        } finally {
            release.countDown(); listener.close()
            try { peer.get(5, TimeUnit.SECONDS) }
            finally { executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
    @Test fun actualMutualTlsStreamsAndRejectsAnUnpinnedIdentity(): Unit = runBlocking {
        val serverId = LanSyncTlsIdentity.create(); val clientId = LanSyncTlsIdentity.create()
        val server = serverId.context { it == clientId.fingerprint }.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.soTimeout = 10_000
        server.needClientAuth = true; server.bind(InetSocketAddress("127.0.0.1", 0))
        try {
        val receiving = async(Dispatchers.IO) {
            server.accept().use { raw ->
                val socket = raw as SSLSocket; LanSyncTls.configure(socket); socket.startHandshake()
                LanSyncWire(socket).use { wire ->
                    assertEquals("synthetic", wire.text()); wire.sendText("authenticated")
                    wire.send(byteArrayOf(0xc3.toByte(), 0x28))
                    val bytes = ByteArray(200_000) { (it % 251).toByte() }
                    wire.sendBlob(bytes.inputStream(), bytes.size.toLong())
                }
            }
        }
        withContext(Dispatchers.IO) {
            val client = clientId.context { it == serverId.fingerprint }.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
            LanSyncTls.configure(client); client.startHandshake()
            LanSyncWire(client).use { wire ->
                wire.sendText("synthetic"); assertEquals("authenticated", wire.text())
                assertFailsWith<IllegalArgumentException> { wire.text() }
                val expected = ByteArray(200_000) { (it % 251).toByte() }; val destination = java.io.ByteArrayOutputStream()
                wire.receiveBlob(destination, expected.size.toLong(), lanSyncHash(expected))
                assertContentEquals(expected, destination.toByteArray())
            }
        }
        receiving.await()
        } finally { server.close() }

        val refusing = serverId.context { false }.serverSocketFactory.createServerSocket() as SSLServerSocket
        refusing.soTimeout = 10_000
        refusing.needClientAuth = true; refusing.bind(InetSocketAddress("127.0.0.1", 0))
        try {
        val refusal = async(Dispatchers.IO) {
            refusing.accept().use { raw -> val accepted = raw as SSLSocket; LanSyncTls.configure(accepted); accepted.soTimeout = 10_000
                assertFails { accepted.startHandshake() } }
        }
        withContext(Dispatchers.IO) {
            val client = clientId.context { it == serverId.fingerprint }.socketFactory.createSocket("127.0.0.1", refusing.localPort) as SSLSocket
            LanSyncTls.configure(client); client.soTimeout = 10_000
            client.use { runCatching { it.startHandshake(); it.outputStream.write(1); it.inputStream.read() } }
        }
        refusal.await()
        } finally { refusing.close() }
    }
    @Test fun numericLocalAddressPolicyRefusesDnsLoopbackAndInternet() {
        listOf("localhost", "127.0.0.1", "8.8.8.8", "https://192.168.1.1", "192.168.1.1/path", "192.168.01.1").forEach {
            assertFails { LanSyncTls.parseAddress(it) }
        }
        assertEquals("192.168.1.1", LanSyncTls.parseAddress("192.168.1.1").hostAddress)
    }
    @Test fun expiredPairCapabilityAndSecondPeerAreRefusedPersistently() {
        val root = java.nio.file.Files.createTempDirectory("ccb-lan-trust-").toRealPath()
        val store = LanSyncStateStore(root, { it }, { it })
        val identity = store.identity(); assertEquals(identity.fingerprint, LanSyncStateStore(root, { it }, { it }).identity().fingerprint)
        store.trust(LanSyncPeer("a".repeat(64), "synthetic"))
        assertFails { store.trust(LanSyncPeer("b".repeat(64), "other synthetic")) }
        store.revoke("a".repeat(64)); assertTrue(store.peers().isEmpty())
        assertFails { LanSyncPairOffer(host = "192.168.1.1", port = 39238, fingerprint = "a".repeat(64), token = "b".repeat(64), expiresAt = 1).validate() }
    }
}
