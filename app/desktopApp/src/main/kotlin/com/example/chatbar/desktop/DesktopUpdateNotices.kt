package com.example.chatbar.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val LocalDesktopUpdateNotices = staticCompositionLocalOf<DesktopUpdateNotices?> { null }

internal data class DesktopUpdateNoticeState(
    val busy: Boolean = false,
    val includePreview: Boolean = true,
    val result: DesktopUpdateResult? = null,
    val error: String? = null,
    val pendingVersion: String? = null,
    val showStartupNotice: Boolean = false,
    val cacheNotice: String? = null,
)

/** One application owner for startup and manual checks. Hints never authorize a download. */
internal class DesktopUpdateNotices(
    private val scope: CoroutineScope,
    private val installed: String,
    private val hints: DesktopUpdateNoticeHints?,
    private val startupAllowed: Boolean,
    private val startupQuery: (suspend () -> DesktopUpdateResult)? = null,
    private val query: suspend (Boolean) -> DesktopUpdateResult,
) {
    private val mutable = MutableStateFlow(DesktopUpdateNoticeState())
    val state = mutable.asStateFlow()
    private var startupChecked = false
    private var closed = false
    private var work: Job? = null

    fun checkOnStartup(): Job? {
        if (startupChecked || closed || !startupAllowed) return null
        startupChecked = true
        // An explicit check already in progress also satisfies this process's startup check.
        if (mutable.value.busy || mutable.value.result != null) return work
        return check(startup = true)
    }

    fun setIncludePreview(value: Boolean) {
        if (closed || mutable.value.busy) return
        mutable.value = mutable.value.copy(includePreview = value, result = null, error = null)
    }

    fun dismissStartupNotice() {
        mutable.value = mutable.value.copy(showStartupNotice = false)
    }

    fun check(startup: Boolean = false): Job? {
        if (closed || mutable.value.busy) return work
        val previews = mutable.value.includePreview
        mutable.value = mutable.value.copy(busy = true, result = null, error = null, showStartupNotice = false)
        work = scope.launch {
            try {
                if (startup && hints != null) {
                    try {
                        val hint = hints.read()?.takeIf {
                            DesktopReleaseVersion.parse(it) > DesktopReleaseVersion.parse(installed) &&
                                (previews || DesktopReleaseVersion.parse(it).preview.isEmpty())
                        }
                        mutable.value = mutable.value.copy(pendingVersion = hint)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        mutable.value = mutable.value.copy(cacheNotice = "上次的更新提示未能读取；将重新检查。")
                    }
                }
                val result = if (startup && startupQuery != null) startupQuery.invoke() else query(previews)
                val pending = result.latest?.takeIf { result.newer }?.version
                pending?.let { require(DesktopReleaseVersion.parse(it) > DesktopReleaseVersion.parse(installed)) }
                mutable.value = mutable.value.copy(result = result, pendingVersion = pending,
                    showStartupNotice = startup && pending != null)
                if (hints != null) {
                    try {
                        hints.write(pending)
                        mutable.value = mutable.value.copy(cacheNotice = null)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        mutable.value = mutable.value.copy(cacheNotice = "更新检查已完成，但提示未能保存；下次启动会重新检查。")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // A failed check is neither proof of being current nor a reason to discard a prior badge.
                mutable.value = mutable.value.copy(error = "未能完成检查：网络或可信发行信息不可用。可在此重试，当前程序可继续使用。")
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
        return work
    }

    fun close() { closed = true; work?.cancel() }
    suspend fun closeAndDrain() { close(); work?.join() }
}

/** Version-only, bounded cache. This file never supplies release notes, URLs, or installer authority. */
internal class DesktopUpdateNoticeHints(cache: Path) {
    private val directory = cache.toAbsolutePath().normalize()
    private val file = directory.resolve("pending-notice.txt")

    suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return@withContext null
        DesktopArchiveFiles.safe(file, directory = false)
        val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(161) }
        require(bytes.size in 1..160)
        bytes.toString(Charsets.UTF_8).also { DesktopReleaseVersion.parse(it) }
    }

    suspend fun write(version: String?) = withContext(Dispatchers.IO) {
        if (version == null) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                DesktopArchiveFiles.safe(file, directory = false)
                Files.delete(file)
            }
        } else {
            DesktopReleaseVersion.parse(version)
            val existing = generateSequence(directory) { it.parent }
                .first { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            DesktopArchiveFiles.safe(existing, directory = true)
            Files.createDirectories(directory)
            DesktopArchiveFiles.safe(directory, directory = true)
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) DesktopArchiveFiles.safe(file, directory = false)
            val temporary = directory.resolve("pending-notice-${UUID.randomUUID()}.tmp")
            try {
                Files.writeString(temporary, version, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        Unit
    }
}
