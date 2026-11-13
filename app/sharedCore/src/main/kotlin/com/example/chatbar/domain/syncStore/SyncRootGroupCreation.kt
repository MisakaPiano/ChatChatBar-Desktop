package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.lanSyncJson
import com.example.chatbar.domain.lanSync.lanSyncUtf8
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class SyncRootGroupCreateRequest(val operation: SyncStoreId, val coveredTypes: Set<String>, val approvalBinding: String) {
    init { require(coveredTypes.isNotEmpty() && coveredTypes.all { it.matches(Regex("[a-z_]{1,80}")) } &&
        approvalBinding.matches(Regex("[a-f0-9]{64}"))) { "SYNC_GROUP_CREATE_REQUEST" } }
}
fun interface SyncRootGroupCreateAdmission { fun requireApproved(request: SyncRootGroupCreateRequest) }
class SyncRootCreatedGroup internal constructor(val trust: SyncGroupTrust, val genesis: SyncDeviceLedgerRecord,
    internal val cipher: SyncStreamingCipher) {
    /** Only the locally approved creator can supply this generated key; joining uses signed HPKE delivery. */
    fun enrollCreator(custody: SyncGroupCustody): SyncDeviceLedger {
        val ledger = custody.enroll(trust, listOf(genesis))
        val actor = SyncDeviceSignatures.decode(trust.ownerCertificate).fingerprint
        custody.rememberKey(SyncAdmittedGroupKey(cipher, actor, genesis.reference()))
        return ledger
    }
}

/** Durable, explicit first-root creation, separate from invitations and existing-group enrollment. */
class SyncRootGroupCreation(private val root: Path, ownerCertificate: String, private val recipient: SyncDeviceEncryptionKey,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray,
    private val observe: (String) -> Unit = {}) {
    private val owner = SyncDeviceSignatures.decode(ownerCertificate)
    private val lock: ReentrantLock
    @Serializable private data class Intent(val request: SyncRootGroupCreateRequest, val genesis: SyncDeviceLedgerRecord, val protectedKey: String)
    @Serializable private data class Signed(val operation: SyncStoreId, val genesis: SyncDeviceLedgerRecord)
    @Serializable private data class Frame(val domain: String, val device: String, val recipientKey: String, val kind: String, val payload: JsonElement)
    init {
        PrivateSyncFile.checked(root, true)
        require(owner.encoded == ownerCertificate && owner.fingerprint == recipient.device) { "SYNC_GROUP_CREATE_DEVICE" }
        lock = locks.computeIfAbsent(root) { ReentrantLock() }
    }
    fun create(request: SyncRootGroupCreateRequest, admission: SyncRootGroupCreateAdmission,
        sign: (ByteArray) -> ByteArray): SyncRootCreatedGroup = guarded {
        val frozen = request.copy(coveredTypes = Collections.unmodifiableSet(request.coveredTypes.toSortedSet()))
        val previous = read("intent.protected", "INTENT", Intent.serializer())
        val intent = if (previous != null) previous.also {
            require(it.request == frozen) { "SYNC_GROUP_CREATE_OPERATION_CHANGED" }
        } else {
            require(!Files.exists(root.resolve("genesis.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_CREATE_INTENT_MISSING" }
            admission.requireApproved(frozen)
            val key = SyncStreamingCipher.create()
            val member = SyncGroupMember(owner.encoded, recipient.publicKey.encoded)
            Intent(frozen, SyncDeviceLedgerRecord(key.group, SyncStoreId.random(), null, 1, key.epoch, key.keyCommitment(),
                frozen.coveredTypes, mapOf(owner.fingerprint to member), emptySet(), "unsigned"),
                Base64.getEncoder().encodeToString(key.protectedKeyset(protect))).also {
                write("intent.protected", "INTENT", Intent.serializer(), it); observe("APPROVED_GROUP_CREATION_RETAINED")
            }
        }
        finish(intent, sign)
    }
    fun resume(operation: SyncStoreId, sign: (ByteArray) -> ByteArray): SyncRootCreatedGroup = guarded {
        val intent = requireNotNull(read("intent.protected", "INTENT", Intent.serializer())) { "SYNC_GROUP_CREATE_INTENT_MISSING" }
        require(intent.request.operation == operation) { "SYNC_GROUP_CREATE_OPERATION_CHANGED" }
        finish(intent, sign)
    }
    private fun finish(intent: Intent, sign: (ByteArray) -> ByteArray): SyncRootCreatedGroup {
        val row = intent.genesis
        require(row.parent == null && row.sequence == 1L && row.signature == "unsigned" && row.acceptedHeads.isEmpty() &&
            row.coveredTypes == intent.request.coveredTypes && row.members ==
                mapOf(owner.fingerprint to SyncGroupMember(owner.encoded, recipient.publicKey.encoded))) { "SYNC_GROUP_CREATE_INTENT_CHANGED" }
        require(intent.protectedKey.length in 1..128 * 1024) { "SYNC_GROUP_CREATE_KEY_BUDGET" }
        val key = SyncStreamingCipher.open(row.group, row.epoch, Base64.getDecoder().decode(intent.protectedKey), unprotect)
        require(key.keyCommitment() == row.keyCommitment) { "SYNC_GROUP_CREATE_KEY_CHANGED" }
        val previous = read("genesis.protected", "SIGNED", Signed.serializer())
        val signed = previous ?: Signed(intent.request.operation,
            row.copy(signature = Base64.getEncoder().encodeToString(sign(row.signingBytes()))))
        require(signed.operation == intent.request.operation && signed.genesis.copy(signature = "unsigned") == row &&
            owner.verifies(signed.genesis.signingBytes(), signed.genesis.signature)) { "SYNC_GROUP_CREATE_SIGNATURE" }
        val trust = SyncGroupTrust(row.group, row.reference(), owner.encoded)
        val ledger = SyncDeviceLedger(trust)
        require(ledger.offer(signed.genesis) == SyncVersionState.ADMITTED && ledger.current() == signed.genesis) { "SYNC_GROUP_CREATE_GENESIS" }
        if (previous == null) { write("genesis.protected", "SIGNED", Signed.serializer(), signed); observe("GROUP_GENESIS_RETAINED") }
        return SyncRootCreatedGroup(trust, signed.genesis, key)
    }
    private fun <T> read(name: String, kind: String, serializer: KSerializer<T>): T? {
        val path = root.resolve(name)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        PrivateSyncFile.checked(path); require(Files.size(path) in 1..MAX_PROTECTED.toLong()) { "SYNC_GROUP_CREATE_WORKING_BUDGET" }
        val encrypted = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size() <= MAX_PROTECTED - count) { "SYNC_GROUP_CREATE_WORKING_BUDGET" }; output.write(buffer, 0, count) }
            output.toByteArray()
        }
        val borrowed = encrypted.copyOf(); val plain = try { unprotect(borrowed) } catch (failure: Throwable) { borrowed.fill(0); throw failure }
        try {
            require(plain.size in 1..MAX_PLAIN) { "SYNC_GROUP_CREATE_WORKING_BUDGET" }
            val text = lanSyncUtf8(plain); var depth = 0; var quoted = false; var escaped = false
            text.forEach { char ->
                if (quoted) { if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false }
                else when (char) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 16) { "SYNC_GROUP_CREATE_WORKING_BUDGET" } }; '}', ']' -> depth-- }
                require(depth >= 0) { "SYNC_GROUP_CREATE_PRIVATE_CONTEXT" }
            }
            require(!quoted && depth == 0) { "SYNC_GROUP_CREATE_PRIVATE_CONTEXT" }
            val frame = lanSyncJson.decodeFromString(Frame.serializer(), text)
            require(frame.domain == DOMAIN && frame.device == owner.fingerprint && frame.recipientKey == recipient.publicKey.hash && frame.kind == kind) {
                "SYNC_GROUP_CREATE_PRIVATE_CONTEXT"
            }
            return lanSyncJson.decodeFromJsonElement(serializer, frame.payload)
        } finally { borrowed.fill(0); plain.fill(0) }
    }
    private fun <T> write(name: String, kind: String, serializer: KSerializer<T>, value: T) {
        val path = root.resolve(name); require(!Files.exists(path, NOFOLLOW_LINKS)) { "SYNC_GROUP_CREATE_IMMUTABLE" }
        val plain = lanSyncJson.encodeToString(Frame.serializer(), Frame(DOMAIN, owner.fingerprint, recipient.publicKey.hash, kind,
            lanSyncJson.encodeToJsonElement(serializer, value))).toByteArray(Charsets.UTF_8)
        require(plain.size in 1..MAX_PLAIN) { "SYNC_GROUP_CREATE_WORKING_BUDGET" }
        val encrypted = try { protect(plain).copyOf() } finally { plain.fill(0) }
        require(encrypted.size in 1..MAX_PROTECTED) { "SYNC_GROUP_CREATE_WORKING_BUDGET" }
        PrivateSyncFile.create(root).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel -> val bytes = ByteBuffer.wrap(encrypted)
                while (bytes.hasRemaining()) channel.write(bytes); channel.force(true) }
            Files.move(file.path, path, ATOMIC_MOVE)
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_CREATE_REENTRY" }; lock.lockInterruptibly()
        try { PrivateSyncFile.checked(root, true); val path = root.resolve("creation.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> PrivateSyncFile.checked(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object {
        private const val DOMAIN = "CCB-SYNC-ROOT-CREATION/1"
        private const val MAX_PLAIN = 1024 * 1024
        private const val MAX_PROTECTED = MAX_PLAIN + 1024 * 1024
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
