package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Base64
import java.util.Collections
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

enum class LanSyncGroupJoinStage { PREPARING, PREPARED, PROOF_ATTEMPTED, WAITING_FOR_APPROVAL, KEYS_ADMITTED, NATIVE_ENROLLED, READY }
data class LanSyncGroupJoiningProfile(val stage: LanSyncGroupJoinStage, val trust: SyncGroupTrust,
    val device: String, val localReplica: SyncDeliveryTarget, val canAbandon: Boolean = false, val canShelve: Boolean = false,
    val membershipVerified: Boolean = false, val membershipRevoked: Boolean = false, val canArchiveRevoked: Boolean = false)
data class LanSyncShelvedJoin(val operation: String, val group: String)
data class LanSyncGroupJoiningResult(val profile: LanSyncGroupJoiningProfile, val node: LanSyncGroupNode?, val waitingCode: String? = null)
internal class LanSyncJoinRouteFailure(cause: IOException) : IOException("SYNC_GROUP_JOIN_CONNECTION_FAILED", cause)

/** Explicit joining retains its recipient before any proof; normal reopen never recreates origins. */
class LanSyncGroupJoining(private val parent: Path, private val workspace: LanSyncWorkspace,
    private val platform: LanSyncPlatform, private val identity: LanSyncTlsIdentity,
    private val protect: (ByteArray) -> ByteArray, private val unprotect: (ByteArray) -> ByteArray,
    private val validateResource: (String, Path, LanSyncRecord) -> Unit, private val observe: (String) -> Unit = {},
    private val wallTime: () -> Long = System::currentTimeMillis) {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable private data class Record(val format: Int, val operation: SyncStoreId, val stage: LanSyncGroupJoinStage,
        val offer: LanSyncGroupInviteOffer? = null, val certificate: String, val recipient: String?, val localReplica: SyncDeliveryTarget,
        val replicaIdentity: String? = null, val projection: SyncPlaintextIdentity? = null,
        val projectionFile: String = "projection.json", val reprepareRequested: Boolean = false,
        val requiredCheckpoints: Set<SyncVersionRef> = emptySet(), val requiredHeads: Set<SyncVersionRef> = emptySet(),
        val recoveryAnchor: LanSyncGroupRecoveryAnchor? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val knownMembership: SyncVersionRef? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val membershipRevoked: Boolean = false)
    @Serializable private data class Projection(val projection: LanSyncProjection, val sourceFiles: Map<String, String>,
        val checkpoint: SyncVersionRef, val heads: Set<SyncVersionRef>)
    private val root = parent.resolve("joining")
    private val shelf = parent.resolve("shelved-joins")
    private val certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
    private val mutex = LanSyncGroupEnrollment.locks.computeIfAbsent(parent) { kotlinx.coroutines.sync.Mutex() }
    private var nextRouteMember = 0
    init { lanSyncSafe(parent, true) }
    private fun directory(name: String, create: Boolean): Path = root.resolve(name).also {
        lanSyncSafe(root, true)
        if (create && !Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectory(it)
        lanSyncSafe(it, true)
    }
    private fun trust(record: Record) = record.offer?.trust ?: requireNotNull(record.recoveryAnchor).trust
    private fun bootstrap(record: Record): LanSyncGroupRecoveryAnchor = record.recoveryAnchor ?: requireNotNull(record.offer).let { offer ->
        requireNotNull(offer.bootstrap).let { LanSyncGroupRecoveryAnchor(offer.trust, it.vault, it.vaultDescriptor, it.dataHeads) }
    }
    private fun profile(record: Record) =
        LanSyncGroupJoiningProfile(record.stage, trust(record), identity.fingerprint, record.localReplica,
            canAbandon(record), canShelve(record, root),
            record.knownMembership != null || record.stage.ordinal >= LanSyncGroupJoinStage.KEYS_ADMITTED.ordinal,
            record.membershipRevoked, canArchiveRevoked(record))

    private fun canAbandon(record: Record): Boolean {
        if (record.offer == null || record.stage !in setOf(LanSyncGroupJoinStage.PREPARING, LanSyncGroupJoinStage.PREPARED)) return false
        return Files.newDirectoryStream(root).use { files ->
            files.all { path ->
                when (path.fileName.toString()) {
                    "profile.protected" -> Files.isRegularFile(path, NOFOLLOW_LINKS)
                    "recipient" -> Files.isDirectory(path, NOFOLLOW_LINKS)
                    else -> false
                }
            }
        }
    }

    /** One same-volume rename retains the entire unused application as a private archive. */
    suspend fun abandonUnprovedInvitation(): Path = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        require(canAbandon(record)) { "SYNC_GROUP_JOIN_APPROVAL_MAY_EXIST" }
        archive(record, "UNPROVED_JOIN_ARCHIVED")
    }

    suspend fun archiveRevokedInvitation(): Path = guarded {
        val record = checkRetainedMembership(requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" })
        require(canArchiveRevoked(record)) { "SYNC_GROUP_JOIN_REVOKED_ARCHIVE_NOT_AVAILABLE" }
        recipient(record)
        archive(record, "REVOKED_JOIN_ARCHIVED")
    }

    private fun archive(record: Record, event: String): Path {
        val archives = parent.resolve("abandoned-joins")
        if (!Files.exists(archives, NOFOLLOW_LINKS)) Files.createDirectory(archives)
        lanSyncSafe(archives, true)
        val archive = archives.resolve(record.operation.value)
        require(!Files.exists(archive, NOFOLLOW_LINKS)) { "SYNC_GROUP_JOIN_ARCHIVE_EXISTS" }
        Files.move(root, archive, ATOMIC_MOVE)
        observe(event)
        return archive
    }
    private fun canShelve(record: Record, directory: Path): Boolean =
        record.knownMembership == null && inactiveApplication(record, directory)
    private fun canArchiveRevoked(record: Record): Boolean =
        record.membershipRevoked && inactiveApplication(record, root)

    private fun inactiveApplication(record: Record, directory: Path): Boolean {
        if (record.offer == null || record.stage !in setOf(LanSyncGroupJoinStage.PROOF_ATTEMPTED, LanSyncGroupJoinStage.WAITING_FOR_APPROVAL) ||
            record.projection != null || record.reprepareRequested) return false
        val allowed = setOf("recipient", "bootstrap", "received-ciphertext", "commit-candidates", "route-cache", "custody")
        return Files.newDirectoryStream(directory).use { files -> files.all { path ->
            when (path.fileName.toString()) {
                "profile.protected" -> Files.isRegularFile(path, NOFOLLOW_LINKS)
                "custody" -> Files.isDirectory(path, NOFOLLOW_LINKS) && Files.newDirectoryStream(path).use { controls ->
                    controls.all { it.fileName.toString() == "group.lock" && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                }
                in allowed -> Files.isDirectory(path, NOFOLLOW_LINKS)
                else -> false
            }
        } }
    }

    /** Neither an old pre-dial marker nor a missing reply proves that the inviter rejected this member. */
    suspend fun shelveUnsettledInvitation(): Unit = guarded {
        val record = checkRetainedMembership(requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" })
        require(canShelve(record, root)) { "SYNC_GROUP_JOIN_HAS_LOCAL_ADMISSION" }
        recipient(record)
        if (!Files.exists(shelf, NOFOLLOW_LINKS)) Files.createDirectory(shelf)
        lanSyncSafe(shelf, true)
        val target = shelf.resolve(record.operation.value)
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "SYNC_GROUP_JOIN_SHELF_EXISTS" }
        Files.move(root, target, ATOMIC_MOVE)
        observe("UNSETTLED_JOIN_SHELVED")
    }

    private fun shelvedRecords(): List<Record> {
        if (!Files.exists(shelf, NOFOLLOW_LINKS)) return emptyList()
        lanSyncSafe(shelf, true)
        return Files.newDirectoryStream(shelf).use { files -> files.map { path ->
            val operation = SyncStoreId.parse(path.fileName.toString())
            lanSyncSafe(path, true)
            val record = requireNotNull(read(path)) { "SYNC_GROUP_JOIN_SHELF_MISSING" }
            require(operation == record.operation && canShelve(record, path)) { "SYNC_GROUP_JOIN_SHELF_CHANGED" }
            record
        }.sortedBy { it.operation.value } }
    }
    suspend fun shelvedInvitations(): List<LanSyncShelvedJoin> = guarded {
        shelvedRecords().map { LanSyncShelvedJoin(it.operation.value, trust(it).group.value) }
    }
    suspend fun restoreShelvedInvitation(operation: String): LanSyncGroupJoiningProfile = guarded {
        require(!Files.exists(parent.resolve("profile.protected"), NOFOLLOW_LINKS) &&
            !Files.exists(parent.resolve("creation"), NOFOLLOW_LINKS) && !Files.exists(parent.resolve("abandon.protected"), NOFOLLOW_LINKS) &&
            read() == null) { "SYNC_GROUP_JOIN_ALREADY_PREPARED" }
        val id = SyncStoreId.parse(operation)
        val record = requireNotNull(shelvedRecords().firstOrNull { it.operation == id }) { "SYNC_GROUP_JOIN_SHELF_MISSING" }
        val source = shelf.resolve(id.value)
        SyncDeviceKeyCustody(source.resolve("recipient"), identity.fingerprint, protect, unprotect)
            .open(requireNotNull(record.recipient))
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            lanSyncSafe(root, true)
            require(Files.newDirectoryStream(root).use { !it.iterator().hasNext() }) { "SYNC_GROUP_JOIN_EXISTING_EVIDENCE" }
            Files.delete(root)
        }
        Files.move(source, root, ATOMIC_MOVE)
        observe("UNSETTLED_JOIN_RESTORED")
        profile(record)
    }
    suspend fun profile(): LanSyncGroupJoiningProfile? = guarded { read()?.let { profile(checkRetainedMembership(it)) } }
    suspend fun recoveryAnchor(): LanSyncGroupRecoveryAnchor? = guarded { read()?.recoveryAnchor }
    /** Public pins of this retained recovery enrollment, never a replacement or private key. */
    suspend fun recoveryMember(): Pair<String, SyncGroupMember>? = guarded {
        val record = read() ?: return@guarded null
        if (record.recoveryAnchor == null || record.recipient == null) return@guarded null
        identity.fingerprint to SyncGroupMember(certificate, recipient(record).publicKey.encoded)
    }
    suspend fun encryptedManagementBackup(): SyncManagementVaultBlob = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }; val bootstrap = bootstrap(record)
        val path = directory("bootstrap", false).resolve("ciphertext-mirror")
        require(lanSyncUtf8(lanSyncRead(path.resolve("store.id"), 32)) == record.replicaIdentity) { "SYNC_GROUP_JOIN_REPLICA_CHANGED" }
        lanSyncReadManagementBackup(FileCiphertextStore(path), bootstrap.vault, bootstrap.vaultDescriptor)
    }
    suspend fun pendingRoute(): Pair<SyncGroupTrust, LanSyncPeer>? = guarded {
        val record = read() ?: return@guarded null
        val offer = record.offer ?: return@guarded null
        val source = requireNotNull(offer.bootstrap)
        offer.trust to LanSyncPeer(offer.inviter, "邀请设备", offer.host, source.storePort)
    }
    suspend fun bootstrapStore(network: LanSyncNetwork): CiphertextStore = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        val offer = requireNotNull(record.offer) { "SYNC_GROUP_JOIN_CLOUD_SOURCE_REQUIRED" }
        val source = requireNotNull(offer.bootstrap)
        val peer = LanSyncPeer(offer.inviter, "邀请设备", offer.host, source.storePort)
        val admitted = record.stage.ordinal >= LanSyncGroupJoinStage.KEYS_ADMITTED.ordinal
        if (admitted) require(custody(record, false).ledger().current().members[peer.fingerprint]?.active == true) {
            "SYNC_GROUP_JOIN_ROUTE_NO_LONGER_ACTIVE"
        }
        bootstrapStore(record, network, peer)
    }

    /** Each activation freezes one authenticated route and starts its own discovery cursor. */
    internal suspend fun discoveredBootstrapStores(network: LanSyncNetwork): List<CiphertextStore> = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        val offer = requireNotNull(record.offer)
        val source = requireNotNull(offer.bootstrap)
        val fingerprints = if (record.stage.ordinal < LanSyncGroupJoinStage.KEYS_ADMITTED.ordinal) listOf(offer.inviter)
        else {
            val active = custody(record, false).ledger().current().members.filterValues { it.active }.keys
            val controller = SyncDeviceSignatures.decode(offer.trust.ownerCertificate).fingerprint
            val members = active - setOf(controller, identity.fingerprint)
            val inviter = listOf(offer.inviter).filter { it in members }
            val alternatives = (members - offer.inviter).sorted()
            val count = minOf(4 - inviter.size, alternatives.size)
            val batch = List(count) { alternatives[(nextRouteMember + it) % alternatives.size] }
            nextRouteMember = if (alternatives.isEmpty()) 0 else (nextRouteMember + count) % alternatives.size
            inviter + batch
        }
        fingerprints.mapNotNull { expected ->
            network.discoverGroup(offer.trust.group, identity.fingerprint, expected)?.takeIf {
                it.fingerprint == expected && (it.host != offer.host || it.port != source.storePort || expected != offer.inviter) &&
                    it.port in 1024..65535 && runCatching { LanSyncTls.parseAddress(it.host) }.isSuccess
            }?.let { bootstrapStore(record, network, it) }
        }
    }

    private fun bootstrapStore(record: Record, network: LanSyncNetwork, peer: LanSyncPeer): CiphertextStore =
        LanSyncGroupRemoteStore(trust(record).group, directory("route-cache", true), {
            val socket = try { network.connect(identity, peer) }
            catch (error: javax.net.ssl.SSLException) { throw error }
            catch (error: IOException) { throw LanSyncJoinRouteFailure(error) }
            try {
                require(LanSyncTls.peerFingerprint(socket) == peer.fingerprint) { "SYNC_GROUP_INVITE_TLS_PIN" }
                socket
            } catch (failure: Exception) {
                runCatching { socket.close() }
                throw failure
            }
        }, authenticatedConnect = true, readTimeoutMillis = 15_000)
    suspend fun runtimeAccess(): LanSyncGroupRuntimeAccess? = guarded {
        val record = read() ?: return@guarded null
        if (record.stage != LanSyncGroupJoinStage.READY) return@guarded null
        val source = bootstrap(record); val path = directory("bootstrap", false).resolve("ciphertext-mirror")
        require(lanSyncUtf8(lanSyncRead(path.resolve("store.id"), 32)) == record.replicaIdentity) { "SYNC_GROUP_JOIN_REPLICA_CHANGED" }
        val projection = readProjection(record)
        LanSyncGroupRuntimeAccess(trust(record), record.localReplica, FileCiphertextStore(path), directory("app-runtime", true),
            identity, custody(record, false), { store -> node(record, projection.projection, store) }, source.vault, source.vaultDescriptor,
            peerNameProtect = protect, peerNameUnprotect = unprotect, candidateRoot = directory("commit-candidates", true))
    }

    suspend fun prepare(code: String, admission: (LanSyncGroupInviteOffer) -> Unit): LanSyncGroupJoiningProfile =
        prepareOffer(LanSyncGroupInviteOffer.parse(code, wallTime()), admission)

    internal suspend fun prepareOffer(parsed: LanSyncGroupInviteOffer, admission: (LanSyncGroupInviteOffer) -> Unit): LanSyncGroupJoiningProfile = guarded {
        require(!Files.exists(parent.resolve("profile.protected"), NOFOLLOW_LINKS) &&
            !Files.exists(parent.resolve("creation"), NOFOLLOW_LINKS) && !Files.exists(parent.resolve("abandon.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_FOUNDER_PROFILE_EXISTS" }
        require(read() == null) { "SYNC_GROUP_JOIN_ALREADY_PREPARED" }
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            lanSyncSafe(root, true)
            require(Files.newDirectoryStream(root).use { !it.iterator().hasNext() }) { "SYNC_GROUP_JOIN_EXISTING_EVIDENCE" }
        }
        parsed.validate(wallTime())
        val offer = parsed.let { it.copy(bootstrap = it.bootstrap?.let { value ->
            value.copy(dataHeads = Collections.unmodifiableSet(value.dataHeads.toSet()))
        }) }
        requireNotNull(offer.bootstrap) { "SYNC_GROUP_JOIN_BOOTSTRAP_REQUIRED" }
        require(shelvedRecords().none { trust(it).group == offer.trust.group }) { "SYNC_GROUP_JOIN_RESTORE_SHELVED_FIRST" }
        admission(offer)
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
        val record = Record(1, SyncStoreId.random(), LanSyncGroupJoinStage.PREPARING, offer, certificate, null,
            SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()))
        write(record); observe("GROUP_JOIN_REQUEST_RETAINED")
        profile(finishPreparation(record))
    }
    suspend fun resumePreparation(): LanSyncGroupJoiningProfile = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        profile(if (record.stage == LanSyncGroupJoinStage.PREPARING) finishPreparation(record) else checkRetainedMembership(record))
    }
    /** Explicit root recovery has no LAN capability; retain a new daily recipient before approval. */
    suspend fun prepareRecovery(anchor: LanSyncGroupRecoveryAnchor,
        admission: (LanSyncGroupRecoveryAnchor) -> Unit): LanSyncGroupJoiningProfile = guarded {
        anchor.validate()
        require(!Files.exists(parent.resolve("profile.protected"), NOFOLLOW_LINKS) &&
            !Files.exists(parent.resolve("creation"), NOFOLLOW_LINKS) && !Files.exists(parent.resolve("abandon.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_FOUNDER_PROFILE_EXISTS" }
        require(read() == null) { "SYNC_GROUP_JOIN_ALREADY_PREPARED" }
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            lanSyncSafe(root, true)
            require(Files.newDirectoryStream(root).use { !it.iterator().hasNext() }) { "SYNC_GROUP_JOIN_EXISTING_EVIDENCE" }
        }
        val frozen = anchor.copy(dataHeads = Collections.unmodifiableSet(anchor.dataHeads.toSet()))
        require(shelvedRecords().none { trust(it).group == anchor.trust.group }) { "SYNC_GROUP_JOIN_RESTORE_SHELVED_FIRST" }
        admission(frozen)
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
        val record = Record(1, SyncStoreId.random(), LanSyncGroupJoinStage.PREPARING, certificate = certificate,
            recipient = null, localReplica = SyncDeliveryTarget(SyncDeliveryKind.LOCAL_REPLICA, SyncStoreId.random()),
            requiredCheckpoints = setOf(frozen.trust.checkpoint), requiredHeads = frozen.dataHeads, recoveryAnchor = frozen)
        write(record); observe("GROUP_RECOVERY_JOIN_REQUEST_RETAINED")
        profile(finishPreparation(record))
    }
    /** Borrow only this joining owner's retained daily keys; no replacement or private-key export. */
    suspend fun prepareRecoveryDevice(management: LanSyncGroupManagementRecovery,
        name: String): LanSyncGroupRecoveryDevicePreview = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        require(record.recoveryAnchor != null && record.stage == LanSyncGroupJoinStage.PREPARED) { "SYNC_GROUP_JOIN_RECOVERY_NOT_AVAILABLE" }
        val known = trust(record); val ledger = management.ledger(); val current = ledger.trustAnchor
        require(current.group == known.group && current.genesis == known.genesis && current.ownerCertificate == known.ownerCertificate) {
            "SYNC_GROUP_JOIN_RECOVERY_TRUST_CHANGED"
        }
        require(ledger.contains(known.checkpoint) && record.requiredCheckpoints.all(ledger::contains)) { "SYNC_GROUP_JOIN_KNOWN_CHECKPOINT_MISSING" }
        management.prepareRecoveryDevice(identity, recipient(record), name)
    }
    /** Root approval is already durable. Activation still authenticates the signed ledger/capsules. */
    suspend fun acceptRecoveryBootstrap(anchor: LanSyncGroupRecoveryAnchor): LanSyncGroupJoiningProfile = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        val before = requireNotNull(record.recoveryAnchor) { "SYNC_GROUP_JOIN_RECOVERY_NOT_AVAILABLE" }
        require(record.stage in setOf(LanSyncGroupJoinStage.PREPARED, LanSyncGroupJoinStage.WAITING_FOR_APPROVAL)) { "SYNC_GROUP_JOIN_RECOVERY_NOT_AVAILABLE" }
        anchor.validate()
        require(anchor.trust.group == before.trust.group && anchor.trust.genesis == before.trust.genesis &&
            anchor.trust.ownerCertificate == before.trust.ownerCertificate && anchor.vault == before.vault &&
            anchor.vaultDescriptor == before.vaultDescriptor) { "SYNC_GROUP_JOIN_RECOVERY_TRUST_CHANGED" }
        recipient(record)
        val frozen = anchor.copy(dataHeads = Collections.unmodifiableSet(anchor.dataHeads.toSet()))
        val updated = record.copy(stage = LanSyncGroupJoinStage.WAITING_FOR_APPROVAL, recoveryAnchor = frozen,
            requiredCheckpoints = record.requiredCheckpoints + before.trust.checkpoint + frozen.trust.checkpoint,
            requiredHeads = record.requiredHeads + before.dataHeads + frozen.dataHeads)
        write(updated); observe("GROUP_RECOVERY_JOIN_APPROVAL_SOURCE_RETAINED"); profile(updated)
    }
    /** A fresh explicit QR/code changes only the route/capability; never recipient/operation or past pins. */
    suspend fun refreshInvitation(code: String, admission: (LanSyncGroupInviteOffer) -> Unit): LanSyncGroupJoiningProfile =
        refreshOffer(LanSyncGroupInviteOffer.parse(code, wallTime()), admission)

    internal suspend fun refreshOffer(parsed: LanSyncGroupInviteOffer, admission: (LanSyncGroupInviteOffer) -> Unit): LanSyncGroupJoiningProfile = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        val offer = requireNotNull(record.offer) { "SYNC_GROUP_JOIN_RECOVERY_NOT_AVAILABLE" }
        require(record.stage in setOf(LanSyncGroupJoinStage.PREPARED, LanSyncGroupJoinStage.PROOF_ATTEMPTED,
            LanSyncGroupJoinStage.WAITING_FOR_APPROVAL, LanSyncGroupJoinStage.KEYS_ADMITTED)) { "SYNC_GROUP_JOIN_INVITATION_REFRESH_NOT_AVAILABLE" }
        parsed.validate(wallTime())
        val replacement = parsed.let { it.copy(bootstrap = it.bootstrap?.let { value ->
            value.copy(dataHeads = Collections.unmodifiableSet(value.dataHeads.toSet()))
        }) }
        val before = requireNotNull(offer.bootstrap); val after = requireNotNull(replacement.bootstrap) { "SYNC_GROUP_JOIN_BOOTSTRAP_REQUIRED" }
        require(replacement.trust.group == trust(record).group && replacement.trust.genesis == trust(record).genesis &&
            replacement.trust.ownerCertificate == trust(record).ownerCertificate && before.vault == after.vault &&
            before.vaultDescriptor == after.vaultDescriptor) { "SYNC_GROUP_JOIN_INVITATION_TRUST_CHANGED" }
        recipient(record); admission(replacement)
        val stage = if (record.stage == LanSyncGroupJoinStage.WAITING_FOR_APPROVAL) LanSyncGroupJoinStage.PROOF_ATTEMPTED else record.stage
        val updated = record.copy(offer = replacement, stage = stage,
            requiredCheckpoints = record.requiredCheckpoints + trust(record).checkpoint + replacement.trust.checkpoint,
            requiredHeads = record.requiredHeads + before.dataHeads + after.dataHeads)
        write(updated); observe("GROUP_JOIN_INVITATION_REFRESHED"); profile(updated)
    }
    /** Re-review is explicit and durable; activation decides from actual retained native progress. */
    suspend fun refreshPreparation(): LanSyncGroupJoiningProfile = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        require(record.stage in setOf(LanSyncGroupJoinStage.KEYS_ADMITTED, LanSyncGroupJoinStage.NATIVE_ENROLLED)) { "SYNC_GROUP_JOIN_REPREPARE_NOT_AVAILABLE" }
        val updated = record.copy(reprepareRequested = true)
        write(updated); profile(updated)
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
    private fun custody(record: Record, create: Boolean) = SyncGroupCustody(directory("custody", create), trust(record).group,
        recipient(record), protect, unprotect)

    suspend fun prove(name: String): LanSyncGroupInviteReceipt = prove(name, null)
    internal suspend fun proveUsing(name: String, network: LanSyncNetwork) = prove(name) { offer ->
        network.connectInvitationOwned(identity, LanSyncPeer(offer.inviter, "邀请设备", offer.host, offer.port)) { }
    }
    internal suspend fun prove(name: String, connect: ((LanSyncGroupInviteOffer) -> SSLSocket)?): LanSyncGroupInviteReceipt = guarded {
        val record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        val offer = requireNotNull(record.offer) { "SYNC_GROUP_JOIN_RECOVERY_NOT_AVAILABLE" }
        require(record.stage in setOf(LanSyncGroupJoinStage.PREPARED, LanSyncGroupJoinStage.PROOF_ATTEMPTED)) { "SYNC_GROUP_JOIN_PROOF_ALREADY_SENT" }
        offer.validate(wallTime())
        val attempted = record.copy(stage = LanSyncGroupJoinStage.PROOF_ATTEMPTED)
        val result = LanSyncGroupInviteClient.exchange(offer, identity, recipient(record), name, {
            if (connect != null) connect(offer)
            else LanSyncTls.connect(identity, offer.host, offer.port, offer.inviter, handshakeTimeoutMillis = 15_000)
        }, now = wallTime(), beforeAnswer = {
            // After an answer leaves, its reply can be lost while the same recipient is approved.
            write(attempted)
            observe("GROUP_JOIN_PROOF_ATTEMPT_RETAINED")
        })
        write(attempted.copy(stage = LanSyncGroupJoinStage.WAITING_FOR_APPROVAL)); observe("GROUP_JOIN_PROOF_SENT")
        result
    }

    /** Backend is a runtime-owned route, never credentials serialized into invitations/profile. */
    suspend fun activate(backend: CiphertextStore): LanSyncGroupJoiningResult = guarded {
        var record = requireNotNull(read()) { "SYNC_GROUP_JOIN_MISSING" }
        if (record.stage == LanSyncGroupJoinStage.READY) return@guarded LanSyncGroupJoiningResult(profile(record), openOwned(record))
        require(record.stage !in setOf(LanSyncGroupJoinStage.PREPARING, LanSyncGroupJoinStage.PREPARED)) { "SYNC_GROUP_JOIN_PROOF_REQUIRED" }
        val bootstrap = bootstrap(record)
        val bootstrapRoot = directory("bootstrap", record.replicaIdentity == null)
        val replicaPath = bootstrapRoot.resolve("ciphertext-mirror")
        record.replicaIdentity?.let { require(lanSyncUtf8(lanSyncRead(replicaPath.resolve("store.id"), 32)) == it) { "SYNC_GROUP_JOIN_REPLICA_CHANGED" } }
        val alreadyAdmitted = record.stage.ordinal >= LanSyncGroupJoinStage.KEYS_ADMITTED.ordinal
        if (alreadyAdmitted) require(Files.isRegularFile(root.resolve("custody/group.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_JOIN_CUSTODY_MISSING" }
        val custody = custody(record, !alreadyAdmitted)
        if (alreadyAdmitted) custody.ledger()
        val replica = FileCiphertextStore(replicaPath)
        val reader = LanSyncGroupReplicaReader(replica, directory("received-ciphertext", true), directory("commit-candidates", true)) { _, failure -> throw failure }
        reader.routes(listOf(LanSyncGroupReplicaReader.Route("JOIN_SOURCE", backend)))
        val inbox = SyncGroupInbox(bootstrapRoot, reader.discoveryFrom(backend), custody, recipient(record))
        if (record.replicaIdentity == null) {
            record = record.copy(replicaIdentity = lanSyncUtf8(lanSyncRead(replicaPath.resolve("store.id"), 32)))
            write(record)
        }
        var cursor: CommitCursor? = null
        do { cursor = inbox.discover(cursor).nextCursor } while (cursor != null)
        val inspection = inbox.prepareEnrollment(trust(record), record.requiredCheckpoints)
        record = retainObservedMembership(record, inspection)
        if (inspection.stage != SyncGroupBootstrapStage.READY) return@guarded LanSyncGroupJoiningResult(profile(record), null,
            inspection.code ?: "SYNC_GROUP_JOIN_${inspection.stage}")
        val evidence = requireNotNull(inspection.evidence)
        val candidate = SyncDeviceLedger(evidence.trust).also { value -> evidence.records.forEach { value.offer(it) } }
        require(candidate.current().coveredTypes == LanSyncScope.schemas.keys) { "SYNC_GROUP_PROFILE_PARTIAL_SCOPE_UNSUPPORTED" }
        require(record.requiredCheckpoints.all(candidate::contains)) { "SYNC_GROUP_JOIN_KNOWN_CHECKPOINT_MISSING" }
        require(candidate.current().members[identity.fingerprint]?.certificate == certificate) { "SYNC_GROUP_JOIN_CERTIFICATE_CHANGED" }
        val owner = candidate.current().members.getValue(bootstrap.vault.owner)
        require(SyncRecipientPublicKey.parse(owner.recipientKey).hash == bootstrap.vault.recipientKey) { "SYNC_GROUP_JOIN_VAULT_CHANGED" }
        val ledger = custody.enrollWithKeys(evidence.trust, evidence.records, evidence.keys)
        if (record.stage.ordinal < LanSyncGroupJoinStage.KEYS_ADMITTED.ordinal) {
            record = record.copy(stage = LanSyncGroupJoinStage.KEYS_ADMITTED, knownMembership = null, membershipRevoked = false)
            write(record); observe("GROUP_JOIN_KEYS_ADMITTED")
        }
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
        if ((bootstrap.dataHeads + record.requiredHeads).any { !history.contains(it) }) return@guarded LanSyncGroupJoiningResult(profile(record), null, "SYNC_GROUP_JOIN_INITIAL_DATA_MISSING")
        if (record.projection != null && record.reprepareRequested) {
            val previous = readProjection(record)
            val node = node(record, previous.projection, reader)
            if (record.stage == LanSyncGroupJoinStage.NATIVE_ENROLLED) {
                node.inspect()
                record = record.copy(reprepareRequested = false); write(record)
            } else when (node.enrollmentProgress()) {
                LanSyncGroupEnrollmentProgress.NONE -> {
                    record = record.copy(projection = null, reprepareRequested = false); write(record)
                }
                LanSyncGroupEnrollmentProgress.IDENTITY_ONLY -> {
                    platform.exclusive { workspace.captureGroup(identity.fingerprint, previous.projection).use { capture ->
                        record = pinProjection(record, previous.copy(sourceFiles = capture.sourceFiles,
                            checkpoint = ledger.current().reference(), heads = history.heads()))
                    } }
                }
                LanSyncGroupEnrollmentProgress.HISTORY -> {
                    node.inspect()
                    record = record.copy(stage = LanSyncGroupJoinStage.NATIVE_ENROLLED, reprepareRequested = false); write(record)
                }
            }
        }
        val projection = if (record.projection != null) readProjection(record) else {
            val service = LanSyncGroupDependencies(trust(record).group, reader, custody, directory("projection-work", true), workspace.resourceStore, validateResource)
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
                workspace.captureGroup(identity.fingerprint).use { capture ->
                    val value = Projection(LanSyncPresetProjection.propose(presets, capture.snapshot.records), capture.sourceFiles,
                        ledger.current().reference(), history.heads())
                    record = pinProjection(record, value); pinned = value
                }
            }
            observe("GROUP_JOIN_PROJECTION_RETAINED")
            requireNotNull(pinned)
        }
        if (record.stage.ordinal < LanSyncGroupJoinStage.NATIVE_ENROLLED.ordinal) {
            require(projection.checkpoint == custody.ledger().current().reference() && projection.heads == history.heads()) { "SYNC_GROUP_JOIN_PROJECTION_VIEW_CHANGED" }
            val node = node(record, projection.projection, reader)
            node.initializeNewEnrollment(projection.sourceFiles)
            record = record.copy(stage = LanSyncGroupJoinStage.NATIVE_ENROLLED); write(record); observe("GROUP_JOIN_NATIVE_ENROLLED")
        }
        val node = node(record, projection.projection, reader)
        cursor = null
        do { cursor = node.discover(reader, cursor).nextCursor } while (cursor != null)
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
        val reader = LanSyncGroupReplicaReader(FileCiphertextStore(path), directory("received-ciphertext", true),
            directory("commit-candidates", true)) { _, failure -> throw failure }
        return node(record, projection.projection, reader).also { it.inspect() }
    }
    private fun node(record: Record, projection: LanSyncProjection, backend: CiphertextStore) = LanSyncGroupNode(directory("node", true), workspace,
        platform, identity, trust(record), custody(record, false), recipient(record), backend, protect, unprotect, { emptySet() }, validateResource, projection, observe)
    private fun identity(path: Path) = SyncPlaintextIdentity(Files.size(path), LanSyncResourceStore.hashFile(path, Files.size(path)))
    private fun pinProjection(record: Record, value: Projection): Record = LanSyncScratch(root, "join-projection").use { scratch ->
        val file = scratch.directory.resolve("projection.json")
        LanSyncJsonFiles.write(file, Projection.serializer(), value); scratch.own(file)
        val pinnedIdentity = identity(file); val name = "projection-${pinnedIdentity.sha256}.json"; val target = root.resolve(name)
        if (!Files.exists(target, NOFOLLOW_LINKS)) Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
            LanSyncJsonFiles.write(target) { output -> input.copyTo(output, 64 * 1024) }
        }
        require(identity(target) == pinnedIdentity) { "SYNC_GROUP_JOIN_PROJECTION_CHANGED" }
        record.copy(projection = pinnedIdentity, projectionFile = name, reprepareRequested = false).also(::write)
    }
    private fun readProjection(record: Record): Projection {
        val file = root.resolve(record.projectionFile); require(identity(file) == record.projection) { "SYNC_GROUP_JOIN_PROJECTION_CHANGED" }
        LanSyncMetadataBudget().validate(file)
        return requireNotNull(LanSyncJsonFiles.read(file, Projection.serializer(), record.projection!!.sha256)).also { it.projection.validate() }
    }
    private fun read(directory: Path = root): Record? {
        val path = directory.resolve("profile.protected")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(!Files.exists(parent.resolve("profile.protected"), NOFOLLOW_LINKS)) { "SYNC_GROUP_MULTIPLE_PROFILES" }
        val plain = unprotect(lanSyncRead(path, 256 * 1024))
        try { return lanSyncJson.decodeFromString(Record.serializer(), lanSyncUtf8(plain)).also {
            require(it.format == 1 && it.certificate == certificate && it.localReplica.kind == SyncDeliveryKind.LOCAL_REPLICA &&
                (it.stage == LanSyncGroupJoinStage.PREPARING || it.recipient != null)) { "SYNC_GROUP_JOIN_PROFILE_CHANGED" }
            require(it.stage.ordinal < LanSyncGroupJoinStage.NATIVE_ENROLLED.ordinal || it.projection != null && it.replicaIdentity != null) { "SYNC_GROUP_JOIN_PROFILE_CHANGED" }
            require((it.knownMembership == null || it.knownMembership in it.requiredCheckpoints) &&
                (!it.membershipRevoked || it.knownMembership != null)) { "SYNC_GROUP_JOIN_PROFILE_CHANGED" }
            require(it.projectionFile == "projection.json" || it.projection?.let { id -> it.projectionFile == "projection-${id.sha256}.json" } == true ||
                it.projection == null && it.projectionFile.matches(Regex("projection-[a-f0-9]{64}\\.json"))) { "SYNC_GROUP_JOIN_PROFILE_CHANGED" }
            val offer = it.offer; val recovery = it.recoveryAnchor
            require((offer == null) != (recovery == null)) { "SYNC_GROUP_JOIN_PROFILE_CHANGED" }
            if (offer != null) requireNotNull(offer.bootstrap).validate(offer.trust, offer.port) else requireNotNull(recovery).validate()
        } } finally { plain.fill(0) }
    }
    private fun checkRetainedMembership(record: Record): Record {
        // Old profiles may already contain verified membership material even with the source offline.
        val expectedReplica = record.replicaIdentity ?: return record
        if (record.knownMembership != null || !canShelve(record, root)) return record
        val bootstrapRoot = directory("bootstrap", false)
        val mirror = bootstrapRoot.resolve("ciphertext-mirror")
        require(lanSyncUtf8(lanSyncRead(mirror.resolve("store.id"), 32)) == expectedReplica) { "SYNC_GROUP_JOIN_REPLICA_CHANGED" }
        val inbox = SyncGroupInbox(bootstrapRoot, FileCiphertextStore(mirror), custody(record, false), recipient(record))
        val inspection = inbox.prepareEnrollment(trust(record), record.requiredCheckpoints)
        require(inspection.stage != SyncGroupBootstrapStage.TRUST_BLOCKED) { "SYNC_GROUP_BOOTSTRAP_LEDGER_CONFLICT" }
        return retainObservedMembership(record, inspection)
    }
    private fun retainObservedMembership(record: Record, inspection: SyncGroupBootstrapInspection): Record {
        val observed = inspection.membershipCheckpoint ?: return record
        val revoked = inspection.stage == SyncGroupBootstrapStage.DEVICE_REVOKED
        if (!revoked && record.stage.ordinal >= LanSyncGroupJoinStage.KEYS_ADMITTED.ordinal) return record
        if (record.knownMembership != null && (!revoked || record.membershipRevoked)) return record
        return record.copy(knownMembership = observed, requiredCheckpoints = record.requiredCheckpoints + observed,
            membershipRevoked = revoked).also(::write)
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
