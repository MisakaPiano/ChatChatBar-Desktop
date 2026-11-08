package com.example.chatbar.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.chatbar.domain.update.DanbooruCatalogUpdateInfo
import com.example.chatbar.domain.update.SharedDanbooruCatalogUpdateChecker
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal val LocalDesktopCatalogUpdates = staticCompositionLocalOf<(() -> DesktopCatalogUpdates)?> { null }

internal data class DesktopCatalogUpdateState(
    val busy: Boolean = false,
    val update: DanbooruCatalogUpdateInfo? = null,
    val status: String = "尚未检查 Danbooru 词库更新",
    val error: String? = null,
    val details: String? = null,
)

/** One app-lifetime auxiliary owner shared by Data and Studio; no image lane or NAI credentials. */
internal class DesktopCatalogUpdates(
    private val scope: CoroutineScope,
    private val checkLatest: suspend () -> DanbooruCatalogUpdateInfo?,
    private val installUpdate: suspend (DanbooruCatalogUpdateInfo, (String) -> Unit, () -> Unit) -> Unit,
) {
    constructor(scope: CoroutineScope, catalog: DesktopNovelAiTagCatalog) : this(scope,
        checkLatest = { catalog.withDataAccess { SharedDanbooruCatalogUpdateChecker(catalog, "CCB-Desktop").checkLatestCatalog() } },
        installUpdate = { info, report, publication -> DesktopNovelAiCatalogUpdate(catalog, onPublication = publication).install(info, report) })

    private val lock = Any()
    private val mutable = MutableStateFlow(DesktopCatalogUpdateState())
    val state = mutable.asStateFlow()
    private val rows = MutableStateFlow<List<DesktopWorkRow>>(emptyList())
    val workRows = rows.asStateFlow()
    private var work: Job? = null
    private var workId: String? = null
    private var closed = false

    fun check(): Job? = start(install = false)
    fun install(): Job? = start(install = true)

    private fun start(install: Boolean): Job? {
        val job = synchronized(lock) {
            if (closed) return null
            if (mutable.value.busy) return work
            val info = if (install) mutable.value.update ?: return null else null
            val id = UUID.randomUUID().toString()
            val publication = AtomicBoolean(false)
            var terminal = DesktopWorkPhase.CANCELLED
            mutable.value = mutable.value.copy(busy = true, status = if (install) "正在下载并校验 Danbooru 词库…" else "正在检查 Danbooru 词库…", error = null, details = null)
            rows.value = (listOf(DesktopWorkRow(id, if (install) DesktopWorkKind.CATALOG_UPDATE else DesktopWorkKind.CATALOG_CHECK,
                DesktopWorkPhase.RUNNING, System.currentTimeMillis(), target = DesktopWorkTarget(DesktopPrimaryRoute.DATA, detail = DesktopWorkDetail.UPDATES), canStop = true)) + rows.value).take(20)
            val launched = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (install) {
                        installUpdate(requireNotNull(info), { progress -> mutable.update { it.copy(status = progress) } }, { publication.set(true) })
                        mutable.update { it.copy(update = null, status = "Danbooru 词库已更新并启用") }
                    } else {
                        val update = checkLatest()
                        mutable.update { it.copy(update = update, status = if (update == null) "Danbooru 词库已是最新版本" else "发现词库更新 · ${update.sizeBytes / (1024 * 1024)} MB；下载后校验并启用") }
                    }
                    terminal = DesktopWorkPhase.COMPLETED
                } catch (cancelled: CancellationException) {
                    terminal = if (publication.get()) DesktopWorkPhase.UNCONFIRMED else DesktopWorkPhase.CANCELLED
                    mutable.update { it.copy(update = if (publication.get()) null else it.update,
                        status = if (publication.get()) "词库更新已进入发布阶段；请重新检查当前版本，未自动重试" else "Danbooru 词库操作已停止；原词库保留") }
                    throw cancelled
                } catch (failure: Exception) {
                    terminal = if (publication.get()) DesktopWorkPhase.UNCONFIRMED else DesktopWorkPhase.FAILED
                    mutable.update { it.copy(update = if (publication.get()) null else it.update,
                        status = "Danbooru 词库操作未完成",
                        error = if (publication.get()) "词库更新的完成状态尚未确认，请重新检查；原文件保留" else
                            if (install) "Danbooru 词库更新未完成；原词库保留，可重试" else "Danbooru 词库检查失败；程序更新不受影响，可重试",
                        details = catalogFailureDetails(failure)) }
                }
            }
            work = launched; workId = id
            launched.invokeOnCompletion {
                synchronized(lock) {
                    rows.update { all -> all.map { if (it.id == id) it.copy(phase = terminal, canStop = false) else it } }
                    if (workId == id) {
                        mutable.update { it.copy(busy = false, status = if (terminal == DesktopWorkPhase.CANCELLED)
                            "Danbooru 词库操作已停止；原词库保留" else it.status) }
                        work = null; workId = null
                    }
                }
            }
            launched
        }
        job.start(); return job
    }

    fun stopTask(id: String): Boolean = synchronized(lock) {
        val job = work.takeIf { workId == id && mutable.value.busy } ?: return false
        rows.update { all -> all.map { if (it.id == id) it.copy(stopRequested = true) else it } }
        job.cancel(); true
    }
    fun stop() { synchronized(lock) { workId }?.let(::stopTask) }
    suspend fun closeAndDrain() {
        val job = synchronized(lock) { closed = true; work.also { it?.cancel() } }
        job?.join()
    }
}

/** Exact exception categories and source frames only; never raw paths, responses or credentials. */
private fun catalogFailureDetails(error: Exception): String = generateSequence<Throwable>(error) { it.cause }
    .take(8).joinToString("\n") { cause ->
        cause.javaClass.name + (if (cause is SQLException) " (SQLite code=${cause.errorCode})" else "") +
            cause.stackTrace.filter { it.className.startsWith("com.example.chatbar.") }.take(6)
                .joinToString("", prefix = "\n") { "${it.className}.${it.methodName}:${it.lineNumber}\n" }
    }

/** Both checks start independently; a catalog failure cannot replace or suppress app discovery. */
internal fun checkDesktopProgramAndCatalog(notices: DesktopUpdateNotices, catalog: DesktopCatalogUpdates?) {
    notices.check()
    catalog?.check()
}
