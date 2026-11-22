package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncStoreId
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/** Platform network boundary; test fixtures may provide a synthetic loopback LAN. */
interface LanSyncNetwork {
    fun addresses(): List<String>
    fun preferredInvitationAddress(eligible: List<String>): String? = null
    fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket
    /** Legacy fixtures may notify only after connect; production Local owns sockets before blocking I/O. */
    fun connectOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit): SSLSocket {
        val socket = connect(identity, peer)
        try { own(socket); return socket }
        catch (failure: Throwable) {
            try { socket.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    fun connectInvitationOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit): SSLSocket =
        connectOwned(identity, peer, own)
    fun connectBootstrapOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit): SSLSocket =
        connectOwned(identity, peer, own)
    fun accepts(address: InetAddress): Boolean
    fun listening(fingerprint: String, port: Int) {}
    fun discover(fingerprint: String): Pair<String, Int>? = LanSyncDiscovery.find(fingerprint)
    fun discoverGroup(group: SyncStoreId, local: String, fingerprint: String): LanSyncPeer? =
        LanSyncGroupDiscovery(group, local, this) { _, _ -> }.use { it.find(fingerprint) }
    val discoveryEnabled: Boolean
    object Local : LanSyncNetwork {
        override fun addresses() = LanSyncTls.localAddresses()
        override fun preferredInvitationAddress(eligible: List<String>): String? = runCatching {
            // UDP connect selects the OS source address without sending a packet.
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByAddress(byteArrayOf(-64, 0, 2, 1)), 9)
                socket.localAddress.hostAddress.takeIf { it in eligible }
            }
        }.getOrNull()
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer) = LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint)
        override fun connectOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit) =
            LanSyncTls.connectOwned(identity, peer.host, peer.port, peer.fingerprint, own = own)
        override fun connectInvitationOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit) =
            LanSyncTls.connectOwned(identity, peer.host, peer.port, peer.fingerprint, handshakeTimeoutMillis = 15_000, own = own)
        override fun connectBootstrapOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (Socket) -> Unit) =
            LanSyncTls.connectOwned(identity, peer.host, peer.port, peer.fingerprint, handshakeTimeoutMillis = 15_000, own = own)
        override fun accepts(address: InetAddress) = address.isSiteLocalAddress && !address.isLoopbackAddress
        override val discoveryEnabled = true
    }
}
