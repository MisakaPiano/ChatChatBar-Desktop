package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.*

/** Real mutual TLS on synthetic loopback endpoints, immutable backend, resumable bytes and actual AEAD. */
class LanSyncGroupCiphertextTransportTest {
    private class Fixture(val root: Path) : AutoCloseable {
        val f = LanSyncGroupPublicationQueueTest.Fixture(root)
        @Volatile var current = f.ledger
        @Volatile var notice: SyncLedgerNoticeEntry? = null
        @Volatile var serverHook: (String, Long) -> Unit = { _, _ -> }
        val resumes = Collections.synchronizedList(mutableListOf<Long>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
        val executor = Executors.newCachedThreadPool()
        val listener = (f.owner.context { it in f.members }.serverSocketFactory.createServerSocket(0, 20, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .also { it.needClientAuth = true }
        val server = LanSyncGroupCiphertextServer(root.resolve("wire-server"), { group, local, peer ->
            require(group == f.cipher.group) { "SYNC_GROUP_WIRE_GROUP" }
            LanSyncGroupPeerStore(f.ledger.trustAnchor, local, peer, { current }, f.backend, { notice })
        }, { stage, offset -> if (stage == "CIPHERTEXT_UPLOAD_RESUME") resumes += offset; serverHook(stage, offset) })
        init {
            executor.submit {
                try {
                    while (!listener.isClosed) {
                        val socket = listener.accept() as SSLSocket; sockets += socket
                        executor.submit {
                            try { server.serve(socket) } catch (failure: Throwable) { if (failure !is IOException) failures += failure }
                            finally { sockets -= socket; socket.close() }
                        }
                    }
                } catch (failure: SocketException) { if (!listener.isClosed) failures += failure }
            }
        }
        fun remote(directory: String = "remote", identity: LanSyncTlsIdentity = f.sender,
            observe: (String, Long) -> Unit = { _, _ -> }) = LanSyncGroupRemoteStore(f.cipher.group, root.resolve(directory), {
            identity.context { it == f.owner.fingerprint }.socketFactory.createSocket(InetAddress.getLoopbackAddress(), listener.localPort) as SSLSocket
        }, observe)
        fun revoke() {
            val key = SyncStreamingCipher.create(f.cipher.group)
            val row = f.sign(f.genesis.copy(id = SyncStoreId.random(), parent = f.genesis.reference(), sequence = 2,
                epoch = key.epoch, keyCommitment = key.keyCommitment(), members = f.members +
                    (f.sender.fingerprint to f.members.getValue(f.sender.fingerprint).copy(active = false))))
            current = SyncDeviceLedger(f.ledger.trustAnchor).also { it.offer(f.genesis); it.offer(row) }
            val queue = SyncLedgerNoticeQueue(Files.createDirectory(root.resolve("notices")), f.ledger.trustAnchor, f.owner.fingerprint, { current })
            notice = queue.prepare(SyncStoreId.random(), f.sender.fingerprint, f.owner::signSyncGroupPayload)
            queue.upload(requireNotNull(notice).request.operationId, f.backend)
        }
        override fun close() {
            listener.close(); sockets.forEach { it.close() }; executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS)); assertTrue(failures.isEmpty(), failures.joinToString { it.javaClass.simpleName })
        }
    }
    private fun withFixture(work: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("synthetic-mutual-tls-ciphertext-").toRealPath()
        try { Fixture(root).use(work) } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun uploadAndDownloadResumeVerifiedCiphertextChunksAndOnlyCompleteAeadBytesReachTheCaller() = withFixture { t ->
        val context = SyncEncryptionContext(t.f.cipher.group, t.f.cipher.epoch, CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()))
        val plain = ByteArray(7 * 1024 * 1024 + 137) { (it * 31).toByte() }
        t.f.cipher.encrypt(plain.inputStream(), context, t.f.work).use { encrypted ->
            val request = CiphertextPutRequest(context.key, SyncStoreId.random(), encrypted.descriptor)
            var lost = false
            t.serverHook = { stage, offset -> if (stage == "CIPHERTEXT_CHUNK_RETAINED" && offset >= 2L * 1024 * 1024 && !lost) {
                lost = true; throw IOException("SYNTHETIC_LOST_UPLOAD_REPLY")
            } }
            assertFailsWith<IOException> { Files.newInputStream(encrypted.path).use { t.remote().put(request, it) } }
            assertNull(t.f.backend.metadata(context.key)); t.serverHook = { _, _ -> }
            val uploaded = Files.newInputStream(encrypted.path).use { t.remote().put(request, it) }
            assertEquals(request.descriptor, uploaded.metadata.descriptor); assertTrue(t.resumes.last() >= 2L * 1024 * 1024)
            assertEquals(CiphertextPutDisposition.ALREADY_PRESENT, Files.newInputStream(encrypted.path).use { t.remote().put(request, it) }.disposition)
            val incompleteOutput = ByteArrayOutputStream(); var interrupted = false
            val reader = t.remote("download") { stage, _ -> if (stage == "CIPHERTEXT_CHUNK_RETAINED" && !interrupted) {
                interrupted = true; throw IOException("SYNTHETIC_INTERRUPTED_DOWNLOAD")
            } }
            assertFailsWith<IOException> { reader.readTo(context.key, incompleteOutput, request.descriptor) }
            assertEquals(0, incompleteOutput.size())
            val output = ByteArrayOutputStream(); val offsets = mutableListOf<Long>()
            assertEquals(uploaded.metadata, t.remote("download", observe = { stage, offset -> if (stage == "CIPHERTEXT_CHUNK_RETAINED") offsets += offset })
                .readTo(context.key, output, request.descriptor))
            assertTrue(offsets.first() >= 2L * 1024 * 1024)
            t.f.cipher.decrypt(output.toByteArray().inputStream(), context, t.f.work).use { actual ->
                assertContentEquals(plain, Files.readAllBytes(actual.path))
            }
            assertEquals(uploaded.metadata, t.remote().metadata(context.key))
        }
    }

    @Test fun revokedTlsPeerReceivesOnlyItsOwnNoticeAndCannotUseCachedDataOrUploadWhileUnknownTlsPeersRefuse() = withFixture { t ->
        val bytes = ByteArray(113) { 23 }; val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random())
        val request = CiphertextPutRequest(key, SyncStoreId.random(), SyncKeyDeliveryCodec.descriptor(bytes))
        val remote = t.remote(); remote.put(request, bytes.inputStream()); remote.readTo(key, ByteArrayOutputStream())
        t.revoke(); val notice = requireNotNull(t.notice)
        assertEquals(listOf(CiphertextObjectMetadata(notice.request.key, notice.request.descriptor)), remote.listCommits().entries)
        assertEquals(CiphertextStoreFailure.PERMISSION, assertFailsWith<CiphertextStoreException> { remote.metadata(key) }.failure)
        val cached = ByteArrayOutputStream()
        assertEquals(CiphertextStoreFailure.PERMISSION, assertFailsWith<CiphertextStoreException> { remote.readTo(key, cached) }.failure)
        assertEquals(0, cached.size())
        assertEquals(CiphertextStoreFailure.PERMISSION, assertFailsWith<CiphertextStoreException> { remote.put(request, bytes.inputStream()) }.failure)
        val output = ByteArrayOutputStream(); remote.readTo(notice.request.key, output, notice.request.descriptor)
        val decoded = SyncLedgerNoticeCodec.decode(output.toByteArray())
        assertEquals(t.current.current().reference(), decoded.open(t.f.ledger.trustAnchor, t.f.recipients.getValue(t.f.sender.fingerprint), notice.request.key).records.last().reference())
        assertFails { t.remote("unknown", LanSyncTlsIdentity.create()).listCommits() }
        assertEquals(request.descriptor, t.f.backend.metadata(key)?.descriptor)
    }
}
