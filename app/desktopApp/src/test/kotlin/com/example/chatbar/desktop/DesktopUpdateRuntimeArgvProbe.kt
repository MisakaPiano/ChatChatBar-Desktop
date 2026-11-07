package com.example.chatbar.desktop

import com.example.chatbar.domain.ApplicationProxyMode
import com.example.chatbar.domain.ApplicationProxySettings
import com.example.chatbar.domain.ProxyAwareClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Explicit cloud-only entry: actual runtime calls, synthetic installation; no installer execution. */
object DesktopUpdateRuntimeArgvProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        check(System.getenv("GITHUB_ACTIONS") == "true")
        check(System.getenv("GITHUB_REPOSITORY") == "MisakaPiano/ChatChatBar-Desktop")
        check(System.getProperty("os.name").startsWith("Windows"))
        val base = Path.of(requireNotNull(System.getenv("CCB_ARGV_FIX_BASE")))
        check(base.parent == base.root && base.fileName.toString().startsWith("ccbargvfix-"))
        val root = base.resolve("synthetic-installed")
        check(!Files.exists(root)); Files.createDirectories(root)
        Files.writeString(root.resolve("install-owner.txt"), "ccb-desktop-nsis-v1")
        val home = root.resolve("versions/synthetic1233")
        Files.createDirectories(home)
        Files.writeString(home.resolve("install-manifest.json"), "{}")
        val runtime = home.resolve("app/desktop-updater")
        Files.walk(base.resolve("runtime")).use { paths -> paths.forEach { source ->
            val target = runtime.resolve(base.resolve("runtime").relativize(source))
            if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
        } }
        val config = runtime.resolve("update-config.json")
        Files.writeString(config, """{"format":1,"product":"synthetic-stop-before-network","platform":"windows-x86_64"}""")
        val local = base.resolve("synthetic-local")
        ProxyAwareClient.applicationRoutingAvailable = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val modes = listOf(ApplicationProxyMode.DIRECT, ApplicationProxyMode.HTTP, ApplicationProxyMode.SOCKS)
            for (mode in modes) {
                val updater = DesktopUpdateRuntime(scope, home, local, proxySettings = {
                    ApplicationProxySettings(mode, "127.0.0.1", 10809)
                })
                check(updater.supportsInstall)
                val failure = runCatching { updater.checkAvailable() }.exceptionOrNull()
                check(failure?.message == "INSTALLED_UPDATE_IDENTITY_REFUSED") { "ACTUAL_RUNTIME_ARGV_FAILED_$mode" }
            }
            check(!Files.exists(local.resolve("ChatChatBarDesktopUpdates")))
            // A synthetic prior identity uses only the unchanged public bootstrap and public target.
            val bootstrap = runtime.resolve("update-root.json")
            Files.copy(Path.of(requireNotNull(System.getenv("GITHUB_WORKSPACE")))
                .resolve("tools/desktop/update/update-root.json"), bootstrap)
            fun sha(path: Path): String = Files.newInputStream(path).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1024 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            val rootHash = sha(bootstrap)
            Files.writeString(config, buildJsonObject {
                put("format", 1); put("product", "ccb-desktop-nsis-v1"); put("platform", "windows-x86_64")
                put("version", 1233); put("displayVersion", "0.1.0-preview.1233"); put("sourceSha", "a".repeat(40))
                put("channel", "preview")
                put("reader", 1); put("rootSha256", rootHash)
            }.toString())
            val configBefore = Files.readAllBytes(config)
            val settings = ApplicationProxySettings(ApplicationProxyMode.DIRECT)
            val updater = DesktopUpdateRuntime(scope, home, local, proxySettings = { settings })
            val discovered = updater.checkAvailable()
            check(discovered.newer && discovered.latest?.version == "0.1.0-preview.1245")
            updater.download("0.1.0-preview.1245")
            withTimeout(240_000) { while (updater.state.value.busy) delay(100) }
            val ready = requireNotNull(updater.state.value.ready) { updater.state.value.error.orEmpty() }
            check(ready.version == "0.1.0-preview.1245" && ready.bytes == 182874532L)
            check(ready.source == "4c7c6a6c61439fb30bcf4f28c08f09fe476bcfee")
            check(ready.sha256 == "baa27152aad4b359ad49d6edce8b585144647b46ddeb7ebcc3ed4f296b53f7cc")
            check(Files.size(ready.installer) == ready.bytes && sha(ready.installer) == ready.sha256)
            val command = DesktopUpdateRuntime.helperArguments(runtime, home, local.resolve("ChatChatBarDesktopUpdates"),
                settings, verify = ready.installer, expectedVersion = ready.version)
            val child = ProcessBuilder(command).directory(runtime.toFile()).redirectErrorStream(true).start()
            val output = child.inputStream.bufferedReader(Charsets.UTF_8).readText()
            check(output.length <= 1024 * 1024 && child.waitFor(30, TimeUnit.SECONDS) && child.exitValue() == 0)
            val verified = output.lineSequence().filter { it.startsWith("{") }
                .map { Json.parseToJsonElement(it).jsonObject }.last { it["status"] != null }
            check(verified["status"]?.jsonPrimitive?.content == "DOWNLOADED_VERIFIED")
            check(verified["sha256"]?.jsonPrimitive?.content == ready.sha256)
            check(sha(bootstrap) == rootHash && Files.readAllBytes(config).contentEquals(configBefore))
            val timestamp = Json.parseToJsonElement(Files.readString(local.resolve(
                "ChatChatBarDesktopUpdates/metadata/timestamp.json"))).jsonObject
            check(timestamp.getValue("signed").jsonObject.getValue("version").jsonPrimitive.int == 27)
            Files.writeString(base.resolve("reports/kotlin-runtime-result.json"), buildJsonObject {
                put("kind", "ACTUAL_KOTLIN_UPDATE_RUNTIME_ARGV_AND_PUBLIC1245_PROBE")
                put("passed", true); put("syntheticInstalledIdentity", 1233); put("publicTarget", ready.version)
                put("installerBytes", ready.bytes); put("installerSha256", ready.sha256)
                put("actualRuntimeDiscovery", true); put("actualRuntimeDownload", true)
                put("sameProductionArgumentsSecondVerification", true)
                put("typedModesBeforeNetwork", JsonArray(modes.map { JsonPrimitive(it.name) }))
                put("rootUnchanged", true); put("installerExecuted", false); put("realUserData", false)
                put("authenticatedMetadataRevision", 27); put("sourceSha", ready.source)
            }.toString())
        } finally { scope.cancel() }
        Unit
    }
}
