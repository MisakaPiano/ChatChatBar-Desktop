package com.example.chatbar.desktop

import com.example.chatbar.domain.*
import com.example.chatbar.domain.image.NovelAiAccountService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Fresh JVMs distinguish the chosen OkHttp route from the socket that actually receives bytes. */
class DesktopProxyPhysicalRoutingTest {
    @Test fun `saved application HTTP reaches its CONNECT port despite JVM and socket selector SOCKS`() = verify("HTTP")
    @Test fun `saved application SOCKS reaches its own SOCKS port`() = verify("SOCKS")
    @Test fun `SYSTEM retains its selected HTTP CONNECT proxy`() = verify("SYSTEM")
    @Test fun `DIRECT ignores JVM proxy in the physical socket`() = verify("DIRECT")
    @Test fun `loopback bypass stays direct with an application HTTP proxy`() = verify("LOCAL")
    @Test fun `update checker HTTP sends CONNECT despite JVM socket SOCKS`() = verify("HTTP", true)
    @Test fun `update checker explicit SOCKS sends SOCKS only to its selected port`() = verify("SOCKS", true)
    @Test fun `update checker SYSTEM keeps the selected HTTP protocol`() = verify("SYSTEM", true)
    @Test fun `update checker DIRECT ignores JVM socket SOCKS`() = verify("DIRECT", true)
    @Test fun `update checker loopback bypass stays direct`() = verify("LOCAL", true)
    @Test fun `update checker reads newly saved HTTP route on its next request`() = verify("HOT", true)

    private fun verify(mode: String, update: Boolean = false) {
        val root = Files.createTempDirectory("proxy-physical-$mode-")
        RecordingEndpoint().use { system -> RecordingEndpoint().use { application -> RecordingEndpoint(success = true).use { target ->
            val image = System.getenv("CCB_PROXY_PRODUCTION_IMAGE")?.takeIf(String::isNotBlank)?.let { Path.of(it) }
            val fixture = Path.of(DesktopProxyPhysicalRoutingProbe::class.java.protectionDomain.codeSource.location.toURI())
            val classpath = if (image == null) requireNotNull(System.getProperty("ccb.readonly.classpath")) else {
                val jars = Files.list(image.resolve("app")).use { files -> files.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList() }
                check(jars.isNotEmpty())
                (jars + listOf(fixture)).joinToString(System.getProperty("path.separator"))
            }
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val log = root.resolve("child.log")
            val command = mutableListOf(java.toString(), "-Xmx128m", "-Djava.net.useSystemProxies=true",
                "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=${system.port}",
                "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=${system.port}",
                "-DsocksProxyHost=127.0.0.1", "-DsocksProxyPort=${system.port}", "-cp", classpath,
                DesktopProxyPhysicalRoutingProbe::class.java.name, mode, root.toString(), system.port.toString(), application.port.toString(), target.port.toString(),
                if (update) "UPDATE" else "NAI")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Fresh proxy probe did not drain")
                assertEquals(0, process.exitValue(), Files.readString(log))
                val result = Json.parseToJsonElement(Files.readString(root.resolve("result.json"))).jsonObject
                assertTrue(result.getValue("startupLoadedSameRoot").jsonPrimitive.boolean)
                val selectedPort = result.getValue("selectedProxyPort").jsonPrimitive.int
                println("CCB_PROXY_PHYSICAL_RECORD " + buildJsonObject {
                    put("mode", mode); put("update", update); put("selectedPort", selectedPort)
                    put("systemPort", system.port); put("applicationPort", application.port); put("targetPort", target.port)
                    putJsonArray("systemKinds") { system.kinds().forEach { add(it) } }
                    putJsonArray("applicationKinds") { application.kinds().forEach { add(it) } }
                    putJsonArray("targetKinds") { target.kinds().forEach { add(it) } }
                })
                assertEquals(when (mode) { "HTTP", "SOCKS", "HOT" -> application.port; "SYSTEM" -> system.port; else -> 0 }, selectedPort)
                if (image != null) {
                    assertTrue(Path.of(URI(result.getValue("proxyClassSource").jsonPrimitive.content)).startsWith(image))
                    assertTrue(Path.of(URI(result.getValue("mainClassSource").jsonPrimitive.content)).startsWith(image))
                    if (update) assertTrue(Path.of(URI(result.getValue("updateClassSource").jsonPrimitive.content)).startsWith(image))
                }
                if (update) {
                    val routes = result.getValue("signedRoutes").jsonObject
                    assertEquals(DesktopUpdateNetworkRouting.signedHosts.toSet(), routes.keys)
                    for (route in routes.values.map { it.jsonObject }) {
                        val kind = if (mode == "DIRECT") "DIRECT" else if (mode == "SOCKS") "SOCKS" else "HTTP"
                        assertEquals(kind, route.getValue("mode").jsonPrimitive.content)
                        if (kind != "DIRECT") assertEquals(if (mode in listOf("SYSTEM", "HOT")) system.port else application.port,
                            route.getValue("port").jsonPrimitive.int)
                    }
                }
                when (mode) {
                    "HTTP" -> { assertEquals(listOf("CONNECT"), application.kinds()); assertTrue(system.kinds().isEmpty()) }
                    "SOCKS" -> { assertEquals(listOf("SOCKS"), application.kinds()); assertTrue(system.kinds().isEmpty()) }
                    "SYSTEM" -> { assertEquals(listOf("CONNECT"), system.kinds()); assertTrue(application.kinds().isEmpty()) }
                    "HOT" -> { assertEquals(listOf("CONNECT"), application.kinds()); assertEquals(listOf("CONNECT"), system.kinds()); assertTrue(target.kinds().isEmpty()) }
                    else -> { assertEquals(listOf("GET"), target.kinds()); assertTrue(system.kinds().isEmpty()); assertTrue(application.kinds().isEmpty()) }
                }
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                root.toFile().deleteRecursively()
            }
        } } }
    }
}

private class RecordingEndpoint(private val success: Boolean = false) : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "synthetic-proxy-recorder").apply { isDaemon = true } }
    init {
        worker.submit {
            try {
                while (!listener.isClosed) listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val first = socket.getInputStream().read()
                    val kind = when (first) { 5 -> "SOCKS"; 'C'.code -> "CONNECT"; 'G'.code -> "GET"; else -> "OTHER" }
                    seen.add(kind)
                    if (kind == "SOCKS") {
                        val methods = socket.getInputStream().read()
                        repeat(methods.coerceIn(0, 255)) { socket.getInputStream().read() }
                    } else {
                        val header = StringBuilder(first.toChar().toString())
                        while (!header.endsWith("\r\n\r\n") && header.length < 16_384) {
                            val next = socket.getInputStream().read()
                            if (next < 0) break
                            header.append(next.toChar())
                        }
                    }
                    val response = if (kind == "SOCKS") byteArrayOf(5, -1) else
                        ("HTTP/1.1 " + if (success) "200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" else "502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    socket.getOutputStream().write(response); socket.getOutputStream().flush()
                }
            } catch (_: SocketException) { check(listener.isClosed) }
        }
    }
    fun kinds(): List<String> = synchronized(seen) { seen.toList() }
    override fun close() { listener.close(); worker.shutdownNow(); check(worker.awaitTermination(6, TimeUnit.SECONDS)) }
}

/** No real credentials or remote service; real packaged startup/store/client classes do all routing. */
object DesktopProxyPhysicalRoutingProbe {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args[0]; val root = Path.of(args[1]); val systemPort = args[2].toInt()
        val applicationPort = args[3].toInt(); val targetPort = args[4].toInt()
        val update = args.getOrNull(5) == "UPDATE"
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy(
                if (uri?.scheme == "socket") Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", systemPort)))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
        })
        val gate = DesktopDataOperationCoordinator()
        try {
            runBlocking {
                val savedMode = if (mode in listOf("LOCAL", "HOT")) ApplicationProxyMode.HTTP else ApplicationProxyMode.valueOf(mode)
                DesktopSettingsStore(root, gate).updateLatest { it.copy(networkProxy = ApplicationProxySettings(savedMode, "127.0.0.1", applicationPort)) }
            }
            val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolveSibling("synthetic-bootstrap.json"))
            var sameRoot = false
            var selectedPort = -1
            var signedRoutes = "{}"
            runDesktopMain(DesktopStartupGate(select = { DesktopStartupRootSelection.Selected(resolved) })) { selected ->
                check(selected.appDataRoot == root)
                runBlocking {
                    val controller = DesktopNetworkProxyController(DesktopSettingsStore(selected.appDataRoot, gate))
                    controller.loadAtStartup()
                    check(controller.state.value.error == null)
                    check(controller.state.value.saved.port == applicationPort)
                    sameRoot = true
                    val client = if (update) DesktopUpdateNetworkRouting.client({ controller.state.value.saved }, true)
                        else if (mode == "DIRECT" || mode == "LOCAL") ProxyAwareClient.modelApiBuilder { true }
                        .apply {
                            if (mode == "DIRECT") dns(object : okhttp3.Dns {
                                override fun lookup(hostname: String): List<InetAddress> {
                                    check(hostname == "direct-route.invalid")
                                    return listOf(InetAddress.getByName("127.0.0.1"))
                                }
                            })
                        }.build() else secureNovelAiClient()
                    try {
                        val requestHost = if (mode == "DIRECT" && !update) "direct-route.invalid" else "127.0.0.1"
                        val endpoint = if (mode == "DIRECT" || mode == "LOCAL") "http://$requestHost:$targetPort/fixture"
                            else if (update) DesktopUpdatePolicy.API else "https://image.novelai.net/user/subscription"
                        val selected = client.proxySelector.select(URI(endpoint)).single()
                        selectedPort = (selected.address() as? InetSocketAddress)?.port ?: 0
                        if (update) {
                            val checker = DesktopUpdateChecker(endpoint = endpoint, proxySettings = { controller.state.value.saved })
                            runCatching { checker.check("0.1.0", true) }
                            if (mode == "HOT") {
                                controller.save(ApplicationProxyMode.HTTP, "127.0.0.1", systemPort.toString())
                                check(controller.state.value.error == null)
                                runCatching { checker.check("0.1.0", true) }
                            }
                            signedRoutes = DesktopUpdateNetworkRouting.signedRoutes(controller.state.value.saved)
                        } else if (mode == "DIRECT" || mode == "LOCAL") {
                            client.newCall(okhttp3.Request.Builder().url("http://$requestHost:$targetPort/fixture").build()).execute().use { }
                        }
                        else NovelAiAccountService(client).fetchCancellable("synthetic-only-token")
                    } catch (_: Exception) {
                        // Proxy endpoints deliberately refuse. Parent checks physical bytes, never error wording.
                    } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                }
            }
            val result = buildJsonObject {
                put("startupLoadedSameRoot", sameRoot)
                put("selectedProxyPort", selectedPort)
                put("proxyClassSource", ProxyAwareClient::class.java.protectionDomain.codeSource.location.toURI().toString())
                put("mainClassSource", Class.forName("com.example.chatbar.desktop.MainKt").protectionDomain.codeSource.location.toURI().toString())
                if (update) {
                    put("updateClassSource", DesktopUpdateChecker::class.java.protectionDomain.codeSource.location.toURI().toString())
                    put("signedRoutes", Json.parseToJsonElement(signedRoutes))
                }
            }
            Files.writeString(root.resolve("result.json"), result.toString())
        } finally { runBlocking { gate.closeAndDrain() } }
    }
}
