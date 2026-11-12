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
    val excluded: Set<String> = emptySet(), val cleanupNotice: String? = null)

@Serializable private data class LanSyncHello(val command: String, val name: String,
    val token: String? = null, val revision: String? = null, val pendingTransaction: String? = null,
    val locallyApplied: Boolean = false, val hasProjection: Boolean = false, val protocol: Int = LAN_SYNC_PROTOCOL)

/** Desktop is the listener; Android maintains a short authenticated poll while its page is visible. */
class LanSyncRuntime(private val store: LanSyncStateStore, private val workspace: LanSyncWorkspace,
    private val platform: LanSyncPlatform, private val name: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val observe: (String, String) -> Unit = { _, _ -> },
    private val network: LanSyncNetwork = LanSyncNetwork.Local) {
    private val identity by lazy { store.identity() }
    private val _state = MutableStateFlow(LanSyncUiState())
    val state = _state.asStateFlow()
    private val operation = Mutex()
    private val offerLock = Any()
    @Volatile private var offer: LanSyncPairOffer? = null
    @Volatile private var requested = false
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
    fun requestSync() { retryPaused = false; requested = true; _state.value = _state.value.copy(status = "已请求同步，等待另一端页面连接") }
    fun resolveConflicts(values: Map<String, LanSyncChoice>) { choices?.complete(values) }
    fun revoke(fingerprint: String) { store.revoke(fingerprint); refresh() }
    fun createPreviewSample() { sample = scope.launch { operation.withLock { safely {
        _state.value = _state.value.copy(busy = true, status = "正在建立离线合成样例")
        platform.exclusive { withContext(NonCancellable) {
            val projection = currentProjection()
            workspace.capture(identity.fingerprint, projection).use { capture ->
                val bytes = LanSyncPreviewSample.image(); val hash = lanSyncHash(bytes)
                val records = LanSyncPreviewSample.records(hash)
                require(capture.snapshot.records.none { current -> records.any { it.key == current.key } }) { "SYNC_SAMPLE_ALREADY_PRESENT" }
                val scratch = LanSyncScratch(store.root, "sample")
                try {
                    val source = scratch.directory.resolve(hash)
                    java.nio.file.Files.write(source, bytes, java.nio.file.StandardOpenOption.CREATE_NEW); scratch.own(source)
                    val snapshot = capture.snapshot.copy(records = capture.snapshot.records + records, resources = capture.snapshot.resources + (hash to bytes.size.toLong()))
                    workspace.apply(LanSyncPlan(UUID.randomUUID().toString(), snapshot, emptyMap()), capture.snapshot.hashes(), identity.fingerprint, capture.blobs + (hash to source), projection)
                    platform.reload(records.filter { it.type == "chat_sessions" }.map { it.id }.toSet())
                } finally { scratch.close() }
            }
        } }
        _state.value = _state.value.copy(status = "已建立“局域网离线样例”；可打开会话编辑消息，无需密钥或生成调用")
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
                try { socket.use { handleHost(it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { showFailure(failure) }
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
                require(wire.text() == "PAIRED") { "SYNC_PAIR_REJECTED" }
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

    private suspend fun handleHost(socket: SSLSocket) {
        require(network.accepts(socket.inetAddress)) { "SYNC_LOCAL_ADDRESS" }
        LanSyncTls.configure(socket); socket.startHandshake()
        val peerId = LanSyncTls.peerFingerprint(socket)
        LanSyncWire(socket).use { wire ->
            val helloText = wire.text().also { require(it.length <= 4096) }
            if (lanSyncJson.parseToJsonElement(helloText).jsonObject["protocol"]?.jsonPrimitive?.intOrNull != LAN_SYNC_PROTOCOL) {
                wire.sendText("SYNC_PROTOCOL"); error("SYNC_PROTOCOL")
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
                wire.sendText("PAIRED"); require(wire.text() == "REMEMBERED")
                _state.value = _state.value.copy(offer = null, peers = store.peers(), status = "已配对 ${hello.name.take(80)}")
                return
            }
            require(store.peers().any { it.fingerprint == peerId }) { "SYNC_UNTRUSTED_DEVICE" }
            require(hello.command == "POLL" || hello.command == "SYNC") { "SYNC_COMMAND" }
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
                _state.value = _state.value.copy(busy = true, status = "正在接收并检查手机快照")
                val phone = LanSyncTransfer.receive(wire, store.root, peerId)
                try {
                val computer = capture()
                try {
                val previous = pending
                var phoneSnapshot = phone.snapshot
                val projection = if (baseline.revision == null && previous == null && !hello.hasProjection)
                    LanSyncPresetProjection.propose(computer.snapshot.records, phoneSnapshot.records) else LanSyncProjection()
                val offer = if (projection.bindings.isEmpty()) LanSyncProjectionOffer() else
                    LanSyncProjectionOffer(projection, phoneSnapshot.hashes(), LanSyncPresetProjection.presets(computer.snapshot.records))
                wire.sendText(lanSyncJson.encodeToString(offer))
                if (projection.bindings.isNotEmpty()) phoneSnapshot = phoneSnapshot.copy(records = projection.rewrite(phoneSnapshot.records))
                phoneSnapshot.validate(); LanSyncScope.validateGraph(phoneSnapshot)
                require(lanSyncJson.decodeFromString<Map<String, String>>(wire.text()) == phoneSnapshot.hashes()) { "SYNC_LOCAL_CHANGED_REVIEW_AGAIN" }
                val computerApplied = previous?.let { workspace.applied(it.plan.transaction) } == true
                val phoneApplied = previous != null && hello.pendingTransaction == previous.plan.transaction && hello.locallyApplied
                val needsRebase = previous != null && when {
                    computerApplied && phoneApplied -> false
                    computerApplied -> phoneSnapshot.hashes() != previous.expectedRemote
                    phoneApplied -> computer.snapshot.hashes() != previous.expectedLocal
                    else -> computer.snapshot.hashes() != previous.expectedLocal || phoneSnapshot.hashes() != previous.expectedRemote
                }
                val prepared = if (previous == null || needsRebase) {
                    val ancestor = if (previous != null && (computerApplied || phoneApplied))
                        LanSyncBaseline(peerId, "recovery:${previous.plan.transaction}",
                            if (computerApplied) previous.expectedRemote else previous.expectedLocal,
                            ownerId = identity.fingerprint, coveredTypes = baseline.coveredTypes)
                        else baseline
                    if (previous != null) _state.value = _state.value.copy(status = "保留上次已应用结果，重新核对两端后续修改")
                    val comparison = LanSyncCompare.compare(computer.snapshot, phoneSnapshot, ancestor)
                    val decisions = decide(comparison)
                    val planned = LanSyncPlanner.plan(computer.snapshot, phoneSnapshot, ancestor, decisions, UUID.randomUUID().toString())
                    val plan = planned.copy(parents = previous?.plan?.parents.orEmpty() + planned.parents, supersedes = previous?.plan?.transaction)
                    LanSyncPending(peerId, plan, computer.snapshot.hashes(), phoneSnapshot.hashes()).also {
                        if (previous == null) store.pending(it) else store.replacePending(it)
                    }
                } else previous
                requireNotNull(prepared)
                // The frozen complete output, not a new import interpretation, is retried after disconnect.
                val output = prepared.plan.snapshot.copy(deviceId = identity.fingerprint)
                val available = computer.blobs + phone.blobs + retainedBlobs(prepared.plan)
                retainBlobs(prepared.plan, available)
                wire.sendText(lanSyncJson.encodeToString(prepared.plan))
                wire.sendText(lanSyncJson.encodeToString(prepared.expectedRemote))
                LanSyncTransfer.send(wire, LanSyncCapture(output, available, emptyMap()))
                require(wire.text() == "APPLIED:${prepared.plan.transaction}") { "SYNC_PHONE_RECEIPT" }
                applyPrepared(prepared, available)
                observe("HOST_APPLIED", prepared.plan.transaction)
                wire.sendText("COMMIT:${prepared.plan.transaction}")
                require(wire.text() == "COMMITTED:${prepared.plan.transaction}") { "SYNC_COMMIT_RECEIPT" }
                complete(peerId, prepared.plan)
                observe("HOST_BASELINE", prepared.plan.transaction)
                store.clearPending(peerId, prepared.plan.transaction)
                requested = computerApplied && computer.snapshot.hashes() != prepared.plan.snapshot.hashes() ||
                    phoneApplied && phoneSnapshot.hashes() != prepared.plan.snapshot.hashes()
                _state.value = _state.value.copy(busy = false, conflicts = emptyList(), status = "同步完成，两端已确认", excluded = output.excludedTypes)
                wire.sendText("DONE")
                } finally { computer.close() }
                } finally { phone.close() }
            }
        }
    }

    private suspend fun phoneAttempt(peer: LanSyncPeer) {
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
                val response = wire.text()
                require(response != "SYNC_PROTOCOL") { "SYNC_PROTOCOL" }
                if (response == "IDLE") return
                if (response.startsWith("FINISHED:") && pending != null && response == "FINISHED:${pending.plan.transaction}") {
                    require(workspace.applied(pending.plan.transaction)) { "SYNC_RECEIPT_WITHOUT_APPLY" }
                    complete(peer.fingerprint, pending.plan)
                    store.clearPending(peer.fingerprint, pending.plan.transaction)
                    requested = true // Finish the old receipt, then exchange any edits made since that apply.
                    _state.value = _state.value.copy(status = "上次同步已核对完成，两端回执一致")
                    return
                }
                require(response == "SYNC") { "SYNC_COMMAND" }
                _state.value = _state.value.copy(busy = true, status = "正在准备一致快照，冲突将在电脑端显示")
                val phone = capture()
                try {
                LanSyncTransfer.send(wire, phone)
                val offer = lanSyncJson.decodeFromString<LanSyncProjectionOffer>(wire.text())
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
                    phoneSnapshot = phoneSnapshot.copy(records = offer.projection.rewrite(phoneSnapshot.records))
                } else require(offer.sourceHashes.isEmpty() && offer.computerPresets.isEmpty()) { "SYNC_PROJECTION_PROOF" }
                wire.sendText(lanSyncJson.encodeToString(phoneSnapshot.hashes()))
                val plan = lanSyncJson.decodeFromString<LanSyncPlan>(wire.text())
                val expected = lanSyncJson.decodeFromString<Map<String, String>>(wire.text())
                val received = LanSyncTransfer.receive(wire, store.root, peer.fingerprint)
                try {
                require(received.snapshot.hashes() == plan.snapshot.hashes()) { "SYNC_PLAN_MISMATCH" }
                val prior = store.pending(peer.fingerprint)
                require(prior == null || prior.plan.transaction == plan.transaction || plan.supersedes == prior.plan.transaction) { "SYNC_PENDING_OTHER_TRANSACTION" }
                val prepared = if (prior?.plan?.transaction == plan.transaction) prior else
                    LanSyncPending(peer.fingerprint, plan, expected, emptyMap()).also {
                        if (prior == null) store.pending(it) else store.replacePending(it)
                    }
                val alreadyApplied = workspace.applied(plan.transaction)
                retainBlobs(plan, received.blobs)
                applyPrepared(prepared, received.blobs)
                observe("PHONE_APPLIED", plan.transaction)
                wire.sendText("APPLIED:${plan.transaction}")
                require(wire.text() == "COMMIT:${plan.transaction}") { "SYNC_COMMIT_RECEIPT" }
                complete(peer.fingerprint, plan)
                observe("PHONE_BASELINE", plan.transaction)
                wire.sendText("COMMITTED:${plan.transaction}")
                require(wire.text() == "DONE") { "SYNC_FINAL_RECEIPT_PENDING" }
                store.clearPending(peer.fingerprint, plan.transaction)
                requested = alreadyApplied && phoneSnapshot.hashes() != plan.snapshot.hashes()
                _state.value = _state.value.copy(busy = false, status = "同步完成，两端已确认", excluded = plan.snapshot.excludedTypes)
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
    private suspend fun decide(comparison: LanSyncComparison): Map<String, LanSyncChoice> {
        if (comparison.conflicts.isEmpty()) return emptyMap()
        val answer = CompletableDeferred<Map<String, LanSyncChoice>>()
        choices = answer
        _state.value = _state.value.copy(status = "请逐项选择双方修改的内容", conflicts = comparison.conflicts)
        try { return withTimeout(10 * 60_000) { answer.await() } } finally { choices = null }
    }
    private suspend fun applyPrepared(pending: LanSyncPending, blobs: Map<String, java.nio.file.Path>) {
        platform.exclusive {
            withContext(NonCancellable) {
                workspace.recover()
                if (!workspace.applied(pending.plan.transaction)) workspace.apply(pending.plan, pending.expectedLocal, identity.fingerprint, blobs,
                    store.projection(pending.peerId, identity.fingerprint))
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
        val directory = store.root.resolve("transfer-${plan.transaction}"); Files.createDirectories(directory)
        plan.snapshot.resources.forEach { (hash, size) ->
            val target = directory.resolve(hash)
            if (Files.notExists(target)) {
                val scratch = LanSyncScratch(store.root, "retaining")
                try {
                    val temporary = scratch.directory.resolve(hash)
                    require(LanSyncWorkspace.copyHash(requireNotNull(blobs[hash]), temporary, size, scratch::own) == hash) { "SYNC_RESOURCE_DAMAGED" }
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    // Ownership moved to the retained immutable transaction; the scratch is now empty.
                    Files.delete(scratch.directory)
                } catch (failure: Exception) { runCatching { scratch.close() }; throw failure }
            }
            require(LanSyncWorkspace.hashFile(target, size) == hash) { "SYNC_RETAINED_BLOB_DAMAGED" }
        }
    }
    private fun retainedBlobs(plan: LanSyncPlan) = plan.snapshot.resources.keys.associateWith { store.root.resolve("transfer-${plan.transaction}/$it") }
        .filterValues { Files.exists(it) }
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
    private fun showFailure(failure: Exception) {
        if (failure !is java.io.IOException) retryPaused = true
        val code = failure.message?.takeIf { it.matches(Regex("SYNC_[A-Z0-9_]+")) }
        val text = when (code) {
            "SYNC_INVALID_UTF8" -> "文字资料含损坏编码，已停止继续写入并保留现有文件；请检查资料后重试"
            "SYNC_LOCAL_CHANGED_REVIEW_AGAIN" -> "同步期间本机资料变化，尚未覆盖；请重试并重新检查"
            "SYNC_PAIR_EXPIRED" -> "配对码已过期，请在电脑生成新的二维码"
            "SYNC_SCOPE_MISMATCH" -> "两端支持的同步范围不同，请使用匹配的同步版本"
            "SYNC_PROTOCOL" -> "两端同步协议版本不同，请使用匹配的双端同步版本"
            "SYNC_PROJECTION_STATE", "SYNC_PROJECTION_DEVICE", "SYNC_PROJECTION_PROOF" -> "本机同步身份映射缺失或不一致，已停止继续写入并保留资料；请检查同步恢复记录"
            "SYNC_BASELINE_MISMATCH" -> "两端共同基线不一致，已保留资料和回执，需恢复上次同步"
            "SYNC_BUSY_MEMORY", "SYNC_PENDING_REPOSITORY_WORK" -> "本机仍有资料处理或恢复任务，请完成后重试"
            "SYNC_ONE_PEER_ONLY" -> "首版一次连接一台可信设备；请先撤销已有配对再连接另一台"
            "SYNC_BACKGROUND_WORK_BUSY", "SYNC_MAINTENANCE" -> "本机仍在生成或处理资料，请完成后点同步重试"
            "SYNC_INITIALIZING" -> "本机资料仍在初始化，请稍后点同步重试"
            "SYNC_SAMPLE_ALREADY_PRESENT" -> "离线样例已存在，原内容已保留；可直接打开并编辑"
            "SYNC_UNTRUSTED_DEVICE", "SYNC_DEVICE_CHANGED" -> "设备身份未受信任，请检查或重新配对"
            else -> if (failure is java.net.ConnectException || failure is java.net.SocketTimeoutException)
                "暂时连不上电脑：请保持两端页面打开，检查同一局域网、防火墙和电脑地址；会自动重连"
                else "同步未完成，资料和恢复记录已保留。" + (code?.let { " 原因：$it" } ?: "请检查连接后重试。")
        }
        _state.value = _state.value.copy(status = text, busy = false, conflicts = emptyList())
    }
    fun stop() {
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
