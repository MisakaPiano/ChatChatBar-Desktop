package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.buffer

/**
 * One application-owned batch, not a scheduler or background-work authorization. Platform policy
 * supplies networkAllowed. Incoming work only authenticates/prefetches and wakes the local owner;
 * this class never applies native data or allocates an author/root revision.
 */
class LanSyncGroupCloudRuntime private constructor(private val access: LanSyncGroupRuntimeAccess,
    private val networkAllowed: () -> Boolean, private val wakeLocal: () -> Unit,
    private val report: (Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) -> Unit,
    private val catalogProvider: () -> LanSyncGroupCloudRoutes, private val clientProvider: () -> OkHttpClient,
    private val endpointPolicy: WebDavEndpointPolicy, private val schedule: LanSyncGroupCloudSchedule,
    private val clockMillis: () -> Long) : AutoCloseable {
    constructor(access: LanSyncGroupRuntimeAccess, networkAllowed: () -> Boolean, wakeLocal: () -> Unit,
        report: (Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) -> Unit) : this(access, networkAllowed, wakeLocal, report,
        access::cloudRoutes, { ProxyAwareClient.builder().build() }, WebDavEndpointPolicy.HTTPS_ONLY,
        LanSyncGroupCloudSchedule(), { System.nanoTime() / 1_000_000L })

    /** Synthetic HTTP/client/custody boundary only; production always uses the four-argument owner. */
    internal constructor(access: LanSyncGroupRuntimeAccess, networkAllowed: () -> Boolean, wakeLocal: () -> Unit,
        report: (Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) -> Unit, catalog: LanSyncGroupCloudRoutes,
        client: OkHttpClient, policy: WebDavEndpointPolicy, schedule: LanSyncGroupCloudSchedule = LanSyncGroupCloudSchedule(),
        clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }) : this(access, networkAllowed, wakeLocal, report,
        { catalog }, { client }, policy, schedule, clockMillis)

    private val cycleAdmission = Mutex()
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private val transports = linkedSetOf<WebDavHttpTransport>()
    private val progress = linkedMapOf<SyncStoreId, LanSyncGroupCloudRouteProgress>()
    private var snapshotCatalog: LanSyncGroupCloudRoutes? = null
    private var snapshotRevision = -1L
    private var scheduleKnown = false
    private var scheduleRevision = -1L
    private var deferred: RelativeWait? = null
    private var dirtyRevision = 0L

    private data class RelativeWait(val started: Long, val delay: Long) {
        fun remaining(now: Long): Long {
            val elapsed = when { now <= started -> 0; started < 0 && now > Long.MAX_VALUE + started -> Long.MAX_VALUE; else -> now - started }
            return (delay - elapsed).coerceAtLeast(0)
        }
    }
    private data class ScheduledSnapshot(val catalog: LanSyncGroupCloudRoutes, val revision: Long, val ids: Set<SyncStoreId>)

    /** Local catalog/schedule peek before asking the platform for a foreground transfer lease. */
    suspend fun prepareScheduledBatch(forceIdle: Boolean = false): Boolean {
        val captured = synchronized(lock) { if (closed) return false else generation }
        val snapshot = scheduledSnapshot(captured) ?: return false
        return synchronized(lock) {
            if (closed || generation != captured || snapshot.catalog.revision.value != snapshot.revision) false
            else {
                if (forceIdle) deferred = null
                (forceIdle || (deferred?.remaining(clockMillis()) ?: 0) == 0L) && snapshot.ids.any { schedule.due(it, forceIdle) }
            }
        }
    }
    /** Unknown configuration needs a local peek; no enabled routes means no timer is needed. */
    fun nextDelayMillis(): Long? = synchronized(lock) {
        if (closed) return@synchronized null
        val delay = if (!scheduleKnown || snapshotCatalog?.revision?.value != scheduleRevision) 0L else schedule.nextDelayMillis() ?: return@synchronized null
        maxOf(delay, deferred?.remaining(clockMillis()) ?: 0)
    }
    fun deferUntilWake() = synchronized(lock) { deferred = RelativeWait(clockMillis(), 60_000) }
    /** Configuration invalidates the local snapshot and closes actual old route calls. */
    fun configurationChanged() {
        synchronized(lock) { scheduleKnown = false; scheduleRevision = -1; deferred = null; schedule.enabled(emptySet()) }
        pause()
    }
    /** Real new source data invalidates selection status without starving an in-flight upload. */
    fun markDirty() {
        synchronized(lock) {
            dirtyRevision++
            progress.replaceAll { _, value -> value.copy(selectionObserved = false) }
        }
        publishCurrent()
    }

    private suspend fun scheduledSnapshot(captured: Long): ScheduledSnapshot? { return try {
        val catalog = runInterruptible(Dispatchers.IO) { catalogProvider() }
        val revision = catalog.revision.value
        val cached = synchronized(lock) { scheduleKnown && snapshotCatalog === catalog && scheduleRevision == revision }
        // A process-local revision cannot prove that protected bytes still exist or remain readable.
        val ids = runInterruptible(Dispatchers.IO) { catalog.rows().filter { it.enabled }.mapTo(linkedSetOf()) { it.id } }
        synchronized(lock) {
            if (closed || generation != captured || catalog.revision.value != revision) return null
            schedule.enabled(ids); scheduleKnown = true; scheduleRevision = revision
            snapshotCatalog = catalog; snapshotRevision = revision
            progress.keys.retainAll(ids)
            ids.forEach { progress.getOrPut(it) { LanSyncGroupCloudRouteProgress("等待检查本次选定版本") } }
        }
        if (!cached) publishCurrent(captured)
        ScheduledSnapshot(catalog, revision, ids)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        configurationUnavailable(captured)
    } }

    private fun configurationUnavailable(captured: Long): Nothing {
        synchronized(lock) {
            if (!closed && generation == captured) {
                scheduleKnown = false; scheduleRevision = -1
                deferred = RelativeWait(clockMillis(), 60_000)
                progress.replaceAll { _, value -> value.copy(text = CONFIGURATION_UNAVAILABLE_TEXT, selectionObserved = false) }
                snapshotCatalog?.let { snapshotRevision = it.revision.value }
            }
        }
        publishCurrent(captured)
        throw IllegalStateException("SYNC_GROUP_CLOUD_CONFIGURATION_UNAVAILABLE")
    }

    /** Stops this batch's HTTP immediately. A later explicitly scheduled cycle may run again. */
    fun pause() {
        val pending = synchronized(lock) {
            generation++
            progress.replaceAll { _, value -> value.copy(selectionObserved = false) }
            transports.toList().also { transports.clear() }
        }
        pending.forEach { it.close() }
        access.reader.removeRoutes(OWNER)
        // Pause must not wait on protected catalog I/O; only publish a still-current enabled snapshot.
        publishCurrent()
    }
    fun cancel() = pause()
    /** The caller cancels/joins its cycle job; this joins the same single-cycle admission. */
    suspend fun drain() { cycleAdmission.withLock { } }
    override fun close() { synchronized(lock) { closed = true }; pause() }

    /** True means every enabled route observed this batch's selection, never whole-group freshness. */
    suspend fun cycle(recoverNative: Boolean = true, outgoingOnly: Boolean = false, forceIdle: Boolean = true): Boolean = cycleAdmission.withLock {
        coroutineScope {
            val finished = AtomicBoolean(false)
            // Cancellation must close HTTP while the I/O child is blocked, before structured join.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!finished.get()) pause() }
            }
            try { withContext(Dispatchers.IO) {
            val captured = synchronized(lock) { if (closed) return@withContext false else generation }
            val capturedDirty = synchronized(lock) { dirtyRevision }
            if (!networkAllowed()) { deferUntilWake(); access.reader.removeRoutes(OWNER); return@withContext false }
            val snapshot = scheduledSnapshot(captured) ?: return@withContext false
            val catalog = snapshot.catalog; val cycleRevision = snapshot.revision; val ids = snapshot.ids
            if (ids.isEmpty()) { access.reader.removeRoutes(OWNER); return@withContext true }
            val deferredNow = synchronized(lock) {
                if (forceIdle) deferred = null
                !forceIdle && (deferred?.remaining(clockMillis()) ?: 0) > 0
            }
            if (deferredNow) return@withContext false
            var complete = true
            try {
                for (id in ids) {
                    if (!globallyActive(captured)) { complete = false; break }
                    if (!schedule.due(id, forceIdle)) continue
                    val revision = catalog.revision.value
                    val row = try { runInterruptible(Dispatchers.IO) { catalog.rows().singleOrNull { it.id == id && it.enabled } } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { currentCoroutineContext().ensureActive(); configurationUnavailable(captured) }
                    if (row == null) {
                        complete = false
                        if (globallyActive(captured) && catalog.revision.value == revision) schedule.defer(id)
                        continue
                    }
                    val responseFailure = AtomicReference<LanSyncGroupCloudSchedule.FailureCategory?>()
                    val serverWait = AtomicReference<RelativeWait?>()
                    val providerBackoff = AtomicBoolean(false)
                    val active = {
                        checkActive(captured, catalog, revision)
                        if (providerBackoff.get() || (serverWait.get()?.remaining(clockMillis()) ?: 0) > 0)
                            throw IOException("SYNC_GROUP_CLOUD_RETRY_LATER")
                    }
                    var borrowedCredentials: LanSyncGroupCloudCredentials? = null
                    try {
                        active()
                        val credentials = runInterruptible(Dispatchers.IO) {
                            catalog.credentials(id).also { borrowedCredentials = it }
                        }
                        credentials.use {
                            active()
                            val root = runInterruptible(Dispatchers.IO) { routeDirectory(id) }
                            val client = clientProvider().newBuilder().addInterceptor(ActiveIo(active) { status, retryAfter ->
                                responseFailure.set(when (status) {
                                    401 -> LanSyncGroupCloudSchedule.FailureCategory.AUTHENTICATION
                                    403 -> LanSyncGroupCloudSchedule.FailureCategory.PERMISSION
                                    else -> LanSyncGroupCloudSchedule.FailureCategory.TRANSIENT
                                })
                                // Candidate readers may retain/skip a transient provider claim. A
                                // rate limit or unavailable service instead ends this route's batch,
                                // even without Retry-After or after its supplied delay has elapsed.
                                if (status == 429 || status == 503) providerBackoff.set(true)
                                if (retryAfter != null) {
                                    val now = clockMillis()
                                    serverWait.updateAndGet { old -> if ((old?.remaining(now) ?: 0) >= retryAfter) old else RelativeWait(now, retryAfter) }
                                }
                            }).build()
                            val transport = WebDavHttpTransport(credentials.endpoint.toHttpUrl(), client, {
                                active()
                                credentials.withPassword { bytes ->
                                    if (credentials.username.isEmpty() && bytes.isEmpty()) null
                                    else Credentials.basic(credentials.username, bytes.toString(Charsets.UTF_8), Charsets.UTF_8)
                                }
                            }, endpointPolicy)
                            own(transport, captured, active)
                            try {
                                val attempts = runInterruptible(Dispatchers.IO) {
                                    WeakWebDavAttemptStore(transport, directory(root.resolve("provider")), id)
                                }
                                val parts = runInterruptible(Dispatchers.IO) {
                                    WeakWebDavChunkAssembly(transport, directory(root.resolve("parts-provider")), id)
                                }
                                val chunkRead = LanSyncGroupChunkCloudReadRoute(parts, active)
                                if (!outgoingOnly) {
                                    update(captured, catalog, id, "正在读取此网盘的加密候选")
                                    val read = LanSyncGroupWeakCloudReadRoute(attempts, active)
                                    val routes = listOf(LanSyncGroupReplicaReader.Route("cloud:${id.value}", read),
                                        LanSyncGroupReplicaReader.Route("cloud:${id.value}:parts", chunkRead))
                                    access.reader.routes(OWNER, routes)
                                    for (route in routes) {
                                        var cursor: CommitCursor? = null
                                        val seen = hashSetOf<String>()
                                        do {
                                            active(); cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_CLOUD_LISTING_CHANGED" } }
                                            cursor = access.node.discover(access.reader.discoveryFrom(route.store, routeId = route.id), cursor).nextCursor
                                        } while (cursor != null)
                                    }
                                    active()
                                    val frozen = access.node.reconcileForPrefetch()
                                    access.node.prefetch(frozen)
                                    active(); wakeLocal()
                                }
                                active()
                                update(captured, catalog, id, "正在检查本次选定版本的投递记录")
                                val owner = access.targetPublication(directory(root.resolve("target")),
                                    SyncDeliveryTarget(SyncDeliveryKind.CLOUD_STORE, id),
                                    recoverNative = recoverNative && !outgoingOnly, prepare = false)
                                val prior = runInterruptible(Dispatchers.IO) { owner.cloudReceipt() }
                                val receipt = if (prior != null) {
                                    runInterruptible(Dispatchers.IO) { check(owner.recoverObjectObservations()) { "SYNC_GROUP_TARGET_RECEIPT_MISSING" } }
                                    prior
                                } else {
                                    update(captured, catalog, id, "正在保全并投递本次选定版本")
                                    owner.prepare(); active()
                                    owner.uploadCloud(attempts, parts)
                                }
                                active()
                                val observed = receipt.objects.maxOfOrNull { it.evidence.readback.observedAtMillis }
                                val warning = buildString {
                                    if (chunkRead.latestFailure.value != null) append("；有未通过检查的分片清单，其候选证据已保留")
                                    if (receipt.rejectedCandidates > 0) append("；投递记录含${receipt.rejectedCandidates}个未采用的不匹配、缺失或损坏候选，原始密文保留")
                                }
                                schedule.success(id, changed = prior == null)
                                update(captured, catalog, id, "本次选定版本已读回核验；全组仍待后续检查$warning", observed, capturedDirty)
                            } finally {
                                access.reader.removeRoutes(OWNER)
                                synchronized(lock) { transports.remove(transport) }
                                transport.close()
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (failure.message == "SYNC_GROUP_CLOUD_CONFIGURATION_UNAVAILABLE") throw failure
                        complete = false
                        var category: LanSyncGroupCloudSchedule.FailureCategory? = null
                        if (globallyActive(captured) && catalog.revision.value == revision && !Thread.currentThread().isInterrupted) {
                            if (failure is IOException) {
                                val actual = failureCategory(failure)
                                category = if (actual == LanSyncGroupCloudSchedule.FailureCategory.TRANSIENT) responseFailure.get() ?: actual else actual
                                schedule.failed(id, category, serverWait.get()?.remaining(clockMillis()))
                            } else schedule.defer(id) // Local incomplete work is neither success nor an invented network error.
                        }
                        val text = when {
                            !globallyActive(captured) -> "本次网盘工作已暂停，待传资料已保留"
                            catalog.revision.value != revision -> "本机网盘设置已变更，待传资料已保留"
                            category == LanSyncGroupCloudSchedule.FailureCategory.AUTHENTICATION -> "账号验证未通过，请修改此网盘配置；待传资料已保留"
                            category == LanSyncGroupCloudSchedule.FailureCategory.PERMISSION -> "此网盘未允许本次访问，请检查此网盘配置；待传资料已保留"
                            else -> failureText(failure)
                        }
                        update(captured, catalog, id, text)
                    } finally { borrowedCredentials?.close() }
                }
            } finally { access.reader.removeRoutes(OWNER) }
                complete && globallyActive(captured) && catalog.revision.value == cycleRevision && synchronized(lock) {
                    dirtyRevision == capturedDirty && ids.all { progress[it]?.selectionObserved == true && schedule.state(it)?.failure == null }
                }
            } } finally {
                finished.set(true)
                withContext(NonCancellable) { cancellation.cancelAndJoin() }
            }
        }
    }

    private fun globallyActive(captured: Long): Boolean = synchronized(lock) { !closed && generation == captured } && networkAllowed()
    private fun checkActive(captured: Long, catalog: LanSyncGroupCloudRoutes, revision: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_GROUP_CLOUD_INTERRUPTED")
        if (!globallyActive(captured)) throw IOException("SYNC_GROUP_CLOUD_PAUSED")
        if (catalog.revision.value != revision) throw IOException("SYNC_GROUP_CLOUD_ROUTE_CHANGED")
    }
    private fun own(transport: WebDavHttpTransport, captured: Long, active: () -> Unit) {
        try { synchronized(lock) { active(); require(!closed && generation == captured); transports += transport } }
        catch (failure: Throwable) { transport.close(); throw failure }
    }
    private suspend fun update(captured: Long, catalog: LanSyncGroupCloudRoutes, id: SyncStoreId, text: String, observed: Long? = null,
        capturedDirty: Long? = null) {
        val revision = catalog.revision.value
        val enabled = try { runInterruptible(Dispatchers.IO) { catalog.rows().filter { it.enabled }.mapTo(linkedSetOf()) { it.id } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); configurationUnavailable(captured) }
        synchronized(lock) {
            if (closed || captured != generation) return
            snapshotCatalog = catalog
            snapshotRevision = if (catalog.revision.value == revision) revision else -1
            progress.keys.retainAll(enabled)
            val previous = progress[id]
            if (id in enabled) progress[id] = LanSyncGroupCloudRouteProgress(text, observed ?: previous?.lastObservedAt,
                pendingOperations = null, selectionObserved = observed != null && capturedDirty == dirtyRevision)
        }
        publishCurrent(captured)
    }
    /** The callback only projects payload-free state; serialize it with dirty/pause mutations. */
    private fun publishCurrent(captured: Long? = null) = synchronized(lock) {
        if (!closed && (captured == null || captured == generation) && snapshotCatalog?.revision?.value == snapshotRevision)
            report(progress.toMap())
    }
    private fun failureCategory(failure: IOException): LanSyncGroupCloudSchedule.FailureCategory = when ((failure as? CiphertextStoreException)?.failure) {
        CiphertextStoreFailure.AUTHENTICATION -> LanSyncGroupCloudSchedule.FailureCategory.AUTHENTICATION
        CiphertextStoreFailure.PERMISSION -> LanSyncGroupCloudSchedule.FailureCategory.PERMISSION
        else -> LanSyncGroupCloudSchedule.FailureCategory.TRANSIENT
    }
    private fun failureText(failure: Exception): String = when (failure) {
        is CiphertextStoreException -> when (failure.failure) {
            CiphertextStoreFailure.AUTHENTICATION -> "账号验证未通过，请修改此网盘配置；待传资料已保留"
            CiphertextStoreFailure.PERMISSION -> "此网盘未允许本次访问，请检查此网盘配置；待传资料已保留"
            CiphertextStoreFailure.QUOTA -> "此网盘空间不足，待传资料已保留"
            CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.OBJECT_CONFLICT, CiphertextStoreFailure.OPERATION_CONFLICT -> "此网盘的候选尚未通过核验，待传资料已保留"
            else -> "此网盘本次尚未完成，待传资料已保留"
        }
        else -> "此网盘本次尚未完成，待传资料已保留"
    }
    private fun routeDirectory(id: SyncStoreId): Path = directory(directory(access.work.resolve("cloud-runtime")).resolve(id.value))
    private fun directory(path: Path): Path {
        if (!Files.exists(path, NOFOLLOW_LINKS)) try { Files.createDirectory(path) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        lanSyncSafe(path, true)
        return path
    }

    /** All provider HTTP bytes retain the generation, platform and enabled-catalog fence. */
    private class ActiveIo(private val active: () -> Unit, private val onErrorResponse: (Int, Long?) -> Unit) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            active()
            val original = chain.request()
            val request = original.body?.let { body -> original.newBuilder().method(original.method, object : RequestBody() {
                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                override fun isOneShot(): Boolean = body.isOneShot()
                override fun isDuplex(): Boolean = body.isDuplex()
                override fun writeTo(sink: BufferedSink) {
                    active()
                    val guarded = object : ForwardingSink(sink) {
                        override fun write(source: Buffer, byteCount: Long) { active(); super.write(source, byteCount); active() }
                    }.buffer()
                    body.writeTo(guarded); guarded.flush(); active()
                }
            }).build() } ?: original
            val response = chain.proceed(request)
            try {
                active()
                if (response.code in 400..599) onErrorResponse(response.code,
                    LanSyncGroupCloudSchedule.parseRetryAfterMillis(response.header("Retry-After")))
                val body = response.body ?: return response
                val guarded = object : ResponseBody() {
                    private val input by lazy { object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long { active(); val count = super.read(sink, byteCount); active(); return count }
                    }.buffer() }
                    override fun contentType(): MediaType? = body.contentType()
                    override fun contentLength(): Long = body.contentLength()
                    override fun source(): BufferedSource = input
                }
                return response.newBuilder().body(guarded).build()
            } catch (failure: Throwable) { response.close(); throw failure }
        }
    }
    companion object {
        private const val OWNER = "cloud"
        private const val CONFIGURATION_UNAVAILABLE_TEXT = "网盘连接设置暂时无法读取，原文件已保留；请打开连接设置检查"
    }
}
