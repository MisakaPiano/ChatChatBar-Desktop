package com.example.chatbar.domain.lanSync

import java.io.Closeable
import java.net.InetAddress
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application lifetime transport. No listener or identity is created until explicit start. */
class LanSyncGroupAppTransport(private val access: LanSyncGroupRuntimeAccess,
    private val network: LanSyncNetwork, private val onFailure: (String, Exception) -> Unit) {
    private class Invitation(val owner: LanSyncGroupInvitation, val listener: SSLServerSocket) {
        val sockets = mutableSetOf<SSLSocket>()
        var timer: Job? = null
        var claimed = false
    }
    private val lock = Any()
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.IO)
    private val ordinary = mutableSetOf<SSLSocket>()
    private val clients = mutableSetOf<SSLSocket>()
    private var connecting = 0
    private var listener: SSLServerSocket? = null
    private var activeInvitation: Invitation? = null
    @Volatile private var closed = false
    private val server by lazy {
        LanSyncGroupCiphertextServer(access.work.resolve("server"), access::peerStore,
            configureAccepted = { socket -> socket.soTimeout = 15_000 })
    }

    /** Ordinary TLS admits only ledger-known certificates. A revoked peer gets its own notice only. */
    fun start(): Int = synchronized(lock) {
        check(!closed) { "SYNC_GROUP_TRANSPORT_CLOSED" }
        listener?.let { return@synchronized it.localPort }
        val created = access.tlsContext().serverSocketFactory.createServerSocket(0, 8) as SSLServerSocket
        try {
            created.needClientAuth = true
            require(created.localPort in 1024..65535) { "SYNC_GROUP_TRANSPORT_PORT" }
            listener = created
            scope.launch { acceptOrdinary(created) }
            created.localPort
        } catch (failure: Throwable) { created.close(); throw failure }
    }

    /** Separate invitation listener; possession proof never grants ordinary data authority. */
    fun invitation(owner: LanSyncGroupInvitation, onPeer: (LanSyncGroupInvitedPeer) -> Unit): LanSyncGroupInviteOffer {
        val host = network.addresses().firstOrNull() ?: error("SYNC_GROUP_LAN_ADDRESS_UNAVAILABLE")
        // Validate through the production numeric-LAN parser even when a platform supplies addresses.
        LanSyncTls.parseAddress(host)
        return synchronized(lock) {
            check(!closed && listener != null) { "SYNC_GROUP_TRANSPORT_NOT_STARTED" }
            closeInvitationOwned()
            val created = owner.invitationContext().serverSocketFactory.createServerSocket(0, 2) as SSLServerSocket
            try {
                created.needClientAuth = true
                val offer = owner.issue(host, created.localPort)
                val current = Invitation(owner, created)
                activeInvitation = current
                current.timer = scope.launch {
                    delay(LanSyncGroupInviteOffer.LIFETIME_MILLIS)
                    synchronized(lock) { if (activeInvitation === current) closeInvitationOwned() }
                }
                scope.launch { acceptInvitation(current, onPeer) }
                offer
            } catch (failure: Throwable) { created.close(); owner.close(); throw failure }
        }
    }

    fun closeInvitation() = synchronized(lock) { closeInvitationOwned() }

    private fun closeInvitationOwned() {
        val current = activeInvitation ?: return
        activeInvitation = null
        current.owner.close()
        current.timer?.cancel()
        current.listener.closeQuietly()
        current.sockets.toList().forEach { it.closeQuietly() }
        current.sockets.clear()
    }

    private fun acceptOrdinary(bound: SSLServerSocket) {
        try {
            while (!closed && !bound.isClosed) {
                val socket = bound.accept() as SSLSocket
                val accepted = try { synchronized(lock) {
                    if (closed || listener !== bound || !network.accepts(socket.inetAddress) || ordinary.size >= 4) false
                    else { ordinary += socket; true }
                } } catch (failure: Exception) { socket.closeQuietly(); throw failure }
                if (!accepted) { socket.closeQuietly(); continue }
                scope.launch {
                    try { server.serve(socket) }
                    catch (failure: Exception) { if (!closed) onFailure("NEARBY_SERVER", failure) }
                    finally { socket.closeQuietly(); synchronized(lock) { ordinary -= socket } }
                }
            }
        } catch (failure: Exception) {
            if (!closed && !bound.isClosed) onFailure("NEARBY_LISTENER", failure)
        }
    }

    private fun acceptInvitation(current: Invitation, onPeer: (LanSyncGroupInvitedPeer) -> Unit) {
        try {
            while (!closed && !current.listener.isClosed) {
                val socket = current.listener.accept() as SSLSocket
                val accepted = try { synchronized(lock) {
                    if (closed || activeInvitation !== current || current.claimed ||
                        !network.accepts(socket.inetAddress) || current.sockets.size >= 2) false
                    else { current.sockets += socket; true }
                } } catch (failure: Exception) { socket.closeQuietly(); throw failure }
                if (!accepted) { socket.closeQuietly(); continue }
                scope.launch {
                    try {
                        val peer = current.owner.serve(socket)
                        val deliver = synchronized(lock) {
                            if (closed || activeInvitation !== current || current.claimed) false
                            else {
                                current.claimed = true
                                current.listener.closeQuietly()
                                current.sockets.filter { it !== socket }.forEach { it.closeQuietly() }
                                true
                            }
                        }
                        // Retain the owner until approval/expiry: root admission rechecks this exact proof.
                        if (deliver) onPeer(peer)
                    } catch (failure: Exception) {
                        if (!closed && synchronized(lock) { activeInvitation === current }) onFailure("INVITATION", failure)
                    } finally { socket.closeQuietly(); synchronized(lock) { current.sockets -= socket } }
                }
            }
        } catch (failure: Exception) {
            if (!closed && !current.listener.isClosed) onFailure("INVITATION_LISTENER", failure)
        }
    }

    /** The same authenticated connect path, with every returned socket owned until close. */
    fun remote(peer: LanSyncPeer): LanSyncGroupRemoteStore {
        synchronized(lock) { check(!closed) { "SYNC_GROUP_TRANSPORT_CLOSED" } }
        val tracked = object : LanSyncNetwork {
            override fun addresses() = network.addresses()
            override fun accepts(address: InetAddress) = network.accepts(address)
            override val discoveryEnabled get() = network.discoveryEnabled
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket {
                synchronized(lock) {
                    clients.removeAll { it.isClosed }
                    check(!closed) { "SYNC_GROUP_TRANSPORT_CLOSED" }
                    require(clients.size + connecting < 4) { "SYNC_GROUP_TRANSPORT_CONNECTION_LIMIT" }
                    connecting++
                }
                var created: SSLSocket? = null
                try {
                    val socket = network.connect(identity, peer)
                    created = socket
                    synchronized(lock) {
                        check(!closed) { "SYNC_GROUP_TRANSPORT_CLOSED" }
                        clients += socket
                    }
                    return socket
                } catch (failure: Throwable) { created?.closeQuietly(); throw failure }
                finally { synchronized(lock) { connecting-- } }
            }
        }
        return access.remote(peer, tracked)
    }

    /** Stops admission, closes owned I/O, then waits for workers and an already-started connect. */
    suspend fun closeAndDrain() = withContext(NonCancellable) {
        synchronized(lock) {
            closed = true
            closeInvitationOwned()
            listener?.closeQuietly(); listener = null
            ordinary.toList().forEach { it.closeQuietly() }
            clients.toList().forEach { it.closeQuietly() }
        }
        lifetime.cancelAndJoin()
        // LanSyncNetwork.connect authenticates before returning. A connect already in progress uses
        // its production TCP/handshake timeout; a late result is closed before it can be handed out.
        while (synchronized(lock) { connecting != 0 }) delay(10)
        synchronized(lock) { ordinary.clear(); clients.clear() }
    }

    private fun Closeable.closeQuietly() { try { close() } catch (_: Exception) { } }
}
