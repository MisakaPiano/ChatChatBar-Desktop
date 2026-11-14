package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncStoreId
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom

/** Address hints only. Every returned route still requires the real pinned mutual TLS handshake. */
class LanSyncGroupDiscovery private constructor(private val group: SyncStoreId, private val device: String,
    private val network: LanSyncNetwork, private val onFailure: (String, Exception) -> Unit) : AutoCloseable {
    constructor(access: LanSyncGroupRuntimeAccess, network: LanSyncNetwork,
        onFailure: (String, Exception) -> Unit) : this(access.trust.group, access.device, network, onFailure)

    private val lock = Any()
    private var responder: DatagramSocket? = null
    private var responderThread: Thread? = null
    private var advertisedPort: Int? = null
    private val searches = linkedSetOf<DatagramSocket>()
    @Volatile private var closed = false

    fun start(port: Int) = synchronized(lock) {
        check(!closed) { "SYNC_GROUP_DISCOVERY_CLOSED" }
        require(port in 1024..65535) { "SYNC_GROUP_DISCOVERY_PORT" }
        require(device.matches(LanSyncGroupDiscoveryProtocol.FINGERPRINT)) { "SYNC_GROUP_DISCOVERY_DEVICE" }
        advertisedPort?.let { require(it == port) { "SYNC_GROUP_DISCOVERY_PORT_CHANGED" }; return@synchronized }
        if (!network.discoveryEnabled) return@synchronized
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(PORT))
            socket.soTimeout = 750
            responder = socket; advertisedPort = port
            responderThread = Thread({ listen(socket, port) }, "ccb-group-discovery").apply {
                isDaemon = true
                start()
            }
        } catch (failure: Exception) {
            socket.close(); responder = null; advertisedPort = null
            throw failure
        }
    }

    private fun listen(socket: DatagramSocket, port: Int) {
        try {
            while (!socket.isClosed) {
                // One extra byte makes an overlong datagram distinguishable from a valid bounded frame.
                val packet = DatagramPacket(ByteArray(LanSyncGroupDiscoveryProtocol.MAX_BYTES + 1),
                    LanSyncGroupDiscoveryProtocol.MAX_BYTES + 1)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                if (!network.accepts(packet.address)) continue
                val request = LanSyncGroupDiscoveryProtocol.request(packet.data, packet.offset, packet.length) ?: continue
                if (request.group != group || request.device != device) continue
                val reply = LanSyncGroupDiscoveryProtocol.reply(group, device, port, request.nonce)
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        } catch (failure: Exception) {
            if (!closed && !socket.isClosed) onFailure("GROUP_DISCOVERY_LISTENER", failure)
        } finally {
            socket.close()
            synchronized(lock) {
                if (responder === socket) {
                    responder = null; advertisedPort = null
                    if (responderThread === Thread.currentThread()) responderThread = null
                }
            }
        }
    }

    /** A bounded search never reads a host string supplied in the UDP body or the legacy namespace. */
    fun find(fingerprint: String): LanSyncPeer? {
        require(fingerprint.matches(LanSyncGroupDiscoveryProtocol.FINGERPRINT)) { "SYNC_GROUP_DISCOVERY_DEVICE" }
        check(!closed) { "SYNC_GROUP_DISCOVERY_CLOSED" }
        if (!network.discoveryEnabled || fingerprint == device) return null
        val nonce = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val bytes = LanSyncGroupDiscoveryProtocol.request(group, fingerprint, nonce)
        val socket = DatagramSocket()
        synchronized(lock) {
            if (closed) { socket.close(); error("SYNC_GROUP_DISCOVERY_CLOSED") }
            if (searches.size >= 4) { socket.close(); error("SYNC_GROUP_DISCOVERY_SEARCH_LIMIT") }
            searches += socket
        }
        try {
            socket.broadcast = true; socket.soTimeout = 250
            val broadcasts = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }.flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }.distinct() + InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
            var sent = false
            for (address in broadcasts) {
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, address, PORT)); sent = true
                } catch (failure: Exception) {
                    if (closed) return null
                    onFailure("GROUP_DISCOVERY_SEND", failure)
                }
            }
            if (!sent) return null
            val deadline = System.nanoTime() + 1_500_000_000L
            while (!closed && System.nanoTime() < deadline) {
                val packet = DatagramPacket(ByteArray(LanSyncGroupDiscoveryProtocol.MAX_BYTES + 1),
                    LanSyncGroupDiscoveryProtocol.MAX_BYTES + 1)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                if (!network.accepts(packet.address)) continue
                val reply = LanSyncGroupDiscoveryProtocol.reply(packet.data, packet.offset, packet.length) ?: continue
                if (reply.group != group || reply.device != fingerprint || reply.nonce != nonce) continue
                val host = packet.address.hostAddress ?: continue
                // Numeric site-local validation remains independent of the discovery packet body.
                if (runCatching { LanSyncTls.parseAddress(host) }.isFailure) continue
                return LanSyncPeer(fingerprint, "同网设备", host, reply.port)
            }
            return null
        } catch (failure: SocketException) {
            if (!closed) throw failure
            return null
        } finally {
            socket.close()
            synchronized(lock) { searches -= socket }
        }
    }

    override fun close() {
        val thread = synchronized(lock) {
            closed = true
            responder?.close(); searches.forEach(DatagramSocket::close)
            responderThread
        }
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(2_500)
            check(!thread.isAlive) { "SYNC_GROUP_DISCOVERY_DRAIN_TIMEOUT" }
        }
    }

    companion object { const val PORT = 39238 }
}

/** Strict fixed-shape ASCII; no names, addresses, capabilities or mutable state travel in discovery. */
internal object LanSyncGroupDiscoveryProtocol {
    const val MAX_BYTES = 384
    val FINGERPRINT = Regex("[a-f0-9]{64}")
    private val NONCE = FINGERPRINT
    data class Request(val group: SyncStoreId, val device: String, val nonce: String)
    data class Reply(val group: SyncStoreId, val device: String, val port: Int, val nonce: String)

    fun request(group: SyncStoreId, device: String, nonce: String): ByteArray {
        require(device.matches(FINGERPRINT) && nonce.matches(NONCE)) { "SYNC_GROUP_DISCOVERY_FIELDS" }
        return "CCB-GROUP-FIND-1 ${group.value} $device $nonce".toByteArray(Charsets.US_ASCII)
    }
    fun reply(group: SyncStoreId, device: String, port: Int, nonce: String): ByteArray {
        require(device.matches(FINGERPRINT) && nonce.matches(NONCE) && port in 1024..65535) { "SYNC_GROUP_DISCOVERY_FIELDS" }
        return "CCB-GROUP-HERE-1 ${group.value} $device $port $nonce".toByteArray(Charsets.US_ASCII)
    }
    fun request(bytes: ByteArray, offset: Int, length: Int): Request? {
        val fields = fields(bytes, offset, length) ?: return null
        if (fields.size != 4 || fields[0] != "CCB-GROUP-FIND-1" || !fields[2].matches(FINGERPRINT) || !fields[3].matches(NONCE)) return null
        val group = runCatching { SyncStoreId.parse(fields[1]) }.getOrNull() ?: return null
        return Request(group, fields[2], fields[3])
    }
    fun reply(bytes: ByteArray, offset: Int, length: Int): Reply? {
        val fields = fields(bytes, offset, length) ?: return null
        if (fields.size != 5 || fields[0] != "CCB-GROUP-HERE-1" || !fields[2].matches(FINGERPRINT) || !fields[4].matches(NONCE)) return null
        val group = runCatching { SyncStoreId.parse(fields[1]) }.getOrNull() ?: return null
        val port = fields[3].toIntOrNull()?.takeIf { it in 1024..65535 && it.toString() == fields[3] } ?: return null
        return Reply(group, fields[2], port, fields[4])
    }
    private fun fields(bytes: ByteArray, offset: Int, length: Int): List<String>? {
        if (offset < 0 || length !in 1..MAX_BYTES || offset > bytes.size - length) return null
        if ((offset until offset + length).any { bytes[it].toInt() !in 32..126 }) return null
        return String(bytes, offset, length, Charsets.US_ASCII).split(' ')
    }
}
