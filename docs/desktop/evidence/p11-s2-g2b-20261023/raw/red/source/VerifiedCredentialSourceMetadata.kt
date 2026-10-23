package com.example.chatbar.desktop.backup

import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Only the Inspector's completed opt-in scan publishes this invocation-owned, nonserialized result.
 * Complete means the declared known-location subset, not arbitrary hidden secrets/portability.
 * Close clears copied char buffers; parser Strings/JSON and JVM heap erasure are not guaranteed.
 */
internal class VerifiedCredentialSourceMetadata internal constructor(
    val sourceSha256: String, val sourceLength: Long,
    val envelopeVersion: Int, val manifestVersion: Int, val appVersionCode: Int,
    records: List<CredentialSourceRecord>, limitations: Set<CensusLimitation>,
    private val secrets: List<CharArray?>,
) : AutoCloseable {
    val records = records.toList()
    val limitations = limitations.toSet()
    val complete: Boolean get() = limitations.isEmpty()
    private var closed = false
    val restoreAuthorized: Boolean get() = false
    override fun toString(): String = "VerifiedCredentialSourceMetadata(complete=$complete,limitations=$limitations,records=${records.size})"

    /** No target is accessed for an incomplete census. No value leaves the scoped comparator. */
    @Synchronized fun observeAndPlan(reader: SyntheticCredentialTargetReader, expected: SyntheticObservationStamp): CredentialTargetObservationResult {
        if (closed || !complete) return CredentialTargetObservationResult(if (closed) TargetObservationStatus.SOURCE_CLOSED else TargetObservationStatus.SOURCE_INCOMPLETE)
        val targets = mutableListOf<ImportCredentialTarget>()
        val comparisons = mutableListOf<ScopedCredentialComparison>()
        try {
            val initial = try { reader.stamp() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val usable = initial == expected && expected.consumersKnown && expected.generation >= 0
            for ((i, record) in records.withIndex()) {
                val source = record.source
                val comparison = ScopedCredentialComparison(secrets[i]); comparisons += comparison
                val target = if (usable) try { reader.observe(source, comparison) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } else null
                comparison.close()
                targets += if (target?.kind == source.kind) target else ImportCredentialTarget(source.kind, ExistingSecretRelation.UNKNOWN, source.account)
            }
            val final = try { reader.stamp() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val stable = usable && final == initial
            if (!stable) return CredentialTargetObservationResult(if (usable) TargetObservationStatus.GENERATION_OR_CONTEXT_CHANGED else TargetObservationStatus.CONTEXT_UNVERIFIED)
            return CredentialTargetObservationResult(TargetObservationStatus.OBSERVED, DesktopCredentialImportPlan.classify(envelopeVersion, manifestVersion, appVersionCode, sourceSha256,
                records.map { it.source }, targets, protectedSinkAvailable = true))
        } finally { comparisons.forEach { it.close() }; close() }
    }
    @Synchronized override fun close() { secrets.forEach { it?.fill('\u0000') }; closed = true }
}

/** Explicit synthetic context assertions, never native lock/profile/CAS evidence. */
internal data class SyntheticObservationStamp(val root: UUID, val context: UUID, val generation: Long, val consumersKnown: Boolean)
