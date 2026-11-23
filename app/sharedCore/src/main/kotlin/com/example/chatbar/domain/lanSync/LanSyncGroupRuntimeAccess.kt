package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** App-owned daily capability. UI receives only its separate panel projection. */
class LanSyncGroupRuntimeAccess internal constructor(val trust: SyncGroupTrust, val localTarget: SyncDeliveryTarget,
    val replica: CiphertextStore, val work: Path, private val identity: LanSyncTlsIdentity,
    private val custody: SyncGroupCustody, node: (CiphertextStore) -> LanSyncGroupNode,
    val vault: SyncManagementVaultContext, val vaultDescriptor: CiphertextDescriptor,
    peerNameProtect: (ByteArray) -> ByteArray, peerNameUnprotect: (ByteArray) -> ByteArray,
    val localManagementAvailable: Boolean = false, candidateRoot: Path? = null) {
    val device get() = identity.fingerprint
    val certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded)
    val reader = LanSyncGroupReplicaReader(replica, work.resolve("replica-reads"),
        candidateRoot ?: work.resolve("replica-reads/commit-candidates")) { route, failure -> routeFailure(route, failure) }
    val node = node(reader)
    @Volatile var routeFailure: (String, Exception) -> Unit = { _, failure -> throw failure }
    private val notices = mutableMapOf<String, Pair<SyncVersionRef, SyncLedgerNoticeEntry>>()
    private data class PeerBlob(val binding: SyncEnvelopeBlobBinding, val source: SyncAuthenticatedGroupObject, val signedDirectly: Boolean)
    private data class RecoveredReads(val objects: Map<CiphertextObjectKey, Set<CiphertextDescriptor>>,
        val blobs: Map<CiphertextObjectMetadata, List<PeerBlob>>)
    private val offlineReadControls = RecoveredReads(mapOf(vault.key to setOf(vaultDescriptor)), emptyMap())
    private var recoveredReads: Pair<SyncInboxReconciliation, RecoveredReads>? = null
    private val verifiedPeerBlobs = java.util.concurrent.ConcurrentHashMap.newKeySet<LanSyncGroupTargetRawCheck>()
    private val peerProofWork by lazy { Files.createDirectories(work.resolve("peer-object-proof")) }

    @Synchronized private fun recoveredReadScope(checkpoint: SyncVersionRef): RecoveredReads {
        val view = node.peerReadView(checkpoint) ?: return offlineReadControls
        recoveredReads?.takeIf { it.first === view }?.let { return it.second }
        val controls = setOf(SyncInboxStage.KEY_ADMITTED, SyncInboxStage.LEDGER_ADMITTED, SyncInboxStage.NOT_FOR_DEVICE, SyncInboxStage.DEVICE_REVOKED)
        val history = requireNotNull(view.history)
        val forkedAuthors = history.authorForks()
        val allowed = linkedMapOf<CiphertextObjectKey, MutableSet<CiphertextDescriptor>>()
        val blobs = linkedMapOf<CiphertextObjectMetadata, MutableList<PeerBlob>>()
        fun allow(key: CiphertextObjectKey, descriptor: CiphertextDescriptor) { allowed.getOrPut(key) { linkedSetOf() } += descriptor }
        allow(vault.key, vaultDescriptor)
        view.observations.forEach { observation ->
            if (observation.stage in controls) observation.descriptor?.let { allow(observation.key, it) }
        }
        view.authenticatedVersions.forEach { actual ->
            val version = requireNotNull(actual.envelope.version)
            if (history.contains(version.reference()) && version.author !in forkedAuthors) {
                allow(actual.metadata.key, actual.metadata.descriptor)
                val signed = version.changes.values.flatMapTo(linkedSetOf()) { listOfNotNull(it.content, it.membership) }
                // These are private lookup candidates. HEAD and GET independently authenticate the exact binding.
                actual.envelope.blobs.values.forEach { binding ->
                    allow(binding.key, binding.descriptor)
                    val metadata = CiphertextObjectMetadata(binding.key, binding.descriptor)
                    blobs.getOrPut(metadata) { mutableListOf() } += PeerBlob(binding, actual, binding.plaintext in signed)
                }
            }
        }
        return RecoveredReads(allowed.mapValues { it.value.toSet() }, blobs.mapValues { it.value.distinct() })
            .also { recoveredReads = view to it }
    }
    private fun verifyPeerBlob(checkpoint: SyncVersionRef, metadata: CiphertextObjectMetadata): Boolean {
        if (metadata.key == vault.key && metadata.descriptor == vaultDescriptor) return true
        val bindings = recoveredReadScope(checkpoint).blobs[metadata].orEmpty()
        return bindings.any { (binding, source, signedDirectly) ->
            if (!signedDirectly && !node.isDeclaredPeerResource(source, binding.plaintext)) return@any false
            val key = custody.key(binding.epoch)
            val proof = LanSyncGroupTargetRawCheck(trust.group, binding.epoch, key.keyCommitment(), metadata, binding.plaintext)
            if (proof !in verifiedPeerBlobs) {
                try { SyncGroupBlobStore(trust.group, peerProofWork).read(reader.cachedOnly(), key, binding, binding.plaintext).use { } }
                catch (failure: IllegalArgumentException) {
                    if (failure.message?.startsWith("SYNC_") == true) return@any false
                    throw failure
                }
                verifiedPeerBlobs += proof
            }
            true
        }
    }
    fun ledger() = custody.ledger()
    internal fun requiresRecoveryProtocol(group: SyncStoreId): Boolean {
        require(group == trust.group) { "SYNC_GROUP_WIRE_GROUP" }
        return ledger().ancestry().any { it.recovery != null }
    }
    internal fun canOfferLedgerTo(peer: String): Boolean {
        val graph = ledger()
        if (graph.ancestry().any { it.recovery != null }) return false
        val members = graph.current().members
        return members[device]?.active == true && members[peer]?.active == true
    }
    internal fun catchUpLedger(peer: LanSyncPeer, connect: (LanSyncTlsIdentity, LanSyncPeer) -> SSLSocket): Boolean {
        val graph = ledger()
        if (graph.ancestry().any { it.recovery != null }) return false
        val current = graph.current()
        if (current.members[device]?.active != true || peer.fingerprint in current.members) return false
        val checkpoint = current.reference()
        val (actualPeer, envelope) = connect(identity, peer).use { socket ->
            val chain = socket.session.peerCertificates
            require(chain.size == 1 && lanSyncHash(chain.single().encoded) == peer.fingerprint) {
                "SYNC_GROUP_LEDGER_RELAY_TLS"
            }
            val actualPeer = Base64.getEncoder().encodeToString(chain.single().encoded)
            val received = receiveGroupLedger(socket, trust.group, checkpoint) ?: return false
            actualPeer to received
        }
        if (requiresRecoveryProtocol(trust.group)) return false
        return custody.extendFromPeer(checkpoint, envelope, certificate, actualPeer)
    }
    private val peerNames by lazy { LanSyncGroupPeerNames(work, trust, custody::ledger, peerNameProtect, peerNameUnprotect) }
    private val cloudRouteCatalog by lazy {
        val directory = work.resolve("cloud-connections")
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
        lanSyncSafe(directory, true)
        LanSyncGroupCloudRoutes(directory, trust, device, peerNameProtect, peerNameUnprotect)
    }
    fun cloudRoutes(): LanSyncGroupCloudRoutes = cloudRouteCatalog
    fun rememberPeerName(peer: LanSyncGroupInvitedPeer) = peerNames.remember(peer)
    internal fun rememberAdmittedPeerName(peer: LanSyncGroupInvitedPeer, admitted: SyncDeviceLedger) = peerNames.rememberAdmitted(peer, admitted)
    fun peerDisplayName(device: String): String? = peerNames.displayName(device)
    internal fun recoveryNames(graph: SyncDeviceLedger, tips: List<SyncDeviceLedgerRecord>) = peerNames.recoveryNames(graph, tips)
    internal fun verifiedLedgerFork() = custody.verifiedFork()
    internal val membershipRecovery by lazy { LanSyncGroupMembershipRecovery(this, custody, peerNameProtect, peerNameUnprotect) }
    private val managementRoot get() = work.resolve("recovered-management")
    fun hasManagementRecoveryProfile() = Files.exists(managementRoot.resolve("profile.protected"), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    fun managementRecovery(): LanSyncGroupManagementRecovery {
        if (!Files.exists(managementRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(managementRoot)
        lanSyncSafe(managementRoot, true)
        return LanSyncGroupManagementRecovery(managementRoot, knownCheckpoint = { ledger().current().reference() })
    }
    fun encryptedManagementBackup() = lanSyncReadManagementBackup(reader, vault, vaultDescriptor)
    /** Uses only this profile's fixed opaque vault and Node's actual admitted history. No admin unlock. */
    suspend fun targetPublication(directory: Path, target: SyncDeliveryTarget,
        recoverNative: Boolean = true, prepare: Boolean = true): LanSyncGroupTargetPublication =
        node.targetPublication(directory, target, vault, vaultDescriptor, recoverNative, prepare)
    internal suspend fun partialTargetPublication(directory: Path, target: SyncDeliveryTarget,
        recoverNative: Boolean = true): LanSyncGroupPartialTargetPublication =
        node.partialTargetPublication(directory, target, vault, vaultDescriptor, recoverNative)
    internal suspend fun prepareEncryptedFile(directory: Path, checkActive: () -> Unit): LanSyncGroupEncryptedFilePackage.Prepared =
        node.prepareEncryptedFile(directory, vault, vaultDescriptor, checkActive)
    internal suspend fun prepareEncryptedFile(directory: Path, checkActive: () -> Unit,
        receive: (LanSyncGroupEncryptedFilePackage.Prepared) -> Unit) =
        node.prepareEncryptedFile(directory, vault, vaultDescriptor, checkActive, receive)
    suspend fun invitation(storePort: Int): LanSyncGroupInvitation {
        val published = reader.cachedOnly()
        lanSyncReadManagementBackup(published, vault, vaultDescriptor)
        val (checkpoint, heads) = node.publishedInvitationHistory(published)
        return LanSyncGroupInvitation(identity, trust, custody::ledger,
            bootstrap = LanSyncGroupJoinBootstrap(storePort, vault, vaultDescriptor, heads), publishedCheckpoint = checkpoint)
    }
    fun tlsContext(): SSLContext = identity.context { peer ->
        val current = ledger().current()
        current.members[device]?.active == true && current.members.containsKey(peer)
    }
    fun peerStore(group: SyncStoreId, localCertificate: String, peerCertificate: String): LanSyncGroupPeerStore {
        require(group == trust.group && localCertificate == certificate) { "SYNC_GROUP_RUNTIME_TLS_IDENTITY" }
        if (!requiresRecoveryProtocol(group)) return LanSyncGroupPeerStore(trust, localCertificate, peerCertificate, custody::ledger, reader,
            null, null, false, ::notice)
        return LanSyncGroupPeerStore(trust, localCertificate, peerCertificate, custody::ledger, reader,
            { checkpoint -> recoveredReadScope(checkpoint).objects }, ::verifyPeerBlob, true, ::notice)
    }
    @Synchronized private fun notice(device: String): SyncLedgerNoticeEntry? {
        val checkpoint = ledger().current().reference()
        notices[device]?.takeIf { it.first == checkpoint }?.let { return it.second }
        var cursor: CommitCursor? = null
        do {
            val page = reader.listCommits(cursor)
            for (metadata in page.entries) {
                if (metadata.descriptor.length > SyncLedgerNoticeCodec.MAX_BYTES) continue
                try {
                val output = ByteArrayOutputStream()
                val prefix = ByteArrayOutputStream()
                val sink = object : java.io.OutputStream() {
                    var accepted: Boolean? = null
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(length >= 0)
                        if (accepted == false) return
                        var position = offset; var remaining = length
                        if (accepted == null) {
                            val count = minOf(32 - prefix.size(), remaining); prefix.write(bytes, position, count); position += count; remaining -= count
                            if (prefix.size() == 32) {
                                accepted = SyncLedgerNoticeCodec.hasPrefix(prefix.toByteArray())
                                if (accepted == true) output.write(prefix.toByteArray())
                            }
                        }
                        if (accepted == true) {
                            require(output.size().toLong() + remaining <= SyncLedgerNoticeCodec.MAX_BYTES) { "SYNC_LEDGER_NOTICE_BUDGET" }
                            output.write(bytes, position, remaining)
                        }
                    }
                }
                reader.readTo(metadata.key, sink, metadata.descriptor)
                if (!SyncLedgerNoticeCodec.hasPrefix(output.toByteArray())) continue
                val value = SyncLedgerNoticeCodec.decode(output.toByteArray())
                if (value.context.recipient != device) continue
                value.verifyOwner(trust, metadata.key)
                require(value.context.revocation == SyncLedgerNotice.revocations(ledger())[device]) { "SYNC_GROUP_PEER_NOTICE_CHANGED" }
                val entry = SyncLedgerNoticeEntry(value, CiphertextPutRequest(metadata.key, value.context.objectId, metadata.descriptor))
                notices[device] = checkpoint to entry; return entry
                } catch (failure: Exception) { routeFailure("REVOCATION_NOTICE", failure) }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return null
    }
    fun remote(peer: LanSyncPeer, network: LanSyncNetwork): LanSyncGroupRemoteStore {
        val directory = work.resolve("peer-${peer.fingerprint}"); Files.createDirectories(directory)
        lanSyncSafe(directory, true)
        return LanSyncGroupRemoteStore(trust.group, directory, {
            val members = ledger().current().members
            require(members[device]?.active == true && members[peer.fingerprint]?.active == true) {
                "SYNC_GROUP_RUNTIME_PEER_NOT_ACTIVE"
            }
            network.connect(identity, peer).also { it.soTimeout = 120_000 }
        }, authenticatedConnect = true, requiresRecoveryProtocol = { requiresRecoveryProtocol(trust.group) })
    }
    override fun toString() = "LanSyncGroupRuntimeAccess(REDACTED)"
}
