package com.example.chatbar.domain.syncStore

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.serialization.Serializable

/** An opaque random identifier, never a title, path or hash of unencrypted user content. */
@JvmInline
@Serializable
value class SyncStoreId private constructor(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{32}"))) { "Invalid opaque store identifier" } }
    companion object {
        private val pattern = Regex("[0-9a-f]{32}")

        fun random(): SyncStoreId = SyncStoreId(UUID.randomUUID().toString().replace("-", ""))

        /** Decodes a previously generated ID; callers must not derive IDs from plaintext. */
        fun parse(value: String): SyncStoreId {
            require(pattern.matches(value)) { "Invalid opaque store identifier" }
            return SyncStoreId(value)
        }
    }
}

/** SHA-256 of ciphertext bytes, not a plaintext fingerprint and not proof of authenticity. */
@JvmInline
@Serializable
value class CiphertextSha256 private constructor(val hex: String) {
    init { require(hex.matches(Regex("[0-9a-f]{64}"))) { "Invalid ciphertext checksum" } }
    companion object {
        private val pattern = Regex("[0-9a-f]{64}")

        fun parse(hex: String): CiphertextSha256 {
            require(pattern.matches(hex)) { "Invalid ciphertext checksum" }
            return CiphertextSha256(hex)
        }
    }
}

@Serializable enum class CiphertextObjectKind(internal val storageCode: Int) { BLOB(1), COMMIT(2) }

@Serializable data class CiphertextObjectKey(val kind: CiphertextObjectKind, val id: SyncStoreId)

@Serializable data class CiphertextDescriptor(val length: Long, val sha256: CiphertextSha256) {
    init {
        require(length >= 0) { "Negative ciphertext length" }
    }
}

data class CiphertextObjectMetadata(val key: CiphertextObjectKey, val descriptor: CiphertextDescriptor)

/** Persist this request, including operationId, before starting an upload. */
data class CiphertextPutRequest(
    val key: CiphertextObjectKey,
    val operationId: SyncStoreId,
    val descriptor: CiphertextDescriptor,
)

enum class CiphertextPutDisposition { CREATED, ALREADY_PRESENT }

data class CiphertextPutResult(
    val metadata: CiphertextObjectMetadata,
    val disposition: CiphertextPutDisposition,
)

enum class CommitListingSemantics {
    /** IDs are ordered within a scan; additions before the cursor require a new full scan. */
    KEYSET_RESCAN_REQUIRED,
}

data class CiphertextStoreCapabilities(
    val commitListing: CommitListingSemantics,
    val conditionalReplace: Boolean = false,
    val conditionalCreate: Boolean = false,
    val changeNotifications: Boolean = false,
    val resumableByteUpload: Boolean = false,
    val multiObjectTransaction: Boolean = false,
    /** File data is forced before publication; this does not promise power-loss directory durability. */
    val fileDataForcedBeforePublication: Boolean = false,
)

/** Provider-bound opaque continuation, not a history checkpoint or proof of completeness. */
data class CommitCursor(val token: String)

data class CommitPage(val entries: List<CiphertextObjectMetadata>, val nextCursor: CommitCursor?)

enum class CiphertextStoreFailure {
    OBJECT_CONFLICT,
    OPERATION_CONFLICT,
    INTEGRITY,
    INVALID_CURSOR,
    UNSAFE_PATH,
    UNSUPPORTED,
    AUTHENTICATION,
    PERMISSION,
    QUOTA,
    TRANSIENT,
    IO,
}

/** Messages intentionally omit object IDs, filesystem paths, credentials and payload text. */
class CiphertextStoreException(val failure: CiphertextStoreFailure) : IOException("Ciphertext store: $failure")

/**
 * Transport/storage only: accepts already encrypted, versioned envelopes from the encryption owner.
 * It performs no encryption, key exchange, merge, freshness validation or resource/commit scheduling.
 * A commit must only be uploaded after its referenced resources, and readers must verify those
 * references before activating it. Shared mutable heads must not be assumed on a backend without CAS.
 *
 * All methods are blocking I/O; call from the platform's I/O executor, not the UI thread.
 */
interface CiphertextStore {
    val capabilities: CiphertextStoreCapabilities

    /**
     * Does not close source. Exact Long length and ciphertext checksum are checked before visibility.
     * Same key/content is idempotent; a different descriptor or reused operation for another request
     * refuses. The source must be replayable from byte zero after failure; byte resumption is optional.
     */
    fun put(request: CiphertextPutRequest, source: InputStream): CiphertextPutResult

    /** Returns null only for an absent object; corrupt/inaccessible objects are failures. */
    fun metadata(key: CiphertextObjectKey): CiphertextObjectMetadata?

    /**
     * Validates stored length/checksum before emitting bytes. Does not close destination.
     * expected should come from an authenticated manifest; an untrusted stored checksum alone does
     * not protect against an attacker replacing both content and checksum. Sink failure may leave
     * partial output: callers must write into their own preparation area before activation.
     */
    fun readTo(
        key: CiphertextObjectKey,
        destination: OutputStream,
        expected: CiphertextDescriptor? = null,
    ): CiphertextObjectMetadata?

    /** Pagination is discovery only; periodically restart with null, even after reaching the end. */
    fun listCommits(cursor: CommitCursor? = null, pageSize: Int = 100): CommitPage
}
