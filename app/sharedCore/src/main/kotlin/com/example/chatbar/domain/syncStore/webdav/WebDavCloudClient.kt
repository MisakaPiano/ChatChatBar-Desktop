package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.ProxyAwareClient
import okhttp3.Dns
import okhttp3.OkHttpClient

/** Cloud-only DNS policy; proxy, platform sockets, TLS and cleartext policy stay app-owned. */
object WebDavCloudClient {
    fun builder(): OkHttpClient.Builder = ProxyAwareClient.builder().dns(Dns.SYSTEM)
    fun create(): OkHttpClient = builder().build()
}
