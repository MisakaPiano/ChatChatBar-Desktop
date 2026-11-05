package com.example.chatbar.desktop

import com.example.chatbar.domain.ProxyAwareClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/** OkHttp already selected HTTP/DIRECT; its explicit SOCKS path creates Socket(proxy) itself. */
internal object DesktopSelectedRouteSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    private fun connected(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(remote)
            return socket
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { }
            throw error
        }
    }

    override fun createSocket(host: String?, port: Int): Socket =
        connected(InetSocketAddress(InetAddress.getByName(host), port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        connected(InetSocketAddress(InetAddress.getByName(host), port), InetSocketAddress(localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket =
        connected(InetSocketAddress(host ?: InetAddress.getLoopbackAddress(), port))
    override fun createSocket(host: InetAddress?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        connected(InetSocketAddress(host ?: InetAddress.getLoopbackAddress(), port), InetSocketAddress(localHost, localPort))
}

internal fun configureDesktopNetworkTransport() {
    ProxyAwareClient.platformSocketFactory = DesktopSelectedRouteSocketFactory
}
