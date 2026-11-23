package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private val reprepareJoinStages = setOf(LanSyncGroupJoinStage.KEYS_ADMITTED, LanSyncGroupJoinStage.NATIVE_ENROLLED)
private const val joiningLedgerConflictText = "原设备组的签名账本存在冲突。已保留本机身份和资料。请让邀请设备核对原组，再重新核对此申请。"
private const val refreshJoiningInvitationText = "请对方生成同组新邀请，再选择「更新邀请并继续」。"
private val localMaintenanceCodes = setOf("SYNC_BACKGROUND_WORK_BUSY", "SYNC_MAINTENANCE")
private val resumableJoinRouteWaits = setOf("SYNC_GROUP_BOOTSTRAP_MEMBERSHIP_MISSING", "SYNC_GROUP_BOOTSTRAP_LEDGER_INCOMPLETE",
    "SYNC_GROUP_BOOTSTRAP_HISTORICAL_KEY_MISSING", "SYNC_GROUP_JOIN_INITIAL_DATA_MISSING", "SYNC_GROUP_JOIN_KNOWN_CHECKPOINT_MISSING",
    "SYNC_GROUP_LEDGER_FORK_OR_MISSING", "SYNC_GROUP_KNOWN_CHECKPOINT_MISSING")

/** One application owner. Screens project state and lock secrets; they never own daily work. */
class LanSyncGroupCoordinator(private val hasExistingEvidence: () -> Boolean,
    private val enrollment: () -> LanSyncGroupEnrollment, private val joining: () -> LanSyncGroupJoining,
    private val network: LanSyncNetwork = LanSyncNetwork.Local, private val deviceName: String,
    private val beforeSetup: suspend () -> Unit = {},
    private val sourceChanges: Flow<Long>? = null, initiallyPaused: Boolean = false,
    private val cloudWorkAllowed: () -> Boolean = { true },
    private val cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() },
    private val cloudRecoveryFactory: ((suspend (LanSyncGroupRuntimeAccess) -> Unit) -> LanSyncGroupCloudRecoveryOwner)? = null,
    private val fileRecoveryFactory: ((suspend (LanSyncGroupRuntimeAccess) -> Unit) -> LanSyncGroupFileRecoveryOwner)? = null,
    private val fileWork: suspend (suspend () -> Boolean) -> Boolean = { it() },
    private val joinProofAllowed: () -> Boolean = { true }) : LanSyncGroupPanelActions, LanSyncGroupCloudSettingsActions {
    /** Actual synthetic transport construction only; production always uses the public constructor. */
    internal constructor(hasExistingEvidence: () -> Boolean, enrollment: () -> LanSyncGroupEnrollment,
        joining: () -> LanSyncGroupJoining, network: LanSyncNetwork, deviceName: String,
        cloudRuntimeFactory: (LanSyncGroupRuntimeAccess, () -> Boolean, () -> Unit,
            (Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) -> Unit) -> LanSyncGroupCloudRuntime,
        sourceChanges: Flow<Long>? = null, initiallyPaused: Boolean = false,
        cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() }) : this(hasExistingEvidence,
        enrollment, joining, network, deviceName, sourceChanges = sourceChanges, initiallyPaused = initiallyPaused,
        cloudWork = cloudWork) {
        makeCloudRuntime = cloudRuntimeFactory
    }
    private var makeCloudRuntime: (LanSyncGroupRuntimeAccess, () -> Boolean, () -> Unit,
        (Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) -> Unit) -> LanSyncGroupCloudRuntime = { owner, allowed, wake, report ->
        LanSyncGroupCloudRuntime(owner, allowed, wake, report)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admission = Mutex()
    private val cloudRecoveryOwner = lazy {
        cloudRecoveryFactory?.invoke { restored -> admission.withLock {
            check(!closed) { "SYNC_GROUP_COORDINATOR_CLOSED" }
            mutableState.update { it.copy(canResumeExternalRecovery = true) }
            attach(restored)
        } }
    }
    val cloudRecovery: LanSyncGroupCloudRecoveryOwner? get() = synchronized(lifecycle) {
        if (closed) null else cloudRecoveryOwner.value
    }
    private val fileRecoveryOwner = lazy {
        fileRecoveryFactory?.invoke { restored -> admission.withLock {
            check(!closed) { "SYNC_GROUP_COORDINATOR_CLOSED" }
            mutableState.update { it.copy(canResumeExternalRecovery = true) }
            attach(restored)
        } }
    }
    val fileRecovery: LanSyncGroupFileRecoveryOwner? get() = synchronized(lifecycle) {
        if (closed) null else fileRecoveryOwner.value
    }
    private val fileExportOwner = lazy {
        LanSyncGroupEncryptedFileExportOwner(LanSyncGroupFilePackagePreparer { checkActive, receive ->
            val current = admission.withLock {
                checkActive(); check(!closed) { "SYNC_GROUP_COORDINATOR_CLOSED" }
                requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
                // Explicit export seals only safe local proposals. It never applies incoming data.
                check(cycle(force = true, shutdown = true)) { "SYNC_GROUP_FILE_EXPORT_LOCAL_NOT_READY" }
                requestCloudCheck(dirty = true)
                requireNotNull(access)
            }
            checkActive()
            val directory = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                current.work.resolve("file-export").also {
                    if (!java.nio.file.Files.exists(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)) java.nio.file.Files.createDirectory(it)
                    lanSyncSafe(it, true)
                }
            }
            current.prepareEncryptedFile(directory, {
                checkActive()
                check(!closed && access === current) { "SYNC_GROUP_FILE_EXPORT_DEVICE_CHANGED" }
            }, receive)
        }, fileWork)
    }
    val fileExport: LanSyncGroupEncryptedFileExportOwner? get() = synchronized(lifecycle) {
        if (closed) null else fileExportOwner.value
    }
    private fun externalRecoveryAllowed(): Boolean {
        val current = mutableState.value
        return !current.busy && current.canEnterExternalRecovery
    }
    fun openCloudRecovery(): Unit = synchronized(lifecycle) {
        if (closed || !externalRecoveryAllowed()) return@synchronized
        if (fileRecoveryOwner.isInitialized() && fileRecoveryOwner.value?.state?.value?.busy == true) {
            mutableState.update { it.copy(status = "加密文件恢复仍在进行，请先继续或停止当前恢复") }; return@synchronized
        }
        if (fileRecoveryOwner.isInitialized()) fileRecoveryOwner.value?.lock()
        cloudRecoveryOwner.value?.open()
        Unit
    }
    fun openFileRecovery(): Unit = synchronized(lifecycle) {
        if (closed || !externalRecoveryAllowed()) return@synchronized
        if (cloudRecoveryOwner.isInitialized() && cloudRecoveryOwner.value?.state?.value?.busy == true) {
            mutableState.update { it.copy(status = "网盘恢复仍在进行，请先继续或停止当前恢复") }; return@synchronized
        }
        if (cloudRecoveryOwner.isInitialized()) cloudRecoveryOwner.value?.lock()
        fileRecoveryOwner.value?.open()
        Unit
    }
    fun openFileExport() = synchronized(lifecycle) { if (!closed) fileExportOwner.value.open() }
    /** Preparation admission also guards delayed picker callbacks, not only modal opening. */
    fun prepareCloudRecovery(draft: LanSyncGroupCloudDraft, code: String): Boolean = synchronized(lifecycle) {
        if (closed || !externalRecoveryAllowed() || cloudRecoveryOwner.value == null) return@synchronized false
        if (cloudRecoveryOwner.value?.state?.value?.busy == true ||
            fileRecoveryOwner.isInitialized() && fileRecoveryOwner.value?.state?.value?.busy == true) return@synchronized false
        if (fileRecoveryOwner.isInitialized()) fileRecoveryOwner.value?.lock()
        requireNotNull(cloudRecoveryOwner.value).prepare(draft, code)
        true
    }
    private val fileRecoveryPickerOwner = UUID.randomUUID().toString()

    /** A rotation retains this selection scope only while this root and enrollment are current. */
    fun fileRecoveryPickerBinding(): String? = synchronized(lifecycle) {
        val current = mutableState.value
        if (closed || !externalRecoveryAllowed() || current.membershipConflict != null) return@synchronized null
        val context = when (current.stage) {
            LanSyncGroupPanelStage.EMPTY -> "empty"
            LanSyncGroupPanelStage.JOINING -> current.fileRecoveryJoinScope ?: return@synchronized null
            LanSyncGroupPanelStage.READY -> access?.let { "${it.trust.genesis}:${it.device}" } ?: return@synchronized null
            else -> return@synchronized null
        }
        "$fileRecoveryPickerOwner:${current.stage}:$context"
    }

    fun prepareFileRecovery(file: () -> java.io.InputStream, code: String, pickerBinding: String? = null): Boolean = synchronized(lifecycle) {
        if (closed || !externalRecoveryAllowed() || fileRecoveryOwner.value == null) return@synchronized false
        if (pickerBinding != null && fileRecoveryPickerBinding() != pickerBinding) return@synchronized false
        if (fileRecoveryOwner.value?.state?.value?.busy == true ||
            cloudRecoveryOwner.isInitialized() && cloudRecoveryOwner.value?.state?.value?.busy == true) return@synchronized false
        if (cloudRecoveryOwner.isInitialized()) cloudRecoveryOwner.value?.lock()
        requireNotNull(fileRecoveryOwner.value).prepare({
            if (pickerBinding != null) {
                check(fileRecoveryPickerBinding() == pickerBinding) { "SYNC_GROUP_FILE_RECOVERY_PICKER_STALE" }
                val current = access
                require(current == null || current.ledger().current().members[current.device]?.active == true) {
                    "SYNC_GROUP_FILE_RECOVERY_DAILY_KEYS_CHANGED"
                }
            }
            file()
        }, code)
        true
    }
    private val actionGeneration = java.util.concurrent.atomic.AtomicLong()
    // Guarded by action admission. This is a current UI intent, never a cold-start command.
    private var pendingJoinProof: Long? = null
    private var automaticLocalBusyNotice: String? = null
    private val mutableState = MutableStateFlow(LanSyncGroupPanelState(paused = initiallyPaused))
    val state = mutableState.asStateFlow()
    private val mutableView = MutableStateFlow<LanSyncGroupNodeView?>(null)
    val view = mutableView.asStateFlow()
    private val mutableActionReview = MutableStateFlow<LanSyncGroupActionReview?>(null)
    val actionsReview = mutableActionReview.asStateFlow()
    private val mutableRecoveryEntries = MutableStateFlow<List<LanSyncRecoveryEntry>>(emptyList())
    val recoveryEntries = mutableRecoveryEntries.asStateFlow()
    private val mutableRecoveryView = MutableStateFlow<LanSyncGroupRecoveryView?>(null)
    val recoveryView = mutableRecoveryView.asStateFlow()
    private val mutableImages = MutableStateFlow(LanSyncGroupImageState())
    val images = mutableImages.asStateFlow()
    private val mutableCloudSettings = MutableStateFlow(LanSyncGroupCloudSettingsState())
    val cloudSettings = mutableCloudSettings.asStateFlow()
    private val cloudAdmission = Mutex()
    private val cloudGeneration = java.util.concurrent.atomic.AtomicLong()
    private val cloudJobs = mutableSetOf<Job>()
    @Volatile private var cloudRuntime: LanSyncGroupCloudRuntime? = null
    private var cloudWorker: Job? = null
    private val cloudWake = Channel<Unit>(Channel.CONFLATED)
    private val cloudRequests = java.util.concurrent.atomic.AtomicLong()
    private val imageGeneration = java.util.concurrent.atomic.AtomicLong()
    private val imageAdmission = Semaphore(1)
    @Volatile private var imageContext: LanSyncGroupFrozenImageContext? = null
    private val recoveryGeneration = java.util.concurrent.atomic.AtomicLong()
    private val reviewGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closed = false
    @Volatile private var paused = initiallyPaused
    private val lifecycle = Any()
    private var privacyGeneration = 0L
    private var invitationCommandGeneration = 0L
    private var pendingInvitationCommand: Long? = null
    private var privacyExpiry: Job? = null
    private val pendingSockets = mutableSetOf<java.net.Socket>()
    private val joiningNetwork = object : LanSyncNetwork by network {
        override fun connectInvitationOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (java.net.Socket) -> Unit): javax.net.ssl.SSLSocket =
            network.connectInvitationOwned(identity, peer) { socket ->
                synchronized(lifecycle) {
                    if (closed || paused || !joinProofAllowed()) { socket.close(); error("SYNC_GROUP_JOIN_PROTECTION_REQUIRED") }
                    pendingSockets += socket
                }
                own(socket)
            }
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): javax.net.ssl.SSLSocket {
            synchronized(lifecycle) {
                check(!closed) { "SYNC_GROUP_COORDINATOR_CLOSED" }
                pendingSockets.removeAll { it.isClosed }
                require(pendingSockets.size < 4) { "SYNC_GROUP_TRANSPORT_CONNECTION_LIMIT" }
            }
            return network.connectBootstrapOwned(identity, peer) { socket ->
                synchronized(lifecycle) {
                    if (closed) { socket.close(); error("SYNC_GROUP_COORDINATOR_CLOSED") }
                    pendingSockets += socket
                }
            }
        }
    }
    private var founder: LanSyncGroupEnrollment? = null
    private var member: LanSyncGroupJoining? = null
    private var recoveredManagement: LanSyncGroupManagementRecovery? = null
    @Volatile private var draft: LanSyncGroupEnrollmentDraft? = null
    @Volatile private var transport: LanSyncGroupAppTransport? = null
    @Volatile private var discovery: LanSyncGroupDiscovery? = null
    @Volatile private var access: LanSyncGroupRuntimeAccess? = null
    private class ActiveInvitation(val owner: LanSyncGroupInvitation, val offer: LanSyncGroupInviteOffer, val fileOnly: Boolean) {
        var proof: LanSyncGroupInvitedPeer? = null
        val fileBinding = if (fileOnly) UUID.randomUUID().toString() else null
    }
    private var invitation: ActiveInvitation? = null
    private var approvalInFlight: ActiveInvitation? = null
    private var membershipRecoveryGeneration = 0L
    @Volatile private var membershipReview: LanSyncGroupMembershipRecovery.OpenReview? = null
    @Volatile private var membershipPackageReview: LanSyncGroupMembershipRecovery.ImportReview? = null
    private var peer: LanSyncGroupMemberPreview? = null
    private var revocation: LanSyncGroupRevocationPreview? = null
    private var loop: Job? = null
    private var port: Int? = null
    private val nearbyAdmission = Mutex()
    private var nearbyReady = false
    private var nearbyRestartRequested = false
    // Guarded by lifecycle. An older Reader snapshot can still report a failure after replacement.
    private class NearbyScan {
        val routePrefix = "nearby:${UUID.randomUUID()}:"
        val outcomes = linkedMapOf<String, LanSyncGroupDeviceNearby>()
        val readFailures = mutableSetOf<String>()
        var lastSuccessAt: Long? = null
        var published = false
    }
    private var nearbyScan: NearbyScan? = null
    private var lastSourceScan = 0L
    private var metadataStamp: String? = null
    private var nearbyStamp: String? = null
    private val localWake = Channel<Unit>(Channel.CONFLATED)
    private val nearbyWake = Channel<Unit>(Channel.CONFLATED)
    private val localRequests = java.util.concurrent.atomic.AtomicLong()
    private val foregroundWake = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var latestSourceRevision = 0L
    private var handledSourceRevision = -1L
    private var handledLocalRequest = -1L
    private var localWorker: Job? = null
    private var nearbyWorker: Job? = null
    private var sourceObserver: Job? = null
    private var quiesced = false
    private val shutdownAdmission = Mutex()
    init {
        sourceObserver = sourceChanges?.let { changes -> scope.launch {
            changes.collect { revision -> latestSourceRevision = revision; requestLocalCheck() }
        } }
    }
    private fun requestLocalCheck() {
        if (!closed) { localRequests.incrementAndGet(); localWake.trySend(Unit) }
    }
    fun onForeground() {
        if (closed || paused || !hasExistingEvidence()) return
        val now = System.nanoTime(); val before = foregroundWake.get()
        if (before != 0L && now - before < 3_000_000_000L) return
        if (!foregroundWake.compareAndSet(before, now)) return
        if (access == null) open()
        requestLocalCheck(); nearbyWake.trySend(Unit); requestCloudCheck()
    }
    fun onNetworkAvailable() {
        onForeground()
        if (!closed && !paused && hasExistingEvidence()) { nearbyWake.trySend(Unit); requestCloudCheck() }
    }
    /** Platform timeout/lost protection cancels only this owner's cloud calls, preserving all queues. */
    fun cancelCloudWork() {
        cloudRuntime?.pause()
        if (cloudRecoveryOwner.isInitialized()) cloudRecoveryOwner.value?.lock()
        if (fileRecoveryOwner.isInitialized()) fileRecoveryOwner.value?.lock()
        if (fileExportOwner.isInitialized() && fileExportOwner.value.state.value.busy) fileExportOwner.value.lock()
    }
    private fun requestCloudCheck(dirty: Boolean = false) {
        if (closed) return
        if (dirty) cloudRuntime?.markDirty()
        cloudRequests.incrementAndGet()
        cloudWake.trySend(Unit)
    }
    private fun cloudConfigurationChanged() {
        cloudRuntime?.configurationChanged()
        requestCloudCheck(dirty = true)
    }

    private fun founder() = founder ?: enrollment().also { founder = it }
    private fun member() = member ?: joining().also { member = it }
    private fun failure(stage: String, failure: Exception, automaticLocalCheck: Boolean = false) {
        if (failure is CancellationException) throw failure
        val code = failure.message?.takeIf { it.matches(Regex("(?:SYNC|STORE)_[A-Z0-9_]{1,100}")) } ?: "SYNC_GROUP_OPERATION_FAILED"
        val explanation = when (code) {
            "SYNC_METADATA_WORKING_BUDGET" -> "本次设备组操作需要读取的同步元数据超过了本机处理容量。"
            "SYNC_METADATA_INDEX_BUDGET" -> "本次设备组操作的同步索引超过了本机处理容量。"
            "SYNC_METADATA_STRING_BUDGET", "SYNC_METADATA_DEPTH", "SYNC_FILE_LIMIT" ->
                "本次设备组操作遇到超出格式或大小限制的同步资料。"
            "SYNC_GROUP_PROFILE_LOCAL_HISTORY_EXISTS" -> "本机已保留原设备组的设置进度。请使用已保存的恢复码继续该组。"
            "SYNC_GROUP_INVITE_OFFER", "SYNC_GROUP_JOIN_BOOTSTRAP_REQUIRED" -> "本机无法识别此邀请码，请重新复制完整内容或扫码。"
            "SYNC_GROUP_INVITE_EXPIRED" -> "邀请码显示已过期。请检查两台设备的系统时间并生成新邀请。"
            "SYNC_GROUP_INVITE_CLOCK", "SYNC_GROUP_INVITE_CERTIFICATE_TIME" -> "两台设备的时间可能不一致。请核对系统时间后重试。"
            "SYNC_GROUP_INVITE_CODE_TOO_LARGE" -> "普通邀请码无法容纳完整记录。请使用「生成邀请文件」。"
            "SYNC_GROUP_INVITE_FILE_TOO_LARGE" -> "完整邀请超过文件大小限制。原设备组历史已保留。"
            "SYNC_GROUP_INVITE_FILE_SELECTION_EXPIRED" -> "这次保存已过期或被取消。请重新生成邀请；如果系统创建了空文件，可以另选名称重试。"
            "SYNC_GROUP_INVITE_FILE_DESTINATION_CHANGED" -> "同名目标已变化或不是邀请文件，原文件已保留。请选择新文件名。"
            "SYNC_GROUP_INVITE_FILE_UNAVAILABLE" -> "无法保存邀请文件，请另选保存位置。"
            "SYNC_GROUP_INVITE_TLS_PIN", "SYNC_GROUP_INVITE_TLS" -> "无法验证邀请设备的连接身份。请核对设备并重新生成有效邀请。"
            "SYNC_GROUP_JOIN_CONNECTION_FAILED" -> "暂时无法连接设备组。请检查两端是否在同一可达网络、邀请地址是否正确。"
            "SYNC_GROUP_INVITE_FRAME", "SYNC_GROUP_INVITE_REQUEST", "SYNC_GROUP_INVITE_REPLY", "SYNC_GROUP_INVITE_CHALLENGE" ->
                "两端邀请证明格式不匹配，或连接没有发送完整证明。请核对版本后重试。"
            "SYNC_GROUP_INVITE_VERSION", "SYNC_GROUP_WIRE_PROTOCOL", "SYNC_GROUP_WIRE_GROUP", "SYNC_GROUP_WIRE_CONTROL_FORMAT",
            "SYNC_GROUP_RUNTIME_TLS_IDENTITY" ->
                "两端使用的邀请或接续协议不同。请核对版本和设备组。"
            "SYNC_GROUP_PROFILE_PARTIAL_SCOPE_UNSUPPORTED" -> "两端支持的同步资料范围不同，已保留资料和原加入身份。"
            "SYNC_GROUP_JOIN_APPROVAL_MAY_EXIST" -> "原设备身份可能已获得批准。请保留该身份并继续加入。"
            "SYNC_GROUP_JOIN_HAS_LOCAL_ADMISSION" -> "本机已保留获准的组资料。请从原申请继续加入。"
            "SYNC_GROUP_JOIN_RESTORE_SHELVED_FIRST" -> "该设备组已有暂存申请。请恢复原申请以沿用原设备身份。"
            "SYNC_GROUP_JOIN_ROUTE_NO_LONGER_ACTIVE", "SYNC_KEY_DELIVERY_RECIPIENT_REVOKED" -> "原连接身份已停用，请通过同组仍有效的设备核对。"
            "SYNC_GROUP_JOIN_INVITATION_REVOKED" -> "原申请已撤销。若可归档，请先结束旧申请，再使用新邀请。"
            "SYNC_GROUP_INVITE_EXPIRED_OR_CHANGED" -> "邀请已过期或设备组已更新。请重新生成邀请。"
            "SYNC_GROUP_INVITE_PUBLISHED_HISTORY_UNAVAILABLE" -> "暂时无法校验已发布的设备组历史。请重新检查同步。"
            "SYNC_GROUP_LOCAL_RESOURCE_BLOCKED" -> "本机会话有无法读取或处理的图片。其他会话仍可同步。补回可读的原图，或保留原件并换用兼容副本。"
            LanSyncMissingImage.CODE -> LanSyncGroupDisplay.missingLocalImageNotice(code)
            "SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT" -> joiningLedgerConflictText
            "SYNC_GROUP_MEMBERSHIP_CONFLICT" -> "成员变更冲突。签名分支和本机资料已保留。"
            "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" -> "已验证的分支或待处理文件发生变化，请重新核对。"
            "SYNC_GROUP_MEMBERSHIP_RECOVERY_PENDING_INTENT" -> "已保留先前确认的恢复操作。请核对原授权和主分支后继续。"
            "SYNC_GROUP_RECOVERY_HISTORICAL_KEYS_MISSING" -> "还缺少分支的原历史密钥。可继续保留当前分支，请向持有原件的设备核对。"
            "SYNC_GROUP_RECOVERY_CAUSAL_HISTORY" -> "还缺少签名分支已确认的资料历史，请补齐原件后重新核对。"
            "SYNC_GROUP_LEDGER_RECOVERY_FILE_RECIPIENT_REVOKED", "SYNC_GROUP_MEMBERSHIP_RECOVERY_DEVICE_REVOKED" ->
                "此设备已撤销，已保留本机资料。请由仍获授权的设备继续恢复。"
            "SYNC_GROUP_MEMBERSHIP_RECOVERY_EXPLICIT_REQUIRED" -> "请核对并导入设备组的恢复控制文件。"
            "SYNC_GROUP_JOIN_RECOVERY_INVITATION_REQUIRED" -> "设备组已使用新版恢复授权。请向仍获授权的设备获取新的邀请。"
            "SYNC_GROUP_STORE_UPGRADE_REQUIRED" -> "该设备组使用新版恢复授权，请先升级并核对恢复控制文件。"
            "SYNC_GROUP_NATIVE_RELOAD_PENDING" -> "同步资料已保存，界面会在后续检查时重新加载。"
            "SYNC_ORPHAN_SESSION" -> "发现找不到原会话的本机消息，原资料已保留。请恢复对应会话后重新检查同步。"
            "SYNC_BACKGROUND_WORK_BUSY", "SYNC_MAINTENANCE" -> "本机正在生成或处理资料。完成后将继续检查同步。"
            else -> null
        }
        mutableState.update { current ->
            val canResumeSetup = current.stage in setOf(LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE, LanSyncGroupPanelStage.PREPARED_LOCKED) &&
                current.localManagementAvailable && (code.startsWith("SYNC_METADATA_") || code == "SYNC_FILE_LIMIT")
            val resume = if (canResumeSetup) "设置进度已保留，可以用之前保存的恢复码继续原设备组。" else ""
            current.copy(error = "$stage：${explanation?.let { "$it$resume（$code）" } ?: code}")
        }
        if (code == "SYNC_GROUP_NATIVE_RELOAD_PENDING" || automaticLocalCheck && code in localMaintenanceCodes) {
            automaticLocalBusyNotice = "$stage：$explanation（$code）"
        }
        if (code == "SYNC_GROUP_NATIVE_RELOAD_PENDING" && !automaticLocalCheck) requestLocalCheck()
    }
    private fun action(label: String, allowWhilePaused: Boolean = false, work: suspend () -> Unit) {
        if (closed || paused && !allowWhilePaused) return
        val generation = actionGeneration.get()
        scope.launch {
            admission.withLock {
                if (closed || paused && !allowWhilePaused || generation != actionGeneration.get()) return@withLock
                mutableState.update { it.copy(busy = true, error = null) }
                try { work() } catch (error: Exception) { failure(label, error) }
                finally { mutableState.update { it.copy(busy = false) } }
            }
        }
    }
    /** Read-only empty startup must not call platform identity/profile factories. */
    fun startIfConfigured() { if (!closed && !paused && hasExistingEvidence()) open() }
    /** Explicit platform resume; ordinary foreground/network hints never clear a user pause. */
    fun resumeIfConfigured() {
        if (closed) return
        val generation = actionGeneration.get()
        scope.launch {
            shutdownAdmission.withLock {
                if (closed || generation != actionGeneration.get() || !hasExistingEvidence()) return@withLock
                paused = false
                mutableState.update { state ->
                    if (state.membershipConflict == null) state.copy(paused = false, status = "正在恢复设备组同步")
                    else state.copy(paused = false)
                }
                action("恢复设备组同步") {
                    openConfiguredProfile()
                    if (pendingJoinProof == generation) {
                        pendingJoinProof = null
                        if (generation == actionGeneration.get() && !paused && joinProofAllowed() &&
                            state.value.stage == LanSyncGroupPanelStage.JOINING && !state.value.recoveryJoining) attemptJoin()
                    }
                }
            }
        }
    }
    fun open() = action("打开设备组", allowWhilePaused = true) { openConfiguredProfile() }
    private suspend fun openConfiguredProfile() {
        access?.let { attach(it) } ?: if (hasExistingEvidence()) load() else Unit
    }
    private fun expirePrivacy(generation: Long) {
        privacyExpiry?.cancel()
        privacyExpiry = scope.launch {
            delay(300_000)
            synchronized(lifecycle) { if (generation == privacyGeneration) lockManagement(retainInvitation = true) }
        }
    }
    private suspend fun load() {
        val owner = founder()
        val setup = owner.panelSetup()
        val own = setup.profile
        if (own != null) {
            if (own.stage == LanSyncGroupEnrollmentStage.READY) attach(requireNotNull(owner.runtimeAccess()))
            else {
                synchronized(lifecycle) {
                    val prepared = draft?.takeIf { it.profile == own && it.session.isUnlocked }
                    if (prepared != null) mutableState.update { it.copy(stage = LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE,
                        recoveryCode = prepared.recoveryCode(), setupBinding = prepared.operation.value,
                        localManagementAvailable = true, managementUnlocked = true, canAbandonUnactivated = setup.canAbandon,
                        status = "请确认已妥善保存恢复码后完成创建") }
                    else mutableState.update { it.copy(stage = LanSyncGroupPanelStage.PREPARED_LOCKED,
                        recoveryCode = null, setupBinding = null, managementUnlocked = false,
                        localManagementAvailable = true, canAbandonUnactivated = setup.canAbandon,
                        status = "设置进度已保留，请使用已保存的恢复码继续") }
                }
            }
        } else if (setup.canAbandon) {
            lockManagement()
            mutableState.update { current -> LanSyncGroupPanelState(stage = LanSyncGroupPanelStage.PREPARED_LOCKED,
                paused = paused, busy = current.busy, privacyRevision = current.privacyRevision, canAbandonUnactivated = true,
                status = "可以放弃未完成的设置，原进度会保留在本机归档。") }
        } else {
            val joined = member().profile()
            val recovery = joined != null && !joined.ledgerConflict && member().recoveryAnchor() != null
            mutableState.update { it.copy(canResumeExternalRecovery = recovery) }
            if (joined?.stage == LanSyncGroupJoinStage.READY) attach(requireNotNull(member().runtimeAccess()))
            else if (joined != null) {
                mutableState.update { it.copy(stage = LanSyncGroupPanelStage.JOINING, recoveryJoining = recovery,
                    fileRecoveryJoinScope = joined.localReplica.id.value.takeUnless { joined.membershipRevoked },
                    joiningDevice = joined.device.takeUnless { recovery }, canAbandonJoin = joined.canAbandon,
                    canRefreshJoinPreparation = joined.canRefreshPreparation(), canShelveJoin = joined.canShelve,
                    canArchiveRevokedJoin = joined.canArchiveRevoked, joiningLedgerConflict = joined.ledgerConflict,
                    joiningMembershipRevoked = joined.membershipRevoked,
                    status = if (joined.ledgerConflict) joiningLedgerConflictText
                    else if (recovery) "恢复进度已保留，可从网盘或加密文件入口接续"
                    else if (joined.membershipRevoked) "原组已撤销此设备，已保留本机资料"
                    else if (joined.membershipVerified) "已核对批准，可继续查收接续所需资料"
                    else if (!joined.invitationExpired && paused && pendingJoinProof == actionGeneration.get())
                        "邀请已核对，等待同步服务就绪后发送证明"
                    else unverifiedJoinStatus(joined)) }
                if (!recovery) startLoop()
            } else {
                val shelved = member().shelvedInvitations()
                require(shelved.isNotEmpty()) { "SYNC_GROUP_EXISTING_EVIDENCE_REQUIRES_RECOVERY" }
                showEmptyJoin(shelved, "有暂存的加入申请。可以恢复旧申请，或使用其他设备组的邀请")
            }
        }
    }
    override fun createGroup() { val generation = synchronized(lifecycle) { privacyGeneration }; action("创建设备组", allowWhilePaused = true) {
        require(!hasExistingEvidence()) { "SYNC_GROUP_PROFILE_ALREADY_PREPARED" }
        beforeSetup()
        val operation = SyncStoreId.random()
        val request = SyncRootGroupCreateRequest(operation, LanSyncScope.schemas.keys.toSet(),
            lanSyncHash(UUID.randomUUID().toString().toByteArray()))
        val owner = founder()
        val prepared = try { owner.prepare(request, SyncRootGroupCreateAdmission { require(it == request) }) }
        catch (error: Exception) { refreshAfterSetupFailure(error) }
        val canAbandon = try { owner.canAbandonUnactivated() } catch (error: Exception) { prepared.close(); throw error }
        synchronized(lifecycle) {
            if (closed || generation != privacyGeneration) { prepared.close(); error("SYNC_GROUP_MANAGEMENT_LOCKED") }
            draft = prepared
            expirePrivacy(generation)
            mutableState.update { it.copy(stage = LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE, status = "请妥善保存恢复码后完成创建",
                recoveryCode = prepared.recoveryCode(), setupBinding = operation.value, managementUnlocked = true,
                localManagementAvailable = true, canAbandonUnactivated = canAbandon) }
        }
    } }
    override fun recoverySaved(binding: String) = action("完成创建", allowWhilePaused = true) {
        val prepared = requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        require(binding == prepared.operation.value) { "SYNC_GROUP_PROFILE_DRAFT_CHANGED" }
        val owner = founder()
        mutableState.update { it.copy(canAbandonUnactivated = false) }
        try {
            owner.activate(prepared, LanSyncGroupRecoverySavedAdmission { require(it == prepared.profile) })
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val canAbandon = try { owner.canAbandonUnactivated() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (eligibilityFailure: Exception) { error.addSuppressed(eligibilityFailure); false }
            mutableState.update { it.copy(canAbandonUnactivated = canAbandon, status = "设置进度已保留，可以用原恢复码继续创建") }
            throw error
        }
        lockManagement()
        attach(requireNotNull(owner.runtimeAccess()))
    }
    override fun abandonUnusedSetup() = action("保留并放弃未完成设置", allowWhilePaused = true) {
        try { founder().abandonUnactivated(LanSyncGroupDraftAbandonAdmission { }) }
        catch (error: Exception) { refreshAfterSetupFailure(error) }
        clearSetup()
    }
    private fun clearSetup() {
        lockManagement()
        mutableState.update { current -> LanSyncGroupPanelState(paused = paused, busy = current.busy,
            privacyRevision = current.privacyRevision) }
    }
    private suspend fun refreshAfterSetupFailure(error: Exception): Nothing {
        if (error is CancellationException) throw error
        try {
            if (hasExistingEvidence()) load() else clearSetup()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (refreshFailure: Exception) { error.addSuppressed(refreshFailure) }
        throw error
    }
    override fun resumeManagementOperation() = action("接续已确认的管理操作") {
        if (access?.localManagementAvailable == false) {
            requireNotNull(recoveredManagement) { "SYNC_GROUP_MANAGEMENT_LOCKED" }.resumeAdministration()
            publishRecoveredManagement()
        } else founder().resumeAdministration(requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" })
        lockManagement(); refreshDevices(); cycle(force = true)
    }
    override fun unlockManagement(code: String) {
        if (access?.localManagementAvailable == false) { recoverManagement(code); return }
        val label = if (state.value.stage == LanSyncGroupPanelStage.PREPARED_LOCKED) "继续设备组设置" else "解锁管理"
        val generation = synchronized(lifecycle) { privacyGeneration }; action(label, allowWhilePaused = true) {
        check(state.value.membershipConflict == null) { "SYNC_GROUP_MEMBERSHIP_CONFLICT" }
        val owner = founder()
        val opened = owner.resume(code)
        val prepared = opened.profile.stage == LanSyncGroupEnrollmentStage.PREPARED
        val canAbandon = try { prepared && owner.canAbandonUnactivated() } catch (error: Exception) { opened.close(); throw error }
        synchronized(lifecycle) {
            if (closed || generation != privacyGeneration) { opened.close(); error("SYNC_GROUP_MANAGEMENT_LOCKED") }
            draft?.close(); draft = opened
            expirePrivacy(generation)
            mutableState.update { it.copy(managementUnlocked = true, managementCanInvite = !prepared, recoveryCode = if (prepared) opened.recoveryCode() else null,
                setupBinding = if (prepared) opened.operation.value else null,
                invitation = invitation?.offer, invitationRetainedWhileLocked = false, invitationFileOnly = invitation?.fileOnly == true,
                invitationFileBinding = invitation?.fileBinding,
                canAbandonUnactivated = canAbandon,
                status = if (prepared) "已找到原设备组，请确认恢复码已保存并完成创建" else it.status,
                stage = if (prepared) LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE else it.stage) }
            refreshDevices()
        }
        presentInvitationProof()
    } }
    private suspend fun publishRecoveredManagement() {
        val current = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
        requireNotNull(recoveredManagement) { "SYNC_GROUP_MANAGEMENT_LOCKED" }.publish(current.replica, current.localTarget)
    }
    override fun recoverManagement(code: String) {
        val generation = synchronized(lifecycle) { privacyGeneration }
        action("恢复组管理") {
            check(state.value.membershipConflict == null) { "SYNC_GROUP_MEMBERSHIP_CONFLICT" }
            val current = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
            require(!current.localManagementAvailable) { "SYNC_GROUP_FOUNDER_MANAGEMENT_AVAILABLE" }
            val owner = synchronized(lifecycle) {
                require(!closed && generation == privacyGeneration) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
                recoveredManagement ?: current.managementRecovery().also { recoveredManagement = it }
            }
            if (current.hasManagementRecoveryProfile()) {
                owner.resumeImport(code, current.reader)
                owner.refresh(current.reader)
            } else owner.recover(code, current.encryptedManagementBackup().bytes(), current.reader,
                LanSyncManagementRecoveryAdmission { anchor ->
                    require(anchor.group == current.trust.group && anchor.genesis == current.trust.genesis &&
                        anchor.ownerCertificate == current.trust.ownerCertificate) { "SYNC_GROUP_MANAGEMENT_TRUST_CHANGED" }
                }, current.trust.copy(checkpoint = current.ledger().current().reference()))
            publishRecoveredManagement()
            cycle(force = true)
            synchronized(lifecycle) {
                if (closed || generation != privacyGeneration) { owner.lock(); error("SYNC_GROUP_MANAGEMENT_LOCKED") }
                require(owner.isUnlocked) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
                expirePrivacy(generation)
                mutableState.update { it.copy(managementUnlocked = true, managementCanInvite = true,
                    invitation = invitation?.offer, invitationRetainedWhileLocked = false, invitationFileOnly = invitation?.fileOnly == true,
                    invitationFileBinding = invitation?.fileBinding,
                    status = "已用本机加密管理资料恢复原设备组管理权") }
                refreshDevices()
            }
            presentInvitationProof()
        }
    }
    override fun lockManagement() = lockManagement(retainInvitation = false)

    /** An issued invitation proves device ownership only; locking management still clears its signing session. */
    fun lockManagementOnDesktopFocusLoss() = lockManagementOnVisibilityLoss()

    fun lockManagementOnVisibilityLoss() = lockManagement(retainInvitation = true)

    private fun lockManagement(retainInvitation: Boolean) {
        if (cloudRecoveryOwner.isInitialized()) cloudRecoveryOwner.value?.hide()
        if (fileRecoveryOwner.isInitialized()) fileRecoveryOwner.value?.hide()
        if (fileExportOwner.isInitialized()) fileExportOwner.value.hide()
        synchronized(lifecycle) {
            lockMembershipRecovery()
            cancelMembershipRecoveryImport()
            closeCloudSettings()
            privacyGeneration++
            pendingInvitationCommand = null
            privacyExpiry?.cancel(); privacyExpiry = null
            draft?.close(); draft = null
            founder?.lockManagement()
            recoveredManagement?.lock()
            if (!retainInvitation && approvalInFlight == null) clearInvitationLocked()
            peer = null; revocation = null
            mutableState.update { it.copy(recoveryCode = null, setupBinding = null, managementUnlocked = false, managementCanInvite = false,
                invitation = null, invitationFileBinding = null, peerConfirmation = null, revocation = null, privacyRevision = it.privacyRevision + 1,
                invitationRetainedWhileLocked = invitation != null && approvalInFlight == null,
                devices = it.devices.map { row -> row.copy(canRevoke = false) },
                stage = if (it.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE) LanSyncGroupPanelStage.PREPARED_LOCKED else it.stage) }
        }
    }

    override fun prepareMembershipRecovery(code: String) {
        val generation = synchronized(lifecycle) { membershipRecoveryGeneration }
        action("核对成员变更冲突", allowWhilePaused = true) {
            check(state.value.stage == LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_NO_FORK" }
            val owner = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.membershipRecovery
            val prepared = owner.prepare(code)
            synchronized(lifecycle) {
                if (closed || generation != membershipRecoveryGeneration) {
                    prepared.close()
                    error("SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED")
                }
                membershipReview?.close()
                membershipReview = prepared
                membershipPackageReview = null
                mutableState.update { it.copy(membershipRecovery = prepared.projection, membershipRecoveryImport = null) }
            }
        }
    }

    override fun confirmMembershipRecovery(binding: String, primaryHeadId: String, activeDevices: Set<String>) {
        val selected = activeDevices.toSet()
        action("确认签名恢复授权", allowWhilePaused = true) {
            val current = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
            val review = synchronized(lifecycle) { requireNotNull(membershipReview) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" } }
            val installed = current.membershipRecovery.confirm(review, binding, primaryHeadId, selected) {
                check(!closed && membershipReview === review && review.session.isUnlocked) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
            }
            lockMembershipRecovery()
            appliedMembershipRecovery(current, installed)
        }
    }

    override fun lockMembershipRecovery() = synchronized(lifecycle) {
        membershipRecoveryGeneration++
        membershipReview?.close()
        membershipReview = null
        mutableState.update { it.copy(membershipRecovery = null) }
    }

    override fun prepareMembershipRecoveryImport(open: () -> java.io.InputStream) {
        val generation = synchronized(lifecycle) { membershipRecoveryGeneration }
        action("核对恢复控制文件", allowWhilePaused = true) {
            check(state.value.membershipRecoveryPackageImportAllowed) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_INPUT_UNAVAILABLE" }
            val current = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
            val imported = current.membershipRecovery.prepareImport(open)
            synchronized(lifecycle) {
                check(!closed && generation == membershipRecoveryGeneration) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
                membershipPackageReview = imported
                mutableState.update { it.copy(membershipRecoveryImport = imported.projection) }
            }
        }
    }

    override fun cancelMembershipRecoveryImport() = synchronized(lifecycle) {
        membershipPackageReview = null
        mutableState.update { it.copy(membershipRecoveryImport = null) }
    }

    override fun confirmMembershipRecoveryImport(binding: String) = action("确认导入恢复授权", allowWhilePaused = true) {
        val current = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
        val review = synchronized(lifecycle) {
            requireNotNull(membershipPackageReview) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        }
        val installed = current.membershipRecovery.confirmImport(review, binding) {
            check(!closed && membershipPackageReview === review) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
        }
        cancelMembershipRecoveryImport()
        lockMembershipRecovery()
        appliedMembershipRecovery(current, installed)
    }

    override fun saveMembershipRecoveryPackage(binding: String?, write: (ByteArray) -> Unit) =
        action("保存设备组恢复控制文件", allowWhilePaused = true) {
            val current = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
            val requested = requireNotNull(binding) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
            check(state.value.membershipRecoveryExportBinding == requested) { "SYNC_GROUP_MEMBERSHIP_RECOVERY_REVIEW_CHANGED" }
            write(current.membershipRecovery.export(requested))
        }

    private suspend fun appliedMembershipRecovery(current: LanSyncGroupRuntimeAccess, installed: Boolean) {
        val binding = current.membershipRecovery.exportBinding()
        mutableState.update { it.copy(membershipRecoveryExportBinding = binding) }
        if (installed) {
            // New transports and normal work are admitted only after custody has the exact new epoch.
            attach(current)
            requestLocalCheck()
            nearbyWake.trySend(Unit)
            requestCloudCheck(dirty = true)
        } else presentMembershipConflict(current)
    }
    override fun beginJoin(code: String) = enterJoin { LanSyncGroupInviteOffer.parse(code) }

    override fun beginJoinFromFile(open: () -> java.io.InputStream) = enterJoin { open().use { LanSyncGroupInviteOffer.readFile(it) } }

    private fun enterJoin(readOffer: () -> LanSyncGroupInviteOffer) = action("加入设备组", allowWhilePaused = true) {
        require(!state.value.joiningLedgerConflict) { "SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT" }
        require(!state.value.joiningMembershipRevoked) { "SYNC_GROUP_JOIN_INVITATION_REVOKED" }
        pendingJoinProof = null
        val offer = readOffer()
        requireNotNull(offer.bootstrap) { "SYNC_GROUP_JOIN_BOOTSTRAP_REQUIRED" }
        val generation = actionGeneration.get()
        beforeSetup()
        val owner = member()
        val profile = if (owner.profile() == null) owner.prepareOffer(offer) { it.validate() }
        else owner.refreshOffer(offer) { it.validate() }
        val authorized = generation == actionGeneration.get() && joinProofAllowed()
        pendingJoinProof = generation.takeIf { paused && authorized }
        showJoining(profile, when {
            pendingJoinProof != null -> "邀请已核对，等待同步服务就绪后发送证明"
            !authorized -> "邀请已保留，请恢复同步后手动继续加入"
            else -> "邀请已核对，正在验证此设备"
        })
        startLoop()
        if (!paused && authorized) attemptJoin()
    }
    override fun abandonUnprovedJoin() = action("放弃未发送的加入申请", allowWhilePaused = true) {
        pendingJoinProof = null
        val owner = member()
        owner.abandonUnprovedInvitation()
        showEmptyJoin(owner.shelvedInvitations(), "申请已保留到本机归档，可以使用正确的邀请重新加入")
    }
    override fun shelveUnsettledJoin() = action("暂存当前加入申请", allowWhilePaused = true) {
        pendingJoinProof = null
        val owner = member()
        owner.shelveUnsettledInvitation()
        showEmptyJoin(owner.shelvedInvitations(), "原申请和设备身份已暂存。可以恢复原申请，或使用其他设备组的邀请")
    }
    override fun archiveRevokedJoin() = action("结束已撤销的加入申请", allowWhilePaused = true) {
        pendingJoinProof = null
        val owner = member()
        owner.archiveRevokedInvitation()
        showEmptyJoin(owner.shelvedInvitations(), "已归档撤销的申请，可以使用其他设备组的邀请")
    }
    override fun restoreShelvedJoin(operation: String) = action("恢复原加入申请", allowWhilePaused = true) {
        pendingJoinProof = null
        val profile = member().restoreShelvedInvitation(operation)
        showJoining(profile, "原申请和原设备身份已恢复，正在检查此前的批准")
        startLoop()
        if (!paused) attemptJoin()
    }
    private fun showEmptyJoin(shelved: List<LanSyncShelvedJoin>, status: String) = mutableState.update {
        LanSyncGroupPanelState(busy = it.busy, paused = paused, privacyRevision = it.privacyRevision + 1,
            shelvedJoins = shelved, status = status)
    }
    private fun LanSyncGroupJoiningProfile.canRefreshPreparation(): Boolean =
        !ledgerConflict && !membershipRevoked && stage in reprepareJoinStages

    private fun showJoining(profile: LanSyncGroupJoiningProfile, status: String) = mutableState.update {
        it.copy(stage = LanSyncGroupPanelStage.JOINING, joiningDevice = profile.device, canAbandonJoin = profile.canAbandon,
            fileRecoveryJoinScope = profile.localReplica.id.value.takeUnless { profile.membershipRevoked },
            canShelveJoin = profile.canShelve, canArchiveRevokedJoin = profile.canArchiveRevoked,
            canRefreshJoinPreparation = profile.canRefreshPreparation(), joiningLedgerConflict = profile.ledgerConflict,
            joiningMembershipRevoked = profile.membershipRevoked, status = if (profile.ledgerConflict) joiningLedgerConflictText else status)
    }
    private fun unverifiedJoinStatus(profile: LanSyncGroupJoiningProfile): String = when {
        profile.invitationExpired -> {
            val checkApproval = if (profile.canAbandon) "" else "仍可查收签名批准。"
            "按本机时间，原邀请已到期。原设备身份已保留。$checkApproval$refreshJoiningInvitationText"
        }
        profile.stage == LanSyncGroupJoinStage.WAITING_FOR_APPROVAL ->
            "已发送证明，正在查收批准。若邀请方已退出，请对方重新打开设备组。若原邀请已关闭，$refreshJoiningInvitationText"
        profile.stage == LanSyncGroupJoinStage.PROOF_ATTEMPTED ->
            "正在核对证明是否送达，原设备身份已保留。若邀请方已退出，请对方重新打开设备组。若原邀请已关闭，$refreshJoiningInvitationText"
        profile.canAbandon ->
            "本机尚未发送加入证明，可重试或放弃本次申请。若对方已退出或关闭邀请，$refreshJoiningInvitationText"
        else -> "原设备身份已保留，可继续查收批准"
    }
    override fun retryJoin() = action("继续加入") { attemptJoin() }
    override fun refreshJoinPreparation() = action("重新核对加入资料") { member().refreshPreparation(); attemptJoin() }
    private suspend fun prove(owner: LanSyncGroupJoining) = owner.proveUsing(deviceName, joiningNetwork)
    private suspend fun attemptJoin() {
        if (closed || paused || !joinProofAllowed()) return
        val owner = member(); val profile = owner.resumePreparation()
        if (profile.ledgerConflict) showJoining(profile, joiningLedgerConflictText)
        if (profile.stage == LanSyncGroupJoinStage.READY) { attach(requireNotNull(owner.runtimeAccess())); return }
        if (profile.membershipRevoked) {
            showJoining(profile, "原组已撤销此设备，已保留本机资料")
            return
        }
        if (!profile.ledgerConflict && owner.recoveryAnchor() != null) {
            mutableState.update { it.copy(stage = LanSyncGroupPanelStage.JOINING, recoveryJoining = true,
                status = "恢复进度已保留，可从网盘或加密文件入口接续") }
            return
        }
        var proofProblem: Exception? = null
        if (!profile.ledgerConflict && profile.stage in setOf(LanSyncGroupJoinStage.PREPARED, LanSyncGroupJoinStage.PROOF_ATTEMPTED) &&
            !profile.membershipVerified) {
            try {
                prove(owner)
                mutableState.update { it.copy(error = null,
                    status = unverifiedJoinStatus(profile.copy(stage = LanSyncGroupJoinStage.WAITING_FOR_APPROVAL, canAbandon = false))) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val problem = joinTransportFailure(error)
                proofProblem = problem
                val retained = requireNotNull(owner.profile())
                if (retained.stage == LanSyncGroupJoinStage.PREPARED) {
                    if (problem.message != "SYNC_GROUP_INVITE_EXPIRED" || !retained.invitationExpired) failure("加入证明", problem)
                    showJoining(retained, unverifiedJoinStatus(retained))
                    return
                }
            }
        }
        mutableState.update { it.copy(canAbandonJoin = false) }
        val result = try {
            activateJoin(owner)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val retained = owner.profile()
            val classified = joinTransportFailure(error)
            val waiting = retained?.stage == LanSyncGroupJoinStage.WAITING_FOR_APPROVAL && !retained.membershipVerified
            val uncertain = retained?.stage == LanSyncGroupJoinStage.PROOF_ATTEMPTED && !retained.membershipVerified
            val approvalGate = (waiting || uncertain) && isAmbiguousJoinConnection(classified)
            val offlineConflict = retained?.ledgerConflict == true && isAmbiguousJoinConnection(classified)
            mutableState.update { it.copy(canRefreshJoinPreparation = retained?.canRefreshPreparation() == true,
                canShelveJoin = retained?.canShelve == true, canArchiveRevokedJoin = retained?.canArchiveRevoked == true,
                joiningLedgerConflict = retained?.ledgerConflict == true, joiningMembershipRevoked = retained?.membershipRevoked == true,
                error = if (approvalGate || offlineConflict) null else it.error,
                status = when {
                    retained?.ledgerConflict == true -> joiningLedgerConflictText
                    retained?.membershipRevoked == true -> "原组已撤销此设备，已保留本机资料"
                    retained?.membershipVerified == true -> "已核对批准，正在等待可达的设备或接续资料"
                    waiting || uncertain -> unverifiedJoinStatus(requireNotNull(retained))
                    else -> it.status
                }) }
            // The normal store deliberately rejects TLS clients until the operator admits them.
            if (offlineConflict) return
            if (approvalGate) {
                proofProblem?.takeUnless { isAmbiguousJoinConnection(it) ||
                    it.message == "SYNC_GROUP_INVITE_EXPIRED" && retained?.invitationExpired == true }
                    ?.let { failure("加入证明", it) }
                return
            }
            throw classified
        }
        if (result.profile.stage == LanSyncGroupJoinStage.READY) attach(requireNotNull(owner.runtimeAccess()))
        else {
            val status = when (result.waitingCode) {
                "SYNC_GROUP_BOOTSTRAP_MEMBERSHIP_MISSING" -> if (result.profile.membershipVerified)
                    "已核对批准，正在等待可达的设备或接续资料" else unverifiedJoinStatus(result.profile)
                "SYNC_GROUP_BOOTSTRAP_HISTORICAL_KEY_MISSING", "SYNC_GROUP_JOIN_INITIAL_DATA_MISSING" -> "已核对批准，等待原组所需的密钥或资料"
                "SYNC_GROUP_JOIN_PRESET_REVIEW_REQUIRED" -> "已核对批准，需要原组先核对冲突的预设"
                "SYNC_KEY_DELIVERY_RECIPIENT_REVOKED" -> "原组已撤销此设备，已保留本机资料"
                "SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT" -> joiningLedgerConflictText
                else -> if (result.profile.membershipVerified) "已核对批准，等待接续资料"
                    else unverifiedJoinStatus(result.profile)
            }
            mutableState.update { it.copy(error = null, status = status, joiningLedgerConflict = result.profile.ledgerConflict,
                canRefreshJoinPreparation = result.profile.canRefreshPreparation(),
                joiningMembershipRevoked = result.profile.membershipRevoked,
                canShelveJoin = result.profile.canShelve, canArchiveRevokedJoin = result.profile.canArchiveRevoked) }
        }
    }
    private suspend fun activateJoin(owner: LanSyncGroupJoining): LanSyncGroupJoiningResult {
        var pending: LanSyncGroupJoiningResult? = null
        var firstFailure: Exception? = null
        suspend fun attempt(source: suspend () -> CiphertextStore): LanSyncGroupJoiningResult? {
            val result = try { owner.activate(source()) }
            catch (error: Exception) {
                if (!isJoinRouteUnavailable(error) && joinTransportFailure(error).message !in setOf(
                        "SYNC_GROUP_INVITE_TLS_PIN", "SYNC_GROUP_RUNTIME_TLS_IDENTITY", "SYNC_GROUP_JOIN_VAULT_MISSING")) throw error
                if (firstFailure == null) firstFailure = error
                return null
            }
            if (result.waitingCode !in resumableJoinRouteWaits ||
                result.profile.membershipRevoked) return result
            if (pending?.let { it.profile.stage > result.profile.stage } != true) pending = result
            return null
        }
        attempt { owner.bootstrapStore(joiningNetwork) }?.let { return it }
        for (source in owner.discoveredBootstrapStores(joiningNetwork)) {
            attempt { source }?.let { return it }
        }
        return pending ?: throw requireNotNull(firstFailure)
    }
    private fun isAmbiguousJoinConnection(error: Exception): Boolean =
        error.message == "SYNC_GROUP_INVITE_TLS" || error.message == "SYNC_GROUP_JOIN_CONNECTION_FAILED"
    private fun joinTransportFailure(error: Exception): Exception {
        if (error is CancellationException) return error
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val code = when {
            causes.any { it is java.security.cert.CertificateExpiredException || it is java.security.cert.CertificateNotYetValidException } ->
                "SYNC_GROUP_INVITE_CERTIFICATE_TIME"
            causes.any { it.message in setOf("SYNC_UNTRUSTED_DEVICE", "SYNC_CERTIFICATE_CHAIN", "SYNC_GROUP_INVITE_TLS_PIN") } ->
                "SYNC_GROUP_INVITE_TLS_PIN"
            causes.any { it is javax.net.ssl.SSLException } -> "SYNC_GROUP_INVITE_TLS"
            isJoinRouteUnavailable(error) && error.message != "SYNC_GROUP_JOIN_ROUTE_NO_LONGER_ACTIVE" -> "SYNC_GROUP_JOIN_CONNECTION_FAILED"
            else -> return error
        }
        return IllegalStateException(code)
    }
    private fun isJoinRouteUnavailable(error: Exception): Boolean = error is LanSyncJoinRouteFailure ||
        error is java.net.SocketException || error is java.net.SocketTimeoutException || error is java.io.EOFException ||
        error.message == "SYNC_GROUP_JOIN_ROUTE_NO_LONGER_ACTIVE"

    override fun createInvitation(host: String?, forFile: Boolean) {
        val (privacy, command) = synchronized(lifecycle) {
            if (closed || paused) return
            val command = ++invitationCommandGeneration
            pendingInvitationCommand = command
            privacyGeneration to command
        }
        action("生成邀请") {
            try {
                if (synchronized(lifecycle) { command != invitationCommandGeneration || privacy != privacyGeneration }) return@action
                issueInvitation(host, privacy, command, forFile)
            }
            catch (error: Exception) {
                if (error is CancellationException || synchronized(lifecycle) {
                        command == invitationCommandGeneration && privacy == privacyGeneration
                    }) throw error
            } finally {
                synchronized(lifecycle) { if (pendingInvitationCommand == command) pendingInvitationCommand = null }
            }
        }
    }

    private suspend fun issueInvitation(host: String?, generation: Long, command: Long, forFile: Boolean) {
        val recovered = access?.localManagementAvailable == false
        if (recovered) require(recoveredManagement?.isUnlocked == true) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        else requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        synchronized(lifecycle) {
            if (command != invitationCommandGeneration || generation != privacyGeneration) return
            mutableState.update { it.copy(status = "正在核对已发布的设备组历史") }
        }
        if (synchronized(lifecycle) { command != invitationCommandGeneration || generation != privacyGeneration }) return
        require(ensureNearbyStarted()) { "SYNC_GROUP_NEARBY_NOT_AVAILABLE" }
        val owner = requireNotNull(access).invitation(requireNotNull(port))
        val live = requireNotNull(transport)
        synchronized(lifecycle) {
            val authorized = if (recovered) recoveredManagement?.isUnlocked == true else draft != null
            if (command != invitationCommandGeneration) { owner.close(); return }
            if (closed || generation != privacyGeneration || !authorized) {
                owner.close()
                error("SYNC_GROUP_MANAGEMENT_LOCKED")
            }
            clearInvitationLocked()
            val offer = live.invitation(owner, selectedHost = host, forFile = forFile, onExpired = {
                synchronized(lifecycle) {
                    if (invitation?.owner === owner && approvalInFlight == null) {
                        clearInvitationLocked()
                        mutableState.update { it.copy(status = "邀请已过期，请重新生成") }
                    }
                }
            }) { invited ->
                val present = synchronized(lifecycle) {
                    val current = invitation
                    if (closed || current == null || current.owner !== owner) false
                    else {
                        current.proof = invited
                        state.value.managementUnlocked
                    }
                }
                if (present) action("核对加入设备") { presentInvitationProof() }
            }
            val active = ActiveInvitation(owner, offer, forFile)
            invitation = active
            mutableState.update { it.copy(invitation = offer, invitationFileOnly = forFile, invitationAddresses = live.invitationAddresses(),
                invitationFileBinding = active.fileBinding,
                status = if (forFile) "邀请文件三分钟内有效。新设备先收到已同步的资料。本机待确认的变动继续保留。"
                else "邀请三分钟内有效。新设备先收到已同步的资料。本机待确认的变动继续保留。") }
        }
    }

    override fun saveInvitationFile(binding: String?, write: (ByteArray) -> Unit) = action("保存邀请文件", allowWhilePaused = true) {
        val bytes = synchronized(lifecycle) {
            val current = requireNotNull(invitation?.takeIf { binding != null && it.fileBinding == binding }) {
                "SYNC_GROUP_INVITE_FILE_SELECTION_EXPIRED"
            }
            try { current.owner.fileBytes(current.offer) }
            catch (_: IllegalArgumentException) { error("SYNC_GROUP_INVITE_FILE_SELECTION_EXPIRED") }
        }
        try { write(bytes) } finally { bytes.fill(0) }
        synchronized(lifecycle) {
            if (invitation?.fileBinding == binding) mutableState.update { it.copy(status = "邀请文件已保存，可在有效期内用新设备打开。") }
        }
    }

    private suspend fun presentInvitationProof() {
        val (current, invited, generation) = synchronized(lifecycle) {
            val current = invitation ?: return
            if (closed || !state.value.managementUnlocked || peer != null) return
            Triple(current, current.proof ?: return, privacyGeneration)
        }
        val preview = try {
            if (access?.localManagementAvailable == false) requireNotNull(recoveredManagement).prepareMember(invited, current.owner)
            else founder().prepareMember(invited, current.owner)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (synchronized(lifecycle) { current !== invitation || generation != privacyGeneration }) return
            throw failure
        }
        synchronized(lifecycle) {
            if (!closed && generation == privacyGeneration && invitation === current && state.value.managementUnlocked) {
                peer = preview
                mutableState.update { it.copy(peerConfirmation = LanSyncGroupPeerConfirmation(preview.request.approvalBinding, preview.name, preview.device)) }
            }
        }
    }

    override fun closeInvitation() = synchronized(lifecycle) {
        if (approvalInFlight != null) {
            mutableState.update { it.copy(status = "正在确认批准结果") }
        } else if (invitation != null || pendingInvitationCommand != null) {
            invitationCommandGeneration++
            pendingInvitationCommand = null
            clearInvitationLocked()
            mutableState.update { it.copy(status = "邀请已取消") }
        }
    }

    private fun clearInvitationLocked() {
        transport?.closeInvitation()
        invitation?.owner?.close()
        invitation = null
        peer = null
        mutableState.update { it.copy(invitation = null, peerConfirmation = null, invitationRetainedWhileLocked = false, invitationFileOnly = false,
            invitationAddresses = emptyList(), invitationFileBinding = null) }
    }
    override fun acceptPeer(binding: String) = action("批准加入") {
        val approval = synchronized(lifecycle) {
            require(state.value.managementUnlocked) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
            val current = requireNotNull(invitation) { "SYNC_GROUP_MEMBER_CONFIRMATION_REQUIRED" }
            val accepted = requireNotNull(peer) { "SYNC_GROUP_MEMBER_CONFIRMATION_REQUIRED" }.acknowledge(binding)
            approvalInFlight = current
            mutableState.update { it.copy(invitationApprovalInProgress = true, status = "正在批准已核对的设备") }
            accepted
        }
        try {
            if (access?.localManagementAvailable == false) {
                requireNotNull(recoveredManagement) { "SYNC_GROUP_MANAGEMENT_LOCKED" }.addMember(approval, ::rememberAdmittedPeer)
                publishRecoveredManagement()
            } else founder().addMember(requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }, approval, ::rememberAdmittedPeer)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val admitted = runCatching {
                access?.ledger()?.current()?.members?.get(approval.preview.device) == approval.preview.peer.member
            }.getOrDefault(false)
            if (admitted) refreshDevices()
            mutableState.update { it.copy(status = if (admitted) "设备已获批准，发布进度可继续核对"
                else "批准结果待核对，请解锁后继续管理操作") }
            throw error
        } finally {
            synchronized(lifecycle) {
                approvalInFlight = null
                clearInvitationLocked()
                mutableState.update { it.copy(invitationApprovalInProgress = false) }
            }
        }
        lockManagement(); refreshDevices(); cycle(force = true)
    }
    private fun rememberAdmittedPeer(peer: LanSyncGroupInvitedPeer, ledger: SyncDeviceLedger) {
        try { requireNotNull(access).rememberAdmittedPeerName(peer, ledger) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure("保存已批准设备的显示名称", error) }
    }
    override fun prepareRevocation(device: String) = action("准备撤销设备") {
        val preview = if (access?.localManagementAvailable == false) {
            requireNotNull(recoveredManagement) { "SYNC_GROUP_MANAGEMENT_LOCKED" }.prepareRevocation(device)
        } else { requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }; founder().prepareRevocation(device) }
        revocation = preview
        val name = state.value.devices.firstOrNull { it.id == preview.device }?.name ?: "已加入设备"
        mutableState.update { it.copy(revocation = LanSyncGroupRevocationConfirmation(preview.request.approvalBinding, name, preview.device)) }
    }
    override fun confirmRevocation(binding: String) = action("撤销设备") {
        val preview = requireNotNull(revocation) { "SYNC_GROUP_REVOCATION_CONFIRMATION_REQUIRED" }
        if (access?.localManagementAvailable == false) {
            requireNotNull(recoveredManagement) { "SYNC_GROUP_MANAGEMENT_LOCKED" }.revokeDevice(preview.acknowledge(binding))
            publishRecoveredManagement()
        } else founder().revokeDevice(requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }, preview.acknowledge(binding))
        lockManagement(); refreshDevices(); cycle(force = true)
    }
    override fun cancelRevocation() { revocation = null; mutableState.update { it.copy(revocation = null) } }
    override fun retry() = action("重新检查同步") {
        access?.let { attach(it) } ?: load()
        if (!closed && !paused) nearbyWake.trySend(Unit)
    }
    override fun openCloudSettings() {
        synchronized(lifecycle) {
            if (closed) return
            closeCloudSettings()
            mutableCloudSettings.update { it.copy(visible = true,
                notice = "新连接默认停止。启用后将核对加密资料，送达记录按连接分别保留。") }
        }
        refreshCloudSettings()
    }
    override fun closeCloudSettings() = synchronized(lifecycle) {
        cloudGeneration.incrementAndGet()
        cloudJobs.toList().forEach { it.cancel() }
        mutableCloudSettings.update { LanSyncGroupCloudSettingsState(revision = it.revision + 1) }
    }
    override fun refreshCloudSettings() = cloudAction("读取网盘连接") { null }
    override fun saveCloudRoute(draft: LanSyncGroupCloudDraft) {
        // The platform form erases its borrowed bytes as soon as this callback returns.
        val secret = draft.password.copyOf()
        val frozen = draft.copy(password = secret, enabled = false)
        cloudAction("保存网盘连接", cleanup = { secret.fill(0) }) { catalog ->
            catalog.add(frozen)
            cloudConfigurationChanged()
            "连接已保存在此设备，当前保持停止；尚未上传资料。"
        }
    }
    override fun setCloudRouteEnabled(id: SyncStoreId, enabled: Boolean) = cloudAction("更新网盘连接") { catalog ->
        require(cloudRuntime != null) { "SYNC_GROUP_CLOUD_DELIVERY_NOT_AVAILABLE" }
        catalog.setEnabled(id, enabled)
        cloudConfigurationChanged()
        if (enabled) "此连接已启用，等待核对加密资料；这不代表已经送达。"
        else "此连接已停止；远端文件、本机原件和待传队列均保留。"
    }
    override fun removeCloudRoute(id: SyncStoreId) = cloudAction("移除本机网盘设置") { catalog ->
        catalog.remove(id)
        cloudConfigurationChanged()
        "已移除此设备的连接设置；远端文件、本机原件和待传队列均保留。"
    }
    private fun cloudAction(label: String, cleanup: () -> Unit = {}, work: (LanSyncGroupCloudRoutes) -> String?) {
        val token = synchronized(lifecycle) {
            if (closed || !mutableCloudSettings.value.visible) { cleanup(); return }
            cloudGeneration.get() to actionGeneration.get()
        }
        fun current() = !closed && mutableCloudSettings.value.visible && cloudGeneration.get() == token.first && actionGeneration.get() == token.second
        fun update(change: (LanSyncGroupCloudSettingsState) -> LanSyncGroupCloudSettingsState) = synchronized(lifecycle) {
            if (current()) mutableCloudSettings.update(change)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            cloudAdmission.withLock {
                if (!current()) return@withLock
                update { it.copy(busy = true, error = null, notice = null) }
                try {
                    val result = runInterruptible(Dispatchers.IO) {
                        val owner = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
                        val catalog = owner.cloudRoutes()
                        val notice = work(catalog)
                        Triple(catalog.rows(), notice, catalog.revision.value)
                    }
                    update { it.copy(rows = result.first, canEnable = cloudRuntime != null,
                        notice = result.second ?: "送达记录只对应本设备的这条连接，其他目标仍单独核对。", revision = result.third) }
                    synchronized(lifecycle) {
                        if (current()) mutableState.update { it.copy(cloud = LanSyncGroupRouteStatus(
                            if (result.first.isEmpty()) "此设备未配置网盘" else if (result.first.none { it.enabled }) "本机连接均已停止，待传队列已保留"
                            else "已有连接启用，送达记录按目标分别核对", it.cloud.lastSuccessAt)) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    val reason = when (failure.message) {
                        "SYNC_GROUP_NODE_NOT_READY", "SYNC_GROUP_CLOUD_DELIVERY_NOT_AVAILABLE" -> "请先完成设备组的创建或加入。"
                        "SYNC_GROUP_CLOUD_ROUTES_ENDPOINT" -> "请填写以 HTTPS 开头、以斜杠结尾的网盘目录地址，账号和密码请填在各自的输入框。"
                        "SYNC_GROUP_CLOUD_ROUTES_NOT_FOUND" -> "这条连接已不存在，请刷新后再操作。"
                        "SYNC_GROUP_CLOUD_ROUTES_DISABLED" -> "这条连接已停止，请先明确启用。"
                        "SYNC_GROUP_CLOUD_ROUTES_PROTECTION_FAILED" -> "系统未能解锁本机连接设置，已有文件已保留。"
                        "SYNC_GROUP_CLOUD_ROUTES_BINDING" -> "这份连接设置不属于当前设备组或设备，已有文件已保留。"
                        "SYNC_GROUP_CLOUD_ROUTES_BUDGET" -> "连接数量或输入内容过长，请缩短输入或移除不再使用的本机连接。"
                        "SYNC_GROUP_CLOUD_ROUTES_INVALID" -> "连接设置格式未通过检查，请核对输入；已有文件已保留。"
                        else -> "本次操作未完成，请稍后重试；已有设置和待传队列已保留。"
                    }
                    update { it.copy(error = "$label：$reason") }
                } finally { update { it.copy(busy = false) } }
            }
        }
        job.invokeOnCompletion { cleanup(); synchronized(lifecycle) { cloudJobs -= job } }
        val start = synchronized(lifecycle) { if (current()) { cloudJobs += job; true } else false }
        if (start) job.start() else job.cancel()
    }
    private suspend fun drainCloudSettings() {
        closeCloudSettings()
        val jobs = synchronized(lifecycle) { cloudJobs.toList() }
        jobs.joinAll()
        cloudAdmission.withLock { }
    }
    override fun cancelActionReview() = synchronized(reviewGeneration) {
        reviewGeneration.incrementAndGet()
        mutableActionReview.value = null
        closeImagePreview()
    }
    override fun prepareResolution(view: LanSyncGroupNodeView, choices: Map<String, LanSyncGroupResolutionChoice>) {
        val generation = synchronized(reviewGeneration) { reviewGeneration.incrementAndGet() }
        val frozenChoices = choices.mapValues { (_, choice) -> when (choice) {
            LanSyncGroupResolutionChoice.Later -> choice
            is LanSyncGroupResolutionChoice.Keep -> choice.copy(copies = choice.copies.toMap())
        } }.toMap()
        action("核对冲突处理目标") {
            require(frozenChoices.isNotEmpty()) { "SYNC_GROUP_RESOLUTION_SELECTION" }
            val plan = requireNotNull(view.plan) { "SYNC_GROUP_NODE_NOT_READY" }
            val prepared = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.node.prepareResolution(frozenChoices)
            require(prepared.checkpoint == plan.checkpoint && prepared.heads == plan.heads &&
                prepared.sourceFiles == plan.sourceFiles && prepared.appliedBefore == plan.appliedBefore) { "SYNC_GROUP_RESOLUTION_STALE" }
            val review = LanSyncGroupActionReview(prepared.binding, resolution = prepared, resolutionBasis = view, canConfirm = true)
            synchronized(reviewGeneration) { if (reviewGeneration.get() == generation) mutableActionReview.value = review }
        }
    }
    override fun resumeResolution(operation: String) = action("接续已确认的冲突处理") {
        requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.node.resumeResolution(operation)
        cycle(force = true)
    }
    override fun prepareSourceReview(view: LanSyncGroupNodeView, unit: String) {
        val generation = synchronized(reviewGeneration) { reviewGeneration.incrementAndGet() }
        action("核对本机发布目标") {
            val plan = requireNotNull(view.plan) { "SYNC_GROUP_NODE_NOT_READY" }
            val selected = plan.components.getValue(unit).filterTo(linkedSetOf()) {
                plan.units.getValue(it).stage != LanSyncGroupNativeStage.UNCHANGED
            }
            val review = view.review(selected)
            val missing = review.units.values.any { it.missingBodies.isNotEmpty() || it.records.missing.isNotEmpty() }
            val dependencies = review.units.values.any { it.dependencies.isNotEmpty() }
            val prepared = LanSyncGroupActionReview(review.binding, source = review, canConfirm = !missing && !dependencies,
                heldReason = if (missing) "缺少已签名正文或记录归属证据，请先补齐后重新核对。" else if (dependencies) "需要一并核对关联内容。" else null)
            synchronized(reviewGeneration) { if (reviewGeneration.get() == generation) mutableActionReview.value = prepared }
        }
    }
    override fun prepareIncomingReview(view: LanSyncGroupNodeView, unit: String) {
        val generation = synchronized(reviewGeneration) { reviewGeneration.incrementAndGet() }
        action("核对传入删除或覆盖") {
            val plan = requireNotNull(view.plan) { "SYNC_GROUP_NODE_NOT_READY" }
            val review = requireNotNull(view.incomingReview) { "SYNC_GROUP_INCOMING_REVIEW_MISSING" }
            val selected = plan.components.getValue(unit).intersect(review.reductions.keys)
            require(unit in selected) { "SYNC_GROUP_INCOMING_CONFIRMATION_REQUIRED" }
            val bindings = java.util.Collections.unmodifiableMap(selected.sorted().associateWith { review.reductions.getValue(it).binding })
            review.acknowledge(bindings) // Complete linked reductions only, without native mutation.
            val binding = lanSyncHash(kotlinx.serialization.json.buildJsonObject {
                put("incoming", kotlinx.serialization.json.JsonObject(bindings.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            })
            val prepared = LanSyncGroupActionReview(binding, incoming = review, incomingBindings = bindings, canConfirm = true)
            synchronized(reviewGeneration) { if (reviewGeneration.get() == generation) mutableActionReview.value = prepared }
        }
    }
    override fun confirmActionReview(binding: String) = action("按已核对目标继续") {
        val selected = requireNotNull(mutableActionReview.value) { "SYNC_GROUP_REVIEW_DISMISSED" }
        require(selected.binding == binding && selected.canConfirm) { "SYNC_GROUP_REVIEW_CHANGED" }
        val node = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.node
        selected.source?.let { node.commit(it, it.requiredBindings) }
        selected.incoming?.let { node.applyIncoming(it.acknowledge(selected.incomingBindings)) }
        selected.recovery?.let {
            try { node.applyRecovery(it) } catch (failure: IllegalStateException) {
                if (failure.message == "SYNC_GROUP_NATIVE_RELOAD_PENDING" && mutableActionReview.value === selected) cancelActionReview()
                throw failure
            }
        }
        selected.resolution?.let { node.resolve(it.acknowledge(it.binding, it.requiredBindings, it.requiredRestorations)) }
        if (mutableActionReview.value === selected) cancelActionReview()
        cycle(force = true)
    }
    override fun refreshRecovery() = action("读取本机保全记录") {
        mutableRecoveryEntries.value = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.node.recoveryEntries()
    }
    override fun closeRecovery() = synchronized(recoveryGeneration) {
        recoveryGeneration.incrementAndGet(); mutableRecoveryView.value = null
        cancelActionReview()
    }
    private fun imageContextAvailable(context: LanSyncGroupFrozenImageContext): Boolean = !closed && when (context) {
        is LanSyncGroupFrozenImageContext.Inspection -> true // Frozen inspection survives subsequent emissions.
        is LanSyncGroupFrozenImageContext.Action -> mutableActionReview.value === context.review
        is LanSyncGroupFrozenImageContext.Recovery -> mutableRecoveryView.value === context.view
    }
    override fun previewImage(context: LanSyncGroupFrozenImageContext, record: LanSyncRecord, reference: String) {
        if (closed) return
        val generation = synchronized(imageGeneration) {
            imageContext = null; mutableImages.value = LanSyncGroupImageState()
            imageGeneration.incrementAndGet()
        }
        scope.launch {
            try { imageAdmission.withPermit {
                require(imageContextAvailable(context) && context.owns(record, reference)) { "SYNC_IMAGE_PREVIEW_EXPIRED" }
                val preview = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.node.prepareFrozenImage(record, reference)
                currentCoroutineContext().ensureActive()
                synchronized(imageGeneration) {
                    if (generation == imageGeneration.get() && imageContextAvailable(context)) {
                        imageContext = context; mutableImages.value = LanSyncGroupImageState(preview)
                    }
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(imageGeneration) {
                    if (generation == imageGeneration.get()) mutableImages.value = LanSyncGroupImageState(error =
                        if (failure.message == "SYNC_IMAGE_PREVIEW_EXPIRED") "此图片已不属于所选冻结内容，请重新打开详情。"
                        else "冻结图片未能通过读取与格式校验，原资料和当前选择已保留。")
                }
            }
        }
    }
    override fun readImagePreview(id: String): ByteArray {
        val selected = synchronized(imageGeneration) {
            val preview = requireNotNull(mutableImages.value.preview?.takeIf { it.id == id }) { "SYNC_IMAGE_PREVIEW_EXPIRED" }
            val context = requireNotNull(imageContext) { "SYNC_IMAGE_PREVIEW_EXPIRED" }
            require(imageContextAvailable(context) && context.owns(preview.record, preview.reference)) { "SYNC_IMAGE_PREVIEW_EXPIRED" }
            preview
        }
        return requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.node.readFrozenImagePreview(selected)
    }
    override fun closeImagePreview() = synchronized(imageGeneration) {
        imageGeneration.incrementAndGet(); imageContext = null; mutableImages.value = LanSyncGroupImageState()
    }
    override fun openRecovery(transaction: String) {
        val generation = synchronized(recoveryGeneration) { recoveryGeneration.incrementAndGet() }
        action("浏览本机保全") {
            val prepared = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.node.browseRecovery(transaction)
            synchronized(recoveryGeneration) { if (generation == recoveryGeneration.get()) mutableRecoveryView.value = prepared }
        }
    }
    override fun prepareRestore(unit: String) {
        val generation = synchronized(reviewGeneration) { reviewGeneration.incrementAndGet() }
        val selected = mutableRecoveryView.value
        action("核对恢复目标") {
            requireNotNull(selected) { "SYNC_RECOVERY_PREVIEW_EXPIRED" }
            require(unit in selected.units && mutableRecoveryView.value === selected) { "SYNC_RECOVERY_PREVIEW_EXPIRED" }
            val preview = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }.node.prepareRecovery(selected.entry.transaction, unit)
            val prepared = LanSyncGroupActionReview(preview.binding, recovery = preview, canConfirm = preview.hasChanges,
                heldReason = if (preview.hasChanges) null else "本机内容与保全目标一致，无需恢复。")
            synchronized(reviewGeneration) { if (generation == reviewGeneration.get()) mutableActionReview.value = prepared }
        }
    }
    private suspend fun attach(opened: LanSyncGroupRuntimeAccess) {
        synchronized(lifecycle) {
        check(!closed) { "SYNC_GROUP_COORDINATOR_CLOSED" }
        if (access == null) {
            opened.routeFailure = ::nearbyReadFailure
            access = opened
            cloudRuntime = makeCloudRuntime(opened, cloudWorkAllowed, ::requestLocalCheck, ::cloudProgress)
            metadataStamp = null
        }
        }
        if (presentMembershipConflict(opened)) return
        opened.membershipRecovery.publishInstalledCheckpoint()
        mutableState.update { it.copy(stage = LanSyncGroupPanelStage.READY, recoveryJoining = false, canAbandonUnactivated = false,
            joiningDevice = null, canAbandonJoin = false, canRefreshJoinPreparation = false, canShelveJoin = false, shelvedJoins = emptyList(),
            canArchiveRevokedJoin = false, joiningLedgerConflict = false, joiningMembershipRevoked = false, membershipConflict = null,
            membershipRecoveryPackageImportAllowed = true, membershipRecoveryExportBinding = opened.membershipRecovery.exportBinding(),
            error = if (it.stage == LanSyncGroupPanelStage.JOINING) null else it.error,
            localManagementAvailable = requireNotNull(access).localManagementAvailable,
            managementRecoveryAvailable = !requireNotNull(access).localManagementAvailable,
            paused = paused, status = if (paused) "设备组同步尚未恢复，原件与待传队列已保留" else "设备组已就绪，应用正在检查同步") }
        refreshDevices()
        if (!paused) try { cycle(force = true) } finally { if (!paused && !closed) startLoop() }
    }

    private suspend fun presentMembershipConflict(opened: LanSyncGroupRuntimeAccess): Boolean {
        val fork = opened.verifiedLedgerFork() ?: return false
        val originalMembers = fork.common.members
        val summary = LanSyncGroupLedgerConflict(fork.common.sequence, fork.headCount, fork.branches.map { signed ->
            LanSyncGroupLedgerConflictBranch(signed.id.value,
                (signed.members.keys - originalMembers.keys).sorted(),
                originalMembers.keys.filter { originalMembers.getValue(it).active && signed.members[it]?.active == false }.sorted())
        })
        if (state.value.stage == LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT && state.value.membershipConflict == summary) return true
        lockManagement()
        cancelActionReview()
        mutableView.value = null
        val storedPackage = opened.membershipRecovery.exportBinding()
        val nearbyToClose = synchronized(lifecycle) {
            val sockets = discovery to transport
            discovery = null
            transport = null
            port = null
            nearbyReady = false
            nearbyScan = null
            nearbyRestartRequested = false
            opened.reader.removeRoutes("nearby")
            mutableState.update { it.copy(stage = LanSyncGroupPanelStage.MEMBERSHIP_CONFLICT, membershipConflict = summary,
                membershipRecoveryPackageImportAllowed = true, membershipRecoveryExportBinding = storedPackage,
                status = "成员变更冲突。已保留签名分支、原件和待传队列，设备组同步和成员操作已暂停。",
                error = null, devices = emptyList(), nearbyNotice = null,
                nearby = LanSyncGroupRouteStatus("成员变更冲突，附近同步已暂停", it.nearby.lastSuccessAt),
                cloud = LanSyncGroupRouteStatus("成员变更冲突，网盘同步已暂停", it.cloud.lastSuccessAt)) }
            sockets
        }
        cancelCloudWork()
        withContext(NonCancellable) {
            try { nearbyToClose.first?.close() } finally { nearbyToClose.second?.closeAndDrain() }
        }
        return true
    }
    private fun refreshDevices() {
        val current = access ?: return
        val ledger = current.ledger().current()
        val manager = SyncDeviceSignatures.decode(current.trust.ownerCertificate).fingerprint
        var hintFailure: Exception? = null
        val rows = ledger.members.map { (id, member) ->
            val name = if (id == current.device) deviceName else if (id == manager) "组管理身份" else {
                val hint = try { current.peerDisplayName(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (hintFailure == null) hintFailure = error; null }
                hint ?: "已加入设备"
            }
            LanSyncGroupDeviceRow(id, name, if (member.active) "已加入" else "已撤销",
                id == current.device, id == manager, false, nearby = if (
                    member.active && ledger.members[current.device]?.active == true && id != current.device && id != manager
                ) LanSyncGroupDeviceNearby() else null)
        }
        mutableState.update { state ->
            val previous = state.devices.associateBy { it.id }
            state.copy(devices = rows.map { row -> row.copy(
                canRevoke = state.managementUnlocked && ledger.members.getValue(row.id).active && !row.thisDevice && !row.managementIdentity,
                nearby = row.nearby?.let { previous[row.id]?.nearby ?: it }) })
        }
        hintFailure?.let { failure("读取设备显示名称（原文件已保留）", it) }
    }
    private fun nearbyReadFailure(route: String, failure: Exception) {
        if (failure is CancellationException) throw failure
        if (!route.startsWith("nearby:")) {
            if (!route.startsWith("cloud:")) nearbyFailure("READ", failure)
            return
        }
        synchronized(lifecycle) {
            if (closed || paused || !nearbyReady) return
            val scan = nearbyScan ?: return
            if (!route.startsWith(scan.routePrefix)) return
            scan.readFailures += route.removePrefix(scan.routePrefix)
            if (scan.published) projectNearby(scan)
        }
    }
    private fun projectNearby(scan: NearbyScan) {
        check(Thread.holdsLock(lifecycle))
        if (closed || paused || !nearbyReady || nearbyScan !== scan) return
        scan.published = true
        mutableState.update { state ->
            var connected = 0
            var failed = 0
            var notDiscovered = 0
            val rows = state.devices.map { device ->
                val previous = device.nearby ?: return@map device
                val outcome = scan.outcomes[device.id] ?: LanSyncGroupDeviceNearby()
                val stage = if (outcome.lastSuccessAt != null && device.id in scan.readFailures)
                    LanSyncGroupNearbyStage.READ_INCOMPLETE else outcome.stage
                if (outcome.lastSuccessAt != null) connected++
                when (stage) {
                    LanSyncGroupNearbyStage.NOT_DISCOVERED -> notDiscovered++
                    LanSyncGroupNearbyStage.DISCOVERY_FAILED, LanSyncGroupNearbyStage.INDEX_FAILED,
                    LanSyncGroupNearbyStage.READ_INCOMPLETE -> failed++
                    else -> Unit
                }
                device.copy(nearby = LanSyncGroupDeviceNearby(stage, outcome.lastSuccessAt ?: previous.lastSuccessAt))
            }
            val message = when {
                connected > 0 -> buildString {
                    append("已读取 ${connected} 台附近设备的加密索引")
                    if (failed > 0) append("，${failed} 台连接或读取未完成")
                    if (notDiscovered > 0) append("，本轮未发现 ${notDiscovered} 台")
                }
                failed == 1 && notDiscovered == 0 -> "暂时无法读取一台附近成员的加密索引。其他连接可继续同步。"
                failed > 0 -> "${failed} 台附近设备连接或读取未完成"
                else -> "暂未发现同网设备"
            }
            state.copy(devices = rows, nearby = LanSyncGroupRouteStatus(message, scan.lastSuccessAt ?: state.nearby.lastSuccessAt))
        }
    }
    private fun resetNearbyObservations(state: LanSyncGroupPanelState): List<LanSyncGroupDeviceRow> =
        state.devices.map { device -> device.copy(nearby = device.nearby?.copy(stage = LanSyncGroupNearbyStage.UNOBSERVED)) }

    private fun nearbyFailure(stage: String, failure: Exception) {
        if (failure is CancellationException) throw failure
        if (stage == "NEARBY_PENDING_APPROVAL") return
        var restart = false
        synchronized(lifecycle) {
            if (closed || paused || state.value.membershipConflict != null) return
            if (stage == "GROUP_DISCOVERY_LISTENER" || stage == "NEARBY_LISTENER") {
                nearbyReady = false
                nearbyRestartRequested = true
                nearbyScan = null
                restart = true
            }
            val message = when (stage) {
                "NEARBY_SERVER" -> "有一条附近设备连接未完成。其他连接可继续同步。"
                "GROUP_DISCOVERY_SEND" -> "有一条附近发现路径暂时不可用。"
                else -> "局域网连接暂时不可用，将在前台或网络变化后重试；本机与网盘工作可继续"
            }
            mutableState.update {
                if (stage in setOf("NEARBY_SERVER", "GROUP_DISCOVERY_SEND") || nearbyScan?.published == true)
                    it.copy(nearbyNotice = message)
                else it.copy(nearby = LanSyncGroupRouteStatus(message, it.nearby.lastSuccessAt), nearbyNotice = null,
                    devices = if (restart) resetNearbyObservations(it) else it.devices)
            }
        }
        // The callback can run on the listener itself. Its independent worker owns teardown/join.
        if (restart) nearbyWake.trySend(Unit)
        // No exception message, address, certificate or user data enters this diagnostic.
        System.err.println("SYNC_GROUP_NEARBY_${if (stage == "START") "START_FAILED" else "IO_FAILED"}")
    }
    private suspend fun ensureNearbyStarted(): Boolean = nearbyAdmission.withLock {
        val obsolete = synchronized(lifecycle) {
            if (closed || paused || access == null || state.value.membershipConflict != null) return@withLock false
            if (nearbyRestartRequested) discovery to transport else null
        }
        if (obsolete != null) {
            try {
                withContext(NonCancellable) {
                    try { obsolete.first?.close() } finally { obsolete.second?.closeAndDrain() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { nearbyFailure("RESTART", error); return@withLock false }
            synchronized(lifecycle) {
                if (discovery === obsolete.first) discovery = null
                if (transport === obsolete.second) transport = null
                port = null; nearbyReady = false; nearbyRestartRequested = false
                clearInvitationLocked()
                access?.reader?.removeRoutes("nearby")
            }
            currentCoroutineContext().ensureActive()
        }
        var failure: Exception? = null
        var failedTransport: LanSyncGroupAppTransport? = null
        var failedDiscovery: LanSyncGroupDiscovery? = null
        synchronized(lifecycle) {
            if (closed || paused || access == null || state.value.membershipConflict != null) return@withLock false
            if (nearbyReady) return@withLock true
            try {
                val current = requireNotNull(access)
                val live = transport ?: LanSyncGroupAppTransport(current, network, ::nearbyFailure).also { transport = it }
                val bound = live.start()
                network.listening(current.device, bound)
                if (network.discoveryEnabled) {
                    val hints = discovery ?: LanSyncGroupDiscovery(current, network, ::nearbyFailure).also { discovery = it }
                    hints.start(bound)
                }
                port = bound; nearbyReady = true
                mutableState.update { it.copy(nearby = LanSyncGroupRouteStatus("局域网监听已启动，等待已加入设备", it.nearby.lastSuccessAt),
                    nearbyNotice = null) }
                return@withLock true
            } catch (error: Exception) {
                failure = error; nearbyReady = false; port = null
                failedTransport = transport; failedDiscovery = discovery
                transport = null; discovery = null
                access?.reader?.removeRoutes("nearby")
            }
        }
        // Optional listener teardown never holds local/native/cloud admission.
        try { failedDiscovery?.close() } finally { failedTransport?.closeAndDrain() }
        requireNotNull(failure).let { nearbyFailure("START", it) }
        false
    }
    private fun startLoop() = synchronized(lifecycle) {
        if (loop?.isActive == true || closed || paused || state.value.membershipConflict != null) return
        localWorker = scope.launch {
            for (ignored in localWake) {
                delay(250)
                if (closed || paused || access == null) continue
                admission.withLock {
                    if (closed || paused || access == null) return@withLock
                    try {
                        if (cycle(force = true)) {
                            automaticLocalBusyNotice?.let { notice ->
                                mutableState.update { if (it.error == notice) it.copy(error = null) else it }
                            }
                            automaticLocalBusyNotice = null
                        }
                    } catch (error: Exception) { failure("本机同步准备", error, automaticLocalCheck = true) }
                }
            }
        }
        nearbyWorker = scope.launch {
            for (ignored in nearbyWake) {
                if (closed || paused || access == null) continue
                try {
                    if (!ensureNearbyStarted()) continue
                    pullNearby()
                } catch (error: Exception) { nearbyFailure("PULL", error) }
            }
        }
        cloudWorker = scope.launch {
            var handled = -1L
            var needsBusinessAttempt = false
            while (isActive && !closed && !paused) {
                if (access == null || cloudRuntime == null) {
                    cloudWake.receive()
                    if (closed || paused || access == null || cloudRuntime == null) continue
                }
                val pending = cloudRequests.get() != handled
                val wait = if (pending) 0L else cloudRuntime?.nextDelayMillis(forceIdle = needsBusinessAttempt)
                when {
                    wait == null -> cloudWake.receive()
                    wait > 0 -> withTimeoutOrNull(wait) { cloudWake.receive() }
                    else -> cloudWake.tryReceive()
                }
                if (closed || paused || access == null) continue
                val requested = cloudRequests.get()
                if (requested == handled) {
                    // A delayed channel hint for an already-consumed request is not a new wake.
                    val remaining = cloudRuntime?.nextDelayMillis(forceIdle = needsBusinessAttempt)
                    if (remaining == null || remaining > 0) continue
                }
                val forceIdle = requested != handled || needsBusinessAttempt
                var invoked = false
                try { runCloudCycle(forceIdle = forceIdle, onInvoked = { invoked = true }) }
                catch (failure: Exception) {
                    // A finite batch may cancel its own child while this worker remains active.
                    // Actual worker cancellation from pause/close must still leave immediately.
                    currentCoroutineContext().ensureActive()
                    cloudRuntime?.deferUntilWake()
                    mutableState.update { it.copy(cloud = LanSyncGroupRouteStatus(cloudFailureText(failure), it.cloud.lastSuccessAt)) }
                }
                finally {
                    handled = requested
                    // Consume the wake without losing its unattempted business or spinning the
                    // platform gate. The timer still honors platform deferral and route errors.
                    needsBusinessAttempt = forceIdle && !invoked
                }
            }
        }
        loop = scope.launch {
            while (isActive) {
                delay(10_000)
                if (closed || paused) break
                if (access == null) {
                    if (admission.tryLock()) try {
                        val page = state.value
                        if (page.stage == LanSyncGroupPanelStage.JOINING && !page.recoveryJoining && !page.canAbandonJoin &&
                            !page.canArchiveRevokedJoin && !page.joiningLedgerConflict) attemptJoin()
                    }
                    catch (error: Exception) { failure("继续加入", error) }
                    finally { admission.unlock() }
                } else {
                    if (latestSourceRevision != handledSourceRevision || localRequests.get() != handledLocalRequest ||
                        System.nanoTime() - lastSourceScan >= 60_000_000_000L) requestLocalCheck()
                    nearbyWake.trySend(Unit)
                }
            }
        }
        localWake.trySend(Unit); nearbyWake.trySend(Unit); requestCloudCheck()
    }
    private fun cloudProgress(progress: Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) {
        mutableCloudSettings.update { it.copy(progress = progress) }
        val completed = progress.values.filter { it.selectionObserved && it.lastObservedAt != null }
        mutableState.update { state -> if (state.membershipConflict != null) state else state.copy(cloud = LanSyncGroupRouteStatus(
            if (progress.isEmpty()) "此设备没有启用的网盘连接"
            else if (completed.size == progress.size) "已核对 ${completed.size} 个目标的所选密文；设备组仍按当前状态检查"
            else "网盘待核对 ${progress.size - completed.size} 个目标，已完成记录与待传队列均保留",
            completed.mapNotNull { it.lastObservedAt }.minOrNull() ?: state.cloud.lastSuccessAt)) }
    }
    private fun cloudFailureText(failure: Exception) = when (failure.message) {
        "SYNC_GROUP_CLOUD_CONFIGURATION_UNAVAILABLE" -> "网盘连接设置暂时无法读取，原文件已保留；请打开连接设置检查"
        "SYNC_GROUP_CLOUD_BACKGROUND_FOREGROUND_REQUIRED" -> "网盘等待切回应用后继续，待传队列已保留"
        "SYNC_GROUP_CLOUD_BACKGROUND_TIME_LIMIT" -> "网盘已达到系统后台时限，待传队列已保留，等待恢复"
        "SYNC_GROUP_CLOUD_BACKGROUND_STOP_PENDING" -> "上一批网盘传输仍在停止，待传队列已保留"
        "SYNC_GROUP_CLOUD_BACKGROUND_START_FAILED", "SYNC_GROUP_CLOUD_BACKGROUND_PROMOTION_FAILED",
        "SYNC_GROUP_CLOUD_BACKGROUND_PROMOTION_TIMEOUT" -> "系统尚未允许本批网盘后台传输，请切回应用检查；待传队列已保留"
        else -> "网盘核对未完成，待传队列已保留"
    }
    private suspend fun runCloudCycle(shutdown: Boolean = false, forceIdle: Boolean = true,
        onInvoked: () -> Unit = {}): Boolean {
        access ?: return true
        if (state.value.membershipConflict != null) return false
        val runtime = cloudRuntime ?: return true
        // An idle/disabled batch starts no platform lease. A deferred enabled target remains
        // pending at final close; its backoff is never promoted to a completion receipt.
        if (!runtime.prepareScheduledBatch(forceIdle)) return !shutdown || runtime.nextDelayMillis() == null
        var invoked = false
        val completed = try {
            cloudWork { invoked = true; onInvoked(); runtime.cycle(recoverNative = !shutdown, outgoingOnly = shutdown, forceIdle = forceIdle) }
        } finally { if (!invoked) runtime.deferUntilWake() }
        if (!completed) mutableState.update { it.copy(cloud = LanSyncGroupRouteStatus("网盘核对尚未完成，待传队列已保留", it.cloud.lastSuccessAt)) }
        return completed
    }
    private suspend fun discoverNearbyIndex(node: LanSyncGroupNode, store: CiphertextStore): Boolean {
        var cursor: CommitCursor? = null
        var complete = true
        do {
            val page = node.discover(store, cursor)
            if (page.observations.any { it.stage == SyncInboxStage.INVALID_OBJECT }) complete = false
            cursor = page.nextCursor
        } while (cursor != null)
        return complete
    }
    private suspend fun checkNearbyMembership(current: LanSyncGroupRuntimeAccess, live: LanSyncGroupAppTransport) {
        val members = current.ledger().current().members
        if (members[current.device]?.active != true) return
        val hints = try {
            network.discoverGroupLedger(current.trust.group, current.device, members.keys)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            System.err.println("SYNC_GROUP_NEARBY_LEDGER_DISCOVERY_FAILED")
            return
        }
        for (hint in hints) {
            currentCoroutineContext().ensureActive()
            if (closed || paused) return
            val advanced = try { live.catchUpLedger(hint) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                System.err.println("SYNC_GROUP_NEARBY_LEDGER_CANDIDATE_REJECTED")
                false
            }
            if (advanced) {
                requestLocalCheck()
                nearbyWake.trySend(Unit)
                return
            }
        }
    }
    /** Pull observations are never labelled as peer/cloud outgoing delivery receipts. */
    private suspend fun pullNearby() {
        if (closed || paused) return
        val current = access ?: return
        val live = transport ?: return
        val routes = linkedMapOf<String, LanSyncGroupReplicaReader.Route>()
        val responder = discovery
        val managementIdentity = SyncDeviceSignatures.decode(current.trust.ownerCertificate).fingerprint
        val members = current.ledger().current().members
        val scan = synchronized(lifecycle) {
            if (closed || paused || !nearbyReady || transport !== live) return
            NearbyScan().also {
                nearbyScan = it
                mutableState.update { state -> state.copy(nearbyNotice = null) }
            }
        }
        for ((id, member) in members) {
            if (members[current.device]?.active != true) break
            if (!member.active || id == current.device || id == managementIdentity) continue
            currentCoroutineContext().ensureActive()
            val outcome = try {
                val peer = if (responder != null) responder.find(id) else network.discoverGroup(current.trust.group, current.device, id)
                if (peer != null) {
                    require(peer.fingerprint == id) { "SYNC_GROUP_DISCOVERY_DEVICE" }
                    routes[id] = LanSyncGroupReplicaReader.Route(scan.routePrefix + id, live.remote(peer))
                }
                LanSyncGroupNearbyStage.NOT_DISCOVERED
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LanSyncGroupNearbyStage.DISCOVERY_FAILED }
            synchronized(lifecycle) { scan.outcomes[id] = LanSyncGroupDeviceNearby(outcome) }
        }
        synchronized(lifecycle) {
            if (closed || paused || !nearbyReady || nearbyScan !== scan) return
            current.reader.routes("nearby", routes.values.toList())
        }
        for ((id, route) in routes) {
            currentCoroutineContext().ensureActive()
            val outcome = try {
                val complete = discoverNearbyIndex(current.node, current.reader.discoveryFrom(route.store, route.id))
                LanSyncGroupDeviceNearby(if (complete) LanSyncGroupNearbyStage.INDEX_READ else LanSyncGroupNearbyStage.READ_INCOMPLETE,
                    System.currentTimeMillis())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LanSyncGroupDeviceNearby(LanSyncGroupNearbyStage.INDEX_FAILED) }
            synchronized(lifecycle) {
                scan.outcomes[id] = outcome
                outcome.lastSuccessAt?.let { scan.lastSuccessAt = it }
            }
        }
        synchronized(lifecycle) { projectNearby(scan) }
        if (closed || paused) return
        checkNearbyMembership(current, live)
        if (closed || paused) return
        val stamp = candidateStamp(current.reader)
        val currentView = mutableView.value
        if (stamp == nearbyStamp && currentView?.needsDependencyFetch != true) return
        val frozen = current.node.reconcileForPrefetch()
        // Immutable dependency I/O never holds the local source/native decision mutex.
        current.node.prefetch(frozen, currentView?.nativeState?.units.orEmpty())
        nearbyStamp = stamp
        requestLocalCheck()
    }
    private fun candidateStamp(store: CiphertextStore): String {
        var cursor: CommitCursor? = null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        do {
            val page = store.listCommits(cursor)
            page.entries.forEach { digest.update(it.key.toString().toByteArray()); digest.update(it.descriptor.toString().toByteArray()) }
            cursor = page.nextCursor
        } while (cursor != null)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    /** Local-only inspection/publication. A completed check is not a peer/cloud completion receipt. */
    private suspend fun cycle(force: Boolean = false, shutdown: Boolean = false): Boolean {
        val current = access ?: return true
        if (state.value.membershipConflict != null) return false
        val finalSeal = shutdown && quiesced
        val request = localRequests.get(); val revision = latestSourceRevision
        val stamp = candidateStamp(current.reader)
        val now = System.nanoTime()
        if (!force && stamp == metadataStamp && revision == handledSourceRevision && request == handledLocalRequest &&
            now - lastSourceScan < 60_000_000_000L) return true
        var localCursor: CommitCursor? = null
        do { localCursor = current.node.discoverLocal(localCursor).nextCursor } while (localCursor != null)
        var inspected = try { current.node.inspectLocal(recoverNative = !shutdown) }
        catch (failure: IllegalArgumentException) {
            if (failure.message == "SYNC_GROUP_LEDGER_FORK_OR_MISSING" && presentMembershipConflict(current)) return false
            throw failure
        }
        mutableView.value = inspected
        if (inspected.stage != LanSyncGroupNodeStage.READY) {
            if (inspected.stage == LanSyncGroupNodeStage.TRUST_BLOCKED && presentMembershipConflict(current)) return false
            if (inspected.stage == LanSyncGroupNodeStage.DEVICE_REVOKED ||
                inspected.stage == LanSyncGroupNodeStage.WAITING_FOR_KEY) refreshDevices()
            mutableState.update { it.copy(status = when (inspected.stage) {
                LanSyncGroupNodeStage.DEVICE_REVOKED -> "此设备已撤销，保留原件和待传队列"
                LanSyncGroupNodeStage.WAITING_FOR_KEY -> "正在等待新密钥，待传队列已保留"
                else -> "设备组信任证据待检查，资料已保留"
            }) }
            return false
        }
        if (!shutdown) {
            inspected.plan?.let { current.node.apply(it) }
            inspected = current.node.inspectLocal()
        }
        val plan = requireNotNull(inspected.plan)
        val safe = linkedSetOf<String>()
        for (unit in plan.units.keys) {
            if (unit in safe || plan.units.getValue(unit).stage != LanSyncGroupNativeStage.LOCAL_PROPOSAL) continue
            val component = plan.components.getValue(unit).filterTo(linkedSetOf()) { plan.units.getValue(it).stage != LanSyncGroupNativeStage.UNCHANGED }
            if (component.any { plan.units.getValue(it).stage != LanSyncGroupNativeStage.LOCAL_PROPOSAL }) continue
            val review = inspected.review(component)
            if (review.requiredBindings.isEmpty() && review.units.values.all { it.missingBodies.isEmpty() && it.records.missing.isEmpty() && it.dependencies.isEmpty() }) safe += component
        }
        if (safe.isNotEmpty()) current.node.commit(inspected.review(safe), recoverNative = !shutdown)
        // Cold operations may span old epochs. upload resumes/rekeys EACH existing operation itself.
        var outgoingChanged = safe.isNotEmpty()
        for (operation in current.node.sourceOperations(eligibleForDelivery = true)) {
            val before = current.node.receipt(operation, current.localTarget)
            // The fresh inspection has already verified the signed source and recorded its native proof.
            if (finalSeal && before != null) continue
            current.node.upload(operation, current.replica, current.localTarget, recoverNative = !shutdown)
            if (before == null) {
                outgoingChanged = true
                mutableState.update { it.copy(local = LanSyncGroupRouteStatus("已保全并发布到本机加密副本", System.currentTimeMillis())) }
            }
        }
        if (!finalSeal || outgoingChanged) {
            mutableView.value = current.node.inspectLocal(recoverNative = !shutdown)
        }
        val checked = mutableView.value
        val heldRecoveryCodes = if (checked?.reconciliation?.recoveryHeldVersions.orEmpty().isNotEmpty())
            setOf("SYNC_GROUP_RECOVERY_VERSION_NOT_REVIEWED", "SYNC_GROUP_REVOKED_VERSION", "SYNC_GROUP_CURRENT_EPOCH_REQUIRED")
        else emptySet()
        val invalid = checked?.reconciliation?.observations.orEmpty().count {
            it.stage in setOf(SyncInboxStage.INVALID_OBJECT, SyncInboxStage.REJECTED, SyncInboxStage.LOCAL_CUSTODY_BLOCKED) &&
                it.code !in heldRecoveryCodes
        }
        if (invalid > 0) mutableState.update { it.copy(error = "已隔离 $invalid 个未通过验证的密文候选，原始证据已保留；正确副本可继续同步。") }
        metadataStamp = stamp; lastSourceScan = now
        handledSourceRevision = revision; handledLocalRequest = request
        refreshDevices()
        val held = checked?.plan?.units?.values?.count { it.stage !in setOf(LanSyncGroupNativeStage.UNCHANGED, LanSyncGroupNativeStage.READY_OBSERVATION) } ?: 0
        val heldByRecovery = checked?.plan?.recoveryHeldUnits?.size ?: 0
        mutableState.update { it.copy(
            status = when {
                heldByRecovery > 0 -> "$heldByRecovery 项历史资料仍保留在本机，等待关联签名记录核对。"
                held > 0 -> "同步检查完成，$held 项需要审阅或补齐依赖"
                else -> "同步检查完成"
            },
            local = if (it.local.lastSuccessAt == null) LanSyncGroupRouteStatus("本机加密副本已检查；历史回执时间未知") else it.local) }
        if (!shutdown && outgoingChanged) requestCloudCheck(dirty = true)
        return true
    }
    /** Recoverable user pause. Source revisions still invalidate in memory; no worker remains active. */
    suspend fun pauseAndDrain() = withContext(NonCancellable) {
        shutdownAdmission.withLock {
            if (closed) return@withLock
            synchronized(lifecycle) {
                paused = true
                actionGeneration.incrementAndGet()
                pendingSockets.forEach { runCatching { it.close() } }; pendingSockets.clear()
            }
            lockManagement(); closeRecovery()
            if (cloudRecoveryOwner.isInitialized()) cloudRecoveryOwner.value?.let { it.lock(); it.drain() }
            if (fileRecoveryOwner.isInitialized()) fileRecoveryOwner.value?.let { it.lock(); it.drain() }
            if (fileExportOwner.isInitialized()) fileExportOwner.value.let { it.lock(); it.drain() }
            cancelCloudWork()
            drainCloudSettings()
            discovery?.close(); transport?.closeAndDrain()
            loop?.cancelAndJoin(); nearbyWorker?.cancelAndJoin(); localWorker?.cancelAndJoin(); cloudWorker?.cancelAndJoin()
            cloudRuntime?.drain()
            admission.withLock { pendingJoinProof = null }
            discovery?.close(); transport?.closeAndDrain()
            discovery = null; transport = null; port = null; nearbyReady = false; nearbyRestartRequested = false; nearbyScan = null
            loop = null; nearbyWorker = null; localWorker = null; cloudWorker = null
            access?.reader?.routes(emptyList())
            mutableState.update { state ->
                if (state.membershipConflict != null) state.copy(paused = true, busy = false)
                else state.copy(paused = true, busy = false, status = "设备组同步已暂停，原件与待传队列已保留",
                    nearby = LanSyncGroupRouteStatus("附近设备连接检查已暂停", state.nearby.lastSuccessAt), nearbyNotice = null,
                    devices = resetNearbyObservations(state))
            }
        }
    }
    /** Terminal shutdown; retains daily owners until final source sealing. */
    suspend fun quiesceAndDrain() = withContext(NonCancellable) {
        shutdownAdmission.withLock {
            if (quiesced) return@withLock
            synchronized(lifecycle) {
                closed = true
                pendingSockets.forEach { runCatching { it.close() } }; pendingSockets.clear()
            }
            lockManagement(); closeRecovery()
            if (cloudRecoveryOwner.isInitialized()) cloudRecoveryOwner.value?.closeAndDrain()
            if (fileRecoveryOwner.isInitialized()) fileRecoveryOwner.value?.closeAndDrain()
            if (fileExportOwner.isInitialized()) fileExportOwner.value.closeAndDrain()
            cancelCloudWork()
            drainCloudSettings()
            discovery?.close(); transport?.closeAndDrain()
            sourceObserver?.cancelAndJoin(); loop?.cancelAndJoin(); nearbyWorker?.cancelAndJoin(); localWorker?.cancelAndJoin(); cloudWorker?.cancelAndJoin()
            cloudRuntime?.drain()
            admission.withLock { }
            scope.coroutineContext[Job]?.cancelAndJoin()
            discovery?.close(); transport?.closeAndDrain()
            quiesced = true
        }
    }
    /** Called only after producer/draft drain through the platform's shutdown-only native gate. */
    suspend fun flushLocalAfterWriters(): Boolean {
        require(quiesced) { "SYNC_GROUP_SHUTDOWN_NOT_QUIESCED" }
        return try {
            val sealed = admission.withLock { cycle(force = true, shutdown = true) }
            if (!sealed) false
            else if (paused) true
            else {
                val outgoing = try { withTimeoutOrNull(15_000) { runCloudCycle(shutdown = true) } == true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                finally { cancelCloudWork(); cloudRuntime?.drain() }
                if (!outgoing) mutableState.update { it.copy(cloud = LanSyncGroupRouteStatus("本机资料已封存，网盘尚有待核对内容；队列将保留供下次继续", it.cloud.lastSuccessAt)) }
                outgoing
            }
        }
        catch (error: Exception) { failure("最终本机封存", error); false }
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) {
        quiesceAndDrain()
        cloudRuntime?.close()
        founder?.close(); recoveredManagement?.close()
    }
}
