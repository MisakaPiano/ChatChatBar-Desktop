package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class LanSyncManagementRecoveryStage { PREPARING, KEYS_ADMITTED, READY }
data class LanSyncManagementRecoveryProfile(val stage: LanSyncManagementRecoveryStage, val trust: SyncGroupTrust,
    val localReplica: SyncDeliveryTarget)
fun interface LanSyncManagementRecoveryAdmission { fun requireApproved(trust: SyncGroupTrust) }

/** Code-authorized management only. It owns no daily TLS/source identity or native user data. */
class LanSyncGroupManagementRecovery(private val root: Path,
    private val knownCheckpoint: () -> SyncVersionRef? = { null }, private val observe: (String) -> Unit = {}) : AutoCloseable {
    @Serializable private data class Record(val format: Int, val stage: LanSyncManagementRecoveryStage,
        val trust: SyncGroupTrust, val vault: SyncManagementVaultContext, val vaultDescriptor: CiphertextDescriptor,
        val vaultOperation: SyncStoreId, val localReplica: SyncDeliveryTarget, val replicaIdentity: String? = null,
        val inboxIdentity: String? = null, val requiredCheckpoint: SyncVersionRef? = null)
    private class Active(val session: SyncManagementSession, val generation: Long)
    private val lifecycle = Any()
    private var active: Active? = null
    private var generation = 0L
    private var closed = false
    private val mutex = locks.computeIfAbsent(root) { Mutex() }
    init { lanSyncSafe(root, true) }
    private fun profile(record: Record) = LanSyncManagementRecoveryProfile(record.stage, record.trust, record.localReplica)
    private fun directory(name: String, create: Boolean): Path = root.resolve(name).also {
        lanSyncSafe(root, true)
        if (create && !Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it)
        lanSyncSafe(it, true)
    }
    private fun requireActive(): Active = synchronized(lifecycle) {
        check(!closed) { "SYNC_GROUP_MANAGEMENT_CLOSED" }
        requireNotNull(active) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
    }.also { require(it.session.isUnlocked) { "SYNC_GROUP_MANAGEMENT_LOCKED" } }
    private fun requireCurrent(value: Active) {
        synchronized(lifecycle) { check(!closed && active === value && generation == value.generation) { "SYNC_GROUP_MANAGEMENT_LOCKED" } }
        check(value.session.isUnlocked) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
    }
    val isUnlocked: Boolean get() = synchronized(lifecycle) { !closed && active?.session?.isUnlocked == true }
    private fun protect(value: Active, bytes: ByteArray): ByteArray = synchronized(lifecycle) { requireCurrent(value); value.session.protectJournal(bytes) }
    private fun unprotect(value: Active, bytes: ByteArray): ByteArray = synchronized(lifecycle) { requireCurrent(value); value.session.unprotectJournal(bytes) }
    private fun requireKnownPrefix(ledger: SyncDeviceLedger) {
        knownCheckpoint()?.let { require(ledger.contains(it)) { "SYNC_GROUP_MANAGEMENT_KNOWN_CHECKPOINT_MISSING" } }
    }
    private fun sign(value: Active, bytes: ByteArray, ledger: SyncDeviceLedger): ByteArray {
        requireKnownPrefix(ledger)
        return synchronized(lifecycle) { requireCurrent(value); value.session.signRootPayload(bytes) }
    }
    private fun install(session: SyncManagementSession, expected: Long): Active {
        var previous: Active? = null
        val installed = synchronized(lifecycle) {
            check(!closed && expected == generation) { "SYNC_GROUP_MANAGEMENT_LOCKED" }
            previous = active
            Active(session, generation).also { active = it }
        }
        previous?.session?.close()
        return installed
    }
    private fun descriptor(bytes: ByteArray) = CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes)))
    private fun write(value: Active, record: Record) {
        requireCurrent(value)
        val bytes = lanSyncJson.encodeToString(Record.serializer(), record).toByteArray(Charsets.UTF_8)
        try { lanSyncAtomicWrite(root.resolve("profile.protected"), protect(value, bytes)) } finally { bytes.fill(0) }
    }
    private fun read(value: Active): Record? {
        val file = root.resolve("profile.protected")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        val bytes = unprotect(value, lanSyncRead(file, 512 * 1024))
        try {
            val record = try { lanSyncJson.decodeFromString(Record.serializer(), lanSyncUtf8(bytes)) }
                catch (_: Exception) { error("SYNC_GROUP_MANAGEMENT_PROFILE_INVALID") }
            require(record.format == 1 && record.trust == value.session.trust && record.vault == value.session.seal(value.session.trust).context &&
                record.vaultDescriptor.length in 1..272L * 1024 && record.localReplica.kind == SyncDeliveryKind.LOCAL_REPLICA) { "SYNC_GROUP_MANAGEMENT_PROFILE_CHANGED" }
            require(descriptor(lanSyncRead(root.resolve("management-vault.bin"), 272 * 1024)) == record.vaultDescriptor) { "SYNC_GROUP_MANAGEMENT_VAULT_CHANGED" }
            return record
        } finally { bytes.fill(0) }
    }

    /** Explicit import: the code alone is insufficient; the authenticated encrypted vault is required. */
    suspend fun recover(code: String, vaultBytes: ByteArray, backend: CiphertextStore,
        admission: LanSyncManagementRecoveryAdmission, knownTrust: SyncGroupTrust? = null): LanSyncManagementRecoveryProfile {
        val expected = synchronized(lifecycle) { check(!closed); generation }
        val session = SyncManagementSession.open(code, vaultBytes)
        try {
            val vault = session.seal(session.trust)
            knownTrust?.let { known -> require(known.group == session.trust.group && known.genesis == session.trust.genesis &&
                known.ownerCertificate == session.trust.ownerCertificate) { "SYNC_GROUP_MANAGEMENT_TRUST_CHANGED" } }
            admission.requireApproved(session.trust)
            val value = install(session, expected)
            return guarded {
                require(!Files.exists(root.resolve("profile.protected"), NOFOLLOW_LINKS) &&
                    !Files.exists(root.resolve("custody"), NOFOLLOW_LINKS) && !Files.exists(root.resolve("root-actions"), NOFOLLOW_LINKS)) { "SYNC_GROUP_MANAGEMENT_RECOVERY_EXISTS" }
                require(Files.newDirectoryStream(root).use { files -> files.all { it.fileName.toString() in setOf("management.lock", "management-vault.bin") } }) {
                    "SYNC_GROUP_MANAGEMENT_FOREIGN_PROFILE"
                }
                val retained = root.resolve("management-vault.bin")
                if (Files.exists(retained, NOFOLLOW_LINKS)) require(lanSyncRead(retained, 272 * 1024).contentEquals(vault.bytes())) { "SYNC_GROUP_MANAGEMENT_VAULT_CHANGED" }
                else lanSyncAtomicWrite(retained, vault.bytes())
                val record = Record(1, LanSyncManagementRecoveryStage.PREPARING, session.trust, vault.context, descriptor(vault.bytes()),
                    SyncStoreId.random(), SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()), requiredCheckpoint = knownTrust?.checkpoint)
                write(value, record); observe("MANAGEMENT_RECOVERY_PREPARED")
                finishImport(value, record, backend)
            }
        } catch (failure: Throwable) { session.close(); synchronized(lifecycle) { if (active?.session === session) active = null }; throw failure }
    }

    /** A retained explicit import can resume; ordinary READY unlock never recreates missing origins. */
    suspend fun resumeImport(code: String, backend: CiphertextStore): LanSyncManagementRecoveryProfile = unlockOwned(code) { value, record ->
        if (record.stage == LanSyncManagementRecoveryStage.READY) { checkedOwners(value, record); profile(record) }
        else finishImport(value, record, backend)
    }
    suspend fun unlock(code: String): LanSyncManagementRecoveryProfile = unlockOwned(code) { value, record ->
        require(record.stage == LanSyncManagementRecoveryStage.READY) { "SYNC_GROUP_MANAGEMENT_IMPORT_INCOMPLETE" }
        checkedOwners(value, record, checkKnown = true); profile(record)
    }
    private suspend fun <T> unlockOwned(code: String, work: (Active, Record) -> T): T {
        val expected = synchronized(lifecycle) { check(!closed); generation }
        val session = SyncManagementSession.open(code, lanSyncRead(root.resolve("management-vault.bin"), 272 * 1024))
        try {
            val value = install(session, expected)
            return guarded { work(value, requireNotNull(read(value)) { "SYNC_GROUP_MANAGEMENT_PROFILE_MISSING" }) }
        } catch (failure: Throwable) { session.close(); synchronized(lifecycle) { if (active?.session === session) active = null }; throw failure }
    }
    suspend fun profile(): LanSyncManagementRecoveryProfile = guarded {
        val value = requireActive(); profile(requireNotNull(read(value)) { "SYNC_GROUP_MANAGEMENT_PROFILE_MISSING" })
    }
    private fun custody(value: Active, record: Record, recipient: SyncDeviceEncryptionKey, create: Boolean) =
        SyncGroupCustody(directory("custody", create), record.trust.group, recipient, { protect(value, it) }, { unprotect(value, it) })
    private fun journal(value: Active, record: Record, create: Boolean) = SyncRootLedgerJournal(directory("root-actions", create), record.trust,
        value.session.ownerCertificate, { protect(value, it) }, { unprotect(value, it) }, observe)
    private fun inbox(value: Active, record: Record, recipient: SyncDeviceEncryptionKey, store: CiphertextStore, create: Boolean): SyncGroupInbox {
        record.inboxIdentity?.let { expected -> require(lanSyncUtf8(lanSyncRead(root.resolve("inbox/ciphertext-mirror/store.id"), 32)) == expected) { "SYNC_GROUP_MANAGEMENT_INBOX_CHANGED" } }
        return SyncGroupInbox(directory("inbox", create), store, custody(value, record, recipient, create), recipient)
    }
    private fun discover(inbox: SyncGroupInbox) {
        var cursor: CommitCursor? = null
        do {
            val page = inbox.discover(cursor)
            require(page.observations.none { it.stage == SyncInboxStage.INVALID_OBJECT }) { "SYNC_GROUP_MANAGEMENT_DISCOVERY_FAILED" }
            cursor = page.nextCursor
        } while (cursor != null)
    }
    private fun validateBootstrap(value: Active, record: Record, inbox: SyncGroupInbox, known: SyncVersionRef? = null): SyncGroupBootstrapEvidence {
        requireCurrent(value)
        val inspected = inbox.prepareEnrollment(record.trust)
        require(inspected.stage == SyncGroupBootstrapStage.READY) { "SYNC_GROUP_MANAGEMENT_${inspected.stage}" }
        val evidence = requireNotNull(inspected.evidence)
        val ledger = SyncDeviceLedger(record.trust).also { target -> evidence.records.forEach { target.offer(it) } }
        requireKnownPrefix(ledger)
        val member = ledger.current().members[record.vault.owner]
        require(known == null || ledger.contains(known)) { "SYNC_GROUP_MANAGEMENT_KNOWN_CHECKPOINT_MISSING" }
        require(record.requiredCheckpoint == null || ledger.contains(record.requiredCheckpoint)) { "SYNC_GROUP_MANAGEMENT_KNOWN_CHECKPOINT_MISSING" }
        require(member?.active == true && member.certificate == value.session.ownerCertificate &&
            member.recipientKey == value.session.recipientPublicKey.encoded && value.session.recipientPublicKey.hash == record.vault.recipientKey) { "SYNC_GROUP_MANAGEMENT_OWNER_CHANGED" }
        return evidence
    }
    private fun finishImport(value: Active, original: Record, backend: CiphertextStore): LanSyncManagementRecoveryProfile = value.session.withRecipient { recipient ->
        var record = original
        val alreadyAdmitted = record.stage != LanSyncManagementRecoveryStage.PREPARING
        if (alreadyAdmitted) require(Files.isRegularFile(root.resolve("custody/group.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_MANAGEMENT_CUSTODY_MISSING" }
        val custody = custody(value, record, recipient, !alreadyAdmitted)
        val known = if (alreadyAdmitted) custody.ledger().current().reference() else null
        val inbox = inbox(value, record, recipient, backend, true)
        if (record.inboxIdentity == null) {
            record = record.copy(inboxIdentity = lanSyncUtf8(lanSyncRead(root.resolve("inbox/ciphertext-mirror/store.id"), 32)))
            write(value, record)
        }
        discover(inbox)
        val evidence = validateBootstrap(value, record, inbox, known)
        custody.enrollWithKeys(evidence.trust, evidence.records, evidence.keys)
        if (record.stage == LanSyncManagementRecoveryStage.PREPARING) {
            record = record.copy(stage = LanSyncManagementRecoveryStage.KEYS_ADMITTED)
            write(value, record); observe("MANAGEMENT_RECOVERY_KEYS_ADMITTED")
        }
        val journal = journal(value, record, true)
        if (!Files.exists(root.resolve("root-actions/origin.protected"), NOFOLLOW_LINKS)) journal.initializeNewEnrollment(custody.ledger())
        val state = journal.state()
        require(state.completedSequence == 0L && state.last == null && state.pendingOperation == null) { "SYNC_GROUP_MANAGEMENT_IMPORT_ALREADY_USED" }
        journal.observeAdmitted(custody.ledger())
        val local = local(record, true)
        if (record.replicaIdentity == null) {
            record = record.copy(replicaIdentity = lanSyncUtf8(lanSyncRead(root.resolve("local-replica/store.id"), 32)))
            write(value, record)
        }
        publish(value, record, custody, local, record.localReplica)
        record = record.copy(stage = LanSyncManagementRecoveryStage.READY)
        write(value, record); observe("MANAGEMENT_RECOVERY_READY")
        profile(record)
    }
    private fun local(record: Record, create: Boolean): FileCiphertextStore {
        val path = directory("local-replica", create)
        record.replicaIdentity?.let { expected -> require(lanSyncUtf8(lanSyncRead(path.resolve("store.id"), 32)) == expected) { "SYNC_GROUP_MANAGEMENT_REPLICA_CHANGED" } }
        return FileCiphertextStore(path)
    }
    private fun checkedOwners(value: Active, record: Record, checkKnown: Boolean = false) = value.session.withRecipient { recipient ->
        require(record.stage == LanSyncManagementRecoveryStage.READY && record.replicaIdentity != null && record.inboxIdentity != null) { "SYNC_GROUP_MANAGEMENT_IMPORT_INCOMPLETE" }
        require(Files.isRegularFile(root.resolve("custody/group.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_MANAGEMENT_CUSTODY_MISSING" }
        require(Files.isRegularFile(root.resolve("root-actions/origin.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_MANAGEMENT_ORIGIN_MISSING" }
        val custody = custody(value, record, recipient, false)
        val ledger = custody.ledger()
        if (checkKnown) requireKnownPrefix(ledger)
        ledger.history().distinctBy { it.epoch }.forEach { custody.key(it.epoch) }
        journal(value, record, false).state(); local(record, false)
        require(lanSyncUtf8(lanSyncRead(root.resolve("inbox/ciphertext-mirror/store.id"), 32)) == record.inboxIdentity) { "SYNC_GROUP_MANAGEMENT_INBOX_CHANGED" }
    }
    private fun ready(value: Active, checkKnown: Boolean = true) = requireNotNull(read(value)) { "SYNC_GROUP_MANAGEMENT_PROFILE_MISSING" }
        .also { checkedOwners(value, it, checkKnown) }

    /** A fresh verified roster for the caller's real daily invitation transport, never a daily identity. */
    suspend fun ledger(): SyncDeviceLedger = guarded {
        val value = requireActive(); val record = ready(value)
        value.session.withRecipient { recipient -> custody(value, record, recipient, false).ledger() }
    }

    suspend fun prepareMember(peer: LanSyncGroupInvitedPeer, invitation: LanSyncGroupInvitation,
        operation: SyncStoreId = SyncStoreId.random()): LanSyncGroupMemberPreview = guarded {
        val value = requireActive(); val record = ready(value)
        value.session.withRecipient { recipient ->
            val current = custody(value, record, recipient, false).ledger().current()
            invitation.requireCurrent(peer)
            require(peer.trust.group == record.trust.group && peer.trust.genesis == record.trust.genesis &&
                peer.trust.ownerCertificate == record.trust.ownerCertificate && peer.trust.checkpoint == current.reference() &&
                peer.member.active && peer.device !in current.members && current.members.values.none { it.recipientKey == peer.member.recipientKey }) {
                "SYNC_GROUP_MEMBER_PROOF_CHANGED"
            }
            val binding = lanSyncHash(buildJsonObject {
                put("action", "ADD_MEMBER"); put("group", record.trust.group.value); put("operation", operation.value)
                put("parent", current.id.value); put("parentHash", current.bodyHash()); put("proof", peer.binding)
                put("device", peer.device); put("name", peer.name); put("certificate", peer.member.certificate); put("recipient", peer.member.recipientKey)
            })
            LanSyncGroupMemberPreview(peer.device, peer.name, SyncRootLedgerRequest(operation, current.reference(),
                Collections.unmodifiableMap(current.members + (peer.device to peer.member)),
                Collections.unmodifiableSet(current.coveredTypes.toSet()), Collections.unmodifiableSet(current.acceptedHeads.toSet()), false, binding), peer, invitation)
        }
    }

    /** Approve only the real daily inviter's still-current TLS/HPKE proof, with exact frozen acknowledgment. */
    suspend fun addMember(approval: LanSyncGroupMemberApproval): LanSyncGroupAdministrationReceipt = guarded {
        val value = requireActive(); val record = ready(value)
        value.session.withRecipient { recipient ->
            val custody = custody(value, record, recipient, false); val journal = journal(value, record, false)
            val preview = approval.preview
            journal.observeAdmitted(custody.ledger())
            val commit = journal.commit(preview.request, custody::ledger, SyncRootLedgerAdmission { request ->
                requireKnownPrefix(custody.ledger())
                require(request == preview.request) { "SYNC_GROUP_MEMBER_REVIEW_CHANGED" }
                preview.invitation.requireCurrent(preview.peer)
                val current = custody.ledger().current()
                require(preview.peer.trust.group == record.trust.group && preview.peer.trust.genesis == record.trust.genesis &&
                    preview.peer.trust.ownerCertificate == record.trust.ownerCertificate && preview.peer.trust.checkpoint == current.reference() &&
                    preview.peer.member.active && preview.peer.device !in current.members &&
                    current.members.values.none { it.recipientKey == preview.peer.member.recipientKey } &&
                    request.members == current.members + (preview.device to preview.peer.member) &&
                    request.coveredTypes == current.coveredTypes && request.acceptedHeads == current.acceptedHeads && !request.rotateKey) {
                    "SYNC_GROUP_MEMBER_REVIEW_CHANGED"
                }
            }, { sign(value, it, custody.ledger()) })
            journal.applyToCustody(commit, custody)
            LanSyncGroupAdministrationReceipt(custody.ledger().current(), publish(value, record, custody, local(record, false), record.localReplica))
        }
    }

    suspend fun refresh(backend: CiphertextStore): LanSyncManagementRecoveryProfile = guarded {
        val value = requireActive(); val record = ready(value, checkKnown = false)
        value.session.withRecipient { recipient ->
            val custody = custody(value, record, recipient, false)
            recoverOwned(value, record, custody)
            val inbox = inbox(value, record, recipient, backend, false)
            discover(inbox)
            val evidence = validateBootstrap(value, record, inbox, custody.ledger().current().reference())
            custody.updateWithKeys(evidence.records, evidence.keys)
            requireKnownPrefix(custody.ledger())
            journal(value, record, false).observeAdmitted(custody.ledger())
            publish(value, record, custody, local(record, false), record.localReplica)
            profile(record)
        }
    }
    private fun heads(value: Active, record: Record, custody: SyncGroupCustody, recipient: SyncDeviceEncryptionKey): Set<SyncVersionRef> {
        val inspection = inbox(value, record, recipient, local(record, false), false).reconcile()
        require(!inspection.trustBlocked) { "SYNC_GROUP_MANAGEMENT_TRUST_BLOCKED" }
        require(inspection.ledgerCheckpoint == custody.ledger().current().reference()) { "SYNC_GROUP_MANAGEMENT_CHECKPOINT_CHANGED" }
        return requireNotNull(inspection.history) { "SYNC_GROUP_MANAGEMENT_HISTORY_MISSING" }.heads()
    }
    suspend fun prepareRevocation(device: String, operation: SyncStoreId = SyncStoreId.random()): LanSyncGroupRevocationPreview = guarded {
        val value = requireActive(); val record = ready(value)
        value.session.withRecipient { recipient ->
            val custody = custody(value, record, recipient, false); val current = custody.ledger().current()
            require(device != record.vault.owner && current.members[device]?.active == true) { "SYNC_GROUP_REVOCATION_TARGET" }
            val heads = heads(value, record, custody, recipient)
            val binding = lanSyncHash(buildJsonObject {
                put("action", "RECOVERED_MANAGEMENT_REVOKE_DEVICE"); put("group", record.trust.group.value); put("operation", operation.value)
                put("device", device); put("parent", current.id.value); put("parentHash", current.bodyHash())
                put("heads", JsonArray(heads.sortedBy { it.id.value }.map { JsonPrimitive("${it.id.value}:${it.bodyHash}") }))
            })
            LanSyncGroupRevocationPreview(device, SyncRootLedgerRequest(operation, current.reference(),
                Collections.unmodifiableMap(current.members + (device to current.members.getValue(device).copy(active = false))),
                Collections.unmodifiableSet(current.coveredTypes.toSet()), Collections.unmodifiableSet(current.acceptedHeads + heads), true, binding),
                Collections.unmodifiableSet(heads.toSet()))
        }
    }
    suspend fun revokeDevice(approval: LanSyncGroupRevocationApproval): LanSyncGroupAdministrationReceipt = guarded {
        val value = requireActive(); val record = ready(value)
        value.session.withRecipient { recipient ->
            val custody = custody(value, record, recipient, false); val journal = journal(value, record, false)
            val preview = approval.preview; val currentHeads = heads(value, record, custody, recipient)
            journal.observeAdmitted(custody.ledger())
            val commit = journal.commit(preview.request, custody::ledger, SyncRootLedgerAdmission { request ->
                requireKnownPrefix(custody.ledger())
                val current = custody.ledger().current(); val member = current.members[preview.device]
                require(request == preview.request && currentHeads == preview.heads && request.expectedParent == current.reference() &&
                    preview.device != record.vault.owner && member?.active == true && request.members == current.members + (preview.device to member.copy(active = false)) &&
                    request.coveredTypes == current.coveredTypes && request.acceptedHeads == current.acceptedHeads + currentHeads && request.rotateKey) { "SYNC_GROUP_REVOCATION_REVIEW_CHANGED" }
            }, { sign(value, it, custody.ledger()) })
            journal.applyToCustody(commit, custody)
            LanSyncGroupAdministrationReceipt(custody.ledger().current(), publish(value, record, custody, local(record, false), record.localReplica))
        }
    }
    private fun recoverOwned(value: Active, record: Record, custody: SyncGroupCustody) {
        val journal = journal(value, record, false); val state = journal.state(); val live = custody.ledger()
        if (state.pendingOperation == null && (state.last == null || live.contains(state.last))) journal.observeAdmitted(live)
        var previous: SyncRootLedgerRequest? = null
        while (true) {
            val request = journal.nextRecoveryRequest(custody) ?: break
            require(request != previous) { "SYNC_GROUP_ROOT_RECOVERY_NO_PROGRESS" }; previous = request
            val commit = journal.commit(request, custody::ledger, SyncRootLedgerAdmission { error("SYNC_GROUP_ROOT_UNAPPROVED_RECOVERY") }, { sign(value, it, custody.ledger()) })
            journal.applyToCustody(commit, custody)
        }
    }
    suspend fun resumeAdministration(): LanSyncGroupAdministrationReceipt = guarded {
        val value = requireActive(); val record = ready(value, checkKnown = false)
        value.session.withRecipient { recipient ->
            val custody = custody(value, record, recipient, false); recoverOwned(value, record, custody)
            LanSyncGroupAdministrationReceipt(custody.ledger().current(), publish(value, record, custody, local(record, false), record.localReplica))
        }
    }
    private fun publish(value: Active, record: Record, custody: SyncGroupCustody, store: CiphertextStore, target: SyncDeliveryTarget): LanSyncGroupPublishReceipt {
        requireCurrent(value)
        requireKnownPrefix(custody.ledger())
        Files.newInputStream(root.resolve("management-vault.bin"), NOFOLLOW_LINKS).use { store.put(CiphertextPutRequest(record.vault.key, record.vaultOperation, record.vaultDescriptor), it) }
        require(store.metadata(record.vault.key)?.descriptor == record.vaultDescriptor) { "SYNC_GROUP_MANAGEMENT_VAULT_DELIVERY_INCOMPLETE" }
        val queues = directory("root-queues", true)
        val publisher = LanSyncGroupRootPublication(directory("root-delivery", true), value.session.ownerCertificate, { sign(value, it, custody.ledger()) }, custody::ledger, custody::key,
            { epoch -> SyncEncryptedOutbox(queues.resolve(epoch.value).also { if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it) }, custody.key(epoch)) }, observe)
        return publisher.upload(store, target)
    }
    suspend fun publish(store: CiphertextStore, target: SyncDeliveryTarget): LanSyncGroupAdministrationReceipt = guarded {
        val value = requireActive(); val record = ready(value)
        value.session.withRecipient { recipient ->
            val custody = custody(value, record, recipient, false)
            LanSyncGroupAdministrationReceipt(custody.ledger().current(), publish(value, record, custody, store, target))
        }
    }
    fun lock() {
        val previous = synchronized(lifecycle) { generation++; val result = active; active = null; result }
        previous?.session?.close()
    }
    override fun close() { synchronized(lifecycle) { closed = true }; lock() }
    private suspend fun <T> guarded(work: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(lifecycle) { check(!closed) { "SYNC_GROUP_MANAGEMENT_CLOSED" } }
            lanSyncSafe(root, true)
            val path = root.resolve("management.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> lanSyncSafe(path); channel.lock().use { work() } }
        }
    }
    companion object { private val locks = ConcurrentHashMap<Path, Mutex>() }
}
