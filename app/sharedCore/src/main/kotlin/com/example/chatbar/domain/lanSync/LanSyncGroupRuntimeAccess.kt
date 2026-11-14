package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.net.ssl.SSLContext

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
    fun ledger() = custody.ledger()
    private val peerNames by lazy { LanSyncGroupPeerNames(work, trust, custody::ledger, peerNameProtect, peerNameUnprotect) }
    private val cloudRouteCatalog by lazy {
        val directory = work.resolve("cloud-connections")
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
        lanSyncSafe(directory, true)
        LanSyncGroupCloudRoutes(directory, trust, device, peerNameProtect, peerNameUnprotect)
    }
    fun cloudRoutes(): LanSyncGroupCloudRoutes = cloudRouteCatalog
    fun rememberPeerName(peer: LanSyncGroupInvitedPeer) = peerNames.remember(peer)
    fun peerDisplayName(device: String): String? = peerNames.displayName(device)
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
        val inspected = node.inspect(); val plan = requireNotNull(inspected.plan) { "SYNC_GROUP_INVITE_DATA_NOT_READY" }
        require(plan.before.hashes().all { (unit, hash) -> inspected.nativeState.units[unit]?.valueHash == hash }) { "SYNC_GROUP_INVITE_PUBLISH_LOCAL_FIRST" }
        val current = ledger().current()
        val anchor = trust.copy(checkpoint = current.reference())
        return LanSyncGroupInvitation(identity, anchor, custody::ledger, bootstrap = LanSyncGroupJoinBootstrap(storePort,
            vault, vaultDescriptor, requireNotNull(inspected.reconciliation.history).heads()))
    }
    fun tlsContext(): SSLContext = identity.context { peer ->
        val current = ledger().current()
        current.members[device]?.active == true && current.members.containsKey(peer)
    }
    fun peerStore(group: SyncStoreId, localCertificate: String, peerCertificate: String): LanSyncGroupPeerStore {
        require(group == trust.group && localCertificate == certificate) { "SYNC_GROUP_RUNTIME_TLS_IDENTITY" }
        return LanSyncGroupPeerStore(trust, localCertificate, peerCertificate, custody::ledger, reader, ::notice)
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
            network.connect(identity, peer).also { it.soTimeout = 120_000 }
        }, authenticatedConnect = true)
    }
    override fun toString() = "LanSyncGroupRuntimeAccess(REDACTED)"
}
