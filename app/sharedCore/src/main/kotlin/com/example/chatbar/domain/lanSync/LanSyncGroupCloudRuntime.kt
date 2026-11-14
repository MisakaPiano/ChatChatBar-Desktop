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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
    private val endpointPolicy: WebDavEndpointPolicy) : AutoCloseable {
    constructor(access: LanSyncGroupRuntimeAccess, networkAllowed: () -> Boolean, wakeLocal: () -> Unit,
        report: (Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) -> Unit) : this(access, networkAllowed, wakeLocal, report,
        access::cloudRoutes, { ProxyAwareClient.builder().build() }, WebDavEndpointPolicy.HTTPS_ONLY)

    /** Synthetic HTTP/client/custody boundary only; production always uses the four-argument owner. */
    internal constructor(access: LanSyncGroupRuntimeAccess, networkAllowed: () -> Boolean, wakeLocal: () -> Unit,
        report: (Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) -> Unit, catalog: LanSyncGroupCloudRoutes,
        client: OkHttpClient, policy: WebDavEndpointPolicy) : this(access, networkAllowed, wakeLocal, report,
        { catalog }, { client }, policy)

    private val cycleAdmission = Mutex()
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private val transports = linkedSetOf<WebDavHttpTransport>()
    private val progress = linkedMapOf<SyncStoreId, LanSyncGroupCloudRouteProgress>()
    private var snapshotCatalog: LanSyncGroupCloudRoutes? = null
    private var snapshotRevision = -1L

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
        val snapshot = synchronized(lock) {
            if (!closed && snapshotCatalog?.revision?.value == snapshotRevision) progress.toMap() else null
        }
        snapshot?.let(report)
    }
    fun cancel() = pause()
    /** The caller cancels/joins its cycle job; this joins the same single-cycle admission. */
    suspend fun drain() { cycleAdmission.withLock { } }
    override fun close() { synchronized(lock) { closed = true }; pause() }

    /** True means every enabled route observed this batch's selection, never whole-group freshness. */
    suspend fun cycle(recoverNative: Boolean = true, outgoingOnly: Boolean = false): Boolean = cycleAdmission.withLock {
        coroutineScope {
            val finished = AtomicBoolean(false)
            // Cancellation must close HTTP while the I/O child is blocked, before structured join.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!finished.get()) pause() }
            }
            try { withContext(Dispatchers.IO) {
            val captured = synchronized(lock) { if (closed) return@withContext false else generation }
            if (!networkAllowed()) { access.reader.removeRoutes(OWNER); return@withContext false }
            val catalog = try { runInterruptible(Dispatchers.IO) { catalogProvider() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@withContext false }
            val cycleRevision = catalog.revision.value
            val ids = try { runInterruptible(Dispatchers.IO) { catalog.rows().filter { it.enabled }.map { it.id } } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@withContext false }
            resetProgress(captured, catalog, cycleRevision, ids.toSet())
            if (ids.isEmpty()) { access.reader.removeRoutes(OWNER); return@withContext true }
            var complete = true
            try {
                for (id in ids) {
                    if (!globallyActive(captured)) { complete = false; break }
                    val revision = catalog.revision.value
                    val row = try { runInterruptible(Dispatchers.IO) { catalog.rows().singleOrNull { it.id == id && it.enabled } } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    if (row == null) { complete = false; continue }
                    val active = { checkActive(captured, catalog, revision) }
                    var borrowedCredentials: LanSyncGroupCloudCredentials? = null
                    try {
                        active()
                        val credentials = runInterruptible(Dispatchers.IO) {
                            catalog.credentials(id).also { borrowedCredentials = it }
                        }
                        credentials.use {
                            active()
                            val root = runInterruptible(Dispatchers.IO) { routeDirectory(id) }
                            val client = clientProvider().newBuilder().addInterceptor(ActiveIo(active)).build()
                            val transport = WebDavHttpTransport(credentials.endpoint.toHttpUrl(), client, {
                                active()
                                credentials.withPassword { bytes ->
                                    Credentials.basic(credentials.username, bytes.toString(Charsets.UTF_8), Charsets.UTF_8)
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
                                val prior = runInterruptible(Dispatchers.IO) { owner.weakReceipt() }
                                val receipt = if (prior != null) {
                                    runInterruptible(Dispatchers.IO) { check(owner.recoverObjectObservations()) { "SYNC_GROUP_TARGET_RECEIPT_MISSING" } }
                                    prior
                                } else {
                                    update(captured, catalog, id, "正在保全并投递本次选定版本")
                                    owner.prepare(); active()
                                    owner.upload(attempts)
                                }
                                active()
                                val observed = receipt.objects.maxOfOrNull { it.readbackObservation.observedAtMillis }
                                val warning = if (chunkRead.latestFailure.value != null) "；有未通过检查的分片清单，其候选证据已保留" else ""
                                update(captured, catalog, id, "本次选定版本已读回核验；全组仍待后续检查$warning", observed)
                            } finally {
                                access.reader.removeRoutes(OWNER)
                                synchronized(lock) { transports.remove(transport) }
                                transport.close()
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        complete = false
                        val text = when {
                            !globallyActive(captured) -> "本次网盘工作已暂停，待传资料已保留"
                            catalog.revision.value != revision -> "本机网盘设置已变更，待传资料已保留"
                            else -> failureText(failure)
                        }
                        update(captured, catalog, id, text)
                    } finally { borrowedCredentials?.close() }
                }
            } finally { access.reader.removeRoutes(OWNER) }
                complete && globallyActive(captured) && catalog.revision.value == cycleRevision
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
    private fun resetProgress(captured: Long, catalog: LanSyncGroupCloudRoutes, revision: Long, ids: Set<SyncStoreId>) {
        val snapshot = synchronized(lock) {
            if (closed || captured != generation) return
            snapshotCatalog = catalog
            snapshotRevision = if (catalog.revision.value == revision) revision else -1
            progress.keys.retainAll(ids)
            ids.forEach { id -> progress[id] = LanSyncGroupCloudRouteProgress("正在检查本次选定版本",
                progress[id]?.lastObservedAt, pendingOperations = null, selectionObserved = false) }
            progress.toMap()
        }
        report(snapshot)
    }
    private fun update(captured: Long, catalog: LanSyncGroupCloudRoutes, id: SyncStoreId, text: String, observed: Long? = null) {
        val revision = catalog.revision.value
        val enabled = try { catalog.rows().filter { it.enabled }.mapTo(linkedSetOf()) { it.id } } catch (_: Exception) { emptySet() }
        val snapshot = synchronized(lock) {
            if (closed || captured != generation) return
            snapshotCatalog = catalog
            snapshotRevision = if (catalog.revision.value == revision) revision else -1
            progress.keys.retainAll(enabled)
            val previous = progress[id]
            if (id in enabled) progress[id] = LanSyncGroupCloudRouteProgress(text, observed ?: previous?.lastObservedAt,
                pendingOperations = null, selectionObserved = observed != null)
            progress.toMap()
        }
        report(snapshot)
    }
    private fun failureText(failure: Exception): String = when (failure) {
        is CiphertextStoreException -> when (failure.failure) {
            CiphertextStoreFailure.AUTHENTICATION -> "账号验证未通过，待传资料已保留"
            CiphertextStoreFailure.PERMISSION -> "此网盘未允许本次访问，待传资料已保留"
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
    private class ActiveIo(private val active: () -> Unit) : Interceptor {
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
    companion object { private const val OWNER = "cloud" }
}
