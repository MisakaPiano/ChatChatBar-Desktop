package com.example.chatbar.domain.lanSync

import java.net.*
import java.security.SecureRandom

/** Discovery is an untrusted address hint. The subsequent pinned mutual TLS is mandatory. */
internal object LanSyncDiscovery {
    const val PORT = 39237
    fun listen(fingerprint: String, port: Int): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true; bind(InetSocketAddress(PORT)); soTimeout = 1000
    }
    fun respond(socket: DatagramSocket, fingerprint: String, port: Int) {
        val packet = DatagramPacket(ByteArray(512), 512)
        try { socket.receive(packet) } catch (_: SocketTimeoutException) { return }
        if (!packet.address.isSiteLocalAddress || packet.address.isLoopbackAddress) return
        val request = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).split(' ')
        if (request.size != 3 || request[0] != "CCB-LAN-DISCOVER-1" || request[1] != fingerprint || !request[2].matches(Regex("[0-9a-f]{32}"))) return
        val reply = "CCB-LAN-HERE-1 $fingerprint $port ${request[2]}".toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
    }
    fun find(fingerprint: String): Pair<String, Int>? {
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        DatagramSocket().use { socket ->
            socket.broadcast = true; socket.soTimeout = 750
            val data = "CCB-LAN-DISCOVER-1 $fingerprint $nonce".toByteArray(Charsets.US_ASCII)
            val broadcasts = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.distinct() + InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
            broadcasts.forEach { address -> runCatching { socket.send(DatagramPacket(data, data.size, address, PORT)) } }
            val deadline = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < deadline) {
                val packet = DatagramPacket(ByteArray(512), 512)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val response = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).split(' ')
                if (response.size == 4 && response[0] == "CCB-LAN-HERE-1" && response[1] == fingerprint && response[3] == nonce) {
                    val port = response[2].toIntOrNull() ?: continue
                    val host = packet.address.hostAddress ?: continue
                    if (port in 1024..65535 && runCatching { LanSyncTls.parseAddress(host) }.isSuccess) return host to port
                }
            }
        }
        return null
    }
}
