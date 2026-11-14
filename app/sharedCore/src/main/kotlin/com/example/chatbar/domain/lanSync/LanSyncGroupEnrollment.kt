package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

enum class LanSyncGroupEnrollmentStage { PREPARED, READY }
data class LanSyncGroupProfile(val stage: LanSyncGroupEnrollmentStage, val trust: SyncGroupTrust, val device: String,
    val coveredTypes: Set<String>, val localReplica: SyncDeliveryTarget)
class LanSyncGroupEnrollmentDraft internal constructor(internal val operation: SyncStoreId,
    val profile: LanSyncGroupProfile, internal val session: SyncManagementSession) : AutoCloseable {
    fun recoveryCode() = session.recoveryCode()
    override fun close() = session.close()
    override fun toString() = "LanSyncGroupEnrollmentDraft(REDACTED)"
}
fun interface LanSyncGroupRecoverySavedAdmission { fun requireSaved(profile: LanSyncGroupProfile) }
fun interface LanSyncGroupDraftAbandonAdmission { fun requireApproved(profile: LanSyncGroupProfile?) }

/** One local app profile: explicit founder enrollment, then ordinary reopen without admin authority. */
class LanSyncGroupEnrollment(private val root: Path, private val workspace: LanSyncWorkspace,
    private val platform: LanSyncPlatform, private val identity: LanSyncTlsIdentity,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray,
    private val validateResource: (String, Path, LanSyncRecord) -> Unit,
    private val observe: (String) -> Unit = {}) : AutoCloseable {
    @Serializable private data class Record(val format: Int, val stage: LanSyncGroupEnrollmentStage,
        val request: SyncRootGroupCreateRequest, val trust: SyncGroupTrust, val certificate: String,
        val recipient: String, val vault: SyncManagementVaultContext, val vaultDescriptor: CiphertextDescriptor, val vaultOperation: SyncStoreId,
        val localReplica: SyncDeliveryTarget)
    @Serializable private data class Abandon(val format: Int, val device: String, val root: String,
        val id: SyncStoreId, val names: List<String>)
    private val certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
    private val mutex = locks.computeIfAbsent(root) { Mutex() }
    private val sessions = Any()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionGeneration = 0L
    private var activeSession: SyncManagementSession? = null
    private var expiry: Job? = null
    @Volatile private var disposed = false
    init { lanSyncSafe(root, true) }
    private fun directory(name: String, create: Boolean): Path = root.resolve(name).also {
        lanSyncSafe(root, true)
        if (create && !Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it)
        lanSyncSafe(it, true)
    }
    private fun profile(record: Record) = LanSyncGroupProfile(record.stage, record.trust, identity.fingerprint,
        Collections.unmodifiableSet(record.request.coveredTypes.toSortedSet()), record.localReplica)
    private fun recipient(record: Record? = null): SyncDeviceEncryptionKey {
        val owner = SyncDeviceKeyCustody(directory("recipient", record == null), identity.fingerprint, protect, unprotect)
        return if (record == null) owner.createIfAbsent() else owner.open(record.recipient)
    }
    private fun custody(record: Record, create: Boolean = false) = SyncGroupCustody(directory("custody", create),
        record.trust.group, recipient(record), protect, unprotect)
    private fun node(record: Record): LanSyncGroupNode {
        val custody = custody(record)
        return LanSyncGroupNode(directory("node", false), workspace, platform, identity, record.trust, custody,
            recipient(record), FileCiphertextStore(directory("local-replica", false)), protect, unprotect,
            { emptySet() }, validateResource, observe = observe)
    }
    suspend fun profile(): LanSyncGroupProfile? = guarded { requireNoAbandon(); read()?.let(::profile) }

    /** No data version is signed or app data modified by preparing the first group. */
    suspend fun prepare(request: SyncRootGroupCreateRequest, admission: SyncRootGroupCreateAdmission): LanSyncGroupEnrollmentDraft = guarded {
        requireNoAbandon()
        require(read() == null) { "SYNC_GROUP_PROFILE_ALREADY_PREPARED" }
        require(!Files.exists(root.resolve("creation"), NOFOLLOW_LINKS) && !Files.exists(root.resolve("management-vault.bin"), NOFOLLOW_LINKS)) {
            "SYNC_GROUP_UNFINISHED_CREATION_REQUIRES_RECOVERY"
        }
        require(request.coveredTypes.all { it in LanSyncScope.schemas }) { "SYNC_GROUP_PROFILE_UNSUPPORTED_SCOPE" }
        // Current native capture is complete-scope; do not create a profile it cannot author safely.
        require(request.coveredTypes == LanSyncScope.schemas.keys) { "SYNC_GROUP_PROFILE_PARTIAL_SCOPE_UNSUPPORTED" }
        val frozen = request.copy(coveredTypes = Collections.unmodifiableSet(request.coveredTypes.toSortedSet()))
        val generation = sessionGeneration()
        // Rejected UI admission must not create a device key or management material.
        admission.requireApproved(frozen)
        val recipient = recipient(); val session = SyncManagementSession.create()
        try {
            retainSession(session, generation)
            val created = SyncRootGroupCreation.managed(directory("creation", true), session.ownerCertificate,
                session.recipientPublicKey, certificate, recipient, session::protectJournal, session::unprotectJournal, observe)
                .create(frozen, SyncRootGroupCreateAdmission { require(it == frozen) }, session::signRootPayload)
            val vault = session.seal(created.trust); val bytes = vault.bytes()
            lanSyncAtomicWrite(root.resolve("management-vault.bin"), bytes)
            observe("GROUP_MANAGEMENT_VAULT_RETAINED")
            val record = Record(1, LanSyncGroupEnrollmentStage.PREPARED, frozen, created.trust, certificate,
                recipient.publicKey.encoded, vault.context, descriptor(bytes), SyncStoreId.random(),
                SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()))
            write(record); observe("GROUP_PROFILE_PREPARED")
            LanSyncGroupEnrollmentDraft(frozen.operation, profile(record), session)
        } catch (failure: Throwable) { releaseSession(session); throw failure }
    }

    /** Recovery code is caller-owned transient input; it is never written to this profile. */
    suspend fun resume(recoveryCode: String): LanSyncGroupEnrollmentDraft = guarded {
        requireNoAbandon()
        val record = requireNotNull(read()) { "SYNC_GROUP_PROFILE_MISSING" }
        val generation = sessionGeneration()
        val session = unlock(record, recoveryCode)
        try { retainSession(session, generation) } catch (failure: Throwable) { session.close(); throw failure }
        LanSyncGroupEnrollmentDraft(record.request.operation, profile(record), session)
    }
    private fun unlock(record: Record, recoveryCode: String): SyncManagementSession {
        val bytes = lanSyncRead(root.resolve("management-vault.bin"), 272 * 1024)
        require(descriptor(bytes) == record.vaultDescriptor) { "SYNC_GROUP_PROFILE_VAULT_CHANGED" }
        require(SyncManagementSession.locate(recoveryCode) == record.vault) { "SYNC_GROUP_PROFILE_RECOVERY_CODE" }
        val session = SyncManagementSession.open(recoveryCode, bytes)
        try { require(session.trust == record.trust) { "SYNC_GROUP_PROFILE_MANAGEMENT_CHANGED" }; return session }
        catch (failure: Throwable) { session.close(); throw failure }
    }

    /** Only after explicit code custody, real local key/native enrollment and root delivery receipts. */
    suspend fun activate(draft: LanSyncGroupEnrollmentDraft, saved: LanSyncGroupRecoverySavedAdmission): LanSyncGroupNode = guarded {
        requireNoAbandon()
        val record = requireNotNull(read()) { "SYNC_GROUP_PROFILE_MISSING" }
        require(record.request.operation == draft.operation && record.trust == draft.profile.trust) { "SYNC_GROUP_PROFILE_DRAFT_CHANGED" }
        if (record.stage == LanSyncGroupEnrollmentStage.READY) return@guarded openOwned(record).also { releaseSession(draft.session) }
        saved.requireSaved(profile(record))
        val session = draft.session
        require(session.trust == record.trust) { "SYNC_GROUP_PROFILE_MANAGEMENT_CHANGED" }
        unlock(record, session.recoveryCode()).close()
        val recipient = recipient(record)
        val created = SyncRootGroupCreation.managed(directory("creation", false), session.ownerCertificate,
            session.recipientPublicKey, certificate, recipient, session::protectJournal, session::unprotectJournal)
            .resume(record.request.operation) { error("SYNC_GROUP_PROFILE_GENESIS_MISSING") }
        require(created.trust == record.trust) { "SYNC_GROUP_PROFILE_MANAGEMENT_CHANGED" }
        val custody = custody(record, true); val ledger = created.enrollCreator(custody)
        val actions = directory("root-actions", true)
        val rootJournal = SyncRootLedgerJournal(actions, record.trust, session.ownerCertificate,
            session::protectJournal, session::unprotectJournal)
        if (Files.exists(actions.resolve("origin.protected"), NOFOLLOW_LINKS)) {
            val state = rootJournal.state()
            require(state.completedSequence == 0L && state.last == null && state.pendingOperation == null) { "SYNC_GROUP_PROFILE_ROOT_ALREADY_USED" }
        } else rootJournal.initializeNewEnrollment(ledger)
        directory("node", true); directory("local-replica", true)
        val node = node(record); node.initializeNewEnrollment()
        val queues = directory("root-queues", true)
        val rootOwner = LanSyncGroupRootPublication(directory("root-delivery", true), session.ownerCertificate, session::signRootPayload,
            custody::ledger, custody::key, { epoch ->
                val path = queues.resolve(epoch.value)
                if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
                SyncEncryptedOutbox(path, custody.key(epoch))
            }, observe)
        val local = FileCiphertextStore(directory("local-replica", false))
        val vaultKey = record.vault.key
        Files.newInputStream(root.resolve("management-vault.bin"), NOFOLLOW_LINKS).use {
            local.put(CiphertextPutRequest(vaultKey, record.vaultOperation, record.vaultDescriptor), it)
        }
        require(local.metadata(vaultKey)?.descriptor == record.vaultDescriptor) { "SYNC_GROUP_PROFILE_VAULT_DELIVERY_INCOMPLETE" }
        rootOwner.upload(local, record.localReplica)
        requireNotNull(rootOwner.receipt(record.localReplica)) { "SYNC_GROUP_PROFILE_LOCAL_DELIVERY_INCOMPLETE" }
        val ready = record.copy(stage = LanSyncGroupEnrollmentStage.READY)
        val opened = openOwned(ready)
        write(ready); releaseSession(session); observe("GROUP_PROFILE_READY")
        opened
    }
    /** Missing/damaged origins refuse; ordinary startup never creates a replacement enrollment. */
    suspend fun open(): LanSyncGroupNode? = guarded {
        requireNoAbandon()
        val record = read() ?: return@guarded null
        require(record.stage == LanSyncGroupEnrollmentStage.READY) { "SYNC_GROUP_PROFILE_ENROLLMENT_INCOMPLETE" }
        openOwned(record)
    }
    private suspend fun openOwned(record: Record): LanSyncGroupNode {
        val custody = custody(record); val ledger = custody.ledger()
        require(ledger.trustAnchor.group == record.trust.group && ledger.trustAnchor.genesis == record.trust.genesis &&
            ledger.trustAnchor.ownerCertificate == record.trust.ownerCertificate) { "SYNC_GROUP_PROFILE_TRUST_CHANGED" }
        // Revocation is a normal visible node state; do not recreate keys or origins to bypass it.
        if (ledger.current().members[identity.fingerprint]?.active == true) custody.key(ledger.current().epoch)
        return node(record).also { it.inspect() }
    }
    private fun read(): Record? {
        val path = root.resolve("profile.protected")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val plain = unprotect(lanSyncRead(path, 256 * 1024))
        try {
            require(plain.size in 1..128 * 1024) { "SYNC_GROUP_PROFILE_BUDGET" }
            return lanSyncJson.decodeFromString(Record.serializer(), lanSyncUtf8(plain)).also {
                require(it.format == 1 && it.certificate == certificate && it.vault.group == it.trust.group &&
                    it.vault.genesis == it.trust.genesis && it.vault.owner == SyncDeviceSignatures.decode(it.trust.ownerCertificate).fingerprint &&
                    it.localReplica.kind == SyncDeliveryKind.LOCAL_REPLICA) { "SYNC_GROUP_PROFILE_BINDING" }
            }
        } finally { plain.fill(0) }
    }
    private fun write(record: Record) {
        val plain = lanSyncJson.encodeToString(Record.serializer(), record).toByteArray(Charsets.UTF_8)
        writeControl(root.resolve("profile.protected"), plain)
    }
    private fun writeControl(path: Path, plain: ByteArray) {
        try {
            require(plain.size in 1..128 * 1024) { "SYNC_GROUP_PROFILE_BUDGET" }
            val protected = protect(plain); val roundTrip = unprotect(protected)
            try { require(roundTrip.contentEquals(plain)) { "SYNC_GROUP_PROFILE_PROTECTION" } } finally { roundTrip.fill(0) }
            require(protected.size in 1..256 * 1024) { "SYNC_GROUP_PROFILE_BUDGET" }
            lanSyncAtomicWrite(path, protected)
        } finally { plain.fill(0) }
    }
    private fun requireNoAbandon() {
        require(!Files.exists(root.resolve("abandon.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_PROFILE_ABANDON_PENDING" }
    }
    private fun noNativeHistory() = listOf("custody", "root-actions", "node", "local-replica", "root-delivery", "root-queues")
        .none { Files.exists(root.resolve(it), NOFOLLOW_LINKS) }
    private fun readAbandon(): Abandon? {
        val path = root.resolve("abandon.protected"); if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val plain = unprotect(lanSyncRead(path, 256 * 1024))
        try {
            require(plain.size in 1..128 * 1024) { "SYNC_GROUP_PROFILE_BUDGET" }
            return lanSyncJson.decodeFromString(Abandon.serializer(), lanSyncUtf8(plain)).also {
                require(it.format == 1 && it.device == identity.fingerprint && it.root == lanSyncHash(root.toString().toByteArray()) &&
                    it.names.isNotEmpty() && it.names.distinct().size == it.names.size && it.names.all { name -> name in draftNames }) {
                    "SYNC_GROUP_PROFILE_ABANDON_CHANGED"
                }
            }
        } finally { plain.fill(0) }
    }
    suspend fun canAbandonUnactivated(): Boolean = guarded {
        noNativeHistory() && read()?.stage != LanSyncGroupEnrollmentStage.READY &&
            (readAbandon() != null || draftNames.any { Files.exists(root.resolve(it), NOFOLLOW_LINKS) })
    }
    /** Explicit discard of unpublished setup only. Retain every byte in a private archive; delete no data. */
    suspend fun abandonUnactivated(admission: LanSyncGroupDraftAbandonAdmission): Path = guarded {
        require(read()?.stage != LanSyncGroupEnrollmentStage.READY) { "SYNC_GROUP_PROFILE_ALREADY_ACTIVE" }
        require(noNativeHistory()) { "SYNC_GROUP_PROFILE_LOCAL_HISTORY_EXISTS" }
        val intent = readAbandon() ?: run {
            require(Files.newDirectoryStream(root).use { entries -> entries.all {
                it.fileName.toString() in draftNames + setOf("profile.lock", "abandoned-drafts")
            } }) { "SYNC_GROUP_PROFILE_UNEXPECTED_EVIDENCE" }
            val names = draftNames.filter { Files.exists(root.resolve(it), NOFOLLOW_LINKS) }.sorted()
            require(names.isNotEmpty()) { "SYNC_GROUP_PROFILE_NO_DRAFT" }
            admission.requireApproved(read()?.let(::profile)); lockManagement()
            Abandon(1, identity.fingerprint, lanSyncHash(root.toString().toByteArray()), SyncStoreId.random(), names).also {
                writeControl(root.resolve("abandon.protected"), lanSyncJson.encodeToString(Abandon.serializer(), it).toByteArray())
                observe("UNACTIVATED_GROUP_ABANDON_INTENT_RETAINED")
            }
        }
        val parent = directory("abandoned-drafts", true); val archive = parent.resolve(intent.id.value)
        if (!Files.exists(archive, NOFOLLOW_LINKS)) Files.createDirectory(archive)
        lanSyncSafe(archive, true)
        intent.names.forEach { name ->
            val source = root.resolve(name); val target = archive.resolve(name)
            if (Files.exists(source, NOFOLLOW_LINKS)) {
                require(!Files.exists(target, NOFOLLOW_LINKS)) { "SYNC_GROUP_PROFILE_ABANDON_CHANGED" }
                val directory = Files.isDirectory(source, NOFOLLOW_LINKS); lanSyncSafe(source, directory)
                Files.move(source, target, ATOMIC_MOVE); lanSyncSafe(target, directory)
                observe("UNACTIVATED_GROUP_ENTRY_RETAINED")
            } else { require(Files.exists(target, NOFOLLOW_LINKS)) { "SYNC_GROUP_PROFILE_ABANDON_CHANGED" }
                lanSyncSafe(target, Files.isDirectory(target, NOFOLLOW_LINKS)) }
        }
        require(readAbandon() == intent) { "SYNC_GROUP_PROFILE_ABANDON_CHANGED" }
        writeControl(archive.resolve("completed.protected"), lanSyncJson.encodeToString(Abandon.serializer(), intent).toByteArray())
        Files.delete(root.resolve("abandon.protected")); observe("UNACTIVATED_GROUP_ABANDONED")
        archive
    }
    private fun descriptor(bytes: ByteArray) = CiphertextDescriptor(bytes.size.toLong(), CiphertextSha256.parse(lanSyncHash(bytes)))
    private fun sessionGeneration() = synchronized(sessions) {
        check(!disposed) { "SYNC_GROUP_PROFILE_CLOSED" }; sessionGeneration
    }
    private fun retainSession(session: SyncManagementSession, generation: Long) = synchronized(sessions) {
        check(!disposed && generation == sessionGeneration) { "SYNC_MANAGEMENT_SESSION_LOCKED" }
        expiry?.cancel(); activeSession?.close(); activeSession = session
        expiry = sessionScope.launch {
            delay(300_000)
            synchronized(sessions) {
                if (activeSession === session) { activeSession = null; sessionGeneration++; session.close() }
            }
        }
    }
    private fun releaseSession(session: SyncManagementSession) = synchronized(sessions) {
        if (activeSession === session) { activeSession = null; expiry?.cancel(); expiry = null }
        session.close()
    }
    /** UI/application background only locks admin capability; it does not cancel daily data work. */
    fun lockManagement() = synchronized(sessions) {
        sessionGeneration++; expiry?.cancel(); expiry = null; activeSession?.close(); activeSession = null
    }
    override fun close() {
        synchronized(sessions) { disposed = true; lockManagement() }
        sessionScope.cancel()
    }
    private suspend fun <T> guarded(work: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!disposed) { "SYNC_GROUP_PROFILE_CLOSED" }
            lanSyncSafe(root, true)
            val path = root.resolve("profile.lock")
            FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                lanSyncSafe(path); channel.lock().use { work() }
            }
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<Path, Mutex>()
        private val draftNames = setOf("creation", "recipient", "management-vault.bin", "profile.protected")
    }
}
