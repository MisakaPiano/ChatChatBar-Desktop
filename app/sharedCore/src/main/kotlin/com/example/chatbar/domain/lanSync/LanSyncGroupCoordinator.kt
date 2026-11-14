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

/** One application owner. Screens project state and lock secrets; they never own daily work. */
class LanSyncGroupCoordinator(private val hasExistingEvidence: () -> Boolean,
    private val enrollment: () -> LanSyncGroupEnrollment, private val joining: () -> LanSyncGroupJoining,
    private val network: LanSyncNetwork = LanSyncNetwork.Local, private val deviceName: String,
    private val beforeSetup: suspend () -> Unit = {},
    private val sourceChanges: Flow<Long>? = null, initiallyPaused: Boolean = false,
    private val cloudWorkAllowed: () -> Boolean = { true },
    private val cloudWork: suspend (suspend () -> Boolean) -> Boolean = { it() }) : LanSyncGroupPanelActions, LanSyncGroupCloudSettingsActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admission = Mutex()
    private val actionGeneration = java.util.concurrent.atomic.AtomicLong()
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
    private val imageGeneration = java.util.concurrent.atomic.AtomicLong()
    private val imageAdmission = Semaphore(1)
    @Volatile private var imageContext: LanSyncGroupFrozenImageContext? = null
    private val recoveryGeneration = java.util.concurrent.atomic.AtomicLong()
    private val reviewGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closed = false
    @Volatile private var paused = initiallyPaused
    private val lifecycle = Any()
    private var privacyGeneration = 0L
    private var privacyExpiry: Job? = null
    private val pendingSockets = mutableSetOf<java.net.Socket>()
    private val joiningNetwork = object : LanSyncNetwork by network {
        override fun connectInvitationOwned(identity: LanSyncTlsIdentity, peer: LanSyncPeer, own: (java.net.Socket) -> Unit): javax.net.ssl.SSLSocket =
            network.connectInvitationOwned(identity, peer) { socket ->
                synchronized(lifecycle) {
                    if (closed) { socket.close(); error("SYNC_GROUP_COORDINATOR_CLOSED") }
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
            return network.connectOwned(identity, peer) { socket ->
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
    private var invitation: LanSyncGroupInvitation? = null
    private var peer: LanSyncGroupMemberPreview? = null
    private var revocation: LanSyncGroupRevocationPreview? = null
    private var loop: Job? = null
    private var port: Int? = null
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
        requestLocalCheck(); nearbyWake.trySend(Unit); cloudWake.trySend(Unit)
    }
    fun onNetworkAvailable() {
        onForeground()
        if (!closed && !paused && hasExistingEvidence()) { nearbyWake.trySend(Unit); cloudWake.trySend(Unit) }
    }
    /** Platform timeout/lost protection cancels only this owner's cloud calls, preserving all queues. */
    fun cancelCloudWork() { cloudRuntime?.pause() }

    private fun founder() = founder ?: enrollment().also { founder = it }
    private fun member() = member ?: joining().also { member = it }
    private fun failure(stage: String, failure: Exception) {
        if (failure is CancellationException) throw failure
        val code = failure.message?.takeIf { it.matches(Regex("(?:SYNC|STORE)_[A-Z0-9_]{1,100}")) } ?: "SYNC_GROUP_OPERATION_FAILED"
        mutableState.update { it.copy(error = "$stage：$code") }
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
                mutableState.update { it.copy(paused = false, status = "正在恢复设备组同步") }
                open()
            }
        }
    }
    fun open() = action("打开设备组", allowWhilePaused = true) { access?.let { attach(it) } ?: if (hasExistingEvidence()) load() else Unit }
    private fun expirePrivacy(generation: Long) {
        privacyExpiry?.cancel()
        privacyExpiry = scope.launch {
            delay(300_000)
            synchronized(lifecycle) { if (generation == privacyGeneration) lockManagement() }
        }
    }
    private suspend fun load() {
        val own = founder().profile()
        if (own != null) {
            if (own.stage == LanSyncGroupEnrollmentStage.READY) attach(requireNotNull(founder().runtimeAccess()))
            else synchronized(lifecycle) {
                val prepared = draft?.takeIf { it.profile == own && it.session.isUnlocked }
                if (prepared != null) mutableState.update { it.copy(stage = LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE,
                    recoveryCode = prepared.recoveryCode(), setupBinding = prepared.operation.value,
                    localManagementAvailable = true, managementUnlocked = true, status = "请确认已妥善保存恢复码后完成创建") }
                else mutableState.update { it.copy(stage = LanSyncGroupPanelStage.PREPARED_LOCKED,
                    recoveryCode = null, setupBinding = null, managementUnlocked = false,
                    localManagementAvailable = true, status = "设置尚未完成，请用已保存的恢复码继续") }
            }
        } else {
            val joined = member().profile()
            if (joined?.stage == LanSyncGroupJoinStage.READY) attach(requireNotNull(member().runtimeAccess()))
            else if (joined != null) {
                mutableState.update { it.copy(stage = LanSyncGroupPanelStage.JOINING, status = "加入进度已保留，等待设备批准或重试") }
                startLoop()
            } else error("SYNC_GROUP_EXISTING_EVIDENCE_REQUIRES_RECOVERY")
        }
    }
    override fun createGroup() { val generation = synchronized(lifecycle) { privacyGeneration }; action("创建设备组", allowWhilePaused = true) {
        require(!hasExistingEvidence()) { "SYNC_GROUP_PROFILE_ALREADY_PREPARED" }
        beforeSetup()
        val operation = SyncStoreId.random()
        val request = SyncRootGroupCreateRequest(operation, LanSyncScope.schemas.keys.toSet(),
            lanSyncHash(UUID.randomUUID().toString().toByteArray()))
        val prepared = founder().prepare(request, SyncRootGroupCreateAdmission { require(it == request) })
        synchronized(lifecycle) {
            if (closed || generation != privacyGeneration) { prepared.close(); error("SYNC_GROUP_MANAGEMENT_LOCKED") }
            draft = prepared
            expirePrivacy(generation)
            mutableState.update { it.copy(stage = LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE, status = "请妥善保存恢复码后完成创建",
                recoveryCode = prepared.recoveryCode(), setupBinding = operation.value, managementUnlocked = true, localManagementAvailable = true) }
        }
    } }
    override fun recoverySaved(binding: String) = action("完成创建", allowWhilePaused = true) {
        val prepared = requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        require(binding == prepared.operation.value) { "SYNC_GROUP_PROFILE_DRAFT_CHANGED" }
        founder().activate(prepared, LanSyncGroupRecoverySavedAdmission { require(it == prepared.profile) })
        lockManagement()
        attach(requireNotNull(founder().runtimeAccess()))
    }
    override fun abandonUnusedSetup() = action("保留并放弃未完成设置", allowWhilePaused = true) {
        founder().abandonUnactivated(LanSyncGroupDraftAbandonAdmission { })
        lockManagement()
        mutableState.value = LanSyncGroupPanelState(paused = paused, privacyRevision = state.value.privacyRevision)
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
        val generation = synchronized(lifecycle) { privacyGeneration }; action("解锁管理", allowWhilePaused = true) {
        val opened = founder().resume(code)
        synchronized(lifecycle) {
            if (closed || generation != privacyGeneration) { opened.close(); error("SYNC_GROUP_MANAGEMENT_LOCKED") }
            draft?.close(); draft = opened
            expirePrivacy(generation)
            val prepared = opened.profile.stage == LanSyncGroupEnrollmentStage.PREPARED
            mutableState.update { it.copy(managementUnlocked = true, managementCanInvite = !prepared, recoveryCode = if (prepared) opened.recoveryCode() else null,
                setupBinding = if (prepared) opened.operation.value else null,
                stage = if (prepared) LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE else it.stage) }
            refreshDevices()
        }
    } }
    private suspend fun publishRecoveredManagement() {
        val current = requireNotNull(access) { "SYNC_GROUP_NODE_NOT_READY" }
        requireNotNull(recoveredManagement) { "SYNC_GROUP_MANAGEMENT_LOCKED" }.publish(current.replica, current.localTarget)
    }
    override fun recoverManagement(code: String) {
        val generation = synchronized(lifecycle) { privacyGeneration }
        action("恢复组管理") {
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
                    status = "已用本机加密管理资料恢复原设备组管理权") }
                refreshDevices()
            }
        }
    }
    override fun lockManagement() = synchronized(lifecycle) {
        closeCloudSettings()
        privacyGeneration++
        privacyExpiry?.cancel(); privacyExpiry = null
        draft?.close(); draft = null
        founder?.lockManagement()
        recoveredManagement?.lock()
        transport?.closeInvitation(); invitation?.close(); invitation = null; peer = null; revocation = null
        mutableState.update { it.copy(recoveryCode = null, setupBinding = null, managementUnlocked = false, managementCanInvite = false,
            invitation = null, peerConfirmation = null, revocation = null, privacyRevision = it.privacyRevision + 1,
            devices = it.devices.map { row -> row.copy(canRevoke = false) },
            stage = if (it.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE) LanSyncGroupPanelStage.PREPARED_LOCKED else it.stage) }
    }
    override fun beginJoin(code: String) = action("加入设备组", allowWhilePaused = true) {
        beforeSetup()
        val owner = member()
        if (owner.profile() == null) owner.prepare(code) { it.validate() }
        else owner.refreshInvitation(code) { it.validate() }
        mutableState.update { it.copy(stage = LanSyncGroupPanelStage.JOINING, status = "邀请已核对，正在验证此设备") }
        startLoop()
        if (!paused) attemptJoin()
    }
    override fun retryJoin() = action("继续加入") { attemptJoin() }
    override fun refreshJoinPreparation() = action("重新核对加入资料") { member().refreshPreparation(); attemptJoin() }
    private suspend fun prove(owner: LanSyncGroupJoining) = owner.proveUsing(deviceName, joiningNetwork)
    private suspend fun attemptJoin() {
        val owner = member(); val profile = owner.resumePreparation()
        if (profile.stage == LanSyncGroupJoinStage.READY) { attach(requireNotNull(owner.runtimeAccess())); return }
        if (profile.stage in setOf(LanSyncGroupJoinStage.PREPARED, LanSyncGroupJoinStage.PROOF_ATTEMPTED)) {
            try { prove(owner) } catch (error: Exception) { failure("加入证明", error) }
        }
        val result = owner.activate(owner.bootstrapStore(joiningNetwork))
        if (result.profile.stage == LanSyncGroupJoinStage.READY) attach(requireNotNull(owner.runtimeAccess()))
        else mutableState.update { it.copy(status = "等待邀请设备批准，加入进度已保留") }
    }
    override fun createInvitation() { val generation = synchronized(lifecycle) { privacyGeneration }; action("生成邀请") {
        val recovered = access?.localManagementAvailable == false
        if (recovered) require(recoveredManagement?.isUnlocked == true) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        else requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        cycle(force = true)
        val owner = if (recovered) requireNotNull(access).invitation(requireNotNull(port)) else founder().invitation(requireNotNull(port))
        val live = requireNotNull(transport)
        synchronized(lifecycle) {
        if (closed || generation != privacyGeneration || (if (recovered) recoveredManagement?.isUnlocked != true else draft == null)) { owner.close(); error("SYNC_GROUP_MANAGEMENT_LOCKED") }
        val offer = live.invitation(owner) { invited -> action("核对加入设备") {
            val preview = if (recovered) requireNotNull(recoveredManagement).prepareMember(invited, owner) else founder().prepareMember(invited, owner)
            try { requireNotNull(access).rememberPeerName(invited) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure("保存设备显示名称（成员核验结果保留）", error) }
            synchronized(lifecycle) {
                require(!closed && generation == privacyGeneration && invitation === owner &&
                    (if (recovered) recoveredManagement?.isUnlocked == true else draft != null)) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
                peer = preview
                mutableState.update { it.copy(peerConfirmation = LanSyncGroupPeerConfirmation(preview.request.approvalBinding, preview.name, preview.device)) }
            }
        } }
        invitation = owner
        mutableState.update { it.copy(invitation = offer, status = "邀请三分钟内有效，请核对新设备指纹后批准") }
        }
    } }
    override fun closeInvitation() {
        transport?.closeInvitation(); invitation?.close(); invitation = null; peer = null
        mutableState.update { it.copy(invitation = null, peerConfirmation = null) }
    }
    override fun acceptPeer(binding: String) = action("批准加入") {
        val preview = requireNotNull(peer) { "SYNC_GROUP_MEMBER_CONFIRMATION_REQUIRED" }
        if (access?.localManagementAvailable == false) {
            requireNotNull(recoveredManagement) { "SYNC_GROUP_MANAGEMENT_LOCKED" }.addMember(preview.acknowledge(binding))
            publishRecoveredManagement()
        } else founder().addMember(requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }, preview.acknowledge(binding))
        lockManagement(); refreshDevices(); cycle(force = true)
    }
    override fun prepareRevocation(device: String) = action("准备撤销设备") {
        val preview = if (access?.localManagementAvailable == false) {
            requireNotNull(recoveredManagement) { "SYNC_GROUP_MANAGEMENT_LOCKED" }.prepareRevocation(device)
        } else { requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }; founder().prepareRevocation(device) }
        revocation = preview
        val name = state.value.devices.firstOrNull { it.id == device }?.name ?: "已加入设备"
        mutableState.update { it.copy(revocation = LanSyncGroupRevocationConfirmation(preview.request.approvalBinding, name)) }
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
    override fun retry() = action("重新检查同步") { access?.let { attach(it) } ?: load() }
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
            "连接已保存在此设备，当前保持停止；尚未上传资料。"
        }
    }
    override fun setCloudRouteEnabled(id: SyncStoreId, enabled: Boolean) = cloudAction("更新网盘连接") { catalog ->
        require(cloudRuntime != null) { "SYNC_GROUP_CLOUD_DELIVERY_NOT_AVAILABLE" }
        catalog.setEnabled(id, enabled)
        cancelCloudWork(); cloudWake.trySend(Unit)
        if (enabled) "此连接已启用，等待核对加密资料；这不代表已经送达。"
        else "此连接已停止；远端文件、本机原件和待传队列均保留。"
    }
    override fun removeCloudRoute(id: SyncStoreId) = cloudAction("移除本机网盘设置") { catalog ->
        catalog.remove(id)
        cancelCloudWork(); cloudWake.trySend(Unit)
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
                update { it.copy(busy = true, error = null) }
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
        selected.recovery?.let { node.applyRecovery(it) }
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
            opened.routeFailure = { route, error -> if (!route.startsWith("cloud:")) failure("近端读取", error) }
            access = opened
            cloudRuntime = LanSyncGroupCloudRuntime(opened, cloudWorkAllowed, ::requestLocalCheck, ::cloudProgress)
            metadataStamp = null
        }
        val current = requireNotNull(access)
        if (!paused) {
            val live = transport ?: LanSyncGroupAppTransport(current, network, ::failure).also { transport = it }
            port = live.start()
            if (network.discoveryEnabled) {
                val hints = discovery ?: LanSyncGroupDiscovery(current, network, ::failure).also { discovery = it }
                hints.start(requireNotNull(port))
            }
        }
        }
        mutableState.update { it.copy(stage = LanSyncGroupPanelStage.READY, localManagementAvailable = requireNotNull(access).localManagementAvailable,
            managementRecoveryAvailable = !requireNotNull(access).localManagementAvailable,
            paused = paused, status = if (paused) "设备组同步尚未恢复，原件与待传队列已保留" else "设备组已就绪，应用正在检查同步") }
        refreshDevices()
        if (!paused) try { cycle(force = true) } finally { if (!paused && !closed) startLoop() }
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
                hint ?: "已加入设备 · ${id.take(8)}"
            }
            LanSyncGroupDeviceRow(id, name, if (member.active) "已加入" else "已撤销",
                id == current.device, id == manager, false)
        }
        mutableState.update { state -> state.copy(devices = rows.map { row -> row.copy(canRevoke =
            state.managementUnlocked && ledger.members.getValue(row.id).active && !row.thisDevice && !row.managementIdentity) }) }
        hintFailure?.let { failure("读取设备显示名称（原文件已保留）", it) }
    }
    private fun startLoop() {
        if (loop?.isActive == true || closed || paused) return
        localWorker = scope.launch {
            for (ignored in localWake) {
                delay(250)
                if (closed || paused || access == null) continue
                admission.withLock {
                    if (closed || paused || access == null) return@withLock
                    try { cycle(force = true) } catch (error: Exception) { failure("本机同步准备", error) }
                }
            }
        }
        nearbyWorker = scope.launch {
            for (ignored in nearbyWake) {
                if (closed || paused || access == null) continue
                try { pullNearby() } catch (error: Exception) { failure("近端同步", error) }
            }
        }
        cloudWorker = scope.launch {
            for (ignored in cloudWake) {
                if (closed || paused || access == null) continue
                try { runCloudCycle() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    mutableState.update { it.copy(cloud = LanSyncGroupRouteStatus(cloudFailureText(failure), it.cloud.lastSuccessAt)) }
                }
            }
        }
        loop = scope.launch {
            while (isActive) {
                delay(10_000)
                if (closed || paused) break
                if (access == null) {
                    if (admission.tryLock()) try { attemptJoin() }
                    catch (error: Exception) { failure("继续加入", error) }
                    finally { admission.unlock() }
                } else {
                    if (latestSourceRevision != handledSourceRevision || localRequests.get() != handledLocalRequest ||
                        System.nanoTime() - lastSourceScan >= 60_000_000_000L) requestLocalCheck()
                    nearbyWake.trySend(Unit)
                    cloudWake.trySend(Unit)
                }
            }
        }
        localWake.trySend(Unit); nearbyWake.trySend(Unit); cloudWake.trySend(Unit)
    }
    private fun cloudProgress(progress: Map<SyncStoreId, LanSyncGroupCloudRouteProgress>) {
        mutableCloudSettings.update { it.copy(progress = progress) }
        val completed = progress.values.filter { it.selectionObserved && it.lastObservedAt != null }
        mutableState.update { state -> state.copy(cloud = LanSyncGroupRouteStatus(
            if (progress.isEmpty()) "此设备没有启用的网盘连接"
            else if (completed.size == progress.size) "已核对 ${completed.size} 个目标的所选密文；设备组仍按当前状态检查"
            else "网盘待核对 ${progress.size - completed.size} 个目标，已完成记录与待传队列均保留",
            completed.mapNotNull { it.lastObservedAt }.minOrNull() ?: state.cloud.lastSuccessAt)) }
    }
    private fun cloudFailureText(failure: Exception) = when (failure.message) {
        "SYNC_GROUP_CLOUD_BACKGROUND_FOREGROUND_REQUIRED" -> "网盘等待切回应用后继续，待传队列已保留"
        "SYNC_GROUP_CLOUD_BACKGROUND_TIME_LIMIT" -> "网盘已达到系统后台时限，待传队列已保留，等待恢复"
        "SYNC_GROUP_CLOUD_BACKGROUND_STOP_PENDING" -> "上一批网盘传输仍在停止，待传队列已保留"
        "SYNC_GROUP_CLOUD_BACKGROUND_START_FAILED", "SYNC_GROUP_CLOUD_BACKGROUND_PROMOTION_FAILED",
        "SYNC_GROUP_CLOUD_BACKGROUND_PROMOTION_TIMEOUT" -> "系统尚未允许本批网盘后台传输，请切回应用检查；待传队列已保留"
        else -> "网盘核对未完成，待传队列已保留"
    }
    private suspend fun runCloudCycle(shutdown: Boolean = false): Boolean {
        val current = access ?: return true
        val runtime = cloudRuntime ?: return true
        val enabled = runInterruptible(Dispatchers.IO) { current.cloudRoutes().rows().any { it.enabled } }
        if (!enabled) return true
        val completed = cloudWork { runtime.cycle(recoverNative = !shutdown, outgoingOnly = shutdown) }
        if (!completed) mutableState.update { it.copy(cloud = LanSyncGroupRouteStatus("网盘核对尚未完成，待传队列已保留", it.cloud.lastSuccessAt)) }
        return completed
    }
    private suspend fun discover(node: LanSyncGroupNode, store: CiphertextStore) {
        var cursor: CommitCursor? = null
        do { cursor = node.discover(store, cursor).nextCursor } while (cursor != null)
    }
    /** Pull observations are never labelled as peer/cloud outgoing delivery receipts. */
    private suspend fun pullNearby() {
        if (closed || paused) return
        val current = access ?: return
        val routes = mutableListOf<LanSyncGroupReplicaReader.Route>()
        val managementIdentity = SyncDeviceSignatures.decode(current.trust.ownerCertificate).fingerprint
        for ((id, member) in current.ledger().current().members) {
            if (!member.active || id == current.device || id == managementIdentity) continue
            try {
                discovery?.find(id)?.let { peer -> routes += LanSyncGroupReplicaReader.Route(id, requireNotNull(transport).remote(peer)) }
            } catch (error: Exception) { failure("发现设备", error) }
        }
        current.reader.routes("nearby", routes)
        for (route in routes) try {
            discover(current.node, current.reader.discoveryFrom(route.store))
            mutableState.update { it.copy(nearby = LanSyncGroupRouteStatus("已读取近端加密索引；不代表对端发布完成")) }
        } catch (error: Exception) { failure("近端索引", error) }
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
        val request = localRequests.get(); val revision = latestSourceRevision
        val stamp = candidateStamp(current.reader)
        val now = System.nanoTime()
        if (!force && stamp == metadataStamp && revision == handledSourceRevision && request == handledLocalRequest &&
            now - lastSourceScan < 60_000_000_000L) return true
        var localCursor: CommitCursor? = null
        do { localCursor = current.node.discoverLocal(localCursor).nextCursor } while (localCursor != null)
        var inspected = current.node.inspectLocal(recoverNative = !shutdown)
        mutableView.value = inspected
        if (inspected.stage != LanSyncGroupNodeStage.READY) {
            mutableState.update { it.copy(status = when (inspected.stage) {
                LanSyncGroupNodeStage.DEVICE_REVOKED -> "此设备已撤销，保留原件和待传队列"
                LanSyncGroupNodeStage.WAITING_FOR_KEY -> "正在等待新密钥，待传队列已保留"
                else -> "设备组信任证据待检查，资料已保留"
            }) }; return false
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
        for (operation in current.node.sourceOperations()) {
            val before = current.node.receipt(operation, current.localTarget)
            current.node.upload(operation, current.replica, current.localTarget, recoverNative = !shutdown)
            if (before == null) {
                outgoingChanged = true
                mutableState.update { it.copy(local = LanSyncGroupRouteStatus("已保全并发布到本机加密副本", System.currentTimeMillis())) }
            }
        }
        mutableView.value = current.node.inspectLocal(recoverNative = !shutdown)
        val invalid = mutableView.value?.reconciliation?.observations.orEmpty().count {
            it.stage in setOf(SyncInboxStage.INVALID_OBJECT, SyncInboxStage.REJECTED, SyncInboxStage.LOCAL_CUSTODY_BLOCKED)
        }
        if (invalid > 0) mutableState.update { it.copy(error = "已隔离 $invalid 个未通过验证的密文候选，原始证据已保留；正确副本可继续同步。") }
        metadataStamp = stamp; lastSourceScan = now
        handledSourceRevision = revision; handledLocalRequest = request
        refreshDevices()
        val held = mutableView.value?.plan?.units?.values?.count { it.stage !in setOf(LanSyncGroupNativeStage.UNCHANGED, LanSyncGroupNativeStage.READY_OBSERVATION) } ?: 0
        mutableState.update { it.copy(status = if (held > 0) "同步检查完成，$held 项需要审阅或补齐依赖" else "同步检查完成", local =
            if (it.local.lastSuccessAt == null) LanSyncGroupRouteStatus("本机加密副本已检查；历史回执时间未知") else it.local) }
        if (!shutdown && outgoingChanged) cloudWake.trySend(Unit)
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
            cancelCloudWork()
            drainCloudSettings()
            discovery?.close(); transport?.closeAndDrain()
            loop?.cancelAndJoin(); nearbyWorker?.cancelAndJoin(); localWorker?.cancelAndJoin(); cloudWorker?.cancelAndJoin()
            cloudRuntime?.drain()
            admission.withLock { }
            discovery?.close(); transport?.closeAndDrain()
            discovery = null; transport = null; port = null
            loop = null; nearbyWorker = null; localWorker = null; cloudWorker = null
            access?.reader?.routes(emptyList())
            mutableState.update { it.copy(paused = true, busy = false, status = "设备组同步已暂停，原件与待传队列已保留") }
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
