package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Authenticated vault only. It grants no daily membership, native apply or cloud freshness. */
class LanSyncGroupCloudRecoveryPreview internal constructor(val trust: SyncGroupTrust,
    val vault: SyncManagementVaultContext, val descriptor: CiphertextDescriptor,
    val rejectedVaultCandidates: Int, internal val owner: Any, private var encrypted: ByteArray?) : AutoCloseable {
    internal fun bytes(): ByteArray = synchronized(this) {
        requireNotNull(encrypted) { "SYNC_GROUP_CLOUD_RECOVERY_PREVIEW_CLOSED" }.copyOf()
    }
    override fun close() = synchronized(this) { encrypted?.fill(0); encrypted = null }
    override fun toString() = "LanSyncGroupCloudRecoveryPreview(REDACTED)"
}

/**
 * Empty-profile read path: the code supplies an untrusted lookup hint; full vault authentication
 * supplies the original owner/genesis. Provider descriptors and COMMITs remain quarantine claims.
 * Credentials/HTTP lifetime stay with the caller. No daily catalog or enrolled identity is needed.
 */
class LanSyncGroupCloudRecoverySource(private val root: Path, private val whole: WeakWebDavAttemptStore,
    private val parts: WeakWebDavChunkAssembly, private val checkActive: () -> Unit = {},
    incomingCache: WeakWebDavIncomingChunkCache? = null) : AutoCloseable {
    private val token = Any()
    @Volatile private var closed = false
    private val cloudPorts: List<CiphertextStore> = listOf(LanSyncGroupWeakCloudReadRoute(whole, ::active), LanSyncGroupChunkCloudReadRoute(parts, ::active, incomingCache))
    private class Listing(val ports: List<CiphertextStore>, val prefix: String = "cloud-recovery1:${SyncStoreId.random().value}:")
    @Volatile private var listing = Listing(cloudPorts)
    private val reader: LanSyncGroupReplicaReader
    init {
        require(whole.routeId == parts.routeId) { "SYNC_GROUP_CLOUD_RECOVERY_ROUTE_CHANGED" }
        lanSyncSafe(root, true)
        fun directory(name: String) = root.resolve(name).also { if (!Files.exists(it)) Files.createDirectory(it); lanSyncSafe(it, true) }
        reader = LanSyncGroupReplicaReader(FileCiphertextStore(directory("ciphertext")), directory("reads")) { _, failure ->
            if (failure is ActiveFailure) throw failure
        }
        reader.routes("recovery", cloudPorts.mapIndexed { index, port -> LanSyncGroupReplicaReader.Route("recovery:$index", port) })
    }

    /** Read-only, descriptor-pinned input for existing management bootstrap and joining owners. */
    private val guardedBackend: CiphertextStore = object : CiphertextStore {
        override val capabilities = CiphertextStoreCapabilities(CommitListingSemantics.KEYSET_RESCAN_REQUIRED,
            untrustedCandidateVariants = true)
        override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult =
            throw CiphertextStoreException(CiphertextStoreFailure.UNSUPPORTED)
        override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? { active(); return reader.metadata(key).also { active() } }
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
            active()
            val guarded = object : OutputStream() {
                override fun write(value: Int) { active(); destination.write(value); active() }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { active(); destination.write(bytes, offset, length); active() }
            }
            return reader.readTo(key, guarded, expected).also { active() }
        }
        override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
            active()
            val current = listing
            val (index, continuation) = parseCursor(cursor, current)
            val page = current.ports[index].listCommits(continuation, pageSize)
            active()
            check(current === listing) { "SYNC_GROUP_CLOUD_RECOVERY_SOURCE_CHANGED" }
            val next = page.nextCursor?.let { encodeCursor(current, index, it) }
                ?: if (index + 1 < current.ports.size) encodeCursor(current, index + 1, null) else null
            return CommitPage(page.entries, next)
        }
    }
    val backend: CiphertextStore = object : CiphertextStore by guardedBackend {
        override fun metadata(key: CiphertextObjectKey) = boundary { guardedBackend.metadata(key) }
        override fun readTo(key: CiphertextObjectKey, destination: OutputStream, expected: CiphertextDescriptor?) =
            boundary { guardedBackend.readTo(key, destination, expected) }
        override fun listCommits(cursor: CommitCursor?, pageSize: Int) = boundary { guardedBackend.listCommits(cursor, pageSize) }
    }

    /** Newly approved controls come from the same recovered root, resources still from the cloud. */
    fun includeControl(preview: LanSyncGroupCloudRecoveryPreview, control: LanSyncGroupRecoveryBootstrap): CiphertextStore = boundary {
        active()
        require(preview.owner === token) { "SYNC_GROUP_CLOUD_RECOVERY_PREVIEW_CHANGED" }
        preview.bytes().fill(0) // A closed/cleared review cannot acquire another source.
        val anchor = control.anchor
        require(anchor.trust.group == preview.trust.group && anchor.trust.genesis == preview.trust.genesis &&
            anchor.trust.ownerCertificate == preview.trust.ownerCertificate && anchor.vault == preview.vault &&
            anchor.vaultDescriptor == preview.descriptor) { "SYNC_GROUP_CLOUD_RECOVERY_PREVIEW_CHANGED" }
        val ports = listOf(control.source) + cloudPorts
        reader.routes("recovery", ports.mapIndexed { index, port -> LanSyncGroupReplicaReader.Route("recovery:$index", port) })
        listing = Listing(ports)
        backend
    }
    /** Keep local guard cancellation opaque through Inbox; unwrap only outside the complete caller. */
    suspend fun <T> withControl(preview: LanSyncGroupCloudRecoveryPreview, control: LanSyncGroupRecoveryBootstrap,
        work: suspend (CiphertextStore) -> T): T {
        includeControl(preview, control)
        return withBackend(work)
    }
    suspend fun <T> withBackend(work: suspend (CiphertextStore) -> T): T {
        try { active(); return work(guardedBackend) } catch (failure: ActiveFailure) { throw failure.original }
    }

    /** Preparation writes only private ciphertext cache; it signs/applies/enrolls nothing. */
    fun prepare(code: String): LanSyncGroupCloudRecoveryPreview = boundary {
        active()
        val hint = SyncManagementSession.locate(code)
        var rejectedCount = 0
        fun rejected() { if (rejectedCount < Int.MAX_VALUE) rejectedCount++ }
        for ((port, metadata) in vaultCandidates(hint.key, ::rejected)) {
            active()
            if (metadata.descriptor.length !in 1..MAX_VAULT) { rejected(); continue }
            val sink = BoundedVault()
            val bytes = try {
                val result = port.readTo(hint.key, sink, metadata.descriptor)
                if (result == null) { rejected(); continue }
                active()
                require(result == metadata) { "SYNC_GROUP_CLOUD_RECOVERY_VAULT_CHANGED" }
                sink.toByteArray()
            } catch (failure: CiphertextStoreException) {
                if (failure.failure !in setOf(CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.TRANSIENT)) throw failure
                rejected()
                continue
            }
            try {
                val session = try { SyncManagementSession.open(code, bytes) }
                    catch (_: IllegalArgumentException) { rejected(); continue }
                session.use {
                    active()
                    require(it.seal(it.trust).context == hint) { "SYNC_GROUP_CLOUD_RECOVERY_VAULT_CHANGED" }
                    return@boundary LanSyncGroupCloudRecoveryPreview(it.trust, hint, metadata.descriptor, rejectedCount, token, bytes.copyOf())
                }
            } finally { bytes.fill(0) }
        }
        error("SYNC_GROUP_CLOUD_RECOVERY_VAULT_UNAVAILABLE")
    }

    /** Explicit approval remains owned by ManagementRecovery; no implicit trust from listing. */
    suspend fun recover(preview: LanSyncGroupCloudRecoveryPreview, code: String,
        management: LanSyncGroupManagementRecovery, admission: LanSyncManagementRecoveryAdmission): LanSyncManagementRecoveryProfile {
        try {
            active()
            require(preview.owner === token) { "SYNC_GROUP_CLOUD_RECOVERY_PREVIEW_CHANGED" }
            val bytes = preview.bytes()
            try {
                return management.recover(code, bytes, guardedBackend, LanSyncManagementRecoveryAdmission { trust ->
                    active()
                    require(trust == preview.trust) { "SYNC_GROUP_CLOUD_RECOVERY_PREVIEW_CHANGED" }
                    admission.requireApproved(trust)
                }, knownTrust = preview.trust)
            } finally { bytes.fill(0) }
        } catch (failure: ActiveFailure) { throw failure.original }
    }

    private fun vaultCandidates(key: CiphertextObjectKey, rejected: () -> Unit): Sequence<Pair<CiphertextStore, CiphertextObjectMetadata>> = sequence {
        var cursor: WeakWebDavCursor? = null
        val wholeSeen = hashSetOf<String>()
        do {
            active(); cursor?.let { require(wholeSeen.add(it.token)) { "SYNC_GROUP_CLOUD_RECOVERY_LISTING_CHANGED" } }
            val page = whole.listCandidates(key, cursor, checkActive = ::active)
            for (candidate in page.entries) yield(cloudPorts[0] to CiphertextObjectMetadata(key, candidate.descriptor))
            cursor = page.nextCursor
        } while (cursor != null)
        val partsSeen = hashSetOf<String>()
        do {
            active(); cursor?.let { require(partsSeen.add(it.token)) { "SYNC_GROUP_CLOUD_RECOVERY_LISTING_CHANGED" } }
            val page = parts.listAssemblies(key, cursor, checkActive = ::active)
            for (candidate in page.entries) {
                val header = try { parts.inspectAssembly(candidate, ::active) }
                catch (failure: CiphertextStoreException) {
                    if (failure.failure !in setOf(CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.TRANSIENT)) throw failure
                    rejected()
                    null
                }
                if (header != null) yield(cloudPorts[1] to CiphertextObjectMetadata(key, header.originalRequest.descriptor))
            }
            cursor = page.nextCursor
        } while (cursor != null)
    }
    private fun encodeCursor(listing: Listing, index: Int, cursor: CommitCursor?) = CommitCursor(listing.prefix + index + ":" +
        (cursor?.token?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) } ?: ""))
    private fun parseCursor(cursor: CommitCursor?, listing: Listing): Pair<Int, CommitCursor?> {
        if (cursor == null) return 0 to null
        try {
            require(cursor.token.startsWith(listing.prefix))
            val value = cursor.token.removePrefix(listing.prefix).split(':', limit = 2)
            val index = value[0].toInt(); require(index in listing.ports.indices && value.size == 2)
            return index to value[1].takeIf { it.isNotEmpty() }?.let {
                CommitCursor(lanSyncUtf8(Base64.getUrlDecoder().decode(it)))
            }
        } catch (_: Exception) { throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR) }
    }
    private class BoundedVault : ByteArrayOutputStream() {
        override fun write(value: Int) { require(count < MAX_VAULT) { "SYNC_GROUP_CLOUD_RECOVERY_VAULT_BUDGET" }; super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && count.toLong() <= MAX_VAULT - length) { "SYNC_GROUP_CLOUD_RECOVERY_VAULT_BUDGET" }
            super.write(bytes, offset, length)
        }
    }
    private fun active() {
        try {
            check(!closed) { "SYNC_GROUP_CLOUD_RECOVERY_CLOSED" }
            checkActive()
        } catch (failure: Throwable) { throw ActiveFailure(failure) }
    }
    private inline fun <T> boundary(work: () -> T): T = try { work() } catch (failure: ActiveFailure) { throw failure.original }
    private class ActiveFailure(val original: Throwable) : java.util.concurrent.CancellationException("SYNC_GROUP_CLOUD_RECOVERY_INTERRUPTED")
    override fun close() { closed = true; reader.removeRoutes("recovery") }
    override fun toString() = "LanSyncGroupCloudRecoverySource(REDACTED)"
    companion object { private const val MAX_VAULT = 272L * 1024 }
}
