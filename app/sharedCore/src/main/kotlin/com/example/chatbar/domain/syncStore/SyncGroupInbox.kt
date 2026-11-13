package com.example.chatbar.domain.syncStore

import com.example.chatbar.domain.lanSync.LanSyncResourceBudget
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.CancellationException

enum class SyncInboxStage {
    MIRRORED, WAITING_FOR_KEY, WAITING_FOR_LEDGER, WAITING_FOR_CURRENT_EPOCH, NOT_FOR_DEVICE,
    KEY_ADMITTED, LEDGER_ADMITTED, DEVICE_REVOKED, CAUSAL_ADMITTED, WAITING_FOR_PARENTS, REJECTED, TRUST_BLOCKED, LOCAL_CUSTODY_BLOCKED, INVALID_OBJECT
}
data class SyncInboxObservation(val key: CiphertextObjectKey, val stage: SyncInboxStage, val code: String? = null)
data class SyncInboxDiscovery(val nextCursor: CommitCursor?, val observations: List<SyncInboxObservation>)
data class SyncInboxReconciliation(val observations: List<SyncInboxObservation>, val history: SyncCausalHistory?,
    val authenticatedVersions: List<SyncAuthenticatedGroupObject>, val trustBlocked: Boolean, val ledgerCheckpoint: SyncVersionRef?,
    val trustFailureCode: String? = null)

/**
 * Discovery and causal metadata admission only. Encrypted evidence survives process restart.
 * A causal admission does not mean resources are available, a reduction is approved, or native data changed.
 * Callers serialize passes on their I/O owner and periodically restart backend discovery from null.
 */
class SyncGroupInbox(private val root: Path, private val backend: CiphertextStore,
    private val custody: SyncGroupCustody, private val recipient: SyncDeviceEncryptionKey,
    private val observe: (SyncInboxObservation) -> Unit = {}) {
    private val work: Path
    private val mirror: FileCiphertextStore
    private val resourceBudget = LanSyncResourceBudget()
    init {
        PrivateSyncFile.checked(root, true)
        work = root.resolve("preparations")
        if (!Files.exists(work, NOFOLLOW_LINKS)) Files.createDirectory(work)
        PrivateSyncFile.checked(work, true)
        mirror = FileCiphertextStore(root.resolve("ciphertext-mirror"))
    }

    /** A null next cursor is an ended discovery scan, never a complete/fresh group checkpoint. */
    fun discover(cursor: CommitCursor? = null, pageSize: Int = 100): SyncInboxDiscovery {
        val page = backend.listCommits(cursor, pageSize)
        val observations = page.entries.map { metadata ->
            try { retain(metadata); SyncInboxObservation(metadata.key, SyncInboxStage.MIRRORED) }
            catch (failure: Exception) { SyncInboxObservation(metadata.key, SyncInboxStage.INVALID_OBJECT, code(failure)) }
                .also(observe)
        }
        return SyncInboxDiscovery(page.nextCursor, observations)
    }

    fun reconcile(localVersions: Collection<SyncAuthenticatedGroupObject> = emptyList()): SyncInboxReconciliation {
        val trust = localCustody { custody.trust() }
        val statuses = linkedMapOf<CiphertextObjectKey, SyncInboxObservation>()
        fun status(key: CiphertextObjectKey, stage: SyncInboxStage, code: String? = null) {
            statuses[key] = SyncInboxObservation(key, stage, code).also(observe)
        }
        val objects = mutableListOf<CiphertextObjectMetadata>(); var cursor: CommitCursor? = null
        do { val page = mirror.listCommits(cursor); objects += page.entries; cursor = page.nextCursor } while (cursor != null)
        val hints = linkedMapOf<CiphertextObjectKey, SyncCiphertextHint>()
        val tentative = linkedMapOf<CiphertextObjectKey, SyncTentativeGroupKey>()
        for (metadata in objects) {
            try {
                spool(mirror, metadata) { path ->
                    val prefix = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                        val bytes = ByteArray(32); var read = 0
                        while (read < bytes.size) { val count = input.read(bytes, read, bytes.size - read); if (count < 0) break; read += count }
                        bytes.copyOf(read)
                    }
                    when {
                        SyncCiphertextHeader.hasPrefix(prefix) -> {
                            val hint = Files.newInputStream(path, NOFOLLOW_LINKS).use(SyncCiphertextHeader::peek)
                            require(hint.context.group == trust.group && hint.context.key == metadata.key) { "SYNC_INBOX_CONTEXT" }
                            hints[metadata.key] = hint
                        }
                        SyncKeyDeliveryCodec.hasPrefix(prefix) -> {
                            require(metadata.descriptor.length <= SyncKeyDeliveryCodec.MAX_BYTES) { "SYNC_KEY_DELIVERY_BUDGET" }
                            val delivery = SyncKeyDeliveryCodec.decode(Files.newInputStream(path, NOFOLLOW_LINKS).use(SyncKeyDeliveryCodec::read))
                            delivery.verifyOwner(trust, metadata.key)
                            if (delivery.context.recipient != recipient.device) status(metadata.key, SyncInboxStage.NOT_FOR_DEVICE)
                            else tentative[metadata.key] = delivery.openTentative(trust, recipient, metadata.key)
                        }
                        SyncLedgerNoticeCodec.hasPrefix(prefix) -> {
                            require(metadata.descriptor.length <= SyncLedgerNoticeCodec.MAX_BYTES) { "SYNC_LEDGER_NOTICE_BUDGET" }
                            val notice = SyncLedgerNoticeCodec.decode(Files.newInputStream(path, NOFOLLOW_LINKS).use(SyncLedgerNoticeCodec::read))
                            notice.verifyOwner(trust, metadata.key)
                            if (notice.context.recipient != recipient.device) status(metadata.key, SyncInboxStage.NOT_FOR_DEVICE)
                            else {
                                val verified = notice.open(trust, recipient, metadata.key)
                                val updated = localCustody { custody.update(verified.records) }
                                require(updated.current().members[recipient.device]?.active == false) { "SYNC_LEDGER_NOTICE_REVOCATION" }
                                status(metadata.key, SyncInboxStage.DEVICE_REVOKED)
                            }
                        }
                        else -> error("SYNC_INBOX_FORMAT")
                    }
                }
            } catch (failure: Exception) {
                val code = code(failure)
                status(metadata.key, when (code) {
                    "SYNC_GROUP_LEDGER_FORK_OR_MISSING" -> SyncInboxStage.TRUST_BLOCKED
                    "SYNC_INBOX_LOCAL_CUSTODY" -> SyncInboxStage.LOCAL_CUSTODY_BLOCKED
                    else -> SyncInboxStage.INVALID_OBJECT
                }, code)
            }
        }
        val codec = SyncGroupObjectCodec(work)
        val decoded = linkedMapOf<CiphertextObjectKey, SyncAuthenticatedGroupObject>()
        fun knownKey(hint: SyncCiphertextHint, allowTentative: Boolean): SyncStreamingCipher? {
            val key = try { localCustody { custody.key(hint.context.epoch) } } catch (failure: IllegalArgumentException) {
                if (failure.message == "SYNC_GROUP_CUSTODY_KEY_MISSING") null else throw failure
            }
            if (key != null) {
                require(key.keyCommitment() == hint.keyCommitment) { "SYNC_INBOX_KEY_COMMITMENT" }; return key
            }
            return if (allowTentative) tentative.values.firstOrNull {
                it.context.epoch == hint.context.epoch && it.context.keyCommitment == hint.keyCommitment
            }?.cipher else null
        }
        var progress: Boolean
        do {
            progress = false
            for ((key, hint) in hints) {
                try {
                    val cipher = knownKey(hint, true)
                    if (cipher == null) { status(key, SyncInboxStage.WAITING_FOR_KEY); continue }
                    val actual = decoded[key] ?: codec.read(mirror, cipher, hint.context).also { decoded[key] = it }
                    val row = actual.envelope.deviceLedger ?: continue
                    require(row.epoch == hint.context.epoch && row.keyCommitment == actual.keyCommitment) { "SYNC_INBOX_LEDGER_KEY_MISMATCH" }
                    val before = try { localCustody { custody.ledger().current().reference() } } catch (failure: IllegalArgumentException) {
                        if (failure.message == "SYNC_GROUP_LEDGER_FORK_OR_MISSING") null else throw failure
                    }
                    // Validate remote signatures/identities before entering the local protected writer.
                    val validation = SyncDeviceLedger(trust)
                    (actual.envelope.ledgerHistory + row).forEach { validation.offer(it) }
                    val updated = localCustody { custody.update(actual.envelope.ledgerHistory + row) }
                    if (before != updated.current().reference()) progress = true
                    when (updated.state(row.id)) {
                        SyncVersionState.ADMITTED -> status(key, SyncInboxStage.LEDGER_ADMITTED)
                        SyncVersionState.WAITING_FOR_PARENTS -> status(key, SyncInboxStage.WAITING_FOR_LEDGER)
                        else -> status(key, SyncInboxStage.REJECTED, updated.failure(row.id))
                    }
                } catch (failure: Exception) {
                    val code = code(failure)
                    status(key, when (code) {
                        "SYNC_GROUP_LEDGER_FORK_OR_MISSING" -> SyncInboxStage.TRUST_BLOCKED
                        "SYNC_INBOX_LOCAL_CUSTODY" -> SyncInboxStage.LOCAL_CUSTODY_BLOCKED
                        else -> SyncInboxStage.INVALID_OBJECT
                    }, code)
                }
            }
            for ((key, candidate) in tentative) {
                try {
                    val admitted = candidate.admit(localCustody { custody.ledger() })
                    val prior = try { localCustody { custody.key(candidate.context.epoch) } } catch (failure: IllegalArgumentException) {
                        if (failure.message == "SYNC_GROUP_CUSTODY_KEY_MISSING") null else throw failure
                    }
                    if (prior == null) { localCustody { custody.rememberKey(admitted) }; progress = true }
                    else require(prior.keyCommitment() == admitted.cipher.keyCommitment()) { "SYNC_INBOX_KEY_COMMITMENT" }
                    status(key, SyncInboxStage.KEY_ADMITTED)
                } catch (failure: Exception) {
                    val code = code(failure)
                    status(key, when (code) {
                        "SYNC_KEY_DELIVERY_LEDGER_MISSING" -> SyncInboxStage.WAITING_FOR_LEDGER
                        "SYNC_GROUP_LEDGER_FORK_OR_MISSING" -> SyncInboxStage.TRUST_BLOCKED
                        "SYNC_INBOX_LOCAL_CUSTODY" -> SyncInboxStage.LOCAL_CUSTODY_BLOCKED
                        else -> SyncInboxStage.REJECTED
                    }, code)
                }
            }
        } while (progress)
        val ledger = try { localCustody { custody.ledger() } } catch (failure: Exception) {
            return SyncInboxReconciliation(statuses.values.toList(), null, emptyList(), true, null, code(failure))
        }
        // Tentative secrets are permitted only for signed ledger bootstrap, never content admission.
        val versions = mutableListOf<SyncAuthenticatedGroupObject>()
        for ((key, hint) in hints) {
            try {
                val cipher = knownKey(hint, false)
                if (cipher == null) { status(key, SyncInboxStage.WAITING_FOR_KEY); continue }
                val actual = decoded[key] ?: codec.read(mirror, cipher, hint.context)
                if (actual.envelope.version != null) versions += actual
            } catch (failure: Exception) {
                val code = code(failure)
                status(key, if (code == "SYNC_INBOX_LOCAL_CUSTODY") SyncInboxStage.LOCAL_CUSTODY_BLOCKED else SyncInboxStage.INVALID_OBJECT, code)
            }
        }
        // Retained outbox AEAD witnesses keep this device's approved, not-yet-uploaded facts in
        // its causal view while offline. They cannot introduce local ledger/key trust.
        for (actual in localVersions.toList()) {
            try {
                require(actual.context.group == trust.group && actual.envelope.group == trust.group &&
                    actual.context.key.kind == CiphertextObjectKind.COMMIT && actual.context.key == actual.metadata.key &&
                    actual.envelope.version != null) { "SYNC_INBOX_LOCAL_VERSION_CONTEXT" }
                val retained = versions.firstOrNull { it.context.key == actual.context.key }
                require(retained == null || retained.context == actual.context && retained.metadata == actual.metadata &&
                    retained.envelope == actual.envelope && retained.keyCommitment == actual.keyCommitment) { "SYNC_INBOX_IMMUTABLE_CONFLICT" }
                if (retained == null) versions += actual
            } catch (failure: Exception) { status(actual.context.key, SyncInboxStage.INVALID_OBJECT, code(failure)) }
        }
        val logical = versions.map { requireNotNull(it.envelope.version) }
        val history = SyncCausalHistory(trust.group, ledger.admission(logical, versions))
        for (actual in versions) {
            val version = requireNotNull(actual.envelope.version)
            try { history.offer(version) } catch (failure: Exception) {
                val code = code(failure)
                status(actual.context.key, when (code) {
                    "SYNC_GROUP_LEDGER_MISSING", "SYNC_GROUP_FENCE_CONTENT_MISSING" -> SyncInboxStage.WAITING_FOR_LEDGER
                    "SYNC_GROUP_CURRENT_EPOCH_REQUIRED" -> SyncInboxStage.WAITING_FOR_CURRENT_EPOCH
                    else -> SyncInboxStage.REJECTED
                }, code)
            }
        }
        versions.forEach { actual -> if (statuses[actual.context.key]?.stage !in setOf(SyncInboxStage.REJECTED,
            SyncInboxStage.INVALID_OBJECT, SyncInboxStage.LOCAL_CUSTODY_BLOCKED,
            SyncInboxStage.WAITING_FOR_LEDGER, SyncInboxStage.WAITING_FOR_CURRENT_EPOCH)) {
            val version = requireNotNull(actual.envelope.version)
            status(actual.context.key, when (history.state(version.id)) {
                SyncVersionState.ADMITTED -> SyncInboxStage.CAUSAL_ADMITTED
                SyncVersionState.WAITING_FOR_PARENTS -> SyncInboxStage.WAITING_FOR_PARENTS
                else -> SyncInboxStage.REJECTED
            }, history.failure(version.id))
        } }
        return SyncInboxReconciliation(statuses.values.toList(), history, versions.toList(), false, ledger.current().reference())
    }

    private fun retain(metadata: CiphertextObjectMetadata) {
        require(metadata.key.kind == CiphertextObjectKind.COMMIT) { "SYNC_INBOX_CONTEXT" }
        mirror.metadata(metadata.key)?.let { require(it == metadata) { "SYNC_INBOX_IMMUTABLE_CONFLICT" }; return }
        spool(backend, metadata) { path ->
            // Derives only from the opaque physical key, never plaintext. Stable retry after a local intent crash.
            val operation = SyncStoreId.parse(MessageDigest.getInstance("SHA-256").digest(
                "CCB-SYNC-INBOX-COPY/1\n${metadata.key.kind}\n${metadata.key.id.value}".toByteArray(Charsets.US_ASCII))
                .take(16).joinToString("") { "%02x".format(it) })
            Files.newInputStream(path, NOFOLLOW_LINKS).use { mirror.put(CiphertextPutRequest(metadata.key, operation, metadata.descriptor), it) }
        }
    }
    private fun <T> spool(store: CiphertextStore, metadata: CiphertextObjectMetadata, workOn: (Path) -> T): T {
        resourceBudget.requireSpace(work, metadata.descriptor.length)
        PrivateSyncFile.create(work).use { file ->
            FileChannel.open(file.path, WRITE, NOFOLLOW_LINKS).use { channel ->
                var count = 0L
                val output = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(length >= 0 && count <= metadata.descriptor.length - length) { "SYNC_INBOX_OBJECT_LENGTH" }
                        val buffer = ByteBuffer.wrap(bytes, offset, length); while (buffer.hasRemaining()) channel.write(buffer); count += length
                    }
                }
                require(store.readTo(metadata.key, output, metadata.descriptor) == metadata && count == metadata.descriptor.length) { "SYNC_INBOX_OBJECT_MISSING" }
                channel.force(true)
            }
            return workOn(file.path)
        }
    }
    private fun code(failure: Exception): String {
        if (failure is CancellationException || failure is InterruptedException || failure is InterruptedIOException) throw failure
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_INBOX_INTERRUPTED")
        return failure.message?.takeIf { it.matches(Regex("SYNC_[A-Z0-9_]{1,100}")) }
            ?: if (failure is CiphertextStoreException) "SYNC_INBOX_STORE_${failure.failure}" else "SYNC_INBOX_INVALID_OBJECT"
    }
    private fun <T> localCustody(work: () -> T): T = try { work() } catch (failure: Exception) {
        code(failure) // Preserve interruption rather than converting it into a blocked-object state.
        if (failure.message?.matches(Regex("SYNC_[A-Z0-9_]{1,100}")) == true) throw failure
        throw IllegalStateException("SYNC_INBOX_LOCAL_CUSTODY", failure)
    }
}
