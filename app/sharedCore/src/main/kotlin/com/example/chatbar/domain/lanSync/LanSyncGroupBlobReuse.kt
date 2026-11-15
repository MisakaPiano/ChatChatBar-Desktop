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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A retained local locator, never a plaintext-derived remote object ID or a publication receipt. */
@Serializable internal data class LanSyncGroupReusableBlob(val owner: SyncStoreId, val epoch: SyncStoreId,
    val commitment: String, val plaintext: SyncPlaintextIdentity, val operation: SyncStoreId, val key: CiphertextObjectKey)
internal data class LanSyncGroupBlobSeed(val owner: SyncStoreId?, val plaintext: SyncPlaintextIdentity,
    val operation: SyncStoreId, val key: CiphertextObjectKey, val mustBeReady: Boolean)

/** Writer-local stable same-key-epoch ciphertext custody. Caller still owns every source authorization. */
internal class LanSyncGroupBlobReuse(private val writerRoot: Path, private val group: SyncStoreId,
    private val device: String, expectedOwners: Set<SyncStoreId>) {
    @Serializable private data class Origin(val format: Int, val group: SyncStoreId, val device: String, val owner: SyncStoreId)
    @Serializable private data class Ready(val slot: LanSyncGroupReusableBlob, val descriptor: CiphertextDescriptor)
    private val lock = locks.computeIfAbsent(writerRoot) { ReentrantLock() }
    private val root = writerRoot.resolve("blob-reuse")
    private val originPath = writerRoot.resolve("blob-reuse-origin.json")
    val owner: SyncStoreId
    init {
        owner = guarded {
            val previous = LanSyncJsonFiles.read(originPath, Origin.serializer())
            if (previous == null) {
                require(expectedOwners.isEmpty()) { "SYNC_GROUP_BLOB_REUSE_ORIGIN_MISSING" }
                if (Files.exists(root, NOFOLLOW_LINKS)) {
                    PrivateSyncFile.checked(root, true)
                    require(Files.newDirectoryStream(root).use { !it.iterator().hasNext() }) { "SYNC_GROUP_BLOB_REUSE_ORIGIN_MISSING" }
                } else Files.createDirectory(root)
                val created = Origin(1, group, device, SyncStoreId.random())
                LanSyncJsonFiles.write(originPath, Origin.serializer(), created)
                created.owner
            } else {
                require(previous.format == 1 && previous.group == group && previous.device == device &&
                    expectedOwners.all { it == previous.owner }) { "SYNC_GROUP_BLOB_REUSE_OWNER_CHANGED" }
                require(Files.exists(root, NOFOLLOW_LINKS)) { "SYNC_GROUP_BLOB_REUSE_CACHE_MISSING" }
                PrivateSyncFile.checked(root, true)
                previous.owner
            }
        }
    }
    private fun name(epoch: SyncStoreId, commitment: String, plaintext: SyncPlaintextIdentity) = lanSyncHash(buildJsonObject {
        put("group", group.value); put("epoch", epoch.value); put("commitment", commitment)
        put("length", plaintext.length); put("sha256", plaintext.sha256)
    })
    private fun slotPath(slot: LanSyncGroupReusableBlob) = root.resolve("${name(slot.epoch, slot.commitment, slot.plaintext)}.json")
    private fun readyPath(slot: LanSyncGroupReusableBlob) = root.resolve("${name(slot.epoch, slot.commitment, slot.plaintext)}.ready.json")

    /** Chooses a complete existing object or forces one new random reservation before use. */
    fun select(cipher: SyncStreamingCipher, plaintext: SyncPlaintextIdentity, queue: SyncEncryptedOutbox,
        seeds: List<LanSyncGroupBlobSeed>): LanSyncGroupReusableBlob = guarded {
        require(cipher.group == group) { "SYNC_GROUP_BLOB_REUSE_CONTEXT" }
        val commitment = cipher.keyCommitment()
        val path = root.resolve("${name(cipher.epoch, commitment, plaintext)}.json")
        val prior = LanSyncJsonFiles.read(path, LanSyncGroupReusableBlob.serializer())
        if (prior != null) {
            require(prior.owner == owner && prior.epoch == cipher.epoch && prior.commitment == commitment &&
                prior.plaintext == plaintext && prior.key.kind == CiphertextObjectKind.BLOB) { "SYNC_GROUP_BLOB_REUSE_SLOT_CHANGED" }
            require(seeds.filter { it.plaintext == plaintext && it.owner != null }.all {
                it.owner == owner && it.operation == prior.operation && it.key == prior.key
            }) { "SYNC_GROUP_BLOB_REUSE_SLOT_CHANGED" }
            readyOwned(prior, queue, seeds.any { it.operation == prior.operation && it.key == prior.key && it.mustBeReady })
            return@guarded prior
        }
        val compatible = seeds.filter { it.plaintext == plaintext }.sortedBy { it.operation.value }
        require(compatible.none { it.owner != null }) { "SYNC_GROUP_BLOB_REUSE_SLOT_MISSING" }
        val legacy = compatible.firstOrNull()
        require(legacy == null || legacy.key.kind == CiphertextObjectKind.BLOB) { "SYNC_GROUP_BLOB_REUSE_SLOT_CHANGED" }
        val slot = LanSyncGroupReusableBlob(owner, cipher.epoch, commitment, plaintext,
            legacy?.operation ?: SyncStoreId.random(), legacy?.key ?: CiphertextObjectKey(CiphertextObjectKind.BLOB, SyncStoreId.random()))
        // Adopt only an original retained slot, never a replacement chosen after a failed raw check.
        if (legacy != null) verifyEntry(slot, queue, legacy.mustBeReady)
        LanSyncJsonFiles.write(path, LanSyncGroupReusableBlob.serializer(), slot)
        readyOwned(slot, queue, legacy?.mustBeReady == true)
        slot
    }
    fun requireSlot(cipher: SyncStreamingCipher, plaintext: SyncPlaintextIdentity, operation: SyncStoreId,
        key: CiphertextObjectKey): LanSyncGroupReusableBlob = guarded {
        val expected = LanSyncGroupReusableBlob(owner, cipher.epoch, cipher.keyCommitment(), plaintext, operation, key)
        require(cipher.group == group && LanSyncJsonFiles.read(slotPath(expected), LanSyncGroupReusableBlob.serializer()) == expected) {
            "SYNC_GROUP_BLOB_REUSE_SLOT_MISSING"
        }
        expected
    }
    fun entry(slot: LanSyncGroupReusableBlob, queue: SyncEncryptedOutbox, mustBeReady: Boolean): SyncOutboxEntry? = guarded {
        readyOwned(slot, queue, mustBeReady)
    }
    fun complete(slot: LanSyncGroupReusableBlob, cipher: SyncStreamingCipher, queue: SyncEncryptedOutbox, source: Path): SyncOutboxEntry = guarded {
        require(slot.owner == owner && cipher.group == group && cipher.epoch == slot.epoch && cipher.keyCommitment() == slot.commitment &&
            LanSyncJsonFiles.read(slotPath(slot), LanSyncGroupReusableBlob.serializer()) == slot) { "SYNC_GROUP_BLOB_REUSE_SLOT_CHANGED" }
        readyOwned(slot, queue, false)?.let { return@guarded it }
        SyncGroupBlobStore(group, writerRoot).enqueue(queue, slot.operation, slot.key, cipher, source, slot.plaintext)
        requireNotNull(readyOwned(slot, queue, true))
    }
    private fun readyOwned(slot: LanSyncGroupReusableBlob, queue: SyncEncryptedOutbox, required: Boolean): SyncOutboxEntry? {
        require(slot.owner == owner && slot.key.kind == CiphertextObjectKind.BLOB) { "SYNC_GROUP_BLOB_REUSE_SLOT_CHANGED" }
        val seal = LanSyncJsonFiles.read(readyPath(slot), Ready.serializer())
        require(seal == null || seal.slot == slot) { "SYNC_GROUP_BLOB_REUSE_READY_CHANGED" }
        val entry = verifyEntry(slot, queue, required || seal != null) ?: return null
        if (seal == null) LanSyncJsonFiles.write(readyPath(slot), Ready.serializer(), Ready(slot, entry.request.descriptor))
        else require(seal.descriptor == entry.request.descriptor) { "SYNC_GROUP_BLOB_REUSE_RAW_CHANGED" }
        return entry
    }
    private fun verifyEntry(slot: LanSyncGroupReusableBlob, queue: SyncEncryptedOutbox, required: Boolean): SyncOutboxEntry? {
        // entry() verifies full ciphertext SHA, AEAD EOF, original context and plaintext identity.
        val entry = queue.entry(slot.operation)
        require(entry != null || !required) { "SYNC_GROUP_BLOB_REUSE_RAW_MISSING" }
        entry?.let {
            require(it.context == SyncEncryptionContext(group, slot.epoch, slot.key) && it.plaintext == slot.plaintext &&
                it.request.operationId == slot.operation && it.request.key == slot.key) { "SYNC_GROUP_BLOB_REUSE_RAW_CHANGED" }
        }
        return entry
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_BLOB_REUSE_REENTRY" }; lock.lockInterruptibly()
        try {
            PrivateSyncFile.checked(writerRoot, true)
            FileChannel.open(writerRoot.resolve("blob-reuse.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { return work() }
            }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
