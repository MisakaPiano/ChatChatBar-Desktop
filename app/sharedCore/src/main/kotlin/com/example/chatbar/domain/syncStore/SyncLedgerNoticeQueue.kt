package com.example.chatbar.domain.syncStore

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

data class SyncLedgerNoticeEntry(val notice: SyncLedgerNotice, val request: CiphertextPutRequest)

/** Root-controlled immutable recipient ciphertext; no group data key is present in this queue. */
class SyncLedgerNoticeQueue(private val root: Path, private val trust: SyncGroupTrust, private val localDevice: String,
    private val ledger: () -> SyncDeviceLedger, private val observe: (String) -> Unit = {}) {
    private val lock: ReentrantLock
    init { PrivateSyncFile.checked(root, true); lock = locks.computeIfAbsent(root) { ReentrantLock() } }
    fun prepare(operation: SyncStoreId, recipient: String, sign: (ByteArray) -> ByteArray): SyncLedgerNoticeEntry = guarded {
        val state = checkedLedger(); val revision = requireNotNull(SyncLedgerNotice.revocations(state)[recipient]) { "SYNC_LEDGER_NOTICE_NOT_REVOKED" }
        read(operation)?.let { existing ->
            val context = existing.notice.context
            require(context.group == trust.group && context.revocation == revision && context.recipient == recipient &&
                SyncRecipientPublicKey.parse(requireNotNull(state.record(revision)).members.getValue(recipient).recipientKey).hash == context.recipientKeyHash) { "SYNC_LEDGER_NOTICE_OPERATION_CHANGED" }
            return@guarded existing
        }
        val notice = SyncLedgerNotice.create(state, recipient, operation, sign)
        publish(file(operation), SyncLedgerNoticeCodec.encode(notice))
        observe("REVOCATION_NOTICE_RETAINED")
        requireNotNull(read(operation))
    }
    fun entry(operation: SyncStoreId): SyncLedgerNoticeEntry? = guarded { read(operation) }
    fun upload(operation: SyncStoreId, backend: CiphertextStore): CiphertextPutResult = guarded {
        val state = checkedLedger(); val entry = requireNotNull(read(operation)) { "SYNC_LEDGER_NOTICE_MISSING" }
        val context = entry.notice.context
        require(SyncLedgerNotice.revocations(state)[context.recipient] == context.revocation) { "SYNC_LEDGER_NOTICE_AUTHORITY_CHANGED" }
        val result = Files.newInputStream(file(operation), NOFOLLOW_LINKS).use { backend.put(entry.request, it) }
        require(result.metadata == CiphertextObjectMetadata(entry.request.key, entry.request.descriptor)) { "SYNC_LEDGER_NOTICE_RECEIPT" }
        observe("REVOCATION_NOTICE_BACKEND_RETURNED")
        result
    }
    private fun checkedLedger(): SyncDeviceLedger = ledger().also {
        require(it.trustAnchor.group == trust.group && it.trustAnchor.genesis == trust.genesis && it.trustAnchor.ownerCertificate == trust.ownerCertificate &&
            localDevice == SyncDeviceSignatures.decode(trust.ownerCertificate).fingerprint && it.current().members[localDevice]?.active == true) { "SYNC_LEDGER_NOTICE_ROOT_AUTHORITY" }
    }
    private fun read(operation: SyncStoreId): SyncLedgerNoticeEntry? {
        val file = file(operation); if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        PrivateSyncFile.checked(file); require(Files.size(file) in 1..SyncLedgerNoticeCodec.MAX_BYTES.toLong()) { "SYNC_LEDGER_NOTICE_BUDGET" }
        val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use(SyncLedgerNoticeCodec::read)
        val notice = SyncLedgerNoticeCodec.decode(bytes)
        val key = CiphertextObjectKey(CiphertextObjectKind.COMMIT, operation); notice.verifyOwner(trust, key)
        return SyncLedgerNoticeEntry(notice, CiphertextPutRequest(key, operation, SyncKeyDeliveryCodec.descriptor(bytes)))
    }
    private fun file(operation: SyncStoreId) = root.resolve("notice-${operation.value}.capsule")
    private fun publish(path: Path, bytes: ByteArray) {
        require(!Files.exists(path, NOFOLLOW_LINKS)) { "SYNC_LEDGER_NOTICE_IMMUTABLE" }
        PrivateSyncFile.create(root).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
            }
            Files.move(file.path, path, ATOMIC_MOVE)
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_LEDGER_NOTICE_REENTRY" }; lock.lockInterruptibly()
        try { PrivateSyncFile.checked(root, true); val path = root.resolve("notices.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> PrivateSyncFile.checked(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
