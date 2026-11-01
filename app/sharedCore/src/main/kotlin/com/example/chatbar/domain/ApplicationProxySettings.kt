package com.example.chatbar.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ApplicationProxyMode { SYSTEM, HTTP, SOCKS, DIRECT }

/** Application-owned routing preference. Contains no authentication or destination URL. */
@Serializable
data class ApplicationProxySettings(
    val mode: ApplicationProxyMode = ApplicationProxyMode.SYSTEM,
    val host: String = "127.0.0.1",
    val port: Int = 10809,
) {
    init {
        require(port in 1..65535)
        require(host.isNotBlank() && host == host.trim() && host.length <= 253 &&
            host.none { it.isWhitespace() || it in "/\\@?#" })
    }
}
