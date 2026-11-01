package com.example.chatbar.desktop

import com.example.chatbar.domain.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopNetworkProxyTest {
    @Test fun `saved proxy is outside Program and applies only after startup while other field owners survive`() = synchronized(ProxyAwareClient) {
        runBlocking {
            val root = Files.createTempDirectory("network-settings-")
            val prior = ProxyAwareClient.applicationProxy; val available = ProxyAwareClient.applicationRoutingAvailable
            val gate = DesktopDataOperationCoordinator()
            try {
                ProxyAwareClient.applicationProxy = ApplicationProxySettings()
                val store = DesktopSettingsStore(root, gate)
                store.updateLatest { it.copy(uiLanguage = DesktopUiLanguage.EN) }
                val controller = DesktopNetworkProxyController(store)
                controller.loadAtStartup()
                controller.save(ApplicationProxyMode.HTTP, "127.0.0.1", "10809")
                assertTrue(controller.state.value.restartRequired)
                assertEquals(ApplicationProxyMode.SYSTEM, ProxyAwareClient.applicationProxy.mode)
                DesktopUiLanguageController(store).select(DesktopUiLanguage.ZH_CN)
                // A fresh owner models a new packaged version using the same selected data root.
                val next = DesktopNetworkProxyController(DesktopSettingsStore(root, gate))
                next.loadAtStartup()
                assertEquals(ApplicationProxySettings(ApplicationProxyMode.HTTP, "127.0.0.1", 10809), ProxyAwareClient.applicationProxy)
                assertFalse(next.state.value.restartRequired)
                assertEquals(DesktopUiLanguage.ZH_CN, assertIs<DesktopSettingsLoadResult.Loaded>(store.load()).document.settings.uiLanguage)
                next.inspectRoute(); assertTrue(next.state.value.route!!.contains("10809")); assertTrue(next.state.value.route!!.contains("本地模型：直连"))
                val bytes = Files.readAllBytes(store.settingsPath)
                next.save(ApplicationProxyMode.SOCKS, "http://bad-host", "0")
                assertNotNull(next.state.value.error); assertContentEquals(bytes, Files.readAllBytes(store.settingsPath))
                assertFalse(Files.exists(root.resolve("Program")))
            } finally {
                ProxyAwareClient.applicationProxy = prior; ProxyAwareClient.applicationRoutingAvailable = available
                gate.closeAndDrain(); root.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `invalid persisted routing is visible and does not silently connect directly`() = synchronized(ProxyAwareClient) {
        runBlocking {
            val root = Files.createTempDirectory("network-invalid-"); val gate = DesktopDataOperationCoordinator()
            val prior = ProxyAwareClient.applicationProxy; val available = ProxyAwareClient.applicationRoutingAvailable
            try {
                Files.writeString(root.resolve("desktop-settings.json"), """{"formatVersion":1,"networkProxy":{"mode":"HTTP","host":"127.0.0.1","port":0}}""")
                val controller = DesktopNetworkProxyController(DesktopSettingsStore(root, gate))
                controller.loadAtStartup(); assertNotNull(controller.state.value.error)
                assertFalse(ProxyAwareClient.applicationRoutingAvailable)
            } finally {
                ProxyAwareClient.applicationProxy = prior; ProxyAwareClient.applicationRoutingAvailable = available
                gate.closeAndDrain(); root.toFile().deleteRecursively()
            }
        }
    }
}
