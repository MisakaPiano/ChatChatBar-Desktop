package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.*
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavAttemptStore
import com.example.chatbar.domain.syncStore.webdav.WeakWebDavCursor
import java.io.InputStream
import java.io.OutputStream

/** Read-only quarantine port. DAV claims still pass the ordinary Inbox/AEAD/causal owners. */
class LanSyncGroupWeakCloudReadRoute(private val attempts: WeakWebDavAttemptStore,
    private val checkActive: () -> Unit = {}) : CiphertextStore {
    override val capabilities = CiphertextStoreCapabilities(CommitListingSemantics.KEYSET_RESCAN_REQUIRED,
        untrustedCandidateVariants = true)
    private val cursorPrefix = "weak-cloud1:${attempts.routeId.value}:"

    override fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult =
        throw CiphertextStoreException(CiphertextStoreFailure.UNSUPPORTED)

    override fun listCommits(cursor: CommitCursor?, pageSize: Int): CommitPage {
        checkActive()
        val continuation = cursor?.token?.let {
            if (!it.startsWith(cursorPrefix)) throw CiphertextStoreException(CiphertextStoreFailure.INVALID_CURSOR)
            WeakWebDavCursor(it.removePrefix(cursorPrefix))
        }
        val page = attempts.listCommitCandidates(continuation, pageSize, checkActive)
        checkActive()
        return CommitPage(page.entries.map { CiphertextObjectMetadata(it.key, it.descriptor) }.distinct(),
            page.nextCursor?.let { CommitCursor(cursorPrefix + it.token) })
    }

    /** Without an authenticated descriptor, conflicting claims cannot select a canonical object. */
    override fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata? {
        var cursor: WeakWebDavCursor? = null
        var selected: CiphertextDescriptor? = null
        do {
            checkActive()
            val page = attempts.listCandidates(key, cursor, checkActive = checkActive)
            for (candidate in page.entries) {
                if (selected != null && selected != candidate.descriptor)
                    throw CiphertextStoreException(CiphertextStoreFailure.OBJECT_CONFLICT)
                selected = candidate.descriptor
            }
            cursor = page.nextCursor
        } while (cursor != null)
        checkActive()
        return selected?.let { CiphertextObjectMetadata(key, it) }
    }

    override fun readTo(key: CiphertextObjectKey, destination: OutputStream,
        expected: CiphertextDescriptor?): CiphertextObjectMetadata? {
        checkActive()
        val descriptor = expected ?: metadata(key)?.descriptor ?: return null
        val observation = attempts.readExact(key, descriptor, destination, checkActive) ?: return null
        checkActive()
        require(observation.candidate.key == key && observation.candidate.descriptor == descriptor &&
            observation.candidate.route == attempts.routeId) { "SYNC_GROUP_CLOUD_READ_CHANGED" }
        return CiphertextObjectMetadata(key, descriptor)
    }

    override fun toString() = "LanSyncGroupWeakCloudReadRoute(REDACTED)"
}
