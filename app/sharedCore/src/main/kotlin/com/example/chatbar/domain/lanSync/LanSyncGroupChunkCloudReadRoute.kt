package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavCandidate
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavChunkAssembly
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavCursor
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavIncomingChunkCache
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavIncomingCacheException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Local ciphertext custody scope; credentials and the recovery code never enter its metadata. */
internal fun lanSyncGroupIncomingScope(trust: SyncGroupTrust, endpoint: String, account: String) =
    lanSyncGroupIncomingScope(trust.group, trust.genesis, endpoint, account)
internal fun lanSyncGroupIncomingScope(group: SyncStoreId, genesis: SyncVersionRef, endpoint: String, account: String) = lanSyncHash(buildJsonObject {
    put("format", 1); put("group", group.value); put("genesis", genesis.id.value)
    put("genesisHash", genesis.bodyHash); put("endpoint", endpoint); put("account", account)
})

/** A provider claim failed inspection/reassembly; this is not an authenticated logical-object fact. */
data class LanSyncGroupChunkReadFailure(val key: CiphertextObjectKey, val failure: CiphertextStoreFailure,
    val assembly: WeakWebDavCandidate? = null, val localCache: Boolean = false)

/**
 * Read-only candidate port for physical chunk assemblies. Only one provider page and one bounded
 * header per manifest are retained here. Inbox/AEAD/ledger owners still authenticate logical bytes.
 */
class LanSyncGroupChunkCloudReadRoute(private val assemblies: WeakWebDavChunkAssembly,
    private val incomingCache: WeakWebDavIncomingChunkCache? = null, private val checkActive: () -> Unit = {}) : CiphertextStore {
    override val capabilities = CiphertextStoreCapabilities(CommitListingSemantics.KEYSET_RESCAN_REQUIRED,
        untrustedCandidateVariants = true)
    private val mutableFailure = MutableStateFlow<LanSyncGroupChunkReadFailure?>(null)
    /** One latest diagnostic, not an unbounded account-wide manifest/error inventory. */
    val latestFailure = mutableFailure.asStateFlow()
    private val cursorPrefix = "chunk-cloud1:"

    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult =
        throw CiphertextStoreException(CiphertextStoreFailure.UNSUPPORTED)

    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage = boundary {
        active()
        val continuation = cursor?.token?.let {
            if (!it.startsWith(cursorPrefix)) throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR)
            WeakWebDavCursor(it.removePrefix(cursorPrefix))
        }
        val page = assemblies.listCommitAssemblies(continuation, pageSize, ::active)
        val logical = linkedSetOf<CiphertextObjectMetadata>()
        for (candidate in page.entries) inspect(candidate)?.let { logical += it }
        active()
        CommitPage(logical.toList(), page.nextCursor?.let { CommitCursor(cursorPrefix + it.token) })
    }

    /** Conflicting untrusted logical descriptors cannot select a canonical value. */
    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? = boundary {
        var cursor: WeakWebDavCursor? = null
        val seen = hashSetOf<String>()
        var descriptor: CiphertextDescriptor? = null
        var unreadable: CiphertextStoreFailure? = null
        do {
            active(); cursor?.let { require(seen.add(it.token)) { "SYNC_GROUP_CHUNK_LISTING_CHANGED" } }
            val page = assemblies.listAssemblies(key, cursor, checkActive = ::active)
            for (candidate in page.entries) {
                val claim = inspect(candidate) { unreadable = it }
                if (claim == null) continue
                if (descriptor != null && descriptor != claim.descriptor) {
                    rejected(key, CiphertextStoreFailure.OBJECT_CONFLICT, candidate)
                    throw CiphertextStoreException(CiphertextStoreFailure.OBJECT_CONFLICT)
                }
                descriptor = claim.descriptor
            }
            cursor = page.nextCursor
        } while (cursor != null)
        active()
        if (descriptor == null && unreadable != null) throw CiphertextStoreException(requireNotNull(unreadable))
        descriptor?.let { CiphertextObjectMetadata(key, it) }
    }

    override fun readTo(key: CiphertextObjectKey, destination: OutputStream,
        expected: CiphertextDescriptor?): CiphertextObjectMetadata? = boundary {
        active()
        val descriptor = expected ?: metadata(key)?.descriptor ?: return@boundary null
        // Runtime sentinels keep active/sink exceptions out of the provider's candidate-retry catch.
        val sink = object : OutputStream() {
            override fun write(value: Int) { try { destination.write(value) } catch (failure: Throwable) { throw SinkFailure(failure) } }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                try { destination.write(bytes, offset, length) } catch (failure: Throwable) { throw SinkFailure(failure) }
            }
        }
        val metadata = try {
            if (incomingCache != null) assemblies.readIncomingExact(key, descriptor, sink, incomingCache, ::active)?.metadata
            else assemblies.readExact(key, descriptor, sink, ::active)?.let {
                require(it.request.key == key && it.request.descriptor == descriptor && it.assembly.key == key) { "SYNC_GROUP_CHUNK_READ_CHANGED" }
                CiphertextObjectMetadata(key, descriptor)
            }
        } catch (failure: WeakWebDavIncomingCacheException) {
            mutableFailure.value = LanSyncGroupChunkReadFailure(key, CiphertextStoreFailure.INTEGRITY, localCache = true)
            throw failure
        } catch (failure: CiphertextStoreException) { rejected(key, failure.failure); throw failure }
            ?: return@boundary null
        active()
        require(metadata == CiphertextObjectMetadata(key, descriptor)) { "SYNC_GROUP_CHUNK_READ_CHANGED" }
        metadata
    }

    private fun inspect(candidate: WeakWebDavCandidate, failed: (CiphertextStoreFailure) -> Unit = {}): CiphertextObjectMetadata? {
        active()
        val header = try { assemblies.inspectAssembly(candidate, ::active) }
        catch (failure: CiphertextStoreException) {
            if (failure.failure !in setOf(CiphertextStoreFailure.INTEGRITY, CiphertextStoreFailure.TRANSIENT)) throw failure
            rejected(candidate.key, failure.failure, candidate)
            failed(failure.failure)
            return null
        }
        active()
        if (header == null) {
            rejected(candidate.key, CiphertextStoreFailure.TRANSIENT, candidate)
            failed(CiphertextStoreFailure.TRANSIENT)
            return null
        }
        require(header.originalRequest.key == candidate.key) { "SYNC_GROUP_CHUNK_HEADER_CHANGED" }
        return CiphertextObjectMetadata(header.originalRequest.key, header.originalRequest.descriptor)
    }
    private fun rejected(key: CiphertextObjectKey, failure: CiphertextStoreFailure, assembly: WeakWebDavCandidate? = null) {
        val diagnostic = LanSyncGroupChunkReadFailure(key, failure, assembly)
        mutableFailure.value = diagnostic
    }
    private fun active() {
        try { checkActive() } catch (failure: Throwable) { throw ActiveFailure(failure) }
    }
    private inline fun <T> boundary(work: () -> T): T = try { work() }
    catch (failure: ActiveFailure) { throw failure.original }
    catch (failure: SinkFailure) { throw failure.original }
    private class ActiveFailure(val original: Throwable) : RuntimeException(null, null, false, false)
    private class SinkFailure(val original: Throwable) : RuntimeException(null, null, false, false)
    override fun toString() = "LanSyncGroupChunkCloudReadRoute(REDACTED)"
}
