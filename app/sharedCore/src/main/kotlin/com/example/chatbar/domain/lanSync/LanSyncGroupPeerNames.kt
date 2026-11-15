package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.Serializable

/** Local authenticated-proof display hints only. Neither a name nor this file grants membership. */
class LanSyncGroupPeerNames(private val root: Path, private val trust: SyncGroupTrust,
    private val currentLedger: () -> SyncDeviceLedger, private val protect: (ByteArray) -> ByteArray,
    private val unprotect: (ByteArray) -> ByteArray) {
    @Serializable private data class Entry(val certificate: String, val recipient: String, val name: String,
        val checkpoint: SyncVersionRef, val proof: String)
    @Serializable private data class State(val format: Int, val trust: SyncGroupTrust, val entries: Map<String, Entry>)
    private val lock = locks.computeIfAbsent(root) { ReentrantLock() }
    private val file = root.resolve("peer-display.protected")
    init { lanSyncSafe(root, true) }
    private fun sameAnchor(a: SyncGroupTrust, b: SyncGroupTrust) = a.group == b.group && a.genesis == b.genesis && a.ownerCertificate == b.ownerCertificate
    private fun ledger(): SyncDeviceLedger = currentLedger().also {
        require(sameAnchor(trust, it.trustAnchor) && it.contains(trust.checkpoint)) { "SYNC_GROUP_PEER_NAMES_GROUP_CHANGED" }
    }
    fun remember(peer: LanSyncGroupInvitedPeer) = guarded {
        val live = ledger()
        require(sameAnchor(peer.trust, trust) && live.contains(peer.trust.checkpoint) &&
            peer.device == SyncDeviceSignatures.decode(peer.member.certificate).fingerprint && peer.member.active &&
            peer.name.isNotBlank() && peer.name.length <= 160 && lanSyncGroupDisplayText(peer.name).isNotBlank() &&
            peer.binding.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_PEER_NAME_PROOF_CHANGED" }
        SyncRecipientPublicKey.parse(peer.member.recipientKey)
        live.current().members[peer.device]?.let { member ->
            require(member.certificate == peer.member.certificate && member.recipientKey == peer.member.recipientKey) { "SYNC_GROUP_PEER_NAME_IDENTITY_CHANGED" }
        }
        val previous = read()
        val entry = Entry(peer.member.certificate, peer.member.recipientKey, peer.name, peer.trust.checkpoint, peer.binding)
        if (previous.entries[peer.device] != entry) write(previous.copy(entries = previous.entries + (peer.device to entry)))
    }
    fun displayName(device: String): String? = guarded {
        require(device.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_PEER_NAME_DEVICE" }
        val live = ledger()
        val entry = read().entries[device] ?: return@guarded null
        val member = live.current().members[device] ?: return@guarded null
        if (!live.contains(entry.checkpoint) || member.certificate != entry.certificate || member.recipientKey != entry.recipient) return@guarded null
        // Revocation changes authority/status, not the already verified display identity.
        lanSyncGroupDisplayText(entry.name)
    }
    private fun read(): State {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return State(1, trust, emptyMap())
        val borrowed = lanSyncRead(file, MAX_BYTES + 1024 * 1024)
        var plain: ByteArray? = null
        try {
            val decoded = try { unprotect(borrowed) }
                catch (_: Exception) { throw IllegalStateException("SYNC_GROUP_PEER_NAMES_PROTECTION_FAILED") }
            plain = decoded
            require(decoded.size in 1..MAX_BYTES) { "SYNC_GROUP_PEER_NAMES_BUDGET" }
            val state = try { lanSyncJson.decodeFromString(State.serializer(), lanSyncUtf8(decoded)) }
                catch (_: Exception) { throw IllegalArgumentException("SYNC_GROUP_PEER_NAMES_INVALID") }
            require(state.format == 1 && sameAnchor(state.trust, trust)) { "SYNC_GROUP_PEER_NAMES_GROUP_CHANGED" }
            state.entries.forEach { (device, entry) ->
                require(device.matches(Regex("[a-f0-9]{64}")) && SyncDeviceSignatures.decode(entry.certificate).fingerprint == device &&
                    entry.name.isNotBlank() && entry.name.length <= 160 && lanSyncGroupDisplayText(entry.name).isNotBlank() &&
                    entry.proof.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_PEER_NAMES_INVALID" }
                SyncRecipientPublicKey.parse(entry.recipient)
            }
            return state
        } finally { borrowed.fill(0); plain?.fill(0) }
    }
    private fun write(state: State) {
        val plain = lanSyncJson.encodeToString(State.serializer(), state.copy(entries = state.entries.toSortedMap())).toByteArray(Charsets.UTF_8)
        try {
            require(plain.size in 1..MAX_BYTES) { "SYNC_GROUP_PEER_NAMES_BUDGET" }
            val encrypted = try { protect(plain).copyOf() } catch (_: Exception) { throw IllegalStateException("SYNC_GROUP_PEER_NAMES_PROTECTION_FAILED") }
            require(encrypted.size in 1..MAX_BYTES + 1024 * 1024) { "SYNC_GROUP_PEER_NAMES_BUDGET" }
            lanSyncAtomicWrite(file, encrypted)
        } finally { plain.fill(0) }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_PEER_NAMES_REENTRY" }
        lock.lockInterruptibly()
        try {
            lanSyncSafe(root, true)
            val path = root.resolve("peer-display.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> lanSyncSafe(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object { private const val MAX_BYTES = 17 * 1024 * 1024; private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
