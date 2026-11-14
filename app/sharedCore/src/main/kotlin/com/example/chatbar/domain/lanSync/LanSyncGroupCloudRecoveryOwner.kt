package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class LanSyncGroupCloudRecoveryReview(val binding: String, val group: String, val rootFingerprint: String,
    val checkpoint: String, val memberCount: Int, val device: String, val name: String) {
    override fun toString() = "LanSyncGroupCloudRecoveryReview(REDACTED)"
}
data class LanSyncGroupCloudRecoveryState(val visible: Boolean = false, val busy: Boolean = false,
    val status: String = "填写网盘目录和恢复码，核对原设备组后再确认", val error: String? = null,
    val review: LanSyncGroupCloudRecoveryReview? = null, val complete: Boolean = false) {
    override fun toString() = "LanSyncGroupCloudRecoveryState(REDACTED)"
}

/** A page-owned temporary recovery session. Only explicit preparation creates private local owners. */
class LanSyncGroupCloudRecoveryOwner private constructor(private val root: () -> Path,
    private val joining: () -> LanSyncGroupJoining, private val deviceName: String,
    private val beforeSetup: suspend () -> Unit, private val networkAllowed: () -> Boolean,
    private val cloudWork: suspend (suspend () -> Boolean) -> Boolean,
    private val onReady: suspend (LanSyncGroupRuntimeAccess) -> Unit,
    private val clientProvider: () -> OkHttpClient, private val ownsClient: Boolean,
    private val endpointPolicy: WebDavEndpointPolicy) {
    constructor(root: () -> Path, joining: () -> LanSyncGroupJoining, deviceName: String,
        beforeSetup: suspend () -> Unit = {}, networkAllowed: () -> Boolean = { true },
        cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() },
        onReady: suspend (LanSyncGroupRuntimeAccess) -> Unit) : this(root, joining, deviceName, beforeSetup,
        networkAllowed, cloudWork, onReady, { ProxyAwareClient.builder().build() }, true, WebDavEndpointPolicy.HTTPS_ONLY)

    /** Synthetic transport boundary only. Production always uses the HTTPS constructor. */
    internal constructor(root: () -> Path, joining: () -> LanSyncGroupJoining, deviceName: String,
        beforeSetup: suspend () -> Unit = {}, networkAllowed: () -> Boolean = { true },
        cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() },
        onReady: suspend (LanSyncGroupRuntimeAccess) -> Unit, client: OkHttpClient,
        endpointPolicy: WebDavEndpointPolicy) : this(root, joining, deviceName, beforeSetup, networkAllowed,
        cloudWork, onReady, { client }, false, endpointPolicy)

    private class Input(val credentials: LanSyncGroupCloudCredentials, private val code: CharArray) {
        fun code() = synchronized(this) { check(code.isNotEmpty() && code.any { it != '\u0000' }); String(code) }
        fun clearCode() = synchronized(this) { code.fill('\u0000') }
        fun close() { clearCode(); credentials.close() }
        override fun toString() = "CloudRecoveryInput(REDACTED)"
    }
    private class Session(val generation: Long, val input: Input) {
        val started = System.nanoTime()
        val id = SyncStoreId.random()
        @Volatile var transport: WebDavHttpTransport? = null
        @Volatile var client: OkHttpClient? = null
        @Volatile var source: LanSyncGroupCloudRecoverySource? = null
        @Volatile var manager: LanSyncGroupManagementRecovery? = null
        @Volatile var vault: LanSyncGroupCloudRecoveryPreview? = null
        @Volatile var daily: LanSyncGroupJoining? = null
        var approval: LanSyncGroupRecoveryDevicePreview? = null
        var review: LanSyncGroupCloudRecoveryReview? = null
        var expiry: Job? = null
    }
    private sealed interface Result {
        data class Review(val value: LanSyncGroupCloudRecoveryReview, val resumed: Boolean) : Result
        data class Ready(val access: LanSyncGroupRuntimeAccess, val pending: Boolean = false) : Result
        data class Waiting(val code: String?) : Result
    }
    private val lifecycle = Any()
    private val admission = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = linkedSetOf<Job>()
    private var generation = 0L
    private var closed = false
    private var session: Session? = null
    private val mutableState = MutableStateFlow(LanSyncGroupCloudRecoveryState())
    val state = mutableState.asStateFlow()

    fun open() = synchronized(lifecycle) {
        if (!closed) mutableState.value = mutableState.value.copy(visible = true)
    }

    /** Copies the borrowed password before returning, so the UI may erase its input immediately. */
    fun prepare(draft: LanSyncGroupCloudDraft, code: String) {
        if (synchronized(lifecycle) { closed }) return
        val input = try { snapshot(draft, code) } catch (_: Exception) {
            invalidate(false, "输入尚未通过检查，请核对 HTTPS 目录、账号和恢复码", "请检查网盘目录、账号和恢复码。", true)
            return
        }
        val next: Session
        val previous: Session?
        val pending: List<Job>
        synchronized(lifecycle) {
            if (closed) { input.close(); return }
            generation++; previous = session; pending = jobs.toList()
            next = Session(generation, input); session = next
            mutableState.value = LanSyncGroupCloudRecoveryState(visible = true, busy = true, status = "正在读取并核验原设备组")
        }
        release(previous); pending.forEach { it.cancel() }
        val expiry = scope.launch(start = CoroutineStart.LAZY) { delay(LIFETIME_MILLIS); expire(next) }
        synchronized(lifecycle) { if (session === next && !closed) next.expiry = expiry else expiry.cancel() }
        expiry.start()
        launch(next) { prepareOwned(next) }
    }

    fun confirm(binding: String) {
        val current = synchronized(lifecycle) {
            val value = session
            if (closed || value == null || mutableState.value.busy) return
            if (value.review?.binding != binding) {
                mutableState.value = mutableState.value.copy(error = "核对信息已失效，请重新读取。")
                return
            }
            mutableState.value = mutableState.value.copy(busy = true, error = null, status = "正在接续已核对的原设备组恢复")
            value
        }
        launch(current) { confirmOwned(current, binding) }
    }

    /** Synchronous privacy boundary; admitted native work remains tracked until drain joins it. */
    fun lock() = invalidate(false, "恢复已锁定，临时连接已关闭", null, false)
    private fun expire(value: Session) {
        synchronized(lifecycle) { if (closed || session !== value) return }
        invalidate(false, "恢复已到期，请重新输入并核对", null, false, value)
    }
    private fun invalidate(terminal: Boolean, status: String, error: String?, visible: Boolean, expected: Session? = null) {
        val previous: Session?
        val pending: List<Job>
        synchronized(lifecycle) {
            if (expected != null && session !== expected) return
            if (terminal) closed = true
            generation++; previous = session; session = null; pending = jobs.toList()
            mutableState.value = LanSyncGroupCloudRecoveryState(visible = visible, status = status, error = error)
        }
        release(previous); pending.forEach { it.cancel() }
    }

    private fun release(value: Session?) {
        if (value == null) return
        value.expiry?.cancel()
        value.input.close()
        value.transport?.close()
        value.source?.close()
        value.vault?.close()
        value.manager?.lock()
        if (ownsClient) value.client?.let { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
    }

    private fun launch(value: Session, work: suspend () -> Result) {
        val job = scope.launch(start = CoroutineStart.LAZY) action@ {
            try {
                val result = admission.withLock {
                    checkSession(value, false)
                    var result: Result? = null
                    val performed = cloudWork {
                        currentCoroutineContext().ensureActive(); checkSession(value)
                        result = work(); true
                    }
                    check(performed && result != null) { "SYNC_GROUP_CLOUD_RECOVERY_TRANSFER_NOT_STARTED" }
                    checkSession(value, false)
                    requireNotNull(result)
                }
                // Coordinator may acquire its own admission here; neither owner lock is held.
                if (result is Result.Ready) {
                    checkSession(value, false)
                    onReady(result.access)
                    checkSession(value, false)
                }
                synchronized(lifecycle) {
                    if (closed || session !== value || generation != value.generation) return@action
                    mutableState.value = when (result) {
                        is Result.Review -> {
                            val status = if (result.resumed) "此设备已获原设备组批准；请核对后接续本机恢复" else "已核验原设备组；确认后才授权这台设备加入"
                            val rejected = value.vault?.rejectedVaultCandidates ?: 0
                            mutableState.value.copy(busy = false, review = result.value, error = null,
                                status = status + if (rejected > 0) "；已隔离${rejected}个未通过核验的恢复包候选，原始密文保留" else "")
                        }
                        is Result.Waiting -> mutableState.value.copy(busy = false, review = null, complete = false,
                            status = "原设备组管理已恢复，本机加入仍待资料或审阅；新管理变更尚未送达网盘", error = "部分资料尚未齐备，或需要在同步页继续审阅。")
                        is Result.Ready -> mutableState.value.copy(busy = false, review = null, complete = true, error = null,
                            status = if (result.pending) "已恢复原设备组并加入；仍有资料待审阅，新管理变更尚未送达网盘"
                                else "已恢复原设备组并加入；新管理变更尚未确认送达网盘")
                    }
                }
                if (result !is Result.Review) retire(value)
            } catch (cancelled: CancellationException) {
                fail(value, "恢复已中止，已保留本机进度", "本次恢复已中止，可重新读取并接续。")
                throw cancelled
            } catch (failure: Exception) {
                fail(value, "本次恢复尚未完成，已有资料和恢复进度已保留；请重新读取并核对", errorText(failure))
            }
        }
        synchronized(lifecycle) {
            if (closed || session !== value || generation != value.generation) { job.cancel(); return }
            jobs += job
        }
        job.invokeOnCompletion { synchronized(lifecycle) { jobs -= job } }
        job.start()
    }
    private fun retire(value: Session) {
        synchronized(lifecycle) { if (session === value) session = null }
        release(value)
        value.manager?.close()
    }
    private fun fail(value: Session, status: String, error: String) {
        synchronized(lifecycle) {
            if (!closed && session === value && generation == value.generation)
                mutableState.value = mutableState.value.copy(busy = false, review = null, complete = false, status = status, error = error)
        }
        retire(value)
    }
    private fun checkSession(value: Session, network: Boolean = true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("SYNC_GROUP_CLOUD_RECOVERY_INTERRUPTED")
        synchronized(lifecycle) {
            check(!closed && session === value && generation == value.generation) { "SYNC_GROUP_CLOUD_RECOVERY_LOCKED" }
        }
        check((System.nanoTime() - value.started) / 1_000_000 < LIFETIME_MILLIS) { "SYNC_GROUP_CLOUD_RECOVERY_EXPIRED" }
        if (network) check(networkAllowed()) { "SYNC_GROUP_CLOUD_RECOVERY_NETWORK_NOT_ALLOWED" }
    }
    private fun <T> own(value: Session, item: T, dispose: (T) -> Unit, install: (T) -> Unit): T {
        try {
            synchronized(lifecycle) { checkSession(value); install(item) }
            return item
        } catch (failure: Throwable) { dispose(item); throw failure }
    }

    private suspend fun prepareOwned(value: Session): Result {
        checkSession(value); beforeSetup(); checkSession(value)
        val daily = joining().also { value.daily = it }
        var profile = daily.profile()
        val known = daily.recoveryAnchor()
        require(profile == null || known != null) { "SYNC_GROUP_CLOUD_RECOVERY_OTHER_JOIN_EXISTS" }
        if (profile?.stage == LanSyncGroupJoinStage.READY) {
            require(requireNotNull(known).vault == SyncManagementSession.locate(value.input.code())) { "SYNC_GROUP_CLOUD_RECOVERY_TRUST_CHANGED" }
            return Result.Ready(requireNotNull(daily.runtimeAccess()))
        }
        val base = runInterruptible(Dispatchers.IO) { checkSession(value); root().also { lanSyncSafe(it, true) } }
        val read = runInterruptible(Dispatchers.IO) { Files.createDirectory(base.resolve("read-${value.id.value}")) }
        val client = own(value, clientProvider(), { if (ownsClient) { it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() } }) { value.client = it }
        val transport = own(value, WebDavHttpTransport(value.input.credentials.endpoint.toHttpUrl(), client, {
            checkSession(value)
            value.input.credentials.withPassword { password ->
                if (value.input.credentials.username.isEmpty() && password.isEmpty()) null
                else Credentials.basic(value.input.credentials.username, password.toString(Charsets.UTF_8), Charsets.UTF_8)
            }
        }, endpointPolicy), WebDavHttpTransport::close) { value.transport = it }
        fun directory(name: String) = Files.createDirectory(read.resolve(name))
        val source = runInterruptible(Dispatchers.IO) {
            checkSession(value)
            val whole = WeakWebDavAttemptStore(transport, directory("whole"), value.input.credentials.id)
            val parts = WeakWebDavChunkAssembly(transport, directory("parts"), value.input.credentials.id)
            own(value, LanSyncGroupCloudRecoverySource(directory("source"), whole, parts, { checkSession(value) }),
                LanSyncGroupCloudRecoverySource::close) { value.source = it }
        }
        val vault = runInterruptible(Dispatchers.IO) {
            own(value, source.prepare(value.input.code()), LanSyncGroupCloudRecoveryPreview::close) { value.vault = it }
        }
        known?.let { requireSame(it, vault) }
        val management = runInterruptible(Dispatchers.IO) {
            checkSession(value)
            val path = base.resolve("management")
            if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
            lanSyncSafe(path, true)
            own(value, LanSyncGroupManagementRecovery(path, knownCheckpoint = { known?.trust?.checkpoint }),
                LanSyncGroupManagementRecovery::close) { value.manager = it }
        }
        try {
            if (Files.exists(base.resolve("management/profile.protected"), NOFOLLOW_LINKS)) {
                source.withBackend { management.resumeImport(value.input.code(), it) }
                checkSession(value)
                management.resumeAdministration()
                val local = management.recoveryBootstrap()
                source.withControl(vault, local) { management.refresh(it) }
            } else source.recover(vault, value.input.code(), management, LanSyncManagementRecoveryAdmission { trust ->
                checkSession(value); require(trust == vault.trust) { "SYNC_GROUP_CLOUD_RECOVERY_TRUST_CHANGED" }
            })
        } finally { value.input.clearCode() }
        checkSession(value)
        val controls = management.recoveryBootstrap()
        requireSame(controls.anchor, vault)
        if (profile == null) profile = daily.prepareRecovery(controls.anchor) { checkSession(value); requireSame(it, vault) }
        if (profile.stage == LanSyncGroupJoinStage.PREPARING) profile = daily.resumePreparation()
        val member = requireNotNull(daily.recoveryMember()) { "SYNC_GROUP_CLOUD_RECOVERY_DAILY_KEYS_MISSING" }
        val ledger = management.ledger().current()
        val admitted = ledger.members[member.first]
        val reused = admitted != null
        if (reused) require(admitted == member.second && admitted?.active == true) { "SYNC_GROUP_CLOUD_RECOVERY_DAILY_KEYS_CHANGED" }
        else {
            require(profile.stage == LanSyncGroupJoinStage.PREPARED) { "SYNC_GROUP_CLOUD_RECOVERY_APPROVAL_MISSING" }
            value.approval = daily.prepareRecoveryDevice(management, deviceName)
        }
        val binding = value.approval?.request?.approvalBinding ?: lanSyncHash(
            "CLOUD-RECOVERY-RESUME\n${value.id.value}\n${ledger.reference()}\n${member.first}".toByteArray(Charsets.UTF_8))
        val review = LanSyncGroupCloudRecoveryReview(binding, vault.trust.group.value,
            SyncDeviceSignatures.decode(vault.trust.ownerCertificate).fingerprint,
            "${ledger.id.value}:${ledger.bodyHash()}", ledger.members.size, member.first, deviceName)
        value.review = review
        return Result.Review(review, reused)
    }

    private suspend fun confirmOwned(value: Session, binding: String): Result {
        checkSession(value)
        require(value.review?.binding == binding) { "SYNC_GROUP_CLOUD_RECOVERY_REVIEW_CHANGED" }
        val daily = requireNotNull(value.daily); val manager = requireNotNull(value.manager)
        val member = requireNotNull(daily.recoveryMember())
        val known = manager.ledger().current().members[member.first]
        if (known == null) manager.addRecoveryDevice(requireNotNull(value.approval).acknowledge(binding))
        else require(known == member.second && known.active) { "SYNC_GROUP_CLOUD_RECOVERY_DAILY_KEYS_CHANGED" }
        checkSession(value)
        val controls = manager.recoveryBootstrap()
        val profile = requireNotNull(daily.profile())
        if (profile.stage in setOf(LanSyncGroupJoinStage.PREPARED, LanSyncGroupJoinStage.WAITING_FOR_APPROVAL))
            daily.acceptRecoveryBootstrap(controls.anchor)
        else requireNotNull(daily.recoveryAnchor()).let { requireSame(it, requireNotNull(value.vault)) }
        checkSession(value)
        return requireNotNull(value.source).withControl(requireNotNull(value.vault), controls) { backend ->
            val joined = daily.activate(backend)
            checkSession(value)
            val node = joined.node
            if (joined.profile.stage != LanSyncGroupJoinStage.READY || node == null)
                Result.Waiting("SYNC_GROUP_CLOUD_RECOVERY_REVIEW_OR_DATA_REQUIRED")
            else {
                val inspected = node.inspect()
                inspected.plan?.let { checkSession(value); node.apply(it) }
                checkSession(value)
                val after = node.inspect().plan
                val pending = after == null || after.units.values.any {
                    it.stage !in setOf(LanSyncGroupNativeStage.UNCHANGED, LanSyncGroupNativeStage.READY_OBSERVATION)
                }
                Result.Ready(requireNotNull(daily.runtimeAccess()), pending)
            }
        }
    }
    private fun requireSame(anchor: LanSyncGroupRecoveryAnchor, vault: LanSyncGroupCloudRecoveryPreview) {
        require(anchor.trust.group == vault.trust.group && anchor.trust.genesis == vault.trust.genesis &&
            anchor.trust.ownerCertificate == vault.trust.ownerCertificate && anchor.vault == vault.vault &&
            anchor.vaultDescriptor == vault.descriptor) { "SYNC_GROUP_CLOUD_RECOVERY_TRUST_CHANGED" }
    }
    private fun errorText(failure: Exception): String = when {
        failure.message in setOf("SYNC_GROUP_CLOUD_RECOVERY_NETWORK_NOT_ALLOWED", "SYNC_GROUP_CLOUD_RECOVERY_TRANSFER_NOT_STARTED") ->
            "请回到前台并允许本次网盘传输。"
        failure.message == "SYNC_GROUP_CLOUD_RECOVERY_VAULT_UNAVAILABLE" ->
            "未找到与恢复码相符的完整管理资料，请核对网盘目录和恢复码。"
        failure.message == "SYNC_GROUP_CLOUD_RECOVERY_OTHER_JOIN_EXISTS" ->
            "此设备已有其他加入进度，请先在同步页检查。"
        failure is CiphertextStoreException -> when (failure.failure) {
            CiphertextStoreFailure.AUTHENTICATION -> "网盘账号验证未通过，请检查账号和密码。"
            CiphertextStoreFailure.PERMISSION -> "网盘未允许读取此目录，请检查权限。"
            CiphertextStoreFailure.QUOTA -> "本机或网盘空间不足，请释放空间后重试。"
            CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.OBJECT_CONFLICT, CiphertextStoreFailure.OPERATION_CONFLICT ->
                "网盘资料未通过完整性检查，原始资料已保留。"
            else -> "网盘本次读取尚未完成，已有资料和恢复进度已保留。"
        }
        else -> "本次恢复未能完成，请核对输入并重新读取；已有资料和进度已保留。"
    }
    private fun snapshot(draft: LanSyncGroupCloudDraft, code: String): Input {
        val password = draft.password.copyOf()
        try {
            require(draft.name.trim().let { it.isNotBlank() && it.length <= 160 && it.none { c -> c.code < 32 || c.code == 127 } })
            require(draft.username.length <= 4096 && draft.username.none { it.code < 32 || it.code == 127 })
            require(password.size <= 64 * 1024)
            val chars = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(password))
            if (chars.hasArray()) chars.array().fill('\u0000')
            require(draft.endpoint.length <= 8192)
            val url = requireNotNull(draft.endpoint.toHttpUrlOrNull())
            val synthetic = endpointPolicy == WebDavEndpointPolicy.SYNTHETIC_LOOPBACK_HTTP && url.scheme == "http" &&
                url.host in setOf("127.0.0.1", "::1", "[::1]")
            require((url.isHttps || synthetic) && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null && url.encodedPath.endsWith('/'))
            SyncManagementSession.locate(code)
            return Input(LanSyncGroupCloudCredentials(SyncStoreId.random(), url.toString(), draft.username, password), code.toCharArray())
        } finally { password.fill(0) }
    }

    suspend fun drain() = withContext(NonCancellable) {
        while (true) {
            val pending = synchronized(lifecycle) { jobs.toList() }
            if (pending.isEmpty()) break
            pending.joinAll()
        }
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) {
        invalidate(true, "恢复已关闭", null, false)
        drain()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
    override fun toString() = "LanSyncGroupCloudRecoveryOwner(REDACTED)"
    companion object { private const val LIFETIME_MILLIS = 5L * 60 * 1000 }
}
