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
    private val revocationNotice: (String) -> SyncLedgerNoticeEntry?) : CiphertextStore {
    private val local = SyncDeviceSignatures.decode(localCertificate)
    private val peer = SyncDeviceSignatures.decode(peerCertificate)
    val group: SyncStoreId get() = trust.group
    @Volatile private var checkedNotice: Pair<SyncVersionRef, CiphertextObjectMetadata>? = null
    override val capabilities get() = backend.capabilities

    private fun ledger(): SyncDeviceLedger = currentLedger().also { ledger ->
        require(ledger.trustAnchor.group == trust.group && ledger.trustAnchor.genesis == trust.genesis &&
            ledger.trustAnchor.ownerCertificate == trust.ownerCertificate && ledger.contains(trust.checkpoint)) { "SYNC_GROUP_PEER_TRUST_CHANGED" }
        val members = ledger.current().members
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
    /** Recheck before each network chunk even when ciphertext is already in a verified local cache. */
    fun requireReadAllowed(key: CiphertextObjectKey, expected: CiphertextDescriptor) {
        val ledger = ledger()
        if (!active(ledger)) {
            val notice = notice(ledger)
            if (key != notice.key || expected != notice.descriptor) denied()
        }
    }
    fun requireWriteAllowed(key: CiphertextObjectKey) = authorize(key, true)

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
    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? {
        authorize(key)
        val before = ledger()
        val expected = if (!active(before)) notice(before).descriptor else null
        val result = if (backend is LanSyncGroupReplicaReader) backend.metadataExact(key, expected) else backend.metadata(key)
        authorize(key)
        val latest = ledger()
        if (!active(latest)) require(result == notice(latest)) { "SYNC_GROUP_PEER_NOTICE_MISSING_OR_CHANGED" }
        return result
    }
    fun metadataExact(key: CiphertextObjectKey, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
        authorize(key)
        val ledger = ledger()
        val chosen = if (!active(ledger)) {
            val notice = notice(ledger)
            require(key == notice.key && (expected == null || expected == notice.descriptor)) { "SYNC_GROUP_PEER_NOTICE_CHANGED" }
            notice.descriptor
        } else expected
        val result = if (backend is LanSyncGroupReplicaReader) backend.metadataExact(key, chosen)
            else backend.metadata(key)?.also { require(chosen == null || chosen == it.descriptor) { "SYNC_GROUP_WIRE_OBJECT_CHANGED" } }
        authorize(key)
        val latest = this.ledger()
        if (!active(latest)) require(result == notice(latest)) { "SYNC_GROUP_PEER_NOTICE_MISSING_OR_CHANGED" }
        return result
    }
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
        val selected = chosen ?: metadata(key)?.descriptor ?: return null
        requireReadAllowed(key, selected)
        val checked = object : OutputStream() {
            override fun write(value: Int) { requireReadAllowed(key, selected); destination.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { requireReadAllowed(key, selected); destination.write(bytes, offset, length) }
            override fun flush() { requireReadAllowed(key, selected); destination.flush() }
        }
        val result = backend.readTo(key, checked, selected)
        requireReadAllowed(key, selected)
        val latest = this.ledger()
        if (!active(latest)) require(result == notice(latest)) { "SYNC_GROUP_PEER_NOTICE_MISSING_OR_CHANGED" }
        return result
    }
    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
        require(pageSize in 1..1000) { "SYNC_GROUP_PEER_PAGE_SIZE" }
        val ledger = ledger()
        if (active(ledger)) {
            val result = backend.listCommits(cursor, pageSize)
            if (!active(this.ledger())) denied()
            return result
        }
        // An active-peer backend cursor has no authority over the newly restricted view.
        if (cursor != null) throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR)
        val notice = notice(ledger)
        require(metadataExact(notice.key, notice.descriptor) == notice) { "SYNC_GROUP_PEER_NOTICE_MISSING_OR_CHANGED" }
        return CommitPage(listOf(notice), null)
    }
}
