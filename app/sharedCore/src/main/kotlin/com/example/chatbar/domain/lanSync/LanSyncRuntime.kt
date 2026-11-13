package com.example.chatbar.domain.lanSync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

data class LanSyncUiState(val status: String = "两端打开此页面后可同步", val busy: Boolean = false,
    val peers: List<LanSyncPeer> = emptyList(), val addresses: List<String> = emptyList(),
    val offer: LanSyncPairOffer? = null, val conflicts: List<LanSyncConflict> = emptyList(),
    val excluded: Set<String> = emptySet(), val cleanupNotice: String? = null, val failureCode: String? = null,
    val reviewBinding: String? = null, val reviewDetails: List<LanSyncDetailedUnit> = emptyList())

@Serializable private data class LanSyncHello(val command: String, val name: String,
    val token: String? = null, val revision: String? = null, val pendingTransaction: String? = null,
    val locallyApplied: Boolean = false, val hasProjection: Boolean = false, val protocol: Int = LAN_SYNC_PROTOCOL,
    val coveredTypes: Set<String> = LanSyncScope.schemas.keys)

/** Desktop is the listener; Android maintains a short authenticated poll while its page is visible. */
class LanSyncRuntime(private val store: LanSyncStateStore, private val workspace: LanSyncWorkspace,
    private val platform: LanSyncPlatform, private val name: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val observe: (String, String) -> Unit = { _, _ -> },
    private val network: LanSyncNetwork = LanSyncNetwork.Local,
    private val transferProgress: (String, Long) -> Unit = { _, _ -> }) {
    private val identity by lazy { store.identity() }
    private val _state = MutableStateFlow(LanSyncUiState())
    val state = _state.asStateFlow()
    private val operation = Mutex()
    private val offerLock = Any()
    @Volatile private var offer: LanSyncPairOffer? = null
    private val syncRequest = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    private val requested get() = syncRequest.get() != null
    private fun queueSync() { syncRequest.set(Any()) }
    private fun finishRequest(token: Any?, followUp: Boolean = false) {
        if (followUp) queueSync() else syncRequest.compareAndSet(token, null)
    }
    private var listener: SSLServerSocket? = null
    private var listening: Job? = null
    private var polling: Job? = null
    private var pairing: Job? = null
    private var sample: Job? = null
    private var discovery: Job? = null
    private var discoverySocket: java.net.DatagramSocket? = null
    private var choices: CompletableDeferred<Map<String, LanSyncChoice>>? = null
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<SSLSocket>()
    @Volatile private var retryPaused = false

    fun refresh() { _state.value = _state.value.copy(peers = store.peers()) }
    fun requestSync() { retryPaused = false; queueSync(); _state.value = _state.value.copy(status = "已请求同步，等待另一端页面连接", failureCode = null) }
    fun resolveConflicts(values: Map<String, LanSyncChoice>, binding: String? = _state.value.reviewBinding) {
        if (binding != null && binding == _state.value.reviewBinding) choices?.complete(values)
    }
    fun revoke(fingerprint: String) { store.revoke(fingerprint); refresh() }
    fun createPreviewSample() { sample = scope.launch { operation.withLock { safely {
        _state.value = _state.value.copy(busy = true, status = "正在建立离线合成样例")
        platform.exclusive { withContext(NonCancellable) {
            val projection = currentProjection()
            workspace.capture(identity.fingerprint, projection).use { capture ->
                val images = listOf(LanSyncPreviewSample.image(), LanSyncPreviewSample.image(warm = true))
                val hashes = images.map(::lanSyncHash)
                val payloads = hashes.zip(images).toMap()
                val records = LanSyncPreviewSample.records(hashes[0], hashes[1])
                require(capture.snapshot.records.none { current -> records.any { it.key == current.key } }) { "SYNC_SAMPLE_ALREADY_PRESENT" }
                val scratch = LanSyncScratch(store.root, "sample")
                try {
                    val sources = payloads.mapValues { (hash, bytes) ->
                        scratch.directory.resolve(hash).also { source ->
                            java.nio.file.Files.write(source, bytes, java.nio.file.StandardOpenOption.CREATE_NEW); scratch.own(source)
                        }
                    }
                    val snapshot = capture.snapshot.copy(records = capture.snapshot.records + records,
                        resources = capture.snapshot.resources + payloads.mapValues { it.value.size.toLong() })
                    workspace.apply(LanSyncPlan(UUID.randomUUID().toString(), snapshot, emptyMap()), capture.snapshot.hashes(), identity.fingerprint, capture.blobs + sources, projection)
                    platform.reload(records.filter { it.type == "chat_sessions" }.map { it.id }.toSet())
                } finally { scratch.close() }
            }
        } }
        _state.value = _state.value.copy(status = "已建立“局域网离线样例 2”：会话版本、朋友圈图片版本和暂停计划，无需密钥或生成调用")
    } } } }
    fun cancelPairing() { synchronized(offerLock) { offer = null }; _state.value = _state.value.copy(offer = null) }

    fun startHost() {
        if (listening?.isActive == true) return
        listening = scope.launch { safely {
            val addresses = network.addresses(); require(addresses.isNotEmpty()) { "SYNC_NO_LOCAL_NETWORK" }
            val context = identity.context { peer -> store.peers().any { it.fingerprint == peer } || offer?.expiresAt?.let { it > System.currentTimeMillis() } == true }
            val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket
            server.reuseAddress = false; server.needClientAuth = true
            server.bind(InetSocketAddress(0)); listener = server
            network.listening(identity.fingerprint, server.localPort)
            if (network.discoveryEnabled) discovery = scope.launch {
                try {
                    LanSyncDiscovery.listen(identity.fingerprint, server.localPort).use { udp ->
                        discoverySocket = udp
                        while (isActive) LanSyncDiscovery.respond(udp, identity.fingerprint, server.localPort)
                    }
                } catch (failure: Exception) {
                    if (isActive) _state.value = _state.value.copy(status = "电脑连接已就绪；地址自动发现暂不可用，请检查专用网络的 UDP 39237 权限")
                }
            }
            _state.value = _state.value.copy(status = "电脑已就绪，安卓可扫码或连接已配对设备", addresses = addresses, peers = store.peers())
            while (isActive) {
                val socket = server.accept() as SSLSocket
                // One bounded handshake/operation at a time; unknown clients get no data protocol access.
                sockets += socket
                var trustedData = false
                try { socket.use { handleHost(it) { trustedData = true } } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { showFailure(failure, pauseRetry = trustedData) }
                finally { sockets -= socket }
            }
        } }
    }

    fun pairOffer(host: String) {
        val server = requireNotNull(listener) { "SYNC_LISTENER_NOT_READY" }
        require(host in _state.value.addresses) { "SYNC_LOCAL_ADDRESS" }
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val value = LanSyncPairOffer(host = host, port = server.localPort, fingerprint = identity.fingerprint,
            token = token, expiresAt = System.currentTimeMillis() + 120_000)
        synchronized(offerLock) { offer = value }
        _state.value = _state.value.copy(offer = value, status = "配对码两分钟内有效；请仅在自己的手机上确认")
    }

    fun pair(code: String) { pairing = scope.launch { operation.withLock { safely {
        val value = LanSyncPairOffer.parse(code)
        _state.value = _state.value.copy(busy = true, status = "正在验证电脑身份")
        network.connect(identity, LanSyncPeer(value.fingerprint, "电脑", value.host, value.port)).use { socket ->
            sockets += socket
            try { LanSyncWire(socket).use { wire ->
                wire.sendText(lanSyncJson.encodeToString(LanSyncHello("PAIR", name, value.token)))
                val result = wire.text(8192)
                require(result != "SYNC_PROTOCOL") { "SYNC_PROTOCOL" }
                require(result != "SYNC_SCOPE_MISMATCH") { "SYNC_SCOPE_MISMATCH" }
                require(result == "PAIRED") { "SYNC_PAIR_REJECTED" }
                store.trust(LanSyncPeer(value.fingerprint, "电脑", value.host, value.port))
                wire.sendText("REMEMBERED")
                _state.value = _state.value.copy(status = "已记住可信电脑", peers = store.peers())
            } } finally { sockets -= socket }
        }
    } } } }

    fun updateAddress(peer: LanSyncPeer, host: String, port: Int) {
        LanSyncTls.parseAddress(host); require(port in 1024..65535)
        require(store.peers().any { it.fingerprint == peer.fingerprint })
        store.trust(peer.copy(host = host, port = port)); refresh()
    }

    fun startPhone() {
        if (polling?.isActive == true) return
        refresh()
        polling = scope.launch {
            while (isActive) {
                if (!operation.isLocked && !retryPaused) store.peers().forEach { peer ->
                    operation.withLock { safely { phoneAttempt(peer) } }
                }
                delay(2500)
            }
        }
    }

    private suspend fun handleHost(socket: SSLSocket, trustedData: () -> Unit) {
        val requestToken = syncRequest.get()
        require(network.accepts(socket.inetAddress)) { "SYNC_LOCAL_ADDRESS" }
        LanSyncTls.configure(socket); socket.startHandshake()
        val peerId = LanSyncTls.peerFingerprint(socket)
        LanSyncWire(socket).use { wire ->
            val helloText = wire.text(8192).also { require(it.length <= 4096) }
            val helloBody = lanSyncJson.parseToJsonElement(helloText).jsonObject
            val protocol = helloBody["protocol"] as? JsonPrimitive
            if (protocol == null || protocol.isString || protocol.intOrNull != LAN_SYNC_PROTOCOL) {
                wire.sendText("SYNC_PROTOCOL"); error("SYNC_PROTOCOL")
            }
            if ((helloBody["coveredTypes"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() != LanSyncScope.schemas.keys) {
                wire.sendText("SYNC_SCOPE_MISMATCH"); error("SYNC_SCOPE_MISMATCH")
            }
            val hello = lanSyncJson.decodeFromString<LanSyncHello>(helloText)
            if (hello.command == "PAIR") {
                synchronized(offerLock) {
                    val current = requireNotNull(offer) { "SYNC_PAIR_CLOSED" }
                    current.validate()
                    require(hello.token != null && java.security.MessageDigest.isEqual(current.token.toByteArray(), hello.token.toByteArray())) { "SYNC_PAIR_REJECTED" }
                    offer = null
                    store.trust(LanSyncPeer(peerId, hello.name))
                }
                wire.sendText("PAIRED"); require(wire.text(8192) == "REMEMBERED")
                _state.value = _state.value.copy(offer = null, peers = store.peers(), status = "已配对 ${hello.name.take(80)}")
                return
            }
            require(store.peers().any { it.fingerprint == peerId }) { "SYNC_UNTRUSTED_DEVICE" }
            require(hello.command == "POLL" || hello.command == "SYNC") { "SYNC_COMMAND" }
            trustedData()
            if (retryPaused) { wire.sendText("PAUSED"); return }
            var pending = store.pending(peerId)
            if (hello.command == "POLL" && !requested && pending == null) { wire.sendText("IDLE"); return }
            operation.withLock {
                val baseline = store.baseline(peerId, identity.fingerprint)
                if (pending?.plan?.transaction in baseline.receipts) {
                    val finished = requireNotNull(pending)
                    require(workspace.applied(finished.plan.transaction)) { "SYNC_RECEIPT_WITHOUT_APPLY" }
                    complete(peerId, finished.plan)
                    store.clearPending(peerId, finished.plan.transaction)
                    pending = null
                }
                if (hello.pendingTransaction != null && hello.pendingTransaction in baseline.receipts) {
                    require(workspace.applied(hello.pendingTransaction)) { "SYNC_RECEIPT_WITHOUT_APPLY" }
                    wire.sendText("FINISHED:${hello.pendingTransaction}"); return@withLock
                }
                require(pending != null || hello.revision == baseline.revision) { "SYNC_BASELINE_MISMATCH" }
                wire.sendText("SYNC")
                _state.value = _state.value.copy(busy = true, status = "正在接收并检查手机快照", failureCode = null)
                val phone = LanSyncTransfer.receive(wire, store.root, peerId, workspace.resourceStore, transferProgress)
                try {
                val computer = capture()
                try {
                val previous = pending
                try { LanSyncCompare.requireCoverage(computer.snapshot, phone.snapshot, baseline) }
                catch (failure: IllegalArgumentException) { wire.sendText("SYNC_SCOPE_MISMATCH"); throw failure }
                var phoneSnapshot = phone.snapshot
                val projection = if (baseline.revision == null && previous == null && !hello.hasProjection)
                    LanSyncPresetProjection.propose(computer.snapshot.records, phoneSnapshot.records) else LanSyncProjection()
                val offer = if (projection.bindings.isEmpty()) LanSyncProjectionOffer() else
                    LanSyncProjectionOffer(projection, phoneSnapshot.hashes(), LanSyncPresetProjection.presets(computer.snapshot.records))
                LanSyncFramedJson.send(wire, store.root, LanSyncProjectionOffer.serializer(), offer)
                if (projection.bindings.isNotEmpty()) phoneSnapshot = phoneSnapshot.copy(records = projection.rewrite(phoneSnapshot.records))
                phoneSnapshot.validate(); LanSyncScope.validateGraph(phoneSnapshot)
                require(LanSyncFramedJson.receive(wire, store.root, MapSerializer(String.serializer(), String.serializer())) == phoneSnapshot.hashes()) { "SYNC_LOCAL_CHANGED_REVIEW_AGAIN" }
                val computerApplied = previous?.let { workspace.applied(it.plan.transaction) } == true
                val phoneApplied = previous != null && hello.pendingTransaction == previous.plan.transaction && hello.locallyApplied
                val protocolChanged = previous != null && previous.plan.snapshot.protocol != LAN_SYNC_PROTOCOL
                val coverageChanged = previous != null && previous.plan.snapshot.coveredTypes != computer.snapshot.coveredTypes
                require(previous == null || computer.snapshot.coveredTypes.containsAll(previous.plan.snapshot.coveredTypes)) { "SYNC_SCOPE_MISMATCH" }
                if (protocolChanged) try { LanSyncScope.validateLegacyPending(requireNotNull(previous).plan.snapshot) }
                catch (failure: IllegalArgumentException) {
                    finishRequest(requestToken); wire.sendText("SYNC_LEGACY_PLAN_UNSUPPORTED"); throw failure
                }
                if ((protocolChanged || coverageChanged) && computerApplied && phoneApplied) {
                    val finished = requireNotNull(previous)
                    // Both endpoints have already published the old payload. Finish its receipts
                    // without replaying it over later edits, then run a fresh current-protocol round.
                    wire.sendText("APPLIED_FINISH:${finished.plan.transaction}")
                    require(wire.text(8192) == "APPLIED_COMMITTED:${finished.plan.transaction}") { "SYNC_COMMIT_RECEIPT" }
                    complete(peerId, finished.plan); store.clearPending(peerId, finished.plan.transaction)
                    queueSync(); wire.sendText("DONE")
                    _state.value = _state.value.copy(busy = false, status = "旧同步回执已确认，正在核对后续修改")
                    return@withLock
                }
                val needsRebase = previous != null && (protocolChanged || coverageChanged || when {
                    computerApplied && phoneApplied -> false
                    computerApplied -> phoneSnapshot.hashes() != previous.expectedRemote
                    phoneApplied -> computer.snapshot.hashes() != previous.expectedLocal
                    else -> computer.snapshot.hashes() != previous.expectedLocal || phoneSnapshot.hashes() != previous.expectedRemote
                })
                val prepared = if (previous == null || needsRebase) {
                    val recoveryHashes = previous?.takeIf { computerApplied || phoneApplied }?.let {
                        LanSyncRebase.ancestorHashes(it, baseline, computer.snapshot, phoneSnapshot, computerApplied)
                    }
                    val ancestor = if (previous != null && recoveryHashes != null)
                        LanSyncBaseline(peerId, "recovery:${previous.plan.transaction}",
                            recoveryHashes, ownerId = identity.fingerprint, coveredTypes = baseline.coveredTypes,
                            componentRecords = LanSyncRebase.ancestorComponents(previous, baseline, recoveryHashes, computerApplied,
                                if (computerApplied) phoneSnapshot else computer.snapshot))
                        else baseline
                    if (previous != null) _state.value = _state.value.copy(status = "保留上次已应用结果，重新核对两端后续修改")
                    val comparison = LanSyncCompare.compare(computer.snapshot, phoneSnapshot, ancestor)
                    val decisions = try { decide(comparison, ancestor, computer, wire) } catch (failure: IllegalArgumentException) {
                        if (failure.message == "SYNC_CONFLICT_TIMEOUT") { finishRequest(requestToken); wire.sendText("SYNC_CONFLICT_TIMEOUT") }
                        throw failure
                    }
                    val planned = LanSyncPlanner.plan(computer.snapshot, phoneSnapshot, ancestor, decisions, UUID.randomUUID().toString())
                    val plan = planned.copy(parents = previous?.plan?.parents.orEmpty() + planned.parents, supersedes = previous?.plan?.transaction)
                    prepareLocalPending(peerId, plan, computer.snapshot.hashes(), phoneSnapshot.hashes(),
                        previous, computer.snapshot, computerApplied, phoneSnapshot).also {
                        if (previous == null) store.pending(it) else store.replacePending(it)
                    }
                } else previous
                requireNotNull(prepared)
                // The frozen complete output, not a new import interpretation, is retried after disconnect.
                val output = prepared.plan.snapshot.copy(deviceId = identity.fingerprint)
                val available = computer.blobs + phone.blobs + retainedBlobs(prepared.plan)
                retainBlobs(prepared.plan, available)
                LanSyncFramedJson.send(wire, store.root, LanSyncPlan.serializer(), prepared.plan)
                LanSyncFramedJson.send(wire, store.root, MapSerializer(String.serializer(), String.serializer()), prepared.expectedRemote)
                LanSyncTransfer.send(wire, store.root, LanSyncCapture(output, available, emptyMap()), transferProgress)
                awaitStage(wire, "PROTECTED:${prepared.plan.transaction}", "WAIT_PROTECTION", "SYNC_PHONE_PROTECTION")
                withStageHeartbeat(wire, "WAIT_PROTECTION") { protectPrepared(prepared) }
                observe("HOST_PROTECTED", prepared.plan.transaction)
                wire.sendText("PUBLISH:${prepared.plan.transaction}")
                awaitStage(wire, "APPLIED:${prepared.plan.transaction}", "WAIT_APPLY", "SYNC_PHONE_RECEIPT")
                withStageHeartbeat(wire, "WAIT_APPLY") { applyPrepared(prepared, available) }
                observe("HOST_APPLIED", prepared.plan.transaction)
                wire.sendText("COMMIT:${prepared.plan.transaction}")
                require(wire.text(8192) == "COMMITTED:${prepared.plan.transaction}") { "SYNC_COMMIT_RECEIPT" }
                complete(peerId, prepared.plan)
                observe("HOST_BASELINE", prepared.plan.transaction)
                store.clearPending(peerId, prepared.plan.transaction)
                observe("HOST_PENDING_CLEARED", prepared.plan.transaction)
                finishRequest(requestToken, prepared.deferredDeletion != null || computerApplied && computer.snapshot.hashes() != prepared.plan.snapshot.hashes() ||
                    phoneApplied && phoneSnapshot.hashes() != prepared.plan.snapshot.hashes())
                _state.value = _state.value.copy(busy = false, conflicts = emptyList(), status = "同步完成，两端已确认", excluded = output.excludedTypes, failureCode = null,
                    reviewBinding = null, reviewDetails = emptyList())
                wire.sendText("DONE")
                } finally { computer.close() }
                } finally { phone.close() }
            }
        }
    }

    private suspend fun phoneAttempt(peer: LanSyncPeer) {
        val requestToken = syncRequest.get()
        val baseline = store.baseline(peer.fingerprint, identity.fingerprint)
        val socket = try { network.connect(identity, peer) }
        catch (failure: java.io.IOException) {
            val address = network.discover(peer.fingerprint) ?: throw failure
            network.connect(identity, peer.copy(host = address.first, port = address.second)).also {
                store.trust(peer.copy(host = address.first, port = address.second))
            }
        }
        socket.use { socket ->
            sockets += socket
            try { LanSyncWire(socket).use { wire ->
                val pending = store.pending(peer.fingerprint)
                wire.sendText(lanSyncJson.encodeToString(LanSyncHello(if (requested || pending != null) "SYNC" else "POLL", name,
                    revision = baseline.revision, pendingTransaction = pending?.plan?.transaction,
                    locallyApplied = pending?.let { workspace.applied(it.plan.transaction) } == true,
                    hasProjection = store.projection(peer.fingerprint, identity.fingerprint).bindings.isNotEmpty())))
                val response = wire.text(8192)
                require(response != "SYNC_PROTOCOL") { "SYNC_PROTOCOL" }
                require(response != "SYNC_SCOPE_MISMATCH") { "SYNC_SCOPE_MISMATCH" }
                if (response == "PAUSED") {
                    // Keep the lightweight authenticated poll alive so a PC-side explicit retry
                    // resumes both endpoints; do not resend snapshots while its owner is paused.
                    _state.value = _state.value.copy(busy = false, failureCode = "SYNC_REMOTE_PAUSED",
                        status = "电脑端同步已暂停，请在电脑核对提示并点同步重试；现有资料与恢复记录保留")
                    return
                }
                if (response == "IDLE") return
                if (response.startsWith("FINISHED:") && pending != null && response == "FINISHED:${pending.plan.transaction}") {
                    require(workspace.applied(pending.plan.transaction)) { "SYNC_RECEIPT_WITHOUT_APPLY" }
                    complete(peer.fingerprint, pending.plan)
                    store.clearPending(peer.fingerprint, pending.plan.transaction)
                    queueSync() // Finish the old receipt, then exchange any edits made since that apply.
                    _state.value = _state.value.copy(status = "上次同步已核对完成，两端回执一致")
                    return
                }
                require(response == "SYNC") { "SYNC_COMMAND" }
                _state.value = _state.value.copy(busy = true, status = "正在准备一致快照，冲突将在电脑端显示", failureCode = null)
                val phone = capture()
                try {
                LanSyncTransfer.send(wire, store.root, phone, transferProgress)
                val offerText = wire.text(8192)
                require(offerText != "SYNC_SCOPE_MISMATCH") { "SYNC_SCOPE_MISMATCH" }
                val offer = LanSyncFramedJson.receive(wire, store.root, LanSyncProjectionOffer.serializer(), offerText)
                offer.projection.validate()
                var phoneSnapshot = phone.snapshot
                if (offer.projection.bindings.isNotEmpty()) {
                    require(baseline.revision == null && pending == null && currentProjection().bindings.isEmpty()) { "SYNC_PROJECTION_ALREADY_ASSIGNED" }
                    require(offer.sourceHashes == phoneSnapshot.hashes() &&
                        LanSyncPresetProjection.propose(offer.computerPresets, phoneSnapshot.records) == offer.projection) { "SYNC_PROJECTION_PROOF" }
                    platform.exclusive {
                        workspace.capture(identity.fingerprint).use { latest ->
                            require(latest.snapshot.hashes() == offer.sourceHashes) { "SYNC_LOCAL_CHANGED_REVIEW_AGAIN" }
                            offer.projection.rewrite(latest.snapshot.records)
                            store.admitProjection(peer.fingerprint, identity.fingerprint, offer.projection)
                        }
                    }
                    observe("PHONE_PROJECTION", offer.projection.digest())
                    phoneSnapshot = phoneSnapshot.copy(records = offer.projection.rewrite(phoneSnapshot.records))
                } else require(offer.sourceHashes.isEmpty() && offer.computerPresets.isEmpty()) { "SYNC_PROJECTION_PROOF" }
                LanSyncFramedJson.send(wire, store.root, MapSerializer(String.serializer(), String.serializer()), phoneSnapshot.hashes())
                val decisionDeadline = System.nanoTime() + 10 * 60_000_000_000L
                var planText = wire.text(8192)
                if (planText == "REVIEW") {
                    reviewOnPhone(wire, phoneSnapshot)
                    planText = wire.text(8192)
                }
                while (planText == "WAIT_DECISION") {
                    require(System.nanoTime() <= decisionDeadline) { "SYNC_CONFLICT_TIMEOUT" }
                    _state.value = _state.value.copy(status = "等待在电脑选择冲突内容，连接保持中")
                    planText = wire.text(8192)
                }
                require(planText != "SYNC_CONFLICT_TIMEOUT") { "SYNC_CONFLICT_TIMEOUT" }
                require(planText != "SYNC_LEGACY_PLAN_UNSUPPORTED") { "SYNC_LEGACY_PLAN_UNSUPPORTED" }
                if (planText.startsWith("APPLIED_FINISH:")) {
                    val finished = requireNotNull(pending) { "SYNC_RECEIPT_WITHOUT_APPLY" }
                    require(planText == "APPLIED_FINISH:${finished.plan.transaction}" && workspace.applied(finished.plan.transaction)) { "SYNC_RECEIPT_WITHOUT_APPLY" }
                    if (finished.plan.snapshot.protocol != LAN_SYNC_PROTOCOL) LanSyncScope.validateLegacyPending(finished.plan.snapshot)
                    else { finished.plan.snapshot.validate(); LanSyncScope.validateGraph(finished.plan.snapshot) }
                    complete(peer.fingerprint, finished.plan)
                    wire.sendText("APPLIED_COMMITTED:${finished.plan.transaction}")
                    require(wire.text(8192) == "DONE") { "SYNC_FINAL_RECEIPT_PENDING" }
                    store.clearPending(peer.fingerprint, finished.plan.transaction)
                    queueSync()
                    _state.value = _state.value.copy(busy = false, status = "旧同步回执已确认，正在核对后续修改")
                    return
                }
                val plan = LanSyncFramedJson.receive(wire, store.root, LanSyncPlan.serializer(), planText)
                val expected = LanSyncFramedJson.receive(wire, store.root, MapSerializer(String.serializer(), String.serializer()))
                val received = LanSyncTransfer.receive(wire, store.root, peer.fingerprint, workspace.resourceStore, transferProgress)
                try {
                require(received.snapshot.hashes() == plan.snapshot.hashes()) { "SYNC_PLAN_MISMATCH" }
                val prior = store.pending(peer.fingerprint)
                require(prior == null || prior.plan.transaction == plan.transaction || plan.supersedes == prior.plan.transaction) { "SYNC_PENDING_OTHER_TRANSACTION" }
                val prepared = if (prior?.plan?.transaction == plan.transaction) prior else
                    prepareLocalPending(peer.fingerprint, plan, expected, emptyMap(), prior, phoneSnapshot,
                        prior?.let { workspace.applied(it.plan.transaction) } == true).also {
                        if (prior == null) store.pending(it) else store.replacePending(it)
                    }
                val alreadyApplied = workspace.applied(plan.transaction)
                retainBlobs(plan, received.blobs)
                withStageHeartbeat(wire, "WAIT_PROTECTION") { protectPrepared(prepared) }
                observe("PHONE_PROTECTED", plan.transaction)
                wire.sendText("PROTECTED:${plan.transaction}")
                awaitStage(wire, "PUBLISH:${plan.transaction}", "WAIT_PROTECTION", "SYNC_HOST_PROTECTION")
                withStageHeartbeat(wire, "WAIT_APPLY") { applyPrepared(prepared, received.blobs) }
                observe("PHONE_APPLIED", plan.transaction)
                wire.sendText("APPLIED:${plan.transaction}")
                awaitStage(wire, "COMMIT:${plan.transaction}", "WAIT_APPLY", "SYNC_COMMIT_RECEIPT")
                complete(peer.fingerprint, plan)
                observe("PHONE_BASELINE", plan.transaction)
                wire.sendText("COMMITTED:${plan.transaction}")
                require(wire.text(8192) == "DONE") { "SYNC_FINAL_RECEIPT_PENDING" }
                store.clearPending(peer.fingerprint, plan.transaction)
                observe("PHONE_PENDING_CLEARED", plan.transaction)
                finishRequest(requestToken, prepared.deferredDeletion != null || alreadyApplied && phoneSnapshot.hashes() != plan.snapshot.hashes())
                _state.value = _state.value.copy(busy = false, status = "同步完成，两端已确认", excluded = plan.snapshot.excludedTypes, failureCode = null,
                    reviewBinding = null, reviewDetails = emptyList())
                } finally { received.close() }
                } finally { phone.close() }
            } } finally { sockets -= socket }
        }
    }

    private suspend fun capture(): LanSyncCapture {
        var value: LanSyncCapture? = null
        platform.exclusive { workspace.recover(); value = workspace.capture(identity.fingerprint, currentProjection()) }
        return requireNotNull(value)
    }
    private fun validateChoices(description: LanSyncReviewDescription, values: Map<String, LanSyncChoice>) {
        require(values.keys == description.conflicts.map { it.unit }.toSet()) { "SYNC_CHOICES" }
        require(description.conflicts.all { it.canKeepBoth || values[it.unit] != LanSyncChoice.KEEP_BOTH }) { "SYNC_SINGLETON_KEEP_BOTH" }
    }
    private suspend fun decide(comparison: LanSyncComparison, baseline: LanSyncBaseline,
        computer: LanSyncCapture, wire: LanSyncWire): Map<String, LanSyncChoice> {
        if (comparison.conflicts.isEmpty()) return emptyMap()
        val description = LanSyncDetailedReview.description(comparison, baseline)
        wire.sendText("REVIEW")
        LanSyncTransfer.send(wire, store.root, computer, transferProgress)
        LanSyncFramedJson.send(wire, store.root, LanSyncReviewContext.serializer(),
            LanSyncReviewContext(baseline.copy(revision = null, receipts = emptyList(), localProjectionHash = null), description))
        val answer = CompletableDeferred<Map<String, LanSyncChoice>>()
        choices = answer
        _state.value = _state.value.copy(status = "两端均可查看冻结内容并选择；先收到的完整选择生效", conflicts = comparison.conflicts,
            reviewBinding = description.binding, reviewDetails = LanSyncDetailedReview.details(description, comparison))
        val deadline = System.nanoTime() + 10 * 60_000_000_000L
        try {
            while (true) {
                require(System.nanoTime() <= deadline) { "SYNC_CONFLICT_TIMEOUT" }
                // One poll/response is always drained before selecting a winner. Concurrent
                // UI submissions cannot leave an unread late vote inside the next stage.
                wire.sendText("REVIEW_POLL")
                val voteText = wire.text(8192)
                val vote = if (voteText == "REVIEW_WAIT") LanSyncReviewVote(description.binding) else
                    LanSyncFramedJson.receive(wire, store.root, LanSyncReviewVote.serializer(), voteText)
                require(vote.binding == description.binding) { "SYNC_REVIEW_BINDING" }
                if (vote.choices.isNotEmpty()) { validateChoices(description, vote.choices); answer.complete(vote.choices) }
                if (answer.isCompleted) {
                    val selected = answer.await(); validateChoices(description, selected)
                    wire.sendText("REVIEW_SELECTED")
                    LanSyncFramedJson.send(wire, store.root, LanSyncReviewVote.serializer(), LanSyncReviewVote(description.binding, selected))
                    return selected
                }
                delay(500)
            }
        } finally { choices = null }
    }
    private suspend fun reviewOnPhone(wire: LanSyncWire, phone: LanSyncSnapshot) {
        val preview = LanSyncTransfer.receive(wire, store.root, requireNotNull(store.peers().singleOrNull()).fingerprint,
            workspace.resourceStore, transferProgress)
        try {
            val context = LanSyncFramedJson.receive(wire, store.root, LanSyncReviewContext.serializer())
            require(context.ancestor.ownerId == preview.snapshot.deviceId && context.ancestor.peerId == identity.fingerprint &&
                context.ancestor.revision == null && context.ancestor.localProjectionHash == null && context.ancestor.receipts.isEmpty()) { "SYNC_REVIEW_DEVICE" }
            val comparison = LanSyncCompare.compare(preview.snapshot, phone, context.ancestor)
            require(context.description == LanSyncDetailedReview.description(comparison, context.ancestor)) { "SYNC_REVIEW_BINDING" }
            val answer = CompletableDeferred<Map<String, LanSyncChoice>>()
            choices = answer
            _state.value = _state.value.copy(status = "可在本机或电脑查看内容并选择；比较起点仅保留指纹", conflicts = context.description.conflicts,
                reviewBinding = context.description.binding, reviewDetails = LanSyncDetailedReview.details(context.description, comparison))
            try {
                while (true) when (wire.text(8192)) {
                    "REVIEW_POLL" -> {
                        val selected = if (answer.isCompleted) answer.await().also { validateChoices(context.description, it) } else emptyMap()
                        if (selected.isEmpty()) wire.sendText("REVIEW_WAIT") else
                            LanSyncFramedJson.send(wire, store.root, LanSyncReviewVote.serializer(), LanSyncReviewVote(context.description.binding, selected))
                    }
                    "REVIEW_SELECTED" -> {
                        val selected = LanSyncFramedJson.receive(wire, store.root, LanSyncReviewVote.serializer())
                        require(selected.binding == context.description.binding) { "SYNC_REVIEW_BINDING" }
                        validateChoices(context.description, selected.choices)
                        _state.value = _state.value.copy(conflicts = emptyList(), status = "两端已核对选择，正在准备保全与写入")
                        return
                    }
                    "SYNC_CONFLICT_TIMEOUT" -> error("SYNC_CONFLICT_TIMEOUT")
                    else -> error("SYNC_REVIEW_STAGE")
                }
            } finally { choices = null }
        } finally { preview.close() }
    }
    private fun prepareLocalPending(peer: String, plan: LanSyncPlan, expectedLocal: Map<String, String>,
        expectedRemote: Map<String, String>, previous: LanSyncPending?, current: LanSyncSnapshot,
        previousApplied: Boolean, remote: LanSyncSnapshot? = null): LanSyncPending {
        val deferred = LanSyncRebase.deferred(previous, current, plan, previousApplied)
        // Validate the complete local graph before publishing a superseding pending envelope.
        LanSyncRebase.localPlan(plan, deferred, previous?.plan)
        return LanSyncPending(peer, plan, expectedLocal, expectedRemote, deferredDeletion = deferred,
            expectedLocalComponents = LanSyncMoments.components(current).filterKeys { it in expectedLocal },
            expectedRemoteComponents = remote?.let(LanSyncMoments::components).orEmpty().filterKeys { it in expectedRemote })
    }
    private fun localPlan(pending: LanSyncPending): LanSyncPlan {
        val old = pending.deferredDeletion?.let { proof ->
            require(workspace.applied(proof.previousTransaction)) { "SYNC_DEFERRED_DELETION_PROOF" }
            store.superseded(pending.peerId, proof.previousTransaction)?.plan
        }
        return LanSyncRebase.localPlan(pending.plan, pending.deferredDeletion, old)
    }
    private suspend fun protectPrepared(pending: LanSyncPending) {
        platform.exclusive {
            workspace.recover()
            if (!workspace.applied(pending.plan.transaction)) {
                workspace.protectBeforeApply(localPlan(pending), pending.expectedLocal,
                    identity.fingerprint, store.projection(pending.peerId, identity.fingerprint))
            }
        }
    }
    private suspend fun withStageHeartbeat(wire: LanSyncWire, marker: String, work: suspend () -> Unit) = coroutineScope {
        val heartbeat = launch { while (isActive) { wire.sendText(marker); delay(5_000) } }
        try { work() } finally { withContext(NonCancellable) { heartbeat.cancelAndJoin() } }
    }
    private fun awaitStage(wire: LanSyncWire, expected: String, waiting: String, failure: String) {
        var response = wire.text(8192)
        while (response == waiting) response = wire.text(8192)
        require(response == expected) { failure }
    }
    private suspend fun applyPrepared(pending: LanSyncPending, blobs: Map<String, java.nio.file.Path>) {
        platform.exclusive {
            withContext(NonCancellable) {
                workspace.recover()
                if (!workspace.applied(pending.plan.transaction)) {
                    workspace.apply(localPlan(pending),
                        pending.expectedLocal, identity.fingerprint, blobs, store.projection(pending.peerId, identity.fingerprint))
                }
                val sessions = pending.plan.snapshot.records.filter { it.type == "chat_sessions" }.map { it.id }.toSet()
                platform.reload(sessions)
            }
        }
        store.pending(pending.copy(localApplied = true))
    }
    private fun currentProjection(): LanSyncProjection = store.peers().singleOrNull()?.let {
        store.projection(it.fingerprint, identity.fingerprint)
    } ?: LanSyncProjection()
    private fun retainBlobs(plan: LanSyncPlan, blobs: Map<String, java.nio.file.Path>) {
        plan.snapshot.resources.forEach { (hash, size) ->
            workspace.resourceStore.retain(requireNotNull(blobs[hash]) { "SYNC_RESOURCE_MISSING" }, hash, size)
        }
    }
    private fun retainedBlobs(plan: LanSyncPlan): Map<String, java.nio.file.Path> = buildMap {
        plan.snapshot.resources.forEach { (hash, size) ->
            val cached = workspace.resourceStore.completed(hash, size)
            if (cached != null) put(hash, cached) else {
                // A pre-v6 pending envelope still owns this exact legacy transfer file.
                val legacy = store.root.resolve("transfer-${plan.transaction}/$hash")
                if (Files.exists(legacy, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    put(hash, workspace.resourceStore.retain(legacy, hash, size))
            }
        }
    }
    private fun complete(peer: String, plan: LanSyncPlan) {
        val previous = store.baseline(peer, identity.fingerprint).revision
        store.complete(peer, identity.fingerprint, plan)
        try {
        if (previous != null && previous != plan.transaction) workspace.retire(previous)
        val retiredPlans = mutableListOf(plan)
        val seen = hashSetOf(plan.transaction)
        var ancestor = plan.supersedes
        while (ancestor != null) {
            require(seen.size < 256 && seen.add(ancestor)) { "SYNC_SUPERSEDED_CHAIN" }
            // An endpoint may never have received an older host plan; only its own durable
            // superseded evidence grants authority to reclaim that older local transfer.
            val older = store.superseded(peer, ancestor)?.plan ?: break
            retiredPlans += older
            ancestor = older.supersedes
        }
        retiredPlans.forEach { retired ->
        if (retired.transaction != plan.transaction) workspace.retire(retired.transaction)
        val directory = store.root.resolve("transfer-${retired.transaction}")
        if (Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            lanSyncSafe(directory, true)
            val actual = Files.newDirectoryStream(directory).use { it.toList() }
            require(actual.map { it.fileName.toString() }.toSet() == retired.snapshot.resources.keys) { "SYNC_RETAINED_UNKNOWN_CHILD" }
            actual.forEach { require(LanSyncWorkspace.hashFile(it, retired.snapshot.resources.getValue(it.fileName.toString())) == it.fileName.toString()) }
            actual.forEach(Files::delete); Files.delete(directory)
        }
        }
        _state.value = _state.value.copy(cleanupNotice = null)
        } catch (_: Exception) {
            _state.value = _state.value.copy(cleanupNotice = "同步结果与回执已保存；部分旧临时资料未能安全回收，已保留供检查。")
        }
    }
    private suspend fun safely(work: suspend () -> Unit) {
        try { work() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { showFailure(failure) }
        finally { _state.value = _state.value.copy(busy = false) }
    }
    private fun showFailure(failure: Exception, pauseRetry: Boolean = true) {
        if (pauseRetry && failure !is java.io.IOException) retryPaused = true
        val code = failure.message?.takeIf { it.matches(Regex("SYNC_[A-Z0-9_]+")) }
        val text = when (code) {
            "SYNC_INVALID_UTF8" -> "文字资料含损坏编码，已停止继续写入并保留现有文件；请检查资料后重试"
            "SYNC_LOCAL_CHANGED_REVIEW_AGAIN" -> "同步期间本机资料变化，尚未覆盖；请重试并重新检查"
            "SYNC_PAIR_EXPIRED" -> "配对码已过期，请在电脑生成新的二维码"
            "SYNC_SCOPE_MISMATCH" -> "两端支持的同步范围不同，请使用匹配的同步版本"
            "SYNC_PROTOCOL" -> "两端同步协议版本不同，请使用匹配的双端同步版本"
            "SYNC_LEGACY_PLAN_UNSUPPORTED" -> "旧同步计划无法由此版本安全接续，原计划与已应用成果已保留；请使用对应版本完成或核对恢复记录"
            "SYNC_DEFERRED_DELETION_PROOF", "SYNC_REBASE_POLICY" -> "范围扩展后的本机修改尚不能安全接续，原资料与恢复记录已保留；请检查同步记录后重试"
            "SYNC_COMPONENT_HISTORY_MISSING" -> "关联动态的共同成员记录缺失，已停止写入并保留资料；请检查同步恢复记录"
            "SYNC_STORAGE_SHAPE" -> "资料存放位置与当前版本不符，已停止写入并保留原文件；请检查后重试"
            "SYNC_SINGLETON_KEEP_BOTH" -> "全局设定只能保留一个生效版本，请选择电脑或手机的版本"
            "SYNC_VIBE_PAYLOAD_INVALID" -> "氛围编码资源缺失或校验未通过，已停止写入并保留原资料；请检查后重试"
            "SYNC_MOMENT_ADMISSION_STORAGE" -> "本机朋友圈调度授权未能安全保存，尚未开始写入同步资料；请检查后重试"
            "SYNC_CONFLICT_TIMEOUT" -> "冲突选择等待已结束，现有资料和恢复记录已保留；请重新点同步检查"
            "SYNC_PROJECTION_STATE", "SYNC_PROJECTION_DEVICE", "SYNC_PROJECTION_PROOF" -> "本机同步身份映射缺失或不一致，已停止继续写入并保留资料；请检查同步恢复记录"
            "SYNC_BASELINE_MISMATCH" -> "两端共同基线不一致，已保留资料和回执，需恢复上次同步"
            "SYNC_BUSY_MEMORY", "SYNC_PENDING_REPOSITORY_WORK" -> "本机仍有资料处理或恢复任务，请完成后重试"
            "SYNC_STUDIO_EDITOR_UNCOMMITTED" -> "工作室仍有未确认的展开编辑或画布，请先确认或关闭，再重试同步"
            "SYNC_STUDIO_DRAFT_MISSING", "SYNC_STUDIO_PENDING_ON_WIRE", "SYNC_STUDIO_HISTORY_MISMATCH" -> "工作室草稿与撤销历史尚未通过一致性检查；原资料保留，请检查后重试"
            "SYNC_ONE_PEER_ONLY" -> "首版一次连接一台可信设备；请先撤销已有配对再连接另一台"
            "SYNC_BACKGROUND_WORK_BUSY", "SYNC_MAINTENANCE" -> "本机仍在生成或处理资料，请完成后点同步重试"
            "SYNC_INITIALIZING" -> "本机资料仍在初始化，请稍后点同步重试"
            "SYNC_SAMPLE_ALREADY_PRESENT" -> "离线样例已存在，原内容已保留；可直接打开并编辑"
            "SYNC_UNTRUSTED_DEVICE", "SYNC_DEVICE_CHANGED" -> "设备身份未受信任，请检查或重新配对"
            else -> if (failure is java.net.ConnectException || failure is java.net.SocketTimeoutException)
                "暂时连不上电脑：请保持两端页面打开，检查同一局域网、防火墙和电脑地址；会自动重连"
                else "同步未完成，资料和恢复记录已保留。" + (code?.let { " 原因：$it" } ?: "请检查连接后重试。")
        }
        _state.value = _state.value.copy(status = text, busy = false, conflicts = emptyList(), failureCode = code,
            reviewBinding = null, reviewDetails = emptyList())
    }
    fun stop() {
        _state.value = _state.value.copy(conflicts = emptyList(), reviewBinding = null, reviewDetails = emptyList())
        cancelPairing(); choices?.cancel(); polling?.cancel(); polling = null
        pairing?.cancel(); pairing = null
        sample?.cancel(); sample = null
        listening?.cancel(); listening = null; listener?.close(); listener = null
        discovery?.cancel(); discovery = null; discoverySocket?.close(); discoverySocket = null
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
    }
    suspend fun closeAndDrain() {
        val jobs = listOfNotNull(listening, polling, pairing, sample, discovery)
        stop()
        jobs.joinAll()
    }
}
