package com.example.chatbar.domain.lanSync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.nio.file.*
import java.nio.channels.FileChannel
import java.util.UUID

@Serializable
data class LanSyncPeer(val fingerprint: String, val name: String, val host: String = "", val port: Int = 0)

@Serializable
data class LanSyncPairOffer(val protocol: Int = 1, val host: String, val port: Int, val fingerprint: String,
    val token: String, val expiresAt: Long) {
    fun validate(now: Long = System.currentTimeMillis()) {
        require(protocol == 1 && port in 1024..65535 && fingerprint.matches(Regex("[0-9a-f]{64}")) &&
            token.matches(Regex("[0-9a-f]{64}")) && expiresAt > now && expiresAt - now <= 180_000) { "SYNC_PAIR_EXPIRED" }
        LanSyncTls.parseAddress(host)
    }
    fun code(): String = "CCB-LAN1:" + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(lanSyncJson.encodeToString(this).toByteArray(Charsets.UTF_8))
    companion object {
        fun parse(code: String): LanSyncPairOffer {
            require(code.length <= 2048 && code.startsWith("CCB-LAN1:")) { "SYNC_PAIR_CODE" }
            return lanSyncJson.decodeFromString<LanSyncPairOffer>(java.util.Base64.getUrlDecoder()
                .decode(code.removePrefix("CCB-LAN1:")).toString(Charsets.UTF_8)).also { it.validate() }
        }
    }
}

@Serializable
data class LanSyncPending(val peerId: String, val plan: LanSyncPlan,
    val expectedLocal: Map<String, String>, val expectedRemote: Map<String, String>, val localApplied: Boolean = false)

/** The platform supplies an app-private, device-local root, excluded from portable user-data exports. */
class LanSyncStateStore(val root: Path, private val protect: (ByteArray) -> ByteArray,
    private val unprotect: (ByteArray) -> ByteArray) {
    init { Files.createDirectories(root); lanSyncSafe(root, true) }
    @Synchronized fun identity(): LanSyncTlsIdentity {
        val path = root.resolve("identity.protected")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return LanSyncTlsIdentity.decode(unprotect(lanSyncRead(path, 32 * 1024)))
        return LanSyncTlsIdentity.create().also { lanSyncAtomicWrite(path, protect(it.encode())) }
    }
    @Synchronized fun peers(): List<LanSyncPeer> = read("peers.json")?.let { lanSyncJson.decodeFromString(it) } ?: emptyList()
    @Synchronized fun trust(peer: LanSyncPeer) {
        require(peer.fingerprint.matches(Regex("[0-9a-f]{64}")))
        write("peers.json", lanSyncJson.encodeToString(peers().filterNot { it.fingerprint == peer.fingerprint } + peer.copy(name = peer.name.take(80))))
    }
    @Synchronized fun revoke(fingerprint: String) {
        write("peers.json", lanSyncJson.encodeToString(peers().filterNot { it.fingerprint == fingerprint }))
        // Baselines/evidence remain; revocation only removes authentication authority.
    }
    @Synchronized fun baseline(peer: String, owner: String): LanSyncBaseline {
        val baseline = read("baseline-${key(peer)}.json")?.let { lanSyncJson.decodeFromString<LanSyncBaseline>(it) }
            ?: LanSyncBaseline(peerId = peer, ownerId = owner)
        require(baseline.peerId == peer && baseline.ownerId == owner) { "SYNC_BASELINE_DEVICE" }
        return baseline
    }
    @Synchronized fun complete(peer: String, owner: String, plan: LanSyncPlan) {
        val previous = baseline(peer, owner)
        val baseline = LanSyncBaseline(peer, plan.transaction, plan.snapshot.hashes(),
            (previous.receipts + plan.transaction).takeLast(64), owner, plan.snapshot.coveredTypes)
        write("baseline-${key(peer)}.json", lanSyncJson.encodeToString(baseline))
        write("receipt-${key(peer)}.json", lanSyncJson.encodeToString(plan.parents))
    }
    @Synchronized fun pending(peer: String): LanSyncPending? = read("pending-${key(peer)}.json")?.let { lanSyncJson.decodeFromString(it) }
    @Synchronized fun pending(value: LanSyncPending) = write("pending-${key(value.peerId)}.json", lanSyncJson.encodeToString(value))
    @Synchronized fun clearPending(peer: String, transaction: String) {
        val pending = pending(peer) ?: return
        require(pending.plan.transaction == transaction)
        Files.delete(root.resolve("pending-${key(peer)}.json"))
    }
    private fun read(name: String): String? = root.resolve(name).let {
        if (Files.notExists(it, LinkOption.NOFOLLOW_LINKS)) null else lanSyncRead(it, LanSyncWire.MAX_MESSAGE).toString(Charsets.UTF_8)
    }
    private fun write(name: String, value: String) = lanSyncAtomicWrite(root.resolve(name), value.toByteArray(Charsets.UTF_8))
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
    FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        var buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
    Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
