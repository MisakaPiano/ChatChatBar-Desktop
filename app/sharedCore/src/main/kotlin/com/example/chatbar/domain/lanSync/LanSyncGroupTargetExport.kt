package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Only Node constructs this frozen admission view. It contains no signing or native-write capability. */
internal class LanSyncGroupTargetExport(val trust: SyncGroupTrust, val device: String,
    val ledger: SyncDeviceLedger, val view: SyncInboxReconciliation,
    val vault: SyncManagementVaultContext, val vaultDescriptor: CiphertextDescriptor,
    val source: CiphertextStore, val authority: () -> SyncDeviceLedger,
    val cipher: (SyncStoreId) -> SyncStreamingCipher,
    private val custody: SyncGroupCustody, val cache: LanSyncResourceStore,
    val validateResource: (String, Path, LanSyncRecord) -> Unit) {
    val current = ledger.current()
    val versions = requireNotNull(view.history).admittedVersions().sortedBy { it.id.value }
    init {
        require(!view.trustBlocked && view.ledgerCheckpoint == current.reference() &&
            requireNotNull(view.history).authorForks().isEmpty()) { "SYNC_GROUP_TARGET_ADMISSION_REQUIRED" }
        require(vault.group == trust.group && vault.genesis == trust.genesis &&
            vault.owner == SyncDeviceSignatures.decode(trust.ownerCertificate).fingerprint) { "SYNC_GROUP_TARGET_VAULT_CHANGED" }
        requireLive()
    }
    fun requireLive() {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("SYNC_GROUP_TARGET_INTERRUPTED")
        val live = authority()
        require(live.trustAnchor.group == trust.group && live.trustAnchor.genesis == trust.genesis &&
            live.ownerCertificate == trust.ownerCertificate && live.contains(trust.checkpoint) &&
            live.current() == current && live.current().members[device]?.active == true) { "SYNC_GROUP_TARGET_AUTHORITY_CHANGED" }
    }
    fun keySlots() = ledger.history().map { it.epoch }.distinct().flatMap { epoch ->
        current.members.filterValues { it.active }.keys.map { recipient -> "${epoch.value}:$recipient" }
    }.toSet()
    fun capsule(value: SyncGroupKeyDelivery, key: CiphertextObjectKey): String {
        value.verifyOwner(trust, key)
        val context = value.context
        require(context.authorizingLedger == current.reference() &&
            ledger.hasKey(context.epoch, context.keyCommitment, current.reference()) &&
            current.members[context.recipient]?.let { it.active &&
                SyncRecipientPublicKey.parse(it.recipientKey).hash == context.recipientKeyHash } == true) { "SYNC_GROUP_TARGET_CAPSULE_CHANGED" }
        return "${context.epoch.value}:${context.recipient}"
    }
    fun notice(value: SyncLedgerNotice, key: CiphertextObjectKey): String {
        value.verifyOwner(trust, key)
        val context = value.context
        require(SyncLedgerNotice.revocations(ledger)[context.recipient] == context.revocation &&
            SyncRecipientPublicKey.parse(requireNotNull(ledger.record(context.revocation)).members.getValue(context.recipient).recipientKey).hash ==
                context.recipientKeyHash) { "SYNC_GROUP_TARGET_NOTICE_CHANGED" }
        return context.recipient
    }
    fun root(value: SyncAuthenticatedGroupObject) {
        require(value.context.epoch == current.epoch && value.keyCommitment == current.keyCommitment &&
            value.envelope.deviceLedger == current && value.envelope.ledgerHistory == ledger.history().dropLast(1)) { "SYNC_GROUP_TARGET_LEDGER_CHANGED" }
    }
    fun version(value: SyncAuthenticatedGroupObject) {
        val version = requireNotNull(value.envelope.version) { "SYNC_GROUP_TARGET_VERSION_CHANGED" }
        require(value.context.epoch == current.epoch && value.keyCommitment == current.keyCommitment &&
            versions.any { it.reference() == version.reference() } && requireNotNull(view.history).contains(version.reference())) { "SYNC_GROUP_TARGET_CURRENT_CONTAINER_REQUIRED" }
        ledger.admission(versions, view.authenticatedVersions + value).requireAuthorized(version)
    }
    fun dependencies(values: List<SyncAuthenticatedGroupObject>, store: CiphertextStore, work: Path) {
        val inspected = view.copy(authenticatedVersions = view.authenticatedVersions + values)
        val service = LanSyncGroupDependencies(trust.group, store, custody, work, cache, validateResource)
        for (actual in values) {
            requireLive(); version(actual)
            val expected = linkedMapOf<String, SyncPlaintextIdentity>()
            fun add(identity: SyncPlaintextIdentity) {
                require(expected.put(identity.sha256, identity)?.let { it == identity } != false) { "SYNC_GROUP_TARGET_DEPENDENCY_CHANGED" }
            }
            for (binding in actual.envelope.blobs.values) {
                requireLive()
                // A plaintext cache hit is insufficient evidence that the original ciphertext exists.
                SyncGroupBlobStore(trust.group, work).read(store, cipher(binding.epoch), binding, binding.plaintext).use { }
            }
            for ((unit, change) in requireNotNull(actual.envelope.version).changes) if (change.content != null) {
                val result = service.inspect(inspected, actual, unit)
                require(result.stage == LanSyncGroupDependencyStage.UNIT_CONTENT_AVAILABLE) { "SYNC_GROUP_TARGET_DEPENDENCY_INCOMPLETE" }
                val decoded = requireNotNull(result.available).decoded
                add(decoded.plaintext); decoded.dependencies.forEach(::add)
                change.membership?.let { require(it == decoded.membership) { "SYNC_GROUP_TARGET_DEPENDENCY_CHANGED" }; add(it) }
            }
            require(actual.envelope.blobs.mapValues { it.value.plaintext } == expected) { "SYNC_GROUP_TARGET_DEPENDENCY_CHANGED" }
        }
        requireLive()
    }
}

internal class LanSyncGroupTargetStage(val metadata: CiphertextObjectMetadata, val file: PrivateSyncFile) : AutoCloseable {
    override fun close() = file.close()
}

internal fun lanSyncTargetStage(work: Path, source: CiphertextStore, metadata: CiphertextObjectMetadata,
    live: () -> Unit): LanSyncGroupTargetStage = lanSyncTargetReceiveStage(work, metadata, live) {
        source.readTo(metadata.key, it, metadata.descriptor)
    }

/** Provider read observations enter an owned preparation, never a pretend strict write backend. */
internal fun lanSyncTargetReceiveStage(work: Path, metadata: CiphertextObjectMetadata, live: () -> Unit,
    receive: (OutputStream) -> CiphertextObjectMetadata?): LanSyncGroupTargetStage {
    live(); LanSyncResourceBudget().requireSpace(work, metadata.descriptor.length, copies = 2)
    val file = PrivateSyncFile.create(work)
    try {
        FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
            val output = Channels.newOutputStream(channel)
            val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
            val checked = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    live(); require(length >= 0 && count <= metadata.descriptor.length - length) { "SYNC_GROUP_TARGET_OBJECT_CHANGED" }
                    output.write(bytes, offset, length); digest.update(bytes, offset, length); count += length
                }
            }
            require(receive(checked) == metadata && count == metadata.descriptor.length &&
                digest.digest().joinToString("") { "%02x".format(it) } == metadata.descriptor.sha256.hex) { "SYNC_GROUP_TARGET_OBJECT_INCOMPLETE" }
            channel.force(true)
        }
        live(); return LanSyncGroupTargetStage(metadata, file)
    } catch (failure: Throwable) { file.close(); throw failure }
}

internal fun lanSyncTargetLens(stages: Collection<LanSyncGroupTargetStage>, live: () -> Unit = {}): CiphertextStore = object : CiphertextStore {
    override val capabilities = CiphertextStoreCapabilities(CommitListingSemantics.KEYSET_RESCAN_REQUIRED)
    override fun put(request: CiphertextPutRequest, source: java.io.InputStream): CiphertextPutResult = error("SYNC_GROUP_TARGET_READ_ONLY")
    override fun metadata(key: CiphertextObjectKey) = stages.firstOrNull { it.metadata.key == key }?.metadata
    override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
        val stage = stages.firstOrNull { it.metadata.key == key && (expected == null || it.metadata.descriptor == expected) } ?: return null
        live()
        PrivateSyncFile.checked(stage.file.path)
        Files.newInputStream(stage.file.path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { live(); val count = input.read(buffer); if (count < 0) break; destination.write(buffer, 0, count) }
        }
        live()
        return stage.metadata
    }
    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = error("SYNC_GROUP_TARGET_EXACT_READ_REQUIRED")
}

/** Retained reads are admitted only for exact objects with authenticated historical target receipts. */
internal fun lanSyncTargetObservedLens(stages: Collection<LanSyncGroupTargetStage>, historical: Set<CiphertextObjectMetadata>,
    retained: CiphertextStore, live: () -> Unit): CiphertextStore {
    val fresh = lanSyncTargetLens(stages, live)
    val admitted = historical.associateBy { it.key }
    return object : CiphertextStore by fresh {
        override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? {
            fresh.metadata(key)?.let { return it }
            val expected = admitted[key] ?: return null
            live(); val actual = retained.metadata(key)
            require(actual == expected) { "SYNC_GROUP_TARGET_HISTORICAL_OBJECT_CHANGED" }; live()
            return actual
        }
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
            if (stages.any { it.metadata.key == key && (expected == null || it.metadata.descriptor == expected) })
                return fresh.readTo(key, destination, expected)
            val known = admitted[key]?.takeIf { expected == null || it.descriptor == expected } ?: return null
            live(); val actual = retained.readTo(key, destination, known.descriptor)
            require(actual == known) { "SYNC_GROUP_TARGET_HISTORICAL_OBJECT_CHANGED" }; live()
            return actual
        }
    }
}

internal fun lanSyncTargetPrefix(stage: LanSyncGroupTargetStage): ByteArray = Files.newInputStream(stage.file.path, NOFOLLOW_LINKS).use { input ->
    val bytes = ByteArray(32); var size = 0
    while (size < bytes.size) { val count = input.read(bytes, size, bytes.size - size); if (count < 0) break; size += count }
    bytes.copyOf(size)
}

internal fun lanSyncTargetCandidateFailure(failure: Exception) {
    if (failure is CancellationException || failure is InterruptedException || failure is java.io.InterruptedIOException) throw failure
}
