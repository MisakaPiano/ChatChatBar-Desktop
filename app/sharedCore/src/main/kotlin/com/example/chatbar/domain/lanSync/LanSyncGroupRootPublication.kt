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

/** Root's retained ledger/key/notice delivery preparation; never creates a membership decision. */
class LanSyncGroupRootPublication(private val root: Path, private val identity: LanSyncTlsIdentity,
    private val authority: () -> SyncDeviceLedger, private val cipher: (SyncStoreId) -> SyncStreamingCipher,
    private val outbox: (SyncStoreId) -> SyncEncryptedOutbox, private val observe: (String) -> Unit = {}) {
    @Serializable private data class Spec(val group: SyncStoreId, val reference: SyncVersionRef, val epoch: SyncStoreId, val commitment: String,
        val operation: SyncStoreId, val metadataKey: CiphertextObjectKey, val keys: Map<String, SyncStoreId>, val notices: Map<String, SyncStoreId>)
    private val lock: ReentrantLock
    private val trust = authority().trustAnchor
    private fun directory(name: String) = root.resolve(name).also {
        lanSyncSafe(root, true); if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it); lanSyncSafe(it, true)
    }
    private val work = directory("preparations"); private val keyRoot = directory("keys")
    private val noticeRoot = directory("notices"); private val publicationRoot = directory("publications")
    init { lock = locks.computeIfAbsent(root) { ReentrantLock() } }
    private fun keys() = SyncKeyDeliveryQueue(keyRoot, trust, identity.fingerprint, ::rootLedger)
    private fun notices() = SyncLedgerNoticeQueue(noticeRoot, trust, identity.fingerprint, ::rootLedger)
    private fun publisher(epoch: SyncStoreId) = LanSyncGroupPublicationQueue(publicationRoot, rootLedger(), outbox(epoch), identity.fingerprint,
        observe, keyQueue = keys(), currentLedger = ::rootLedger, noticeQueue = notices())
    private fun specPath(reference: SyncVersionRef) = root.resolve("ledger-${reference.id.value}.json")
    private fun rootLedger() = authority().also { ledger ->
        require(ledger.trustAnchor.group == trust.group && ledger.trustAnchor.genesis == trust.genesis &&
            ledger.trustAnchor.ownerCertificate == trust.ownerCertificate && ledger.contains(trust.checkpoint) &&
            ledger.ownerCertificate == java.util.Base64.getEncoder().encodeToString(identity.certificate.encoded) &&
            ledger.current().members[identity.fingerprint]?.active == true) { "SYNC_GROUP_ROOT_PUBLICATION_AUTHORITY" }
    }
    private fun slots(ledger: SyncDeviceLedger) = ledger.history().map { it.epoch }.distinct().flatMap { epoch ->
        ledger.current().members.filterValues { it.active }.keys.map { device -> "${epoch.value}:$device" to (epoch to device) }
    }.toMap()
    fun prepare(): LanSyncGroupPublishPlan = guarded { prepareOwned() }
    private fun prepareOwned(): LanSyncGroupPublishPlan {
        val ledger = rootLedger(); val current = ledger.current(); val path = specPath(current.reference())
        val requiredKeys = slots(ledger); val requiredNotices = SyncLedgerNotice.revocations(ledger)
        val spec = read(current.reference()) ?: Spec(current.group, current.reference(), current.epoch, current.keyCommitment,
            SyncStoreId.random(), CiphertextObjectKey(CiphertextObjectKind.COMMIT, SyncStoreId.random()),
            requiredKeys.keys.sorted().associateWith { SyncStoreId.random() }, requiredNotices.keys.sorted().associateWith { SyncStoreId.random() }).also {
            LanSyncJsonFiles.write(path, Spec.serializer(), it); observe("ROOT_DELIVERY_SPEC_RETAINED")
        }
        require(spec.group == current.group && spec.reference == current.reference() && spec.epoch == current.epoch &&
            spec.commitment == current.keyCommitment && spec.keys.keys == requiredKeys.keys && spec.notices.keys == requiredNotices.keys) { "SYNC_GROUP_ROOT_PUBLICATION_CHANGED" }
        spec.keys.forEach { (slot, operation) -> val (epoch, recipient) = requiredKeys.getValue(slot)
            keys().prepare(operation, cipher(epoch), recipient, identity::signSyncGroupPayload) }
        spec.notices.forEach { (recipient, operation) -> notices().prepare(operation, recipient, identity::signSyncGroupPayload) }
        val key = cipher(current.epoch)
        require(key.group == current.group && key.epoch == current.epoch && key.keyCommitment() == current.keyCommitment) { "SYNC_GROUP_ROOT_PUBLICATION_KEY" }
        val metadata = SyncGroupObjectCodec(work).enqueue(outbox(current.epoch), spec.operation, spec.metadataKey, key,
            SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = current, ledgerHistory = ledger.history().dropLast(1)))
        require(rootLedger().current().reference() == current.reference()) { "SYNC_GROUP_ROOT_PUBLICATION_CHANGED" }
        return publisher(current.epoch).prepare(metadata, emptyList(), emptyMap(), emptyList(), spec.keys, spec.notices)
    }
    fun upload(backend: CiphertextStore, target: SyncDeliveryTarget): LanSyncGroupPublishReceipt = guarded {
        val plan = prepareOwned(); val receipt = publisher(plan.epoch).upload(plan.metadata.operation, backend, target)
        require(rootLedger().current().reference() == plan.reference) { "SYNC_GROUP_ROOT_PUBLICATION_CHANGED" }; receipt
    }
    /** Current signed revision/target only; inspection never signs, prepares or contacts a backend. */
    fun receipt(target: SyncDeliveryTarget): LanSyncGroupPublishReceipt? = guarded {
        val current = rootLedger().current(); val spec = read(current.reference()) ?: return@guarded null
        require(spec.group == current.group && spec.epoch == current.epoch && spec.commitment == current.keyCommitment) { "SYNC_GROUP_ROOT_PUBLICATION_CHANGED" }
        val receipt = publisher(current.epoch).receipt(spec.operation, target)
        require(rootLedger().current().reference() == current.reference()) { "SYNC_GROUP_ROOT_PUBLICATION_CHANGED" }; receipt
    }
    private fun read(reference: SyncVersionRef): Spec? {
        val path = specPath(reference); if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        LanSyncMetadataBudget().validate(path)
        return requireNotNull(LanSyncJsonFiles.read(path, Spec.serializer())).also {
            require(it.group == trust.group && it.reference == reference && it.commitment.matches(Regex("[a-f0-9]{64}")) && it.metadataKey.kind == CiphertextObjectKind.COMMIT &&
                it.keys.keys.all { key -> key.matches(Regex("[a-f0-9]{32}:[a-f0-9]{64}")) } &&
                it.notices.keys.all { device -> device.matches(Regex("[a-f0-9]{64}")) } &&
                (it.keys.values + it.notices.values + it.operation).toSet().size == it.keys.size + it.notices.size + 1) { "SYNC_GROUP_ROOT_PUBLICATION_CHANGED" }
        }
    }
    private fun <T> guarded(work: () -> T): T {
        check(!lock.isHeldByCurrentThread) { "SYNC_GROUP_ROOT_PUBLICATION_REENTRY" }; lock.lockInterruptibly()
        try { lanSyncSafe(root, true); val path = root.resolve("root-publication.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> lanSyncSafe(path); channel.lock().use { return work() } }
        } finally { lock.unlock() }
    }
    companion object { private val locks = ConcurrentHashMap<Path, ReentrantLock>() }
}
