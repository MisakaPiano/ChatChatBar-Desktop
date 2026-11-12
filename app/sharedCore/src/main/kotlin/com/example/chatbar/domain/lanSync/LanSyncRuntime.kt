package com.example.chatbar.domain.lanSync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
    val excluded: Set<String> = emptySet())

@Serializable private data class LanSyncHello(val command: String, val name: String,
    val token: String? = null, val revision: String? = null, val pendingTransaction: String? = null)

/** Desktop is the listener; Android maintains a short authenticated poll while its page is visible. */
class LanSyncRuntime(private val store: LanSyncStateStore, private val workspace: LanSyncWorkspace,
    private val platform: LanSyncPlatform, private val name: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
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
    private var choices: CompletableDeferred<Map<String, LanSyncChoice>>? = null
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<SSLSocket>()

    fun refresh() { _state.value = _state.value.copy(peers = store.peers()) }
    fun requestSync() { requested = true; _state.value = _state.value.copy(status = "已请求同步，等待另一端页面连接") }
    fun resolveConflicts(values: Map<String, LanSyncChoice>) { choices?.complete(values) }
    fun revoke(fingerprint: String) { store.revoke(fingerprint); refresh() }
    fun cancelPairing() { synchronized(offerLock) { offer = null }; _state.value = _state.value.copy(offer = null) }

    fun startHost() {
        if (listening?.isActive == true) return
        listening = scope.launch { safely {
            val addresses = LanSyncTls.localAddresses(); require(addresses.isNotEmpty()) { "SYNC_NO_LOCAL_NETWORK" }
            val context = identity.context { peer -> store.peers().any { it.fingerprint == peer } || offer?.expiresAt?.let { it > System.currentTimeMillis() } == true }
            val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket
            server.reuseAddress = false; server.needClientAuth = true
            server.bind(InetSocketAddress(0)); listener = server
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

    fun pair(code: String) { scope.launch { operation.withLock { safely {
        val value = LanSyncPairOffer.parse(code)
        _state.value = _state.value.copy(busy = true, status = "正在验证电脑身份")
        LanSyncTls.connect(identity, value.host, value.port, value.fingerprint).use { socket ->
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
                if (!operation.isLocked) store.peers().forEach { peer ->
                    operation.withLock { safely { phoneAttempt(peer) } }
                }
                delay(2500)
            }
        }
    }

    private suspend fun handleHost(socket: SSLSocket) {
        require(socket.inetAddress.isSiteLocalAddress && !socket.inetAddress.isLoopbackAddress) { "SYNC_LOCAL_ADDRESS" }
        LanSyncTls.configure(socket); socket.startHandshake()
        val peerId = LanSyncTls.peerFingerprint(socket)
        LanSyncWire(socket).use { wire ->
            val hello = lanSyncJson.decodeFromString<LanSyncHello>(wire.text().also { require(it.length <= 4096) })
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
            val pending = store.pending(peerId)
            if (hello.command == "POLL" && !requested && pending == null) { wire.sendText("IDLE"); return }
            operation.withLock {
                val baseline = store.baseline(peerId, identity.fingerprint)
                if (hello.pendingTransaction != null && hello.pendingTransaction in baseline.receipts) {
                    wire.sendText("FINISHED:${hello.pendingTransaction}"); return@withLock
                }
                require(pending != null || hello.revision == baseline.revision) { "SYNC_BASELINE_MISMATCH" }
                wire.sendText("SYNC")
                _state.value = _state.value.copy(busy = true, status = "正在接收并检查手机快照")
                val phone = LanSyncTransfer.receive(wire, store.root, peerId)
                val computer = capture()
                val prepared = pending ?: run {
                    val comparison = LanSyncCompare.compare(computer.snapshot, phone.snapshot, baseline)
                    val decisions = if (comparison.conflicts.isEmpty()) emptyMap() else {
                        val answer = CompletableDeferred<Map<String, LanSyncChoice>>()
                        choices = answer
                        _state.value = _state.value.copy(status = "请逐项选择双方修改的内容", conflicts = comparison.conflicts)
                        try { withTimeout(10 * 60_000) { answer.await() } } finally { choices = null }
                    }
                    val plan = LanSyncPlanner.plan(computer.snapshot, phone.snapshot, baseline, decisions, UUID.randomUUID().toString())
                    LanSyncPending(peerId, plan, computer.snapshot.hashes(), phone.snapshot.hashes()).also(store::pending)
                }
                // The frozen complete output, not a new import interpretation, is retried after disconnect.
                val output = prepared.plan.snapshot.copy(deviceId = identity.fingerprint)
                val available = computer.blobs + phone.blobs + retainedBlobs(prepared.plan)
                retainBlobs(prepared.plan, available)
                wire.sendText(lanSyncJson.encodeToString(prepared.plan))
                wire.sendText(lanSyncJson.encodeToString(prepared.expectedRemote))
                LanSyncTransfer.send(wire, LanSyncCapture(output, available, emptyMap()))
                require(wire.text() == "APPLIED:${prepared.plan.transaction}") { "SYNC_PHONE_RECEIPT" }
                applyPrepared(prepared, available)
                wire.sendText("COMMIT:${prepared.plan.transaction}")
                require(wire.text() == "COMMITTED:${prepared.plan.transaction}") { "SYNC_COMMIT_RECEIPT" }
                store.complete(peerId, identity.fingerprint, prepared.plan)
                store.clearPending(peerId, prepared.plan.transaction)
                requested = false
                _state.value = _state.value.copy(busy = false, conflicts = emptyList(), status = "同步完成，两端已确认", excluded = output.excludedTypes)
                wire.sendText("DONE")
            }
        }
    }

    private suspend fun phoneAttempt(peer: LanSyncPeer) {
        val baseline = store.baseline(peer.fingerprint, identity.fingerprint)
        LanSyncTls.connect(identity, peer.host, peer.port, peer.fingerprint).use { socket ->
            sockets += socket
            try { LanSyncWire(socket).use { wire ->
                val pending = store.pending(peer.fingerprint)
                wire.sendText(lanSyncJson.encodeToString(LanSyncHello(if (requested || pending != null) "SYNC" else "POLL", name,
                    revision = baseline.revision, pendingTransaction = pending?.plan?.transaction)))
                val response = wire.text()
                if (response == "IDLE") return
                if (response.startsWith("FINISHED:") && pending != null && response == "FINISHED:${pending.plan.transaction}") {
                    require(workspace.applied(pending.plan.transaction)) { "SYNC_RECEIPT_WITHOUT_APPLY" }
                    store.complete(peer.fingerprint, identity.fingerprint, pending.plan)
                    store.clearPending(peer.fingerprint, pending.plan.transaction)
                    requested = false
                    _state.value = _state.value.copy(status = "上次同步已核对完成，两端回执一致")
                    return
                }
                require(response == "SYNC") { "SYNC_COMMAND" }
                _state.value = _state.value.copy(busy = true, status = "正在准备一致快照，冲突将在电脑端显示")
                val phone = capture(); LanSyncTransfer.send(wire, phone)
                val plan = lanSyncJson.decodeFromString<LanSyncPlan>(wire.text())
                val expected = lanSyncJson.decodeFromString<Map<String, String>>(wire.text())
                val received = LanSyncTransfer.receive(wire, store.root, peer.fingerprint)
                require(received.snapshot.hashes() == plan.snapshot.hashes()) { "SYNC_PLAN_MISMATCH" }
                val prior = store.pending(peer.fingerprint)
                require(prior == null || prior.plan.transaction == plan.transaction) { "SYNC_PENDING_OTHER_TRANSACTION" }
                val prepared = prior ?: LanSyncPending(peer.fingerprint, plan, expected, emptyMap()).also(store::pending)
                retainBlobs(plan, received.blobs)
                applyPrepared(prepared, received.blobs)
                wire.sendText("APPLIED:${plan.transaction}")
                require(wire.text() == "COMMIT:${plan.transaction}") { "SYNC_COMMIT_RECEIPT" }
                store.complete(peer.fingerprint, identity.fingerprint, plan)
                wire.sendText("COMMITTED:${plan.transaction}")
                require(wire.text() == "DONE") { "SYNC_FINAL_RECEIPT_PENDING" }
                store.clearPending(peer.fingerprint, plan.transaction)
                requested = false
                _state.value = _state.value.copy(busy = false, status = "同步完成，两端已确认", excluded = plan.snapshot.excludedTypes)
            } } finally { sockets -= socket }
        }
    }

    private suspend fun capture(): LanSyncCapture {
        var value: LanSyncCapture? = null
        platform.exclusive { workspace.recover(); value = workspace.capture(identity.fingerprint) }
        return requireNotNull(value)
    }
    private suspend fun applyPrepared(pending: LanSyncPending, blobs: Map<String, java.nio.file.Path>) {
        if (!workspace.applied(pending.plan.transaction)) platform.exclusive {
            withContext(NonCancellable) {
                workspace.recover()
                if (!workspace.applied(pending.plan.transaction)) workspace.apply(pending.plan, pending.expectedLocal, identity.fingerprint, blobs)
                val sessions = pending.plan.snapshot.records.filter { it.type == "chat_sessions" }.map { it.id }.toSet()
                platform.reload(sessions)
            }
        }
        store.pending(pending.copy(localApplied = true))
    }
    private fun retainBlobs(plan: LanSyncPlan, blobs: Map<String, java.nio.file.Path>) {
        val directory = store.root.resolve("transfer-${plan.transaction}"); Files.createDirectories(directory)
        plan.snapshot.resources.forEach { (hash, size) ->
            val target = directory.resolve(hash)
            if (Files.notExists(target)) Files.copy(requireNotNull(blobs[hash]), target)
            require(LanSyncWorkspace.hashFile(target, size) == hash) { "SYNC_RETAINED_BLOB_DAMAGED" }
        }
    }
    private fun retainedBlobs(plan: LanSyncPlan) = plan.snapshot.resources.keys.associateWith { store.root.resolve("transfer-${plan.transaction}/$it") }
        .filterValues { Files.exists(it) }
    private suspend fun safely(work: suspend () -> Unit) {
        try { work() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { showFailure(failure) }
        finally { _state.value = _state.value.copy(busy = false) }
    }
    private fun showFailure(failure: Exception) {
        val code = failure.message?.takeIf { it.matches(Regex("SYNC_[A-Z_]+")) }
        val text = when (code) {
            "SYNC_LOCAL_CHANGED_REVIEW_AGAIN" -> "同步期间本机资料变化，尚未覆盖；请重试并重新检查"
            "SYNC_PAIR_EXPIRED" -> "配对码已过期，请在电脑生成新的二维码"
            "SYNC_SCOPE_MISMATCH" -> "两端支持的同步范围不同，请使用匹配的同步版本"
            "SYNC_BASELINE_MISMATCH" -> "两端共同基线不一致，已保留资料和回执，需恢复上次同步"
            "SYNC_BUSY_MEMORY", "SYNC_PENDING_REPOSITORY_WORK" -> "本机仍有资料处理或恢复任务，请完成后重试"
            "SYNC_UNTRUSTED_DEVICE", "SYNC_DEVICE_CHANGED" -> "设备身份未受信任，请检查或重新配对"
            else -> if (failure is java.net.ConnectException || failure is java.net.SocketTimeoutException)
                "暂时连不上电脑：请保持两端页面打开，检查同一局域网、防火墙和电脑地址；会自动重连"
                else "同步未完成，资料和恢复记录已保留。" + (code?.let { " 原因：$it" } ?: "请检查连接后重试。")
        }
        _state.value = _state.value.copy(status = text, busy = false, conflicts = emptyList())
    }
    fun stop() {
        cancelPairing(); choices?.cancel(); polling?.cancel(); polling = null
        listening?.cancel(); listening = null; listener?.close(); listener = null
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
    }
}
