package com.example.chatbar.desktop

import com.example.chatbar.domain.ApplicationProxyMode
import com.example.chatbar.domain.ApplicationProxySettings
import com.example.chatbar.domain.ProxyAwareClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopNetworkProxyState(
    val saved: ApplicationProxySettings = ApplicationProxySettings(),
    val restartRequired: Boolean = false,
    val error: String? = null,
    val route: String? = null,
    val checkingRoute: Boolean = false,
)

/** Saves outside versioned Program; only production startup installs the process routing policy. */
internal class DesktopNetworkProxyController(private val store: DesktopSettingsStore) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DesktopNetworkProxyState())
    val state = mutableState.asStateFlow()
    private val routeBusy = java.util.concurrent.atomic.AtomicBoolean()

    suspend fun loadAtStartup() = mutex.withLock {
        when (val result = store.load()) {
            is DesktopSettingsLoadResult.Loaded -> install(result.document.settings.networkProxy)
            is DesktopSettingsLoadResult.Missing -> install(result.document.settings.networkProxy)
            is DesktopSettingsLoadResult.Failure -> {
                ProxyAwareClient.applicationRoutingAvailable = false
                mutableState.value = mutableState.value.copy(error = "网络设置未能读取，联网已暂停；请检查资料目录中的设置文件。")
            }
        }
    }
    private fun install(settings: ApplicationProxySettings) {
        ProxyAwareClient.applicationProxy = settings
        ProxyAwareClient.applicationRoutingAvailable = true
        mutableState.value = DesktopNetworkProxyState(saved = settings)
    }

    suspend fun save(mode: ApplicationProxyMode, host: String, port: String) = mutex.withLock {
        try {
            val previous = state.value.saved
            val manual = mode == ApplicationProxyMode.HTTP || mode == ApplicationProxyMode.SOCKS
            val settings = if (manual) ApplicationProxySettings(mode, host.trim(), requireNotNull(port.toIntOrNull()))
                else previous.copy(mode = mode)
            store.updateLatest { it.copy(networkProxy = settings) }
            mutableState.value = mutableState.value.copy(saved = settings,
                restartRequired = settings != ProxyAwareClient.applicationProxy || !ProxyAwareClient.applicationRoutingAvailable,
                error = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "未保存。请填写有效的代理主机和 1–65535 端口，并检查设置文件是否可写。") }
    }

    /** No provider request. System PAC/WPAD resolution itself may contact configuration services. */
    suspend fun inspectRoute() {
        if (!routeBusy.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(checkingRoute = true)
        val client = ProxyAwareClient.builder().build()
        try {
            val description = withContext(Dispatchers.IO) {
                check(ProxyAwareClient.applicationRoutingAvailable)
                fun route(url: String): String = client.proxySelector.select(URI(url)).joinToString { proxy ->
                if (proxy.type() == Proxy.Type.DIRECT) "直连" else {
                    val address = proxy.address() as? InetSocketAddress
                    "${proxy.type()} ${address?.hostString ?: "未知主机"}:${address?.port ?: 0}"
                }
            }
                "NovelAI 账户／生图：${route("https://image.novelai.net/user/subscription")}；辅助 API：${route("https://api.novelai.net/")}；本地模型：${route("http://127.0.0.1:8200/")}。未向服务发送账户或生成请求；系统可能读取自动代理配置。路由结果不代表连接成功。"
            }
            mutableState.value = mutableState.value.copy(route = description, error = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "当前代理路由读取失败；未改用直连，请检查设置后重启。") }
        finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            mutableState.value = mutableState.value.copy(checkingRoute = false)
            routeBusy.set(false)
        }
    }
}
