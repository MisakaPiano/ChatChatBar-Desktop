package com.example.chatbar.domain.lanSync

import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
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
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Application lifetime transport. No listener or identity is created until explicit start. */
class LanSyncGroupAppTransport(private val access: LanSyncGroupRuntimeAccess,
    private val network: LanSyncNetwork, private val onFailure: (String, Exception) -> Unit) {
    private class Invitation(val owner: LanSyncGroupInvitation, val listener: SSLServerSocket) {
        val sockets = mutableSetOf<SSLSocket>()
        var timer: Job? = null
        var claimedDevice: String? = null
    }
    private val lock = Any()
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.IO)
    private val ordinary = mutableSetOf<SSLSocket>()
    private val clients = mutableSetOf<Socket>()
    private val activeClients = mutableSetOf<SSLSocket>()
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
    internal fun invitationAddresses(): List<String> {
        val eligible = network.addresses().distinct().filter { runCatching { LanSyncTls.parseAddress(it) }.isSuccess }.sorted()
        val preferred = network.preferredInvitationAddress(eligible)?.takeIf { it in eligible }
        return listOfNotNull(preferred) + eligible.filterNot { it == preferred }
    }

    fun invitation(owner: LanSyncGroupInvitation, onExpired: () -> Unit = {},
        selectedHost: String? = null, forFile: Boolean = false,
        onPeer: (LanSyncGroupInvitedPeer) -> Unit): LanSyncGroupInviteOffer {
        val addresses = invitationAddresses()
        val host = selectedHost ?: addresses.firstOrNull() ?: error("SYNC_GROUP_LAN_ADDRESS_UNAVAILABLE")
        require(host in addresses) { "SYNC_GROUP_LAN_ADDRESS_CHANGED" }
        return synchronized(lock) {
            check(!closed && listener != null) { "SYNC_GROUP_TRANSPORT_NOT_STARTED" }
            closeInvitationOwned()
            val created = owner.invitationContext().serverSocketFactory.createServerSocket(0, 2) as SSLServerSocket
            try {
                created.needClientAuth = true
                val offer = owner.issue(host, created.localPort, forFile)
                val current = Invitation(owner, created)
                activeInvitation = current
                current.timer = scope.launch {
                    delay(LanSyncGroupInviteOffer.LIFETIME_MILLIS)
                    val expired = synchronized(lock) {
                        if (activeInvitation !== current) false
                        else { closeInvitationOwned(); true }
                    }
                    if (expired) onExpired()
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
                    catch (failure: Exception) {
                        if (!closed) {
                            val pending = synchronized(lock) { activeInvitation?.claimedDevice }
                            val refusedPending = pending != null && generateSequence<Throwable>(failure) { it.cause }
                                .take(8).filterIsInstance<LanSyncUntrustedDevice>().any { it.fingerprint == pending }
                            onFailure(if (refusedPending) "NEARBY_PENDING_APPROVAL" else "NEARBY_SERVER", failure)
                        }
                    }
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
                    if (closed || activeInvitation !== current || current.claimedDevice != null ||
                        !network.accepts(socket.inetAddress) || current.sockets.size >= 2) false
                    else { current.sockets += socket; true }
                } } catch (failure: Exception) { socket.closeQuietly(); throw failure }
                if (!accepted) { socket.closeQuietly(); continue }
                scope.launch {
                    try {
                        val peer = current.owner.serve(socket)
                        val deliver = synchronized(lock) {
                            if (closed || activeInvitation !== current || current.claimedDevice != null) false
                            else {
                                current.claimedDevice = peer.device
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
            override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket =
                connectTracked(identity, peer, network::connectOwned)
        }
        return access.remote(peer, tracked)
    }

    /** UDP hints can only enter this control connection. They never construct a data route. */
    internal suspend fun catchUpLedger(peer: LanSyncPeer): Boolean {
        val sockets = mutableSetOf<Socket>()
        var expired = false
        val timeout = scope.launch {
            delay(LEDGER_EXCHANGE_TIMEOUT)
            val pending = synchronized(sockets) { expired = true; sockets.toList() }
            pending.forEach { it.closeQuietly() }
        }
        try {
            return runInterruptible(Dispatchers.IO) {
                access.catchUpLedger(peer) { identity, destination ->
                    connectTracked(identity, destination) { local, hint, own ->
                        network.connectLedgerOwned(local, hint) { socket ->
                            own(socket)
                            synchronized(sockets) {
                                check(!expired) { "SYNC_GROUP_LEDGER_RELAY_TIMEOUT" }
                                sockets += socket
                            }
                        }
                    }
                }
            }
        } finally { timeout.cancel() }
    }

    private fun connectTracked(identity: LanSyncTlsIdentity, peer: LanSyncPeer,
        connect: (LanSyncTlsIdentity, LanSyncPeer, (Socket) -> Unit) -> SSLSocket): SSLSocket {
        synchronized(lock) {
            clients.removeAll { it.isClosed }
            activeClients.removeAll { it.isClosed }
            check(!closed) { "SYNC_GROUP_TRANSPORT_CLOSED" }
            require(activeClients.size + connecting < 4) { "SYNC_GROUP_TRANSPORT_CONNECTION_LIMIT" }
            connecting++
        }
        val owned = mutableSetOf<Socket>()
        val own: (Socket) -> Unit = { socket ->
            try { synchronized(lock) {
                check(!closed) { "SYNC_GROUP_TRANSPORT_CLOSED" }
                clients.removeAll { it.isClosed }
                require(!socket.isClosed && (socket in owned || owned.size < 2) &&
                    (socket in clients || clients.size < 8)) { "SYNC_GROUP_TRANSPORT_CONNECTION_LIMIT" }
                owned += socket; clients += socket
            } } catch (failure: Throwable) { socket.closeQuietly(); throw failure }
        }
        var created: SSLSocket? = null
        var returned = false
        try {
            val socket = connect(identity, peer, own)
            created = socket
            own(socket)
            synchronized(lock) {
                check(!closed) { "SYNC_GROUP_TRANSPORT_CLOSED" }
                activeClients += socket
            }
            returned = true
            return socket
        } catch (failure: Throwable) {
            synchronized(lock) { owned.toList() }.forEach { it.closeQuietly() }
            created?.closeQuietly(); throw failure
        } finally { synchronized(lock) {
            connecting--
            if (!returned) { clients.removeAll(owned); activeClients.removeAll { it in owned } }
        } }
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
        // Production Local registers plain/TLS sockets before blocking I/O, so closing them aborts
        // an in-flight dial/handshake. Legacy custom adapters may notify only after bounded connect.
        while (synchronized(lock) { connecting != 0 }) delay(10)
        synchronized(lock) { ordinary.clear(); clients.clear(); activeClients.clear() }
    }

    private fun Closeable.closeQuietly() { try { close() } catch (_: Exception) { } }

    private companion object { const val LEDGER_EXCHANGE_TIMEOUT = 10_000L }
}
