package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import java.io.InputStream
import java.io.OutputStream

/**
 * Per-authenticated-TLS-peer view of one group's store. A transport must obtain both certificates
 * from its real TLS session/identity, never a claimed request field. A removed peer sees only its own
 * sealed first-revocation notice; it cannot retain ordinary data access to learn its removal.
 */
class LanSyncGroupPeerStore(private val trust: SyncGroupTrust, localCertificate: String, peerCertificate: String,
    private val currentLedger: () -> SyncDeviceLedger, private val backend: CiphertextStore,
    private val recoveredReads: ((SyncVersionRef) -> Map<CiphertextObjectKey, Set<CiphertextDescriptor>>)?,
    private val verifyRecoveredBlob: ((SyncVersionRef, CiphertextObjectMetadata) -> Boolean)?,
    private val recoveredProtocol: Boolean?,
    private val revocationNotice: (String) -> SyncLedgerNoticeEntry?) : CiphertextStore {
    constructor(trust: SyncGroupTrust, localCertificate: String, peerCertificate: String, currentLedger: () -> SyncDeviceLedger,
        backend: CiphertextStore, revocationNotice: (String) -> SyncLedgerNoticeEntry?) :
        this(trust, localCertificate, peerCertificate, currentLedger, backend, null, null, null, revocationNotice)
    private val local = SyncDeviceSignatures.decode(localCertificate)
    private val peer = SyncDeviceSignatures.decode(peerCertificate)
    val group: SyncStoreId get() = trust.group
    @Volatile private var checkedNotice: Pair<SyncVersionRef, CiphertextObjectMetadata>? = null
    @Volatile private var checkedProtocolCheckpoint: SyncVersionRef? = null
    override val capabilities get() = backend.capabilities

    private fun ledger(): SyncDeviceLedger = currentLedger().also { ledger ->
        require(ledger.trustAnchor.group == trust.group && ledger.trustAnchor.genesis == trust.genesis &&
            ledger.trustAnchor.ownerCertificate == trust.ownerCertificate && ledger.contains(trust.checkpoint)) { "SYNC_GROUP_PEER_TRUST_CHANGED" }
        val current = ledger.current()
        val checkpoint = current.reference()
        if (recoveredProtocol != null && checkedProtocolCheckpoint != checkpoint) {
            if (ledger.authorityHistory(checkpoint).any { it.recovery != null } != recoveredProtocol) denied()
            checkedProtocolCheckpoint = checkpoint
        }
        val members = current.members
        if (members[local.fingerprint]?.let { it.active && it.certificate == local.encoded } != true ||
            members[peer.fingerprint]?.certificate != peer.encoded) denied()
    }
    private fun active(ledger: SyncDeviceLedger) = ledger.current().members.getValue(peer.fingerprint).active
    private fun notice(ledger: SyncDeviceLedger): CiphertextObjectMetadata {
        val checkpoint = ledger.current().reference()
        checkedNotice?.takeIf { it.first == checkpoint }?.let { return it.second }
        val entry = requireNotNull(revocationNotice(peer.fingerprint)) { "SYNC_GROUP_PEER_NOTICE_MISSING" }
        entry.notice.verifyOwner(trust, entry.request.key)
        val context = entry.notice.context
        require(context.recipient == peer.fingerprint && context.revocation == SyncLedgerNotice.revocations(ledger)[peer.fingerprint] &&
            context.recipientKeyHash == SyncRecipientPublicKey.parse(ledger.current().members.getValue(peer.fingerprint).recipientKey).hash &&
            entry.request.operationId == context.objectId && entry.request.descriptor ==
                SyncKeyDeliveryCodec.descriptor(SyncLedgerNoticeCodec.encode(entry.notice))) { "SYNC_GROUP_PEER_NOTICE_CHANGED" }
        return CiphertextObjectMetadata(entry.request.key, entry.request.descriptor).also { checkedNotice = checkpoint to it }
    }
    private fun authorize(key: CiphertextObjectKey, write: Boolean = false) {
        val ledger = ledger()
        if (!active(ledger) && (write || notice(ledger).key != key)) denied()
    }
    private fun denied(): Nothing = throw CiphertextStoreException(CiphertextStoreFailure.PERMISSION)
    private fun readChoices(ledger: SyncDeviceLedger, key: CiphertextObjectKey): Set<CiphertextDescriptor>? =
        recoveredReads?.let { readScope -> readScope(ledger.current().reference())[key].orEmpty() }
    /** Recheck before each network chunk even when ciphertext is already in a verified local cache. */
    fun requireReadAllowed(key: CiphertextObjectKey, expected: CiphertextDescriptor) {
        val ledger = ledger()
        if (!active(ledger)) {
            val notice = notice(ledger)
            if (key != notice.key || expected != notice.descriptor) denied()
        } else {
            if (readChoices(ledger, key)?.contains(expected) == false) denied()
            if (key.kind == CiphertextObjectKind.BLOB && verifyRecoveredBlob?.invoke(
                    ledger.current().reference(), CiphertextObjectMetadata(key, expected)) == false) denied()
        }
    }
    fun requireWriteAllowed(key: CiphertextObjectKey) = authorize(key, true)

    /** The caller's real TLS identity still needs ordinary active membership. */
    internal fun membershipLedger(after: SyncVersionRef, recovery: SyncVersionRef?): SyncGroupEnvelope? {
        val ledger = ledger()
        if (!active(ledger)) denied()
        val tip = ledger.current()
        val history = ledger.authorityHistory(tip.reference())
        val anchor = history.indexOfFirst { it.reference() == after }
        require(anchor >= 0) { "SYNC_GROUP_LEDGER_RELAY_ANCESTRY" }
        val lastRecovery = history.indexOfLast { it.recovery != null }
        require(history.getOrNull(lastRecovery)?.reference() == recovery && anchor >= lastRecovery) {
            "SYNC_GROUP_LEDGER_RELAY_RECOVERY_REQUIRED"
        }
        if (recovery != null) {
            require(history[anchor].members[peer.fingerprint] == tip.members[peer.fingerprint]) {
                "SYNC_GROUP_LEDGER_RELAY_PEER"
            }
            for (index in anchor until history.lastIndex) {
                require(history[index + 1].isApprovalAfter(history[index])) { "SYNC_GROUP_LEDGER_RELAY_ANCESTRY" }
            }
        }
        if (anchor == history.lastIndex) return null
        return SyncGroupEnvelope(2, SyncGroupObjectKind.DEVICE_LEDGER, deviceLedger = tip,
            ledgerHistory = history.subList(anchor + 1, history.lastIndex))
    }

    internal fun requireLedgerReadAllowed(reference: SyncVersionRef) {
        val ledger = ledger()
        if (!active(ledger)) denied()
        require(ledger.current().reference() == reference) { "SYNC_GROUP_LEDGER_RELAY_TIP_CHANGED" }
    }

    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult {
        authorize(request.key, true)
        val checked = object : InputStream() {
            override fun read(): Int { authorize(request.key, true); return source.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                authorize(request.key, true); return source.read(bytes, offset, length)
            }
        }
        val result = backend.put(request, checked)
        authorize(request.key, true)
        return result
    }
    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? = metadataExact(key, null)
    fun metadataExact(key: CiphertextObjectKey, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
        authorize(key)
        val ledger = ledger()
        val chosen = if (!active(ledger)) {
            val notice = notice(ledger)
            require(key == notice.key && (expected == null || expected == notice.descriptor)) { "SYNC_GROUP_PEER_NOTICE_CHANGED" }
            notice.descriptor
        } else {
            val allowed = readChoices(ledger, key)
            if (allowed == null) expected
            else if (expected == null) allowed.firstOrNull() ?: return null
            else if (expected in allowed) expected else return null
        }
        val result = rawMetadata(key, chosen)
        authorize(key)
        val latest = this.ledger()
        if (!active(latest)) require(result == notice(latest)) { "SYNC_GROUP_PEER_NOTICE_MISSING_OR_CHANGED" }
        else if (result != null && readChoices(latest, key)?.contains(result.descriptor) == false) denied()
        if (result != null && key.kind == CiphertextObjectKind.BLOB && verifyRecoveredBlob != null) {
            if (!verifyRecoveredBlob.invoke(latest.current().reference(), result)) return null
            requireReadAllowed(key, result.descriptor)
        }
        return result
    }
    private fun rawMetadata(key: CiphertextObjectKey, expected: CiphertextDescriptor?): CiphertextObjectMetadata? =
        if (backend is LanSyncGroupReplicaReader) backend.metadataExact(key, expected)
        else backend.metadata(key)?.also { require(expected == null || expected == it.descriptor) { "SYNC_GROUP_WIRE_OBJECT_CHANGED" } }
    override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
        authorize(key)
        val ledger = ledger()
        val chosen = if (!active(ledger)) {
            val notice = notice(ledger)
            require(expected == null || expected == notice.descriptor) { "SYNC_GROUP_PEER_NOTICE_CHANGED" }
            require(metadataExact(key, notice.descriptor) == notice) { "SYNC_GROUP_PEER_NOTICE_MISSING_OR_CHANGED" }
            notice.descriptor
        } else expected
        // Pin even an active caller's default read, so a later revocation cannot authorize a
        // different same-key raw candidate merely because the authentic notice uses that key.
        val selected = if (recoveredReads == null) chosen ?: metadata(key)?.descriptor ?: return null
            else metadataExact(key, chosen)?.descriptor ?: return null
        requireReadAllowed(key, selected)
        val checked = object : OutputStream() {
            override fun write(value: Int) { requireReadAllowed(key, selected); destination.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { requireReadAllowed(key, selected); destination.write(bytes, offset, length) }
            override fun flush() { requireReadAllowed(key, selected); destination.flush() }
        }
        val source = if (recoveredReads != null && backend is LanSyncGroupReplicaReader) backend.cachedOnly() else backend
        val result = source.readTo(key, checked, selected)
        requireReadAllowed(key, selected)
        val latest = this.ledger()
        if (!active(latest)) require(result == notice(latest)) { "SYNC_GROUP_PEER_NOTICE_MISSING_OR_CHANGED" }
        return result
    }
    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
        require(pageSize in 1..1000) { "SYNC_GROUP_PEER_PAGE_SIZE" }
        val ledger = ledger()
        if (active(ledger)) {
            val checkpoint = ledger.current().reference()
            val scope = recoveredReads?.invoke(checkpoint)
            val result = if (scope == null) backend.listCommits(cursor, pageSize) else recoveredPage(checkpoint, scope, cursor, pageSize)
            val latest = this.ledger()
            if (!active(latest)) denied()
            if (scope != null && (latest.current().reference() != checkpoint || result.entries.any {
                readChoices(latest, it.key)?.contains(it.descriptor) == false
            })) {
                throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR)
            }
            return result
        }
        // An active-peer backend cursor has no authority over the newly restricted view.
        if (cursor != null) throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR)
        val notice = notice(ledger)
        require(metadataExact(notice.key, notice.descriptor) == notice) { "SYNC_GROUP_PEER_NOTICE_MISSING_OR_CHANGED" }
        return CommitPage(listOf(notice), null)
    }

    private fun recoveredPage(checkpoint: SyncVersionRef, scope: Map<CiphertextObjectKey, Set<CiphertextDescriptor>>,
        cursor: CommitCursor?, size: Int): CommitPage {
        val prefix = "recovered1:${checkpoint.bodyHash}:"
        fun position(value: CiphertextObjectMetadata) =
            "${value.key.id.value}:${value.descriptor.length.toString().padStart(19, '0')}:${value.descriptor.sha256.hex}"
        val after = cursor?.token?.let { token ->
            if (!token.startsWith(prefix)) throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR)
            token.removePrefix(prefix).also {
                if (!it.matches(Regex("[a-f0-9]{32}:[0-9]{19}:[a-f0-9]{64}"))) throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR)
            }
        }
        val visible = scope.asSequence().filter { it.key.kind == CiphertextObjectKind.COMMIT }
            .flatMap { (key, descriptors) -> descriptors.asSequence().map { CiphertextObjectMetadata(key, it) } }
            .filter { after == null || position(it) > after }.sortedBy(::position)
            .filter { rawMetadata(it.key, it.descriptor) == it }.take(size + 1).toList()
        val rows = visible.take(size)
        val next = if (visible.size > size) CommitCursor(prefix + position(rows.last())) else null
        return CommitPage(rows, next)
    }
}
