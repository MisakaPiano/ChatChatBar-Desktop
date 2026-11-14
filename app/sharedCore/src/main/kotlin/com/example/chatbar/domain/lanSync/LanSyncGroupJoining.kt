package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64
import java.util.Collections
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

enum class LanSyncGroupJoinStage { PREPARING, PREPARED, PROOF_ATTEMPTED, WAITING_FOR_APPROVAL, KEYS_ADMITTED, NATIVE_ENROLLED, READY }
data class LanSyncGroupJoiningProfile(val stage: LanSyncGroupJoinStage, val trust: SyncGroupTrust,
    val device: String, val localReplica: SyncDeliveryTarget)
data class LanSyncGroupJoiningResult(val profile: LanSyncGroupJoiningProfile, val node: LanSyncGroupNode?, val waitingCode: String? = null)

/** Explicit joining retains its recipient before any proof; normal reopen never recreates origins. */
class LanSyncGroupJoining(private val parent: Path, private val workspace: LanSyncWorkspace,
    private val platform: LanSyncPlatform, private val identity: LanSyncTlsIdentity,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray,
    private val validateResource: (String, Path, LanSyncRecord) -> Unit, private val observe: (String) -> Unit = {}) {
    @Serializable private data class Record(val format: Int, val operation: SyncStoreId, val stage: LanSyncGroupJoinStage,
        val offer: LanSyncGroupInviteOffer, val certificate: String, val recipient: String?, val localReplica: SyncDeliveryTarget,
        val replicaIdentity: String? = null, val projection: SyncPlaintextIdentity? = null)
    @Serializable private data class Projection(val projection: LanSyncProjection, val sourceFiles: Map<String, String>,
        val checkpoint: SyncVersionRef, val heads: Set<SyncVersionRef>)
    private val root = parent.resolve("joining")
    private val certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
    private val mutex = LanSyncGroupEnrollment.locks.computeIfAbsent(parent) { kotlinx.coroutines.sync.Mutex() }
    init { lanSyncSafe(parent, true) }
    private fun directory(name: String, create: Boolean): Path = root.resolve(name).also {
        lanSyncSafe(root, true)
        if (create && !Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it)
        lanSyncSafe(it, true)
    }
    private fun profile(record: Record) = LanSyncGroupJoiningProfile(record.stage, record.offer.trust, identity.fingerprint, record.localReplica)
    suspend fun profile(): LanSyncGroupJoiningProfile? = guarded { read()?.let(::profile) }

    suspend fun prepare(code: String, admission: (LanSyncGroupInviteOffer) -> Unit): LanSyncGroupJoiningProfile = guarded {
        require(!Files.exists(parent.resolve("profile.protected"), NOFOLLOW_LINKS) &&
            !Files.exists(parent.resolve("creation"), NOFOLLOW_LINKS) && !Files.exists(parent.resolve("abandon.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_FOUNDER_PROFILE_EXISTS" }
        require(read() == null) { "SYNC_GROUP_JOIN_ALREADY_PREPARED" }
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            lanSyncSafe(root, true)
            require(Files.newDirectoryStream(root).use { !it.iterator().hasNext() }) { "SYNC_GROUP_JOIN_EXISTING_EVIDENCE" }
        }
        val offer = LanSyncGroupInviteOffer.parse(code).let { it.copy(bootstrap = it.bootstrap?.let { value ->
            value.copy(dataHeads = Collections.unmodifiableSet(value.dataHeads.toSet()))
        }) }
        requireNotNull(offer.bootstrap) { "SYNC_GROUP_JOIN_BOOTSTRAP_REQUIRED" }
        admission(offer)
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
        val record = Record(1, SyncStoreId.random(), LanSyncGroupJoinStage.PREPARING, offer, certificate, null,
            SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()))
        write(record); observe("GROUP_JOIN_REQUEST_RETAINED")
        profile(finishPreparation(record))
    }
    suspend fun resumePreparation(): LanSyncGroupJoiningProfile = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        profile(if (record.stage == LanSyncGroupJoinStage.PREPARING) finishPreparation(record) else record)
    }
    private fun finishPreparation(record: Record): Record {
        require(record.stage == LanSyncGroupJoinStage.PREPARING && record.recipient == null) { "SYNC_GROUP_JOIN_PREPARATION_CHANGED" }
        val recipient = SyncDeviceKeyCustody(directory("recipient", true), identity.fingerprint, protect, unprotect).createIfAbsent()
        return record.copy(stage = LanSyncGroupJoinStage.PREPARED, recipient = recipient.publicKey.encoded).also {
            write(it); observe("GROUP_JOIN_RECIPIENT_RETAINED")
        }
    }
    private fun recipient(record: Record) = SyncDeviceKeyCustody(directory("recipient", false), identity.fingerprint, protect, unprotect)
        .open(requireNotNull(record.recipient) { "SYNC_GROUP_JOIN_PREPARATION_INCOMPLETE" })
    private fun custody(record: Record, create: Boolean) = SyncGroupCustody(directory("custody", create), record.offer.trust.group,
        recipient(record), protect, unprotect)

    suspend fun prove(name: String): LanSyncGroupInviteReceipt = prove(name, null)
    internal suspend fun prove(name: String, connect: ((LanSyncGroupInviteOffer) -> SSLSocket)?): LanSyncGroupInviteReceipt = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        require(record.stage in setOf(LanSyncGroupJoinStage.PREPARED, LanSyncGroupJoinStage.PROOF_ATTEMPTED)) { "SYNC_GROUP_JOIN_PROOF_ALREADY_SENT" }
        record.offer.validate()
        // A lost reply may already have led to root approval. Never discard/recreate this recipient.
        val attempted = record.copy(stage = LanSyncGroupJoinStage.PROOF_ATTEMPTED)
        write(attempted); observe("GROUP_JOIN_PROOF_ATTEMPT_RETAINED")
        val result = if (connect == null) LanSyncGroupInviteClient.exchange(record.offer, identity, recipient(record), name)
            else LanSyncGroupInviteClient.exchange(record.offer, identity, recipient(record), name, { connect(record.offer) })
        write(attempted.copy(stage = LanSyncGroupJoinStage.WAITING_FOR_APPROVAL)); observe("GROUP_JOIN_PROOF_SENT")
        result
    }

    /** Backend is a runtime-owned route, never credentials serialized into invitations/profile. */
    suspend fun activate(backend: CiphertextStore): LanSyncGroupJoiningResult = guarded {
        var record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        if (record.stage == LanSyncGroupJoinStage.READY) return@guarded LanSyncGroupJoiningResult(profile(record), openOwned(record))
        require(record.stage !in setOf(LanSyncGroupJoinStage.PREPARING, LanSyncGroupJoinStage.PREPARED)) { "SYNC_GROUP_JOIN_PROOF_REQUIRED" }
        val bootstrap = requireNotNull(record.offer.bootstrap)
        val bootstrapRoot = directory("bootstrap", record.replicaIdentity == null)
        val replicaPath = bootstrapRoot.resolve("ciphertext-mirror")
        record.replicaIdentity?.let { require(lanSyncUtf8(lanSyncRead(replicaPath.resolve("store.id"), 32)) == it) { "SYNC_GROUP_JOIN_REPLICA_CHANGED" } }
        val alreadyAdmitted = record.stage.ordinal >= LanSyncGroupJoinStage.KEYS_ADMITTED.ordinal
        if (alreadyAdmitted) require(Files.isRegularFile(root.resolve("custody/group.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_JOIN_CUSTODY_MISSING" }
        val custody = custody(record, !alreadyAdmitted)
        if (alreadyAdmitted) custody.ledger()
        val inbox = SyncGroupInbox(bootstrapRoot, backend, custody, recipient(record))
        if (record.replicaIdentity == null) {
            record = record.copy(replicaIdentity = lanSyncUtf8(lanSyncRead(replicaPath.resolve("store.id"), 32)))
            write(record)
        }
        var cursor: CommitCursor? = null
        do { cursor = inbox.discover(cursor).nextCursor } while (cursor != null)
        val inspection = inbox.prepareEnrollment(record.offer.trust)
        if (inspection.stage != SyncGroupBootstrapStage.READY) return@guarded LanSyncGroupJoiningResult(profile(record), null,
            inspection.code ?: "SYNC_GROUP_JOIN_${inspection.stage}")
        val evidence = requireNotNull(inspection.evidence)
        require(evidence.records.last().coveredTypes == LanSyncScope.schemas.keys) { "SYNC_GROUP_PROFILE_PARTIAL_SCOPE_UNSUPPORTED" }
        val ledger = custody.enrollWithKeys(evidence.trust, evidence.records, evidence.keys)
        require(ledger.current().members[identity.fingerprint]?.certificate == certificate) { "SYNC_GROUP_JOIN_CERTIFICATE_CHANGED" }
        val owner = ledger.current().members.getValue(bootstrap.vault.owner)
        require(SyncRecipientPublicKey.parse(owner.recipientKey).hash == bootstrap.vault.recipientKey) { "SYNC_GROUP_JOIN_VAULT_CHANGED" }
        if (record.stage.ordinal < LanSyncGroupJoinStage.KEYS_ADMITTED.ordinal) {
            record = record.copy(stage = LanSyncGroupJoinStage.KEYS_ADMITTED); write(record); observe("GROUP_JOIN_KEYS_ADMITTED")
        }
        val replica = FileCiphertextStore(replicaPath)
        val vault = ByteArrayOutputStream()
        val bounded = object : java.io.OutputStream() {
            override fun write(value: Int) { require(vault.size() < 272 * 1024) { "SYNC_GROUP_JOIN_VAULT_BUDGET" }; vault.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(length >= 0 && vault.size().toLong() + length <= 272L * 1024) { "SYNC_GROUP_JOIN_VAULT_BUDGET" }; vault.write(bytes, offset, length)
            }
        }
        requireNotNull(backend.readTo(bootstrap.vault.key, bounded, bootstrap.vaultDescriptor)) { "SYNC_GROUP_JOIN_VAULT_MISSING" }
        val vaultBytes = vault.toByteArray()
        require(vaultBytes.size.toLong() == bootstrap.vaultDescriptor.length && lanSyncHash(vaultBytes) == bootstrap.vaultDescriptor.sha256.hex) { "SYNC_GROUP_JOIN_VAULT_CHANGED" }
        replica.put(CiphertextPutRequest(bootstrap.vault.key, record.operation, bootstrap.vaultDescriptor), vaultBytes.inputStream())
        val reconciled = inbox.reconcile()
        require(!reconciled.trustBlocked) { "SYNC_GROUP_JOIN_TRUST_BLOCKED" }
        val history = requireNotNull(reconciled.history) { "SYNC_GROUP_JOIN_HISTORY_MISSING" }
        if (bootstrap.dataHeads.any { !history.contains(it) }) return@guarded LanSyncGroupJoiningResult(profile(record), null, "SYNC_GROUP_JOIN_INITIAL_DATA_MISSING")
        val projection = if (record.projection != null) readProjection(record) else {
            val service = LanSyncGroupDependencies(record.offer.trust.group, backend, custody, directory("projection-work", true), workspace.resourceStore, validateResource)
            val presets = mutableListOf<LanSyncRecord>()
            val versions = reconciled.authenticatedVersions.groupBy { requireNotNull(it.envelope.version).reference() }
            val units = history.admittedVersions().flatMap { it.changes.keys }.filter {
                it.startsWith("character_cards/") || it.startsWith("format_cards/") || it.startsWith("world_books/")
            }.toSortedSet()
            for (unit in units) {
                val frontiers = history.frontier(unit)
                if (frontiers.size != 1) return@guarded LanSyncGroupJoiningResult(profile(record), null, "SYNC_GROUP_JOIN_PRESET_REVIEW_REQUIRED")
                val frontier = frontiers.single()
                if (frontier.change.content == null) continue
                val candidates = requireNotNull(versions[frontier.version]) { "SYNC_GROUP_JOIN_PRESET_EVIDENCE_MISSING" }
                val metadata = candidates.firstOrNull { it.context.epoch == ledger.current().epoch } ?: candidates.first()
                val result = service.inspect(reconciled, metadata, unit)
                if (result.stage != LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE) return@guarded LanSyncGroupJoiningResult(profile(record), null,
                    result.code ?: "SYNC_GROUP_JOIN_PRESET_${result.stage}")
                presets += requireNotNull(result.available).decoded.payload.records
            }
            var pinned: Projection? = null
            platform.exclusive {
                workspace.capture(identity.fingerprint).use { capture ->
                    val value = Projection(LanSyncPresetProjection.propose(presets, capture.snapshot.records), capture.sourceFiles,
                        ledger.current().reference(), history.heads())
                    LanSyncJsonFiles.write(root.resolve("projection.json"), Projection.serializer(), value)
                    val identity = identity(root.resolve("projection.json"))
                    record = record.copy(projection = identity); write(record); pinned = value
                }
            }
            observe("GROUP_JOIN_PROJECTION_RETAINED")
            requireNotNull(pinned)
        }
        if (record.stage.ordinal < LanSyncGroupJoinStage.NATIVE_ENROLLED.ordinal) {
            require(projection.checkpoint == custody.ledger().current().reference() && projection.heads == history.heads()) { "SYNC_GROUP_JOIN_PROJECTION_VIEW_CHANGED" }
            val node = node(record, projection.projection, backend)
            node.initializeNewEnrollment(projection.sourceFiles)
            record = record.copy(stage = LanSyncGroupJoinStage.NATIVE_ENROLLED); write(record); observe("GROUP_JOIN_NATIVE_ENROLLED")
        }
        val node = node(record, projection.projection, backend)
        cursor = null
        do { cursor = node.discover(replica, cursor).nextCursor } while (cursor != null)
        node.inspect()
        record = record.copy(stage = LanSyncGroupJoinStage.READY); write(record); observe("GROUP_JOIN_READY")
        LanSyncGroupJoiningResult(profile(record), node)
    }
    suspend fun open(): LanSyncGroupNode? = guarded {
        val record = read() ?: return@guarded null
        require(record.stage == LanSyncGroupJoinStage.READY) { "SYNC_GROUP_JOIN_INCOMPLETE" }
        openOwned(record)
    }
    private suspend fun openOwned(record: Record): LanSyncGroupNode {
        val path = directory("bootstrap", false).resolve("ciphertext-mirror")
        require(lanSyncUtf8(lanSyncRead(path.resolve("store.id"), 32)) == record.replicaIdentity) { "SYNC_GROUP_JOIN_REPLICA_CHANGED" }
        val projection = readProjection(record)
        return node(record, projection.projection, FileCiphertextStore(path)).also { it.inspect() }
    }
    private fun node(record: Record, projection: LanSyncProjection, backend: CiphertextStore) = LanSyncGroupNode(directory("node", true), workspace,
        platform, identity, record.offer.trust, custody(record, false), recipient(record), backend, protect, unprotect, { emptySet() }, validateResource, projection, observe)
    private fun identity(path: Path) = SyncPlaintextIdentity(Files.size(path), LanSyncResourceStore.hashFile(path, Files.size(path)))
    private fun readProjection(record: Record): Projection {
        val file = root.resolve("projection.json"); require(identity(file) == record.projection) { "SYNC_GROUP_JOIN_PROJECTION_CHANGED" }
        LanSyncMetadataBudget().validate(file)
        return requireNotNull(LanSyncJsonFiles.read(file, Projection.serializer(), record.projection!!.sha256)).also { it.projection.validate() }
    }
    private fun read(): Record? {
        val path = root.resolve("profile.protected")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(!Files.exists(parent.resolve("profile.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_MULTIPLE_PROFILES" }
        val plain = unprotect(lanSyncRead(path, 256 * 1024))
        try { return lanSyncJson.decodeFromString(Record.serializer(), lanSyncUtf8(plain)).also {
            require(it.format == 1 && it.certificate == certificate && it.localReplica.kind == SyncDeliveryKind.LOCAL_REPLICA &&
                (it.stage == LanSyncGroupJoinStage.PREPARING || it.recipient != null)) { "SYNC_GROUP_JOIN_PROFILE_CHANGED" }
            require(it.stage.ordinal < LanSyncGroupJoinStage.NATIVE_ENROLLED.ordinal || it.projection != null && it.replicaIdentity != null) { "SYNC_GROUP_JOIN_PROFILE_CHANGED" }
            requireNotNull(it.offer.bootstrap).validate(it.offer.trust, it.offer.port)
        } } finally { plain.fill(0) }
    }
    private fun write(record: Record) {
        val bytes = lanSyncJson.encodeToString(Record.serializer(), record).toByteArray(Charsets.UTF_8)
        try { require(bytes.size <= 128 * 1024) { "SYNC_GROUP_JOIN_PROFILE_BUDGET" }; lanSyncAtomicWrite(root.resolve("profile.protected"), protect(bytes)) }
        finally { bytes.fill(0) }
    }
    private suspend fun <T> guarded(work: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            lanSyncSafe(parent, true)
            FileChannel.open(parent.resolve("profile.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> channel.lock().use { work() } }
        }
    }
}
