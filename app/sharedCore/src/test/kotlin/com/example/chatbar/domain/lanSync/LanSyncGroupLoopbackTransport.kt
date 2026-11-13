package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Synthetic cloud proof harness only; production LAN selection/pinning stays with its app owner. */
internal class LanSyncGroupLoopbackTransport(private val root: Path, private val owner: LanSyncTlsIdentity,
    private val ledger: SyncDeviceLedger, private val backend: CiphertextStore) : AutoCloseable {
    val uploadBytes = AtomicLong(); val downloadBytes = AtomicLong(); val maximumUploadResume = AtomicLong()
    private val lastUpload = ThreadLocal.withInitial { 0L }; private val lastDownload = ThreadLocal.withInitial { 0L }
    private val failures = Collections.synchronizedList(mutableListOf<Throwable>())
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>(); private val executor = Executors.newCachedThreadPool()
    private val listener = (owner.context { it in ledger.current().members }.serverSocketFactory
        .createServerSocket(0, 20, InetAddress.getLoopbackAddress()) as SSLServerSocket).also { it.needClientAuth = true }
    private val server = LanSyncGroupCiphertextServer(root.resolve("server"), { group, local, peer ->
        require(group == ledger.trustAnchor.group) { "SYNTHETIC_WIRE_GROUP" }
        LanSyncGroupPeerStore(ledger.trustAnchor, local, peer, { ledger }, backend, { null })
    }, { stage, offset -> when (stage) {
        "CIPHERTEXT_UPLOAD_RESUME" -> { lastUpload.set(offset); maximumUploadResume.accumulateAndGet(offset) { first, second -> maxOf(first, second) } }
        "CIPHERTEXT_CHUNK_RETAINED" -> { uploadBytes.addAndGet(offset - lastUpload.get()); lastUpload.set(offset) }
        "CIPHERTEXT_DOWNLOAD_RESUME" -> lastDownload.set(offset)
        "CIPHERTEXT_CHUNK_SENT" -> { downloadBytes.addAndGet(offset - lastDownload.get()); lastDownload.set(offset) }
        else -> Unit
    } })
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
    fun remote(identity: LanSyncTlsIdentity, name: String) = LanSyncGroupRemoteStore(ledger.trustAnchor.group, root.resolve(name), {
        identity.context { it == owner.fingerprint }.socketFactory.createSocket(InetAddress.getLoopbackAddress(), listener.localPort) as SSLSocket
    })
    override fun close() {
        listener.close(); sockets.forEach { it.close() }; executor.shutdownNow()
        check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "SYNTHETIC_WIRE_THREADS_REMAIN" }
        check(failures.isEmpty()) { "SYNTHETIC_WIRE_SERVER_FAILURE" }
    }
}
