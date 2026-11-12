package com.example.chatbar.domain.lanSync

import java.net.InetAddress
import javax.net.ssl.SSLSocket

/** Platform network boundary; test fixtures may provide a synthetic loopback LAN. */
interface LanSyncNetwork {
    fun addresses(): List<String>
    fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): SSLSocket
    fun accepts(address: InetAddress): Boolean
    fun listening(fingerprint: String, port: Int) {}
    fun discover(fingerprint: String): Pair<String, Int>? = LanSyncDiscovery.find(fingerprint)
    val discoveryEnabled: Boolean
    object Local : LanSyncNetwork {
        override fun addresses() = LanSyncTls.localAddresses()
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer) = LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint)
        override fun accepts(address: InetAddress) = address.isSiteLocalAddress && !address.isLoopbackAddress
        override val discoveryEnabled = true
    }
}
