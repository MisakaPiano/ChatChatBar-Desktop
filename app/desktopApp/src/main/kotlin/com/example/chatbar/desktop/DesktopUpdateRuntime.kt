package com.example.chatbar.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

internal data class DesktopVerifiedUpdate(val version: String, val source: String, val installer: Path,
    val bytes: Long, val sha256: String)
internal data class DesktopUpdateRuntimeState(val busy: Boolean = false, val phase: String = "",
    val received: Long = 0, val total: Long = 0, val ready: DesktopVerifiedUpdate? = null, val error: String? = null,
    val notice: String? = null)
internal val LocalDesktopUpdater = staticCompositionLocalOf<DesktopUpdateRuntime?> { null }
private class DesktopUpdateRefusal(val code: String, val space: DesktopUpdateSpace? = null) : IllegalStateException(code)
private class DesktopUpdateDataOverlap : IllegalStateException()

/** App-lifetime owner; the isolated TUF helper has no business repositories or credential access. */
internal class DesktopUpdateRuntime(private val scope: CoroutineScope,
    private val home: Path? = (DesktopApplicationHome.resolve() as? DesktopApplicationHomeResult.Available)?.path,
    private val local: Path? = System.getenv("LOCALAPPDATA")?.let(Path::of),
    private val dataRoot: Path? = null,
    private val proxySettings: () -> com.example.chatbar.domain.ApplicationProxySettings = {
        com.example.chatbar.domain.ProxyAwareClient.applicationProxy }) {
    private val mutable = MutableStateFlow(DesktopUpdateRuntimeState())
    val state = mutable.asStateFlow()
    val supportsInstall: Boolean = runCatching { packageHome(); true }.getOrDefault(false)
    @Volatile private var activeWork: Pair<String, Job>? = null
    private val mutableWork = MutableStateFlow<List<DesktopWorkRow>>(emptyList())
    val workRows = mutableWork.asStateFlow()
    val cacheLocation: Path? get() = local?.resolve("ChatChatBarDesktopUpdates")?.toAbsolutePath()?.normalize()
    private class Outcome(var phase: DesktopWorkPhase = DesktopWorkPhase.COMPLETED)
    private fun failurePhase(failure: Exception) = if ((failure as? DesktopUpdateRefusal)?.code == "UPDATE_HELPER_EXIT_UNCONFIRMED")
        DesktopWorkPhase.UNCONFIRMED else DesktopWorkPhase.FAILED
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchWork(kind: DesktopWorkKind, block: suspend (Outcome) -> Unit): Job {
        val id = UUID.randomUUID().toString(); val outcome = Outcome()
        val job = scope.launch(start = CoroutineStart.ATOMIC) { block(outcome) }
        activeWork = id to job
        mutableWork.update { (listOf(DesktopWorkRow(id, kind, DesktopWorkPhase.RUNNING, System.currentTimeMillis(),
            target = DesktopWorkTarget(DesktopPrimaryRoute.DATA, detail = DesktopWorkDetail.UPDATES), canStop = true)) + it).take(40) }
        job.invokeOnCompletion { failure ->
            val phase = if (outcome.phase == DesktopWorkPhase.UNCONFIRMED) outcome.phase
                else if (failure is CancellationException) DesktopWorkPhase.CANCELLED
                else if (failure != null) DesktopWorkPhase.FAILED else outcome.phase
            mutableWork.update { rows -> rows.map { if (it.id == id) it.copy(phase = phase, canStop = false, stopRequested = false) else it } }
        }
        return job
    }
    fun stopTask(id: String): Boolean {
        val (activeId, job) = activeWork ?: return false
        if (activeId != id || job.isCompleted || state.value.phase == "WAITING_FOR_NORMAL_EXIT") return false
        mutableWork.update { rows -> rows.map { if (it.id == id && it.active) it.copy(stopRequested = true, canStop = false) else it } }
        job.cancel(); return true
    }

    internal companion object {
        fun helperArguments(runtime: Path, packageHome: Path, cache: Path,
            settings: com.example.chatbar.domain.ApplicationProxySettings, download: Boolean = false,
            verify: Path? = null, expectedVersion: String? = null, cleanCache: Boolean = false): List<String> = buildList {
            addAll(listOf(runtime.resolve("python.exe").toString(), "-B", runtime.resolve("lib/desktop_update.py").toString(),
                "--package", packageHome.toString(), "--cache", cache.toString(),
                "--proxy-routes-base64", DesktopUpdateNetworkRouting.signedRoutesArgument(settings)))
            if (download) add("--download")
            if (cleanCache) add("--clean-cache")
            if (verify != null) addAll(listOf("--verify-file", verify.toString()))
            if (expectedVersion != null) addAll(listOf("--expected-version", expectedVersion))
        }
    }

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
        launchWork(DesktopWorkKind.PROGRAM_UPDATE) { outcome ->
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
                outcome.phase = failurePhase(failure)
                val code = (failure as? DesktopUpdateRefusal)?.code ?: "UPDATE_RUNTIME_FAILED"
                mutable.value = DesktopUpdateRuntimeState(error = (failure as? DesktopUpdateRefusal)?.space?.let(::desktopUpdateSpaceMessage)
                    ?: "下载或可信校验未通过（$code）。当前程序和资料未改变，请重试或使用官方安装包。")
            }
        }
    }

    fun stop() { activeWork?.first?.let(::stopTask) }

    fun cleanCache(selectedVersion: String?) {
        if (state.value.busy || !supportsInstall) return
        val previous = state.value
        mutable.value = previous.copy(busy = true, phase = "CLEANING_CACHE", error = null, notice = null)
        launchWork(DesktopWorkKind.UPDATE_CACHE) { outcome ->
            try {
                val result = invoke(cleanCache = true, expectedVersion = previous.ready?.version ?: selectedVersion, observeProgress = false)
                check(result["status"]?.jsonPrimitive?.contentOrNull == "CACHE_CLEANED")
                val cache = requireNotNull(cacheLocation)
                val removed = result.getValue("removedCount").jsonPrimitive.long.also { require(it in 0..1000) }
                val bytes = result.getValue("removedBytes").jsonPrimitive.long.also { require(it in 0..1024L * 1024 * 1024 * 1000) }
                val retained = result.getValue("retainedCount").jsonPrimitive.long.also { require(it in 0..1000) }
                val missing = result.getValue("missingCount").jsonPrimitive.long.also { require(it in 0..1000) }
                val unavailable = result.getValue("unavailableCount").jsonPrimitive.long.also { require(it in 0..1000) }
                check(Path.of(result.getValue("cache").jsonPrimitive.content).toAbsolutePath().normalize() == cache)
                val available = result["availableBytes"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
                mutable.value = previous.copy(busy = false, error = null, notice =
                    (if (result["installationConfirmed"]?.jsonPrimitive?.booleanOrNull == false) "尚无本版本的安装完成确认；未把下载或待安装包当作已用包。\n" else "") +
                    "更新缓存：$cache\n已清理 $removed 份旧安装包，共 $bytes 字节；保留 $retained 份待安装或尚未确认完成的包。" +
                        "保留 $missing 份已无安装包的校验记录；另有 $unavailable 项正使用或无法确认，保留原件与记录。\n" +
                        (available?.let { "该磁盘当前可用 $it 字节，可重新点击下载。" } ?: "已完成清理，但无法读取磁盘余量，请核对后重试下载。"))
            } catch (cancelled: CancellationException) {
                mutable.value = previous.copy(busy = false, error = "清理已停止；可能已回收部分旧安装包。校验记录保留，请核对空间后再下载。")
                throw cancelled
            } catch (failure: Exception) {
                outcome.phase = failurePhase(failure)
                val code = (failure as? DesktopUpdateRefusal)?.code
                mutable.value = previous.copy(busy = false, error = if (code == "UPDATE_BUSY")
                    "更新器正在工作，本次未开始清理；请等待当前下载或安装交接结束。"
                    else "缓存清理未完成（${code ?: "UPDATE_RUNTIME_FAILED"}）；可能已清理部分可确认的旧包，校验记录与未知文件保留。请核对缓存空间后重试。")
            }
        }
    }

    fun install(onExit: () -> Unit) {
        val ready = mutable.value.ready ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, phase = "VERIFYING", error = null)
        launchWork(DesktopWorkKind.PROGRAM_UPDATE) { outcome ->
            try {
                withContext(Dispatchers.IO) {
                    if (dataRoot != null && DesktopProgramDataBoundary.conflicts(packageHome(), dataRoot))
                        throw DesktopUpdateDataOverlap()
                }
                // Re-authenticate current signed metadata and bytes immediately before handoff.
                val checked = verified(invoke(verify = ready.installer, expectedVersion = ready.version))
                check(checked == ready) { "UPDATE_SELECTION_CHANGED" }
                withContext(Dispatchers.IO) { handoff(ready) }
                mutable.value = mutable.value.copy(phase = "WAITING_FOR_NORMAL_EXIT")
                outcome.phase = DesktopWorkPhase.UNCONFIRMED
                onExit()
            } catch (cancelled: CancellationException) {
                mutable.value = mutable.value.copy(busy = false, phase = "DOWNLOADED_VERIFIED", error = "已取消安装校验；尚未关闭或安装。")
                throw cancelled
            }
            catch (_: DesktopUpdateDataOverlap) {
                outcome.phase = DesktopWorkPhase.FAILED
                mutable.value = mutable.value.copy(busy = false, phase = "DOWNLOADED_VERIFIED",
                    error = "资料目录与程序安装目录重叠，已停止安装并保留更新文件。请先在“数据→数据目录”检查并迁移到程序目录之外，正常退出重开后再更新。保留旧目录；迁移未通过时不要删除文件或手动修改启动路径。")
            }
            catch (failure: Exception) {
                outcome.phase = failurePhase(failure)
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
        observeProgress: Boolean = true, cleanCache: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        val packageHome = packageHome()
        val runtime = packageHome.resolve("app/desktop-updater")
        val cache = requireNotNull(local).resolve("ChatChatBarDesktopUpdates")
        val args = helperArguments(runtime, packageHome, cache, proxySettings(), download, verify, expectedVersion, cleanCache)
        ensureActive()
        val process = ProcessBuilder(args).directory(runtime.toFile()).redirectErrorStream(true).start()
        try { coroutineScope {
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
                    throw DesktopUpdateRefusal(code, if (code == "UPDATE_SPACE_REQUIRED") desktopUpdateSpace(requireNotNull(result), cache) else null)
                }
                check(process.exitValue() == 0)
                requireNotNull(result).also { check(it["status"]?.jsonPrimitive?.content != "FAILED") }
            } finally { cancellation.cancelAndJoin() }
        } } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (process.isAlive) process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) throw DesktopUpdateRefusal("UPDATE_HELPER_EXIT_UNCONFIRMED")
            }
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
