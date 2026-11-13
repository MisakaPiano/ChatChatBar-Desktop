package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.serializer
import java.nio.file.*
import java.nio.channels.FileChannel
import java.util.UUID

@Serializable
data class LanSyncPeer(val fingerprint: String, val name: String, val host: String = "", val port: Int = 0)

@Serializable
data class LanSyncPairOffer(val protocol: Int = LAN_SYNC_PROTOCOL, val host: String, val port: Int, val fingerprint: String,
    val token: String, val expiresAt: Long) {
    fun validate(now: Long = System.currentTimeMillis()) {
        require(protocol == LAN_SYNC_PROTOCOL && port in 1024..65535 && fingerprint.matches(Regex("[0-9a-f]{64}")) &&
            token.matches(Regex("[0-9a-f]{64}")) && expiresAt > now && expiresAt - now <= 180_000) { "SYNC_PAIR_EXPIRED" }
        LanSyncTls.parseAddress(host)
    }
    fun code(): String = "CCB-LAN$LAN_SYNC_PROTOCOL:" + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(lanSyncJson.encodeToString(this).toByteArray(Charsets.UTF_8))
    companion object {
        fun parse(code: String): LanSyncPairOffer {
            require(code.length <= 2048) { "SYNC_PAIR_CODE" }
            val prefix = "CCB-LAN$LAN_SYNC_PROTOCOL:"
            val version = Regex("^CCB-LAN([0-9]+):").find(code)?.groupValues?.get(1)
            require(version == null || version == LAN_SYNC_PROTOCOL.toString()) { "SYNC_PROTOCOL" }
            require(code.startsWith(prefix)) { "SYNC_PAIR_CODE" }
            return lanSyncJson.decodeFromString<LanSyncPairOffer>(lanSyncUtf8(java.util.Base64.getUrlDecoder()
                .decode(code.removePrefix(prefix)))).also { it.validate() }
        }
    }
}

@Serializable
data class LanSyncPending(val peerId: String, val plan: LanSyncPlan,
    val expectedLocal: Map<String, String>, val expectedRemote: Map<String, String>, val localApplied: Boolean = false,
    val ownerId: String? = null, val localProjectionHash: String? = null,
    val deferredDeletion: LanSyncDeferredDeletion? = null,
    val expectedLocalComponents: Map<String, Set<String>> = emptyMap(),
    val expectedRemoteComponents: Map<String, Set<String>> = emptyMap())

@Serializable
data class LanSyncDeferredDeletion(val previousTransaction: String, val recordKeys: Set<String>)

@Serializable private data class LanSyncDeviceProjection(val peerId: String, val ownerId: String, val projection: LanSyncProjection)

/** The platform supplies an app-private, device-local root, excluded from portable user-data exports. */
class LanSyncStateStore(val root: Path, private val protect: (ByteArray) -> ByteArray,
    private val unprotect: (ByteArray) -> ByteArray) {
    init { Files.createDirectories(root); lanSyncSafe(root, true) }
    @Synchronized fun identity(): LanSyncTlsIdentity {
        val path = root.resolve("identity.protected")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return LanSyncTlsIdentity.decode(unprotect(lanSyncRead(path, 32 * 1024)))
        return LanSyncTlsIdentity.create().also { lanSyncAtomicWrite(path, protect(it.encode())) }
    }
    @Synchronized fun peers(): List<LanSyncPeer> = read<List<LanSyncPeer>>("peers.json") ?: emptyList()
    @Synchronized fun trust(peer: LanSyncPeer) {
        require(peer.fingerprint.matches(Regex("[0-9a-f]{64}")))
        require(peers().none { it.fingerprint != peer.fingerprint }) { "SYNC_ONE_PEER_ONLY" }
        write("peers.json", peers().filterNot { it.fingerprint == peer.fingerprint } + peer.copy(name = peer.name.take(80)))
    }
    @Synchronized fun revoke(fingerprint: String) {
        write("peers.json", peers().filterNot { it.fingerprint == fingerprint })
        // Baselines/evidence remain; revocation only removes authentication authority.
    }
    @Synchronized fun baseline(peer: String, owner: String): LanSyncBaseline {
        val baseline = read<LanSyncBaseline>("baseline-${key(peer)}.json")
            ?: LanSyncBaseline(peerId = peer, ownerId = owner)
        require(baseline.peerId == peer && baseline.ownerId == owner) { "SYNC_BASELINE_DEVICE" }
        if (baseline.localProjectionHash != null) require(baseline.localProjectionHash == projection(peer, owner).digest()) { "SYNC_PROJECTION_STATE" }
        else require(baseline.revision == null || projection(peer, owner).bindings.isEmpty()) { "SYNC_PROJECTION_STATE" }
        return baseline
    }
    @Synchronized fun complete(peer: String, owner: String, plan: LanSyncPlan) {
        val previous = baseline(peer, owner)
        val baseline = LanSyncBaseline(peer, plan.transaction, plan.snapshot.hashes(),
            (previous.receipts + plan.transaction).takeLast(64), owner, plan.snapshot.coveredTypes, projection(peer, owner).digest(),
            LanSyncMoments.components(plan.snapshot))
        write("baseline-${key(peer)}.json", baseline)
        val previousParents = read<Map<String, String>>("receipt-${key(peer)}.json").orEmpty()
        write("receipt-${key(peer)}.json", previousParents + plan.parents)
    }
    @Synchronized fun pending(peer: String): LanSyncPending? = read<LanSyncPending>("pending-${key(peer)}.json")?.also { value ->
        require(value.peerId == peer) { "SYNC_PENDING_DEVICE" }
        if (value.ownerId != null) require(value.ownerId == identity().fingerprint) { "SYNC_PENDING_DEVICE" }
        if (value.localProjectionHash != null) require(value.localProjectionHash == projection(peer, identity().fingerprint).digest()) { "SYNC_PROJECTION_STATE" }
    }
    @Synchronized fun pending(value: LanSyncPending) {
        val owner = identity().fingerprint
        require(value.ownerId == null || value.ownerId == owner) { "SYNC_PENDING_DEVICE" }
        val hash = projection(value.peerId, owner).digest()
        require(value.localProjectionHash == null || value.localProjectionHash == hash) { "SYNC_PROJECTION_STATE" }
        write("pending-${key(value.peerId)}.json", value.copy(ownerId = owner, localProjectionHash = hash))
    }
    @Synchronized fun replacePending(value: LanSyncPending) {
        pending(value.peerId)?.let { previous ->
            require(value.plan.supersedes == previous.plan.transaction) { "SYNC_PENDING_OTHER_TRANSACTION" }
            write("superseded-${previous.plan.transaction}.json", previous)
        }
        pending(value)
    }
    @Synchronized fun superseded(peer: String, transaction: String): LanSyncPending? {
        require(transaction.matches(Regex("[a-f0-9-]{36}"))) { "SYNC_TRANSACTION_ID" }
        return read<LanSyncPending>("superseded-$transaction.json")?.also {
            require(it.peerId == peer && it.plan.transaction == transaction) { "SYNC_SUPERSEDED_IDENTITY" }
            require(it.ownerId == null || it.ownerId == identity().fingerprint) { "SYNC_SUPERSEDED_IDENTITY" }
        }
    }
    @Synchronized fun projection(peer: String, owner: String): LanSyncProjection = read<LanSyncDeviceProjection>("projection-${key(peer)}.json")?.let { stored ->
        stored.also {
            require(it.peerId == peer && it.ownerId == owner) { "SYNC_PROJECTION_DEVICE" }; it.projection.validate()
        }.projection
    } ?: LanSyncProjection()
    @Synchronized fun admitProjection(peer: String, owner: String, value: LanSyncProjection) {
        value.validate()
        require(peers().any { it.fingerprint == peer }) { "SYNC_UNTRUSTED_DEVICE" }
        require(baseline(peer, owner).revision == null && pending(peer) == null) { "SYNC_PROJECTION_BASELINE_EXISTS" }
        val previous = projection(peer, owner)
        require(previous.bindings.isEmpty() || previous == value) { "SYNC_PROJECTION_ALREADY_ASSIGNED" }
        write("projection-${key(peer)}.json", LanSyncDeviceProjection(peer, owner, value))
    }
    @Synchronized fun clearPending(peer: String, transaction: String) {
        val pending = pending(peer) ?: return
        require(pending.plan.transaction == transaction)
        Files.delete(root.resolve("pending-${key(peer)}.json"))
    }
    private inline fun <reified T> read(name: String): T? = LanSyncJsonFiles.read(root.resolve(name), serializer<T>())
    private inline fun <reified T> write(name: String, value: T) = LanSyncJsonFiles.write(root.resolve(name), serializer<T>(), value)
    private fun key(peer: String): String = peer.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
}

internal fun lanSyncSafe(path: Path, directory: Boolean = false) {
    require(path.isAbsolute && path.normalize() == path && path.toRealPath() == path) { "SYNC_UNSAFE_PATH" }
    var part: Path? = path
    while (part != null) {
        require(!Files.isSymbolicLink(part) && !Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).isOther) { "SYNC_LINK" }
        part = part.parent
    }
    require(if (directory) Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) else Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "SYNC_FILE_TYPE" }
}
internal fun lanSyncRead(path: Path, limit: Int): ByteArray {
    lanSyncSafe(path); require(Files.size(path) <= limit) { "SYNC_FILE_LIMIT" }
    return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= limit); output.write(buffer, 0, count) }
        output.toByteArray()
    }
}
internal fun lanSyncAtomicWrite(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent); lanSyncSafe(path.parent, true)
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) lanSyncSafe(path)
    val part = path.resolveSibling(".${UUID.randomUUID()}.part")
    var partKey: Any? = null
    var created = false
    var moved = false
    try {
    FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        created = true
        partKey = Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        var buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
    Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    moved = true
    } finally {
        if (created && !moved && Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
            lanSyncSafe(part)
            require(Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() == partKey) { "SYNC_TEMPORARY_CHANGED" }
            Files.delete(part)
        }
    }
}
