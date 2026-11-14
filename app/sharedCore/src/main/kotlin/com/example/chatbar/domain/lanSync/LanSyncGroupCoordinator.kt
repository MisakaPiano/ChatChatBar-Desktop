package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application owner. Screens project state and lock secrets; they never own daily work. */
class LanSyncGroupCoordinator(private val hasExistingEvidence: () -> Boolean,
    private val enrollment: () -> LanSyncGroupEnrollment, private val joining: () -> LanSyncGroupJoining,
    private val network: LanSyncNetwork = LanSyncNetwork.Local, private val deviceName: String,
    private val beforeSetup: suspend () -> Unit = {}) : LanSyncGroupPanelActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admission = Mutex()
    private val mutableState = MutableStateFlow(LanSyncGroupPanelState())
    val state = mutableState.asStateFlow()
    private val mutableView = MutableStateFlow<LanSyncGroupNodeView?>(null)
    val view = mutableView.asStateFlow()
    @Volatile private var closed = false
    private val lifecycle = Any()
    private var privacyGeneration = 0L
    private var privacyExpiry: Job? = null
    private val pendingSockets = mutableSetOf<javax.net.ssl.SSLSocket>()
    private val joiningNetwork = object : LanSyncNetwork by network {
        override fun connect(identity: LanSyncTlsIdentity, peer: LanSyncPeer): javax.net.ssl.SSLSocket {
            synchronized(lifecycle) {
                check(!closed) { "SYNC_GROUP_COORDINATOR_CLOSED" }
                pendingSockets.removeAll { it.isClosed }
                require(pendingSockets.size < 4) { "SYNC_GROUP_TRANSPORT_CONNECTION_LIMIT" }
            }
            val socket = network.connect(identity, peer)
            synchronized(lifecycle) {
                if (closed) { socket.close(); error("SYNC_GROUP_COORDINATOR_CLOSED") }
                pendingSockets += socket
            }
            return socket
        }
    }
    private var founder: LanSyncGroupEnrollment? = null
    private var member: LanSyncGroupJoining? = null
    @Volatile private var draft: LanSyncGroupEnrollmentDraft? = null
    @Volatile private var transport: LanSyncGroupAppTransport? = null
    @Volatile private var discovery: LanSyncGroupDiscovery? = null
    private var access: LanSyncGroupRuntimeAccess? = null
    private var invitation: LanSyncGroupInvitation? = null
    private var peer: LanSyncGroupMemberPreview? = null
    private var revocation: LanSyncGroupRevocationPreview? = null
    private var loop: Job? = null
    private var port: Int? = null
    private var lastSourceScan = 0L
    private var metadataStamp: String? = null
    private fun founder() = founder ?: enrollment().also { founder = it }
    private fun member() = member ?: joining().also { member = it }
    private fun failure(stage: String, failure: Exception) {
        if (failure is CancellationException) throw failure
        val code = failure.message?.takeIf { it.matches(Regex("(?:SYNC|STORE)_[A-Z0-9_]{1,100}")) } ?: "SYNC_GROUP_OPERATION_FAILED"
        mutableState.update { it.copy(error = "$stage：$code") }
    }
    private fun action(label: String, work: suspend () -> Unit) {
        if (closed) return
        scope.launch {
            admission.withLock {
                if (closed) return@withLock
                mutableState.update { it.copy(busy = true, error = null) }
                try { work() } catch (error: Exception) { failure(label, error) }
                finally { mutableState.update { it.copy(busy = false) } }
            }
        }
    }
    /** Read-only empty startup must not call platform identity/profile factories. */
    fun startIfConfigured() { if (!closed && hasExistingEvidence()) open() }
    fun open() = action("打开设备组") { access?.let { attach(it) } ?: if (hasExistingEvidence()) load() else Unit }
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
            else mutableState.update { it.copy(stage = LanSyncGroupPanelStage.PREPARED_LOCKED, localManagementAvailable = true, status = "设置尚未完成，请用已保存的恢复码继续") }
        } else {
            val joined = member().profile()
            if (joined?.stage == LanSyncGroupJoinStage.READY) attach(requireNotNull(member().runtimeAccess()))
            else if (joined != null) {
                mutableState.update { it.copy(stage = LanSyncGroupPanelStage.JOINING, status = "加入进度已保留，等待设备批准或重试") }
                startLoop()
            } else error("SYNC_GROUP_EXISTING_EVIDENCE_REQUIRES_RECOVERY")
        }
    }
    override fun createGroup() { val generation = synchronized(lifecycle) { privacyGeneration }; action("创建设备组") {
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
    override fun recoverySaved(binding: String) = action("完成创建") {
        val prepared = requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        require(binding == prepared.operation.value) { "SYNC_GROUP_PROFILE_DRAFT_CHANGED" }
        founder().activate(prepared, LanSyncGroupRecoverySavedAdmission { require(it == prepared.profile) })
        lockManagement()
        attach(requireNotNull(founder().runtimeAccess()))
    }
    override fun abandonUnusedSetup() = action("保留并放弃未完成设置") {
        founder().abandonUnactivated(LanSyncGroupDraftAbandonAdmission { })
        lockManagement()
        mutableState.value = LanSyncGroupPanelState(privacyRevision = state.value.privacyRevision)
    }
    override fun resumeManagementOperation() = action("接续已确认的管理操作") {
        founder().resumeAdministration(requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" })
        lockManagement(); refreshDevices(); cycle(force = true)
    }
    override fun unlockManagement(code: String) { val generation = synchronized(lifecycle) { privacyGeneration }; action("解锁管理") {
        val opened = founder().resume(code)
        synchronized(lifecycle) {
            if (closed || generation != privacyGeneration) { opened.close(); error("SYNC_GROUP_MANAGEMENT_LOCKED") }
            draft?.close(); draft = opened
            expirePrivacy(generation)
            val prepared = opened.profile.stage == LanSyncGroupEnrollmentStage.PREPARED
            mutableState.update { it.copy(managementUnlocked = true, recoveryCode = if (prepared) opened.recoveryCode() else null,
                setupBinding = if (prepared) opened.operation.value else null,
                stage = if (prepared) LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE else it.stage) }
            refreshDevices()
        }
    } }
    override fun lockManagement() = synchronized(lifecycle) {
        privacyGeneration++
        privacyExpiry?.cancel(); privacyExpiry = null
        draft?.close(); draft = null
        founder?.lockManagement()
        transport?.closeInvitation(); invitation?.close(); invitation = null; peer = null; revocation = null
        mutableState.update { it.copy(recoveryCode = null, setupBinding = null, managementUnlocked = false,
            invitation = null, peerConfirmation = null, revocation = null, privacyRevision = it.privacyRevision + 1,
            devices = it.devices.map { row -> row.copy(canRevoke = false) },
            stage = if (it.stage == LanSyncGroupPanelStage.NEEDS_RECOVERY_SAVE) LanSyncGroupPanelStage.PREPARED_LOCKED else it.stage) }
    }
    override fun beginJoin(code: String) = action("加入设备组") {
        beforeSetup()
        val owner = member()
        if (owner.profile() == null) owner.prepare(code) { it.validate() }
        else owner.refreshInvitation(code) { it.validate() }
        mutableState.update { it.copy(stage = LanSyncGroupPanelStage.JOINING, status = "邀请已核对，正在验证此设备") }
        startLoop()
        attemptJoin()
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
        requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        cycle(force = true)
        val owner = founder().invitation(requireNotNull(port))
        val live = requireNotNull(transport)
        synchronized(lifecycle) {
        if (closed || generation != privacyGeneration || draft == null) { owner.close(); error("SYNC_GROUP_MANAGEMENT_LOCKED") }
        val offer = live.invitation(owner) { invited -> action("核对加入设备") {
            val preview = founder().prepareMember(invited, owner)
            synchronized(lifecycle) {
                require(!closed && generation == privacyGeneration && invitation === owner && draft != null) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
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
        founder().addMember(requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }, preview.acknowledge(binding))
        lockManagement(); refreshDevices(); cycle(force = true)
    }
    override fun prepareRevocation(device: String) = action("准备撤销设备") {
        requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
        val preview = founder().prepareRevocation(device); revocation = preview
        mutableState.update { it.copy(revocation = LanSyncGroupRevocationConfirmation(preview.request.approvalBinding, device.take(12))) }
    }
    override fun confirmRevocation(binding: String) = action("撤销设备") {
        val preview = requireNotNull(revocation) { "SYNC_GROUP_REVOCATION_CONFIRMATION_REQUIRED" }
        founder().revokeDevice(requireNotNull(draft) { "SYNC_GROUP_MANAGEMENT_LOCKED" }, preview.acknowledge(binding))
        lockManagement(); refreshDevices(); cycle(force = true)
    }
    override fun cancelRevocation() { revocation = null; mutableState.update { it.copy(revocation = null) } }
    override fun retry() = action("重新检查同步") { access?.let { attach(it) } ?: load() }
    override fun openCloudSettings() {
        mutableState.update { it.copy(error = "此候选的网盘配置入口尚在接线；本地与同网同步可独立使用。") }
    }
    private suspend fun attach(opened: LanSyncGroupRuntimeAccess) {
        synchronized(lifecycle) {
        check(!closed) { "SYNC_GROUP_COORDINATOR_CLOSED" }
        if (access == null) {
            opened.routeFailure = { _, error -> failure("近端读取", error) }
            access = opened
            metadataStamp = null
        }
        val current = requireNotNull(access)
        val live = transport ?: LanSyncGroupAppTransport(current, network, ::failure).also { transport = it }
        port = live.start()
        if (network.discoveryEnabled) {
            val hints = discovery ?: LanSyncGroupDiscovery(current, network, ::failure).also { discovery = it }
            hints.start(requireNotNull(port))
        }
        }
        mutableState.update { it.copy(stage = LanSyncGroupPanelStage.READY, localManagementAvailable = requireNotNull(access).localManagementAvailable,
            status = "设备组已就绪，应用正在检查同步") }
        refreshDevices(); cycle(force = true); startLoop()
    }
    private fun refreshDevices() {
        val current = access ?: return
        val ledger = current.ledger().current()
        val manager = SyncDeviceSignatures.decode(current.trust.ownerCertificate).fingerprint
        mutableState.update { state -> state.copy(devices = ledger.members.map { (id, member) ->
            LanSyncGroupDeviceRow(id, if (id == current.device) deviceName else if (id == manager) "组管理身份" else id.take(12),
                if (member.active) "已加入" else "已撤销", id == current.device, id == manager,
                state.managementUnlocked && member.active && id != current.device && id != manager)
        }) }
    }
    private fun startLoop() {
        if (loop?.isActive == true || closed) return
        loop = scope.launch {
            while (isActive) {
                delay(10_000)
                admission.withLock {
                    if (closed) return@withLock
                    try { if (access == null) attemptJoin() else cycle() }
                    catch (error: Exception) { failure("后台同步", error) }
                }
            }
        }
    }
    private suspend fun discover(node: LanSyncGroupNode, store: CiphertextStore) {
        var cursor: CommitCursor? = null
        do { cursor = node.discover(store, cursor).nextCursor } while (cursor != null)
    }
    /** Pull observations are never labelled as peer/cloud outgoing delivery receipts. */
    private suspend fun cycle(force: Boolean = false) {
        val current = access ?: return
        val routes = mutableListOf<LanSyncGroupReplicaReader.Route>()
        for ((id, member) in current.ledger().current().members) {
            if (!member.active || id == current.device) continue
            try {
                discovery?.find(id)?.let { peer -> routes += LanSyncGroupReplicaReader.Route(id, requireNotNull(transport).remote(peer)) }
            } catch (error: Exception) { failure("发现设备", error) }
        }
        current.reader.routes(routes)
        for (route in routes) try {
            discover(current.node, current.reader.discoveryFrom(route.store))
            mutableState.update { it.copy(nearby = LanSyncGroupRouteStatus("已读取近端加密索引；不代表对端发布完成")) }
        } catch (error: Exception) { failure("近端索引", error) }
        var cursor: CommitCursor? = null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        do {
            val page = current.replica.listCommits(cursor)
            page.entries.forEach { digest.update(it.key.toString().toByteArray()); digest.update(it.descriptor.toString().toByteArray()) }
            cursor = page.nextCursor
        } while (cursor != null)
        val stamp = digest.digest().joinToString("") { "%02x".format(it) }
        val now = System.nanoTime()
        if (!force && stamp == metadataStamp && now - lastSourceScan < 60_000_000_000L) return
        discover(current.node, current.replica)
        var inspected = current.node.inspect()
        mutableView.value = inspected
        if (inspected.stage != LanSyncGroupNodeStage.READY) {
            mutableState.update { it.copy(status = when (inspected.stage) {
                LanSyncGroupNodeStage.DEVICE_REVOKED -> "此设备已撤销，保留原件和待传队列"
                LanSyncGroupNodeStage.WAITING_FOR_KEY -> "正在等待新密钥，待传队列已保留"
                else -> "设备组信任证据待检查，资料已保留"
            }) }; return
        }
        inspected.plan?.let { current.node.apply(it) }
        inspected = current.node.inspect()
        val plan = requireNotNull(inspected.plan)
        val safe = linkedSetOf<String>()
        for (unit in plan.units.keys) {
            if (unit in safe || plan.units.getValue(unit).stage != LanSyncGroupNativeStage.LOCAL_PROPOSAL) continue
            val component = plan.components.getValue(unit).filterTo(linkedSetOf()) { plan.units.getValue(it).stage != LanSyncGroupNativeStage.UNCHANGED }
            if (component.any { plan.units.getValue(it).stage != LanSyncGroupNativeStage.LOCAL_PROPOSAL }) continue
            val review = inspected.review(component)
            if (review.requiredBindings.isEmpty() && review.units.values.all { it.missingBodies.isEmpty() && it.records.missing.isEmpty() && it.dependencies.isEmpty() }) safe += component
        }
        if (safe.isNotEmpty()) current.node.commit(inspected.review(safe))
        // Cold operations may span old epochs. upload resumes/rekeys EACH existing operation itself.
        for (operation in current.node.sourceOperations()) {
            val before = current.node.receipt(operation, current.localTarget)
            current.node.upload(operation, current.replica, current.localTarget)
            if (before == null) mutableState.update { it.copy(local = LanSyncGroupRouteStatus("已保全并发布到本机加密副本", System.currentTimeMillis())) }
        }
        mutableView.value = current.node.inspect()
        metadataStamp = stamp; lastSourceScan = now; refreshDevices()
        val held = mutableView.value?.plan?.units?.values?.count { it.stage !in setOf(LanSyncGroupNativeStage.UNCHANGED, LanSyncGroupNativeStage.READY_OBSERVATION) } ?: 0
        mutableState.update { it.copy(status = if (held > 0) "同步检查完成，$held 项需要审阅或补齐依赖" else "同步检查完成", local =
            if (it.local.lastSuccessAt == null) LanSyncGroupRouteStatus("本机加密副本已检查；历史回执时间未知") else it.local) }
    }
    suspend fun closeAndDrain() = withContext(NonCancellable) {
        synchronized(lifecycle) { closed = true; pendingSockets.forEach { runCatching { it.close() } }; pendingSockets.clear() }
        discovery?.close(); transport?.closeAndDrain()
        scope.coroutineContext[Job]?.cancelAndJoin()
        // A final pass also owns resources assigned by an action already entering shutdown.
        discovery?.close(); transport?.closeAndDrain()
        lockManagement()
        founder?.close()
    }
}
