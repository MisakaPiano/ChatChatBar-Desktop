package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LanSyncGroupFileRecoveryReview(val binding: String, val group: String, val rootFingerprint: String,
    val checkpoint: String, val memberCount: Int, val device: String, val name: String) {
    override fun toString() = "LanSyncGroupFileRecoveryReview(REDACTED)"
}
data class LanSyncGroupFileRecoveryState(val visible: Boolean = false, val busy: Boolean = false,
    val status: String = "选择加密文件并输入恢复码，核对后再恢复", val error: String? = null,
    val review: LanSyncGroupFileRecoveryReview? = null, val complete: Boolean = false, val pending: Boolean = false) {
    override fun toString() = "LanSyncGroupFileRecoveryState(REDACTED)"
}

/** Explicit file recovery only. Construction/open creates no identity, profile or file. */
class LanSyncGroupFileRecoveryOwner internal constructor(private val root: () -> Path,
    private val joining: () -> LanSyncGroupJoining, private val deviceName: String,
    private val beforeSetup: suspend () -> Unit,
    private val dataWork: suspend (suspend () -> Boolean) -> Boolean,
    private val onReady: suspend (LanSyncGroupRuntimeAccess) -> Unit,
    private val clockMillis: () -> Long, private val observe: (String) -> Unit) {
    constructor(root: () -> Path, joining: () -> LanSyncGroupJoining, deviceName: String,
        beforeSetup: suspend () -> Unit = {}, dataWork: suspend (suspend () -> Boolean) -> Boolean = { it() },
        onReady: suspend (LanSyncGroupRuntimeAccess) -> Unit) : this(root, joining, deviceName,
        beforeSetup, dataWork, onReady, { System.nanoTime() / 1_000_000L }, {})

    private class Input(file: () -> InputStream, private val code: CharArray) {
        private var factory: (() -> InputStream)? = file
        fun takeFile() = synchronized(this) { requireNotNull(factory) { "SYNC_GROUP_FILE_RECOVERY_LOCKED" }.also { factory = null } }
        fun code() = synchronized(this) { check(code.isNotEmpty() && code.any { it != '\u0000' }); String(code) }
        fun clearCode() = synchronized(this) { code.fill('\u0000') }
        fun close() = synchronized(this) { factory = null; code.fill('\u0000') }
        override fun toString() = "FileRecoveryInput(REDACTED)"
    }
    private class OwnedInput(source: InputStream) : FilterInputStream(source) {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) super.close() }
    }
    private class Session(val generation: Long, val input: Input) {
        val id = SyncStoreId.random()
        val cleaned = AtomicBoolean()
        @Volatile var stream: OwnedInput? = null
        @Volatile var stage: LanSyncGroupEncryptedFilePackage.Staged? = null
        @Volatile var source: LanSyncGroupFileRecoverySource? = null
        @Volatile var vault: LanSyncGroupCloudRecoveryPreview? = null
        @Volatile var manager: LanSyncGroupManagementRecovery? = null
        @Volatile var daily: LanSyncGroupJoining? = null
        @Volatile var root: Path? = null
        @Volatile var managementStarted: Long? = null
        @Volatile var dataPhase = false
        @Volatile var readyAccess: LanSyncGroupRuntimeAccess? = null
        var approval: LanSyncGroupRecoveryDevicePreview? = null
        var review: LanSyncGroupFileRecoveryReview? = null
        var expiry: Job? = null
    }
    private sealed interface Result {
        data class Review(val value: LanSyncGroupFileRecoveryReview, val resumed: Boolean) : Result
        data class Ready(val access: LanSyncGroupRuntimeAccess, val pending: Boolean) : Result
        data object Waiting : Result
    }
    private val lifecycle = Any()
    private val admission = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = linkedSetOf<Job>()
    private val cleanups = linkedSetOf<Job>()
    private val cleanupFailures = mutableListOf<Exception>()
    private var generation = 0L
    private var closed = false
    private var session: Session? = null
    private val mutableState = MutableStateFlow(LanSyncGroupFileRecoveryState())
    val state = mutableState.asStateFlow()

    fun open() = synchronized(lifecycle) { if (!closed) mutableState.value = mutableState.value.copy(visible = true) }

    /** The factory is called once off the lifecycle lock; this owner closes the returned stream. */
    fun prepare(file: () -> InputStream, code: String) {
        if (synchronized(lifecycle) { closed }) return
        val input = try {
            require(deviceName.isNotBlank() && deviceName.length <= 160)
            SyncManagementSession.locate(code)
            Input(file, code.toCharArray())
        } catch (_: Exception) {
            invalidate(false, "输入尚未通过检查，请核对恢复码", "请检查所选文件和恢复码。", true)
            return
        }
        val next: Session; val previous: Session?; val pending: List<Job>; val cleanup: Job?
        synchronized(lifecycle) {
            if (closed) { input.close(); return }
            generation++; previous = session; pending = jobs.toList()
            cleanup = previous?.let { cleanupJobLocked(it, pending) }
            next = Session(generation, input); session = next
            mutableState.value = LanSyncGroupFileRecoveryState(visible = true, busy = true, status = "正在读取加密文件；尚未解锁管理授权")
        }
        release(previous); pending.forEach { it.cancel() }; cleanup?.start()
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
    /** Only already confirmed data work may continue after the management UI is hidden. */
    fun hide() {
        val management = synchronized(lifecycle) {
            if (!closed && session?.dataPhase == true) {
                mutableState.value = mutableState.value.copy(visible = false, review = null); false
            } else true
        }
        if (management) lock()
    }
    fun lockManagement() = hide()
    fun lock() = invalidate(false, "恢复已锁定，已有进度已保留", null, false)
    private fun invalidate(terminal: Boolean, status: String, error: String?, visible: Boolean,
        expected: Session? = null, reviewOnly: Boolean = false) {
        val previous: Session?; val pending: List<Job>; val cleanup: Job?
        synchronized(lifecycle) {
            if (expected != null && session !== expected) return
            if (reviewOnly && session?.dataPhase == true) return
            if (terminal) closed = true
            generation++; previous = session; session = null; pending = jobs.toList()
            cleanup = previous?.let { cleanupJobLocked(it, pending) }
            mutableState.value = LanSyncGroupFileRecoveryState(visible = visible, status = status, error = error)
        }
        release(previous); pending.forEach { it.cancel() }; cleanup?.start()
    }
    private fun release(value: Session?) {
        if (value == null) return
        value.expiry?.cancel(); value.input.close()
        listOf<() -> Unit>({ value.source?.close() }, { value.vault?.close() }, { value.manager?.lock() }).forEach {
            try { it() } catch (failure: Exception) { synchronized(lifecycle) { cleanupFailures += failure } }
        }
    }
    private fun cleanup(value: Session) {
        if (!value.cleaned.compareAndSet(false, true)) return
        release(value); closeInput(value)
        listOf<() -> Unit>({ value.manager?.close() }, { value.stage?.close() }).forEach {
            try { it() } catch (failure: Exception) { synchronized(lifecycle) { cleanupFailures += failure } }
        }
        value.stage = null
    }
    private fun closeInput(value: Session) {
        val stream = synchronized(lifecycle) { value.stream.also { value.stream = null } }
        try { stream?.close() } catch (failure: Exception) { synchronized(lifecycle) { cleanupFailures += failure } }
    }
    /** Register before releasing the lifecycle lock, so concurrent close/drain cannot miss cleanup. */
    private fun cleanupJobLocked(value: Session, pending: List<Job>): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            withContext(NonCancellable) { closeInput(value); pending.joinAll(); cleanup(value) }
        }
        cleanups += job
        job.invokeOnCompletion { synchronized(lifecycle) { cleanups -= job } }
        return job
    }
    private fun launch(value: Session, work: suspend () -> Result) {
        val job = scope.launch(start = CoroutineStart.LAZY) action@ {
            var keepReview = false
            try {
                val result = admission.withLock {
                    checkSession(value); var result: Result? = null
                    val performed = dataWork {
                        currentCoroutineContext().ensureActive(); checkSession(value); result = work(); true
                    }
                    check(performed && result != null) { "SYNC_GROUP_FILE_RECOVERY_WORK_NOT_STARTED" }
                    checkSession(value); requireNotNull(result)
                }
                if (result is Result.Ready) { checkSession(value); onReady(result.access); checkSession(value) }
                synchronized(lifecycle) {
                    if (closed || session !== value || generation != value.generation) return@action
                    mutableState.value = when (result) {
                        is Result.Review -> {
                            keepReview = true
                            mutableState.value.copy(busy = false, error = null, review = result.value,
                                status = if (result.resumed) "此设备已获原设备组批准；请核对后接续本机恢复"
                                    else "已核验加密文件和原管理资料；确认后才授权这台设备加入")
                        }
                        Result.Waiting -> mutableState.value.copy(busy = false, review = null, complete = false, pending = true,
                            status = "原管理权已恢复，本机仍有资料待检查或审阅", error = "部分资料尚未齐备，或需要在同步页继续审阅。")
                        is Result.Ready -> mutableState.value.copy(busy = false, review = null, complete = true, pending = result.pending, error = null,
                            status = if (result.pending) "已恢复原设备组并加入；仍有资料待审阅，原加密文件保持不变"
                                else "已恢复原设备组并核验本机资料；原加密文件保持不变")
                    }
                    if (!keepReview && session === value) session = null
                }
            } catch (cancelled: CancellationException) {
                fail(value, "恢复已中止，已有进度已保留", "本次恢复已中止，可重新选择原文件并接续。")
                throw cancelled
            } catch (_: Exception) {
                fail(value, "本次恢复尚未完成，已有资料和恢复进度已保留", "所选文件、恢复码或资料核验未通过，请重新读取并核对。")
            } finally { if (!keepReview) cleanup(value) }
        }
        synchronized(lifecycle) {
            if (closed || session !== value || generation != value.generation) { job.cancel(); return }
            jobs += job
        }
        job.invokeOnCompletion { synchronized(lifecycle) { jobs -= job } }; job.start()
    }
    private fun fail(value: Session, status: String, error: String) = synchronized(lifecycle) {
        if (!closed && session === value && generation == value.generation) {
            mutableState.value = mutableState.value.copy(busy = false, review = null, complete = false, status = status, error = error)
            session = null
        }
    }
    private fun checkSession(value: Session) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_GROUP_FILE_RECOVERY_INTERRUPTED")
        synchronized(lifecycle) { check(!closed && session === value && generation == value.generation) { "SYNC_GROUP_FILE_RECOVERY_LOCKED" } }
        if (!value.dataPhase) value.managementStarted?.let {
            check(clockMillis() - it < LIFETIME_MILLIS) { "SYNC_GROUP_FILE_RECOVERY_EXPIRED" }
        }
    }
    private fun startManagement(value: Session) {
        synchronized(lifecycle) { checkSession(value); value.managementStarted = clockMillis() }
        val expiry = scope.launch(start = CoroutineStart.LAZY) {
            delay(LIFETIME_MILLIS)
            invalidate(false, "管理授权已到期，请重新读取并核对", null, false, value, reviewOnly = true)
        }
        synchronized(lifecycle) { if (session === value && !closed) value.expiry = expiry else expiry.cancel() }; expiry.start()
        observe("FILE_RECOVERY_MANAGEMENT_STARTED")
    }
    private fun <T> own(value: Session, item: T, dispose: (T) -> Unit, install: (T) -> Unit): T {
        try { synchronized(lifecycle) { checkSession(value); install(item) }; return item }
        catch (failure: Throwable) { try { dispose(item) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure }
    }
    private suspend fun prepareOwned(value: Session): Result {
        checkSession(value); beforeSetup(); checkSession(value)
        val base = runInterruptible(Dispatchers.IO) { checkSession(value); root().also { lanSyncSafe(it, true) } }
        value.root = base
        val read = runInterruptible(Dispatchers.IO) { Files.createDirectory(base.resolve("read-${value.id.value}")) }
        val packageWork = runInterruptible(Dispatchers.IO) { Files.createDirectory(read.resolve("package")) }
        val staged = runInterruptible(Dispatchers.IO) {
            val stream = own(value, OwnedInput(value.input.takeFile()()), OwnedInput::close) { value.stream = it }
            try {
                stream.use { input ->
                    own(value, LanSyncGroupEncryptedFilePackage.stage(input, packageWork,
                        { lanSyncRecoveryInputActive { checkSession(value) } }), LanSyncGroupEncryptedFilePackage.Staged::close) { value.stage = it }
                }
            } finally { synchronized(lifecycle) { if (value.stream === stream) value.stream = null } }
        }
        val source = runInterruptible(Dispatchers.IO) {
            val path = Files.createDirectory(read.resolve("source"))
            own(value, LanSyncGroupFileRecoverySource(path, staged) { checkSession(value) }, LanSyncGroupFileRecoverySource::close) { value.source = it }
        }
        observe("FILE_RECOVERY_STAGED")
        startManagement(value)
        val vault = runInterruptible(Dispatchers.IO) {
            own(value, source.prepare(value.input.code()), LanSyncGroupCloudRecoveryPreview::close) { value.vault = it }
        }
        checkSession(value)
        val daily = joining()
        checkSession(value); value.daily = daily
        var profile = daily.profile(); val known = daily.recoveryAnchor()
        require(profile == null || known != null) { "SYNC_GROUP_FILE_RECOVERY_OTHER_JOIN_EXISTS" }
        known?.let { requireSame(it, vault) }
        // The portable joining anchor is historical once daily custody has advanced.
        val readyAccess = if (profile?.stage == LanSyncGroupJoinStage.READY) requireNotNull(daily.runtimeAccess()) else null
        val management = runInterruptible(Dispatchers.IO) {
            val path = base.resolve("management"); if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
            lanSyncSafe(path, true)
            own(value, LanSyncGroupManagementRecovery(path, knownCheckpoint = {
                readyAccess?.ledger()?.current()?.reference() ?: known?.trust?.checkpoint
            }),
                LanSyncGroupManagementRecovery::close) { value.manager = it }
        }
        try {
            if (Files.exists(base.resolve("management/profile.protected"), NOFOLLOW_LINKS)) {
                source.withBackend { management.resumeImport(value.input.code(), it) }
                checkSession(value)
                if (management.requiresInputRefreshBeforeResume()) {
                    source.withBackend { management.refresh(it) }
                    checkSession(value)
                }
                management.resumeAdministration()
                val local = management.recoveryBootstrap()
                source.withControl(vault, local) { management.refresh(it) }
            } else source.recover(vault, value.input.code(), management, LanSyncManagementRecoveryAdmission { trust ->
                checkSession(value); require(trust == vault.trust) { "SYNC_GROUP_FILE_RECOVERY_TRUST_CHANGED" }
            })
            source.verifyRecoveredInventory(management)
            observe("FILE_RECOVERY_INVENTORY_VERIFIED")
        } finally { value.input.clearCode() }
        checkSession(value)
        val controls = management.recoveryBootstrap(); requireSame(controls.anchor, vault)
        if (readyAccess != null) {
            val access = readyAccess
            val current = access.ledger().current()
            require(current.members[access.device]?.active == true && access.trust.group == vault.trust.group &&
                access.trust.genesis == vault.trust.genesis && access.trust.ownerCertificate == vault.trust.ownerCertificate) {
                "SYNC_GROUP_FILE_RECOVERY_DAILY_KEYS_CHANGED"
            }
            value.readyAccess = access
            return review(value, vault, current, access.device, resumed = true)
        }
        if (profile == null) profile = daily.prepareRecovery(controls.anchor) { checkSession(value); requireSame(it, vault) }
        if (profile.stage == LanSyncGroupJoinStage.PREPARING) profile = daily.resumePreparation()
        val member = requireNotNull(daily.recoveryMember()) { "SYNC_GROUP_FILE_RECOVERY_DAILY_KEYS_MISSING" }
        val ledger = management.ledger().current(); val admitted = ledger.members[member.first]
        if (admitted != null) require(admitted == member.second && admitted.active) { "SYNC_GROUP_FILE_RECOVERY_DAILY_KEYS_CHANGED" }
        else {
            require(profile.stage == LanSyncGroupJoinStage.PREPARED) { "SYNC_GROUP_FILE_RECOVERY_APPROVAL_MISSING" }
            value.approval = daily.prepareRecoveryDevice(management, deviceName)
        }
        return review(value, vault, ledger, member.first, admitted != null)
    }
    private fun review(value: Session, vault: LanSyncGroupCloudRecoveryPreview, ledger: SyncDeviceLedgerRecord,
        device: String, resumed: Boolean): Result.Review {
        val binding = value.approval?.request?.approvalBinding ?: lanSyncHash(
            "FILE-RECOVERY-RESUME\n${value.id.value}\n${ledger.reference()}\n$device".toByteArray(Charsets.UTF_8))
        val review = LanSyncGroupFileRecoveryReview(binding, vault.trust.group.value,
            SyncDeviceSignatures.decode(vault.trust.ownerCertificate).fingerprint, "${ledger.id.value}:${ledger.bodyHash()}",
            ledger.members.size, device, deviceName)
        value.review = review; return Result.Review(review, resumed)
    }
    private suspend fun confirmOwned(value: Session, binding: String): Result {
        checkSession(value); require(value.review?.binding == binding) { "SYNC_GROUP_FILE_RECOVERY_REVIEW_CHANGED" }
        val source = requireNotNull(value.source); val vault = requireNotNull(value.vault); val manager = requireNotNull(value.manager)
        source.verifyRecoveredInventory(manager) // Recheck before any root approval/signature.
        checkSession(value)
        val daily = requireNotNull(value.daily)
        if (value.readyAccess == null) {
            val member = requireNotNull(daily.recoveryMember()); val known = manager.ledger().current().members[member.first]
            if (known == null) manager.addRecoveryDevice(requireNotNull(value.approval).acknowledge(binding))
            else require(known == member.second && known.active) { "SYNC_GROUP_FILE_RECOVERY_DAILY_KEYS_CHANGED" }
        }
        checkSession(value)
        val controls = manager.retainRecoveryBootstrap(requireNotNull(value.root).resolve("controls-${value.id.value}")) { checkSession(value) }
        val profile = requireNotNull(daily.profile())
        if (profile.stage in setOf(LanSyncGroupJoinStage.PREPARED, LanSyncGroupJoinStage.WAITING_FOR_APPROVAL)) daily.acceptRecoveryBootstrap(controls.anchor)
        else requireNotNull(daily.recoveryAnchor()).let { requireSame(it, vault) }
        return source.withControl(vault, controls) { backend ->
            beginDataPhase(value, manager)
            value.readyAccess?.let { access ->
                require(access.ledger().current().members[access.device]?.active == true) { "SYNC_GROUP_FILE_RECOVERY_DAILY_KEYS_CHANGED" }
                return@withControl finishNative(value, access, backend)
            }
            val joined = daily.activate(backend); checkSession(value)
            if (joined.profile.stage != LanSyncGroupJoinStage.READY || joined.node == null) Result.Waiting
            else finishNative(value, requireNotNull(daily.runtimeAccess()), backend)
        }
    }
    private suspend fun finishNative(value: Session, access: LanSyncGroupRuntimeAccess, backend: CiphertextStore): Result.Ready {
        checkSession(value)
        access.reader.routes("filerecovery", listOf(LanSyncGroupReplicaReader.Route("FILE_RECOVERY:${value.id.value}", backend)))
        try {
            var cursor: CommitCursor? = null
            do { checkSession(value); cursor = access.node.discover(backend, cursor).nextCursor } while (cursor != null)
            val inspected = access.node.inspect()
            inspected.plan?.let { checkSession(value); access.node.apply(it) }
            checkSession(value); val after = access.node.inspect()
            val pending = after.stage != LanSyncGroupNodeStage.READY || after.nativeState.pendingTransaction != null ||
                after.plan == null || after.plan.units.values.any {
                it.stage !in setOf(LanSyncGroupNativeStage.UNCHANGED, LanSyncGroupNativeStage.READY_OBSERVATION)
            }
            return Result.Ready(access, pending)
        } finally { access.reader.removeRoutes("filerecovery") }
    }
    private fun beginDataPhase(value: Session, manager: LanSyncGroupManagementRecovery) {
        checkSession(value); manager.lock(); check(!manager.isUnlocked) { "SYNC_GROUP_FILE_RECOVERY_MANAGEMENT_STILL_UNLOCKED" }
        synchronized(lifecycle) {
            checkSession(value); value.dataPhase = true; value.expiry?.cancel(); value.expiry = null
            value.approval = null; value.review = null; value.input.clearCode(); value.vault?.close(); value.vault = null; value.manager = null
            mutableState.value = mutableState.value.copy(review = null, status = "管理授权已锁定，正在核验并恢复资料；可随时停止并保留进度")
        }
        manager.close(); observe("FILE_RECOVERY_MANAGEMENT_LOCKED"); observe("FILE_RECOVERY_DATA_PHASE")
    }
    private fun requireSame(anchor: LanSyncGroupRecoveryAnchor, vault: LanSyncGroupCloudRecoveryPreview) {
        require(anchor.trust.group == vault.trust.group && anchor.trust.genesis == vault.trust.genesis &&
            anchor.trust.ownerCertificate == vault.trust.ownerCertificate && anchor.vault == vault.vault &&
            anchor.vaultDescriptor == vault.descriptor) { "SYNC_GROUP_FILE_RECOVERY_TRUST_CHANGED" }
    }
    suspend fun drain() = withContext(NonCancellable) {
        while (true) {
            val pending = synchronized(lifecycle) { jobs.toList() + cleanups.toList() }
            if (pending.isEmpty()) break
            pending.joinAll()
        }
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) {
        invalidate(true, "恢复已关闭", null, false); drain(); scope.coroutineContext[Job]?.cancelAndJoin()
        val failures = synchronized(lifecycle) { cleanupFailures.toList().also { cleanupFailures.clear() } }
        if (failures.isNotEmpty()) throw IllegalStateException("SYNC_GROUP_FILE_RECOVERY_CLEANUP_PENDING").also { result -> failures.forEach(result::addSuppressed) }
    }
    override fun toString() = "LanSyncGroupFileRecoveryOwner(REDACTED)"
    companion object { private const val LIFETIME_MILLIS = 5L * 60 * 1000 }
}
