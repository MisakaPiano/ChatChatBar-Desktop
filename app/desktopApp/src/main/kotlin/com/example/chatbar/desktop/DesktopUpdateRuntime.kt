package com.example.chatbar.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

internal data class DesktopVerifiedUpdate(val version: String, val source: String, val installer: Path,
    val bytes: Long, val sha256: String)
internal data class DesktopUpdateRuntimeState(val busy: Boolean = false, val phase: String = "",
    val received: Long = 0, val total: Long = 0, val ready: DesktopVerifiedUpdate? = null, val error: String? = null,
    val notice: String? = null)
internal val LocalDesktopUpdater = staticCompositionLocalOf<DesktopUpdateRuntime?> { null }
private class DesktopUpdateRefusal(val code: String) : IllegalStateException(code)

/** App-lifetime owner; the isolated TUF helper has no business repositories or credential access. */
internal class DesktopUpdateRuntime(private val scope: CoroutineScope,
    private val home: Path? = (DesktopApplicationHome.resolve() as? DesktopApplicationHomeResult.Available)?.path,
    private val local: Path? = System.getenv("LOCALAPPDATA")?.let(Path::of)) {
    private val mutable = MutableStateFlow(DesktopUpdateRuntimeState())
    val state = mutable.asStateFlow()
    val supportsInstall: Boolean = runCatching { packageHome(); true }.getOrDefault(false)
    private var work: Job? = null

    /** Startup/manual discovery for installed programs authenticates metadata without downloading. */
    suspend fun checkAvailable(): DesktopUpdateResult {
        check(!mutable.value.busy)
        val result = invoke(expectedVersion = null, observeProgress = false)
        fun string(key: String) = result.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        val status = string("status")
        require(status in setOf("AVAILABLE", "UP_TO_DATE"))
        val version = string("displayVersion")
        DesktopReleaseVersion.parse(version)
        val url = "https://github.com/${DesktopUpdatePolicy.REPOSITORY}/releases/tag/${DesktopUpdatePolicy.TAG_PREFIX}$version"
        require(DesktopUpdatePolicy.trustedPage(url, version))
        return DesktopUpdateResult(DesktopRelease(version, null, string("notes"), url, string("channel") == "preview"),
            newer = status == "AVAILABLE")
    }

    fun download(version: String) {
        if (mutable.value.busy) return
        mutable.value = DesktopUpdateRuntimeState(busy = true, phase = "CHECKING")
        work = scope.launch {
            try {
                val result = invoke(download = true, expectedVersion = version)
                check(result["displayVersion"]?.jsonPrimitive?.content == version) { "UPDATE_SELECTION_CHANGED" }
                check(result["status"]?.jsonPrimitive?.content == "DOWNLOADED_VERIFIED")
                mutable.value = DesktopUpdateRuntimeState(ready = verified(result), phase = "DOWNLOADED_VERIFIED",
                    notice = if (result["cacheRejected"]?.jsonPrimitive?.booleanOrNull == true)
                        "旧缓存未通过校验并已保留；本次使用的更新已重新验证。" else null)
            } catch (cancelled: CancellationException) {
                mutable.value = DesktopUpdateRuntimeState(error = "下载已停止，尚未安装；可重新下载。")
                throw cancelled
            } catch (failure: Exception) {
                val code = (failure as? DesktopUpdateRefusal)?.code ?: "UPDATE_RUNTIME_FAILED"
                mutable.value = DesktopUpdateRuntimeState(error = "下载或可信校验未通过（$code）。当前程序和资料未改变，请重试或使用官方安装包。")
            }
        }
    }

    fun stop() { work?.cancel() }

    fun install(onExit: () -> Unit) {
        val ready = mutable.value.ready ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, phase = "VERIFYING", error = null)
        work = scope.launch {
            try {
                // Re-authenticate current signed metadata and bytes immediately before handoff.
                val checked = verified(invoke(verify = ready.installer, expectedVersion = ready.version))
                check(checked == ready) { "UPDATE_SELECTION_CHANGED" }
                withContext(Dispatchers.IO) { handoff(ready) }
                mutable.value = mutable.value.copy(phase = "WAITING_FOR_NORMAL_EXIT")
                onExit()
            } catch (cancelled: CancellationException) {
                mutable.value = mutable.value.copy(busy = false, phase = "DOWNLOADED_VERIFIED", error = "已取消安装校验；尚未关闭或安装。")
                throw cancelled
            }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(busy = false, phase = "DOWNLOADED_VERIFIED",
                    error = "安装交接未完成，应用仍可使用。请重试或正常退出后运行官方安装包。")
            }
        }
    }

    private fun verified(value: JsonObject): DesktopVerifiedUpdate {
        fun string(key: String) = value.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        val version = string("displayVersion"); DesktopReleaseVersion.parse(version)
        val sha = string("sha256"); require(sha.matches(Regex("[a-f0-9]{64}")))
        val source = string("sourceSha"); require(source.matches(Regex("[a-f0-9]{40}")))
        val bytes = value.getValue("bytes").jsonPrimitive.let { require(!it.isString); it.long }
        require(bytes in 1..1024L * 1024 * 1024)
        return DesktopVerifiedUpdate(version, source, Path.of(string("installer")).toAbsolutePath().normalize(), bytes, sha)
    }

    private fun packageHome(): Path = requireNotNull(home).toAbsolutePath().normalize().also {
        require(Files.isRegularFile(it.resolve("install-manifest.json")))
        require(it.parent.fileName.toString() == "versions")
        require(Files.readString(it.parent.parent.resolve("install-owner.txt")) == "ccb-desktop-nsis-v1")
    }

    private suspend fun invoke(download: Boolean = false, verify: Path? = null, expectedVersion: String?,
        observeProgress: Boolean = true): JsonObject = withContext(Dispatchers.IO) {
        val packageHome = packageHome()
        val runtime = packageHome.resolve("app/desktop-updater")
        val cache = requireNotNull(local).resolve("ChatChatBarDesktopUpdates")
        val args = mutableListOf(runtime.resolve("python.exe").toString(), "-B", runtime.resolve("lib/desktop_update.py").toString(),
            "--package", packageHome.toString(), "--cache", cache.toString())
        if (download) args += "--download"
        if (verify != null) args += listOf("--verify-file", verify.toString())
        if (expectedVersion != null) args += listOf("--expected-version", expectedVersion)
        val process = ProcessBuilder(args).directory(runtime.toFile()).redirectErrorStream(true).start()
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { process.destroy() }
            }
            try {
                var result: JsonObject? = null
                var observed = 0
                process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    while (true) {
                        ensureActive()
                        val line = input.readLine() ?: break
                        observed += line.length
                        check(line.length <= 32768 && observed <= 1024 * 1024)
                        val value = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        val phase = value["phase"]?.jsonPrimitive?.contentOrNull
                        if (observeProgress && phase in setOf("CHECKING", "DOWNLOADING", "VERIFYING")) mutable.value = mutable.value.copy(
                            phase = phase!!, received = value["received"]?.jsonPrimitive?.longOrNull ?: 0,
                            total = value["total"]?.jsonPrimitive?.longOrNull ?: 0)
                        if (value["status"] != null) result = value
                    }
                }
                check(process.waitFor(10, TimeUnit.SECONDS))
                if (result?.get("status")?.jsonPrimitive?.content == "FAILED") {
                    val code = result?.get("reason")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,100}")) } ?: "UPDATE_VERIFY_FAILED"
                    throw DesktopUpdateRefusal(code)
                }
                check(process.exitValue() == 0)
                requireNotNull(result).also { check(it["status"]?.jsonPrimitive?.content != "FAILED") }
            } finally { cancellation.cancelAndJoin() }
        }
    }

    private fun handoff(update: DesktopVerifiedUpdate) {
        val packageHome = packageHome()
        val root = packageHome.parent.parent
        val current = Files.readString(root.resolve("current.txt")).trim()
        check(current == packageHome.fileName.toString())
        val started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()
        ProcessBuilder(packageHome.resolve("app/desktop-updater/update-handoff.exe").toString(), root.toString(),
            update.installer.toString(), update.sha256, update.bytes.toString(), ProcessHandle.current().pid().toString(),
            started.toString(), current).directory(packageHome.toFile()).start()
    }
}
