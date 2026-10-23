package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.security.DesktopCredentialKey
import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

internal enum class TargetEvidenceState { VERIFIED, UNKNOWN, UNAVAILABLE, CONFLICT }
internal enum class TargetEvidenceReason {
    BASELINE_MISSING, DIRECTORY_UNAVAILABLE, DIRECTORY_IDENTITY_CHANGED, OBSERVATION_CHANGED,
    CONFIGURATION_UNAVAILABLE, CONFIGURATION_INVALID, DUPLICATE_ROOT, DUPLICATE_KEY,
    ACCOUNT_OR_ENDPOINT_CONFLICT, NAME_COLLISION, NO_PROTECTED_SINK, UNKNOWN_KIND,
    GLOBAL_KEY_SCOPE_UNKNOWN, UNDECLARED_CONSUMERS_POSSIBLE, PROTECTION_CONTEXT_UNPROVEN,
    ACCOUNT_BINDING_UNPROVEN, INPUT_INVALID,
}

/** A native directory observation, not a Windows SID, DPAPI context, durable root ID or lock. */
internal class TargetDirectoryWitness private constructor(
    private val volume: Long, private val id: String, private val created: Long,
    private val modified: Long, private val changed: Long,
) {
    override fun equals(other: Any?): Boolean = other is TargetDirectoryWitness &&
        volume == other.volume && id == other.id && created == other.created &&
        modified == other.modified && changed == other.changed
    override fun hashCode(): Int = listOf(volume, id, created, modified, changed).hashCode()
    internal fun sameObject(other: TargetDirectoryWitness): Boolean =
        volume == other.volume && id == other.id && created == other.created
    override fun toString(): String = "TargetDirectoryWitness(REDACTED)"

    companion object {
        internal fun fromNative(stamp: com.example.chatbar.desktop.DesktopDocumentIndexReadEvidence.Stamp) =
            TargetDirectoryWitness(stamp.volume, stamp.id, stamp.created, stamp.modified, stamp.changed)
    }
}

/** Only explicit test-owned directories may be supplied. No discovery or ambient environment. */
internal fun interface TargetDirectoryWitnessReader {
    suspend fun observe(directory: Path): TargetDirectoryWitness
}

/** Producer-supplied synthetic metadata; generation is NOT OS CAS or a native account proof. */
internal class TargetConfigurationSnapshot(val generation: Long, targets: List<ImportCredentialTarget>) {
    val targets = targets.map { ImportCredentialTarget(it.kind, ExistingSecretRelation.UNKNOWN, it.account) }
    override fun toString(): String = "TargetConfigurationSnapshot(count=${targets.size})"
}

/** Deliberately has no SecretStore, value-comparison, environment or secret-read interface. */
internal fun interface SyntheticTargetConfigurationReader {
    suspend fun snapshot(): TargetConfigurationSnapshot
}

internal class DeclaredTargetConsumer(
    internal val dataRoot: Path,
    internal val credentialRoot: Path,
    internal val expectedData: TargetDirectoryWitness?,
    internal val expectedCredential: TargetDirectoryWitness?,
    internal val configurations: SyntheticTargetConfigurationReader,
) {
    override fun toString(): String = "DeclaredTargetConsumer(REDACTED)"
}

internal class TargetConsumerEvidence internal constructor(
    val ordinal: Int,
    val dataIdentity: TargetEvidenceState,
    val credentialIdentity: TargetEvidenceState,
    val configuration: TargetEvidenceState,
    targets: List<ImportCredentialTarget>,
    reasons: Set<TargetEvidenceReason>,
) {
    // No secret relation is inferred from metadata, including on otherwise verified roots.
    val targets = targets.toList()
    val reasons = reasons.toSet()
    val protectionContext: TargetEvidenceState get() = TargetEvidenceState.UNKNOWN
    val accountBinding: TargetEvidenceState get() = TargetEvidenceState.UNKNOWN
    override fun toString(): String =
        "TargetConsumerEvidence($ordinal,$dataIdentity,$credentialIdentity,$configuration,count=${targets.size},$reasons)"
}

internal class TargetIdentityEvidence internal constructor(consumers: List<TargetConsumerEvidence>) {
    val consumers = consumers.toList()
    val globalConsumerClosure: TargetEvidenceState get() = TargetEvidenceState.UNKNOWN
    val closureReason: TargetEvidenceReason get() = TargetEvidenceReason.UNDECLARED_CONSUMERS_POSSIBLE
    val restoreAuthorized: Boolean get() = false
    val readyToCommit: Boolean get() = false
    override fun toString(): String = "TargetIdentityEvidence(count=${consumers.size},closure=UNKNOWN,restoreAuthorized=false)"
}

/** T1: topology-neutral, unwired, read-only observations of explicitly injected synthetic roots.
 * VERIFIED is scoped to directory observations / declared metadata consistency only. Even an empty
 * or fully observed declaration cannot establish global consumer closure. Two passes detect observed
 * changes, not every interleaving/ABA or mutation after the last read. No lease, CAS or lock is held.
 */
internal class DesktopTargetIdentityEvidence(private val directories: TargetDirectoryWitnessReader) {
    private class Capture(
        val data: TargetDirectoryWitness?, val credential: TargetDirectoryWitness?,
        val snapshot: TargetConfigurationSnapshot?,
    )
    private class Row(
        val data: TargetEvidenceState, val credential: TargetEvidenceState,
        var configuration: TargetEvidenceState, val targets: List<ImportCredentialTarget>,
        val store: TargetDirectoryWitness?, val root: TargetDirectoryWitness?,
        val reasons: MutableSet<TargetEvidenceReason>,
    )

    suspend fun observe(declarations: List<DeclaredTargetConsumer>): TargetIdentityEvidence {
        currentCoroutineContext().ensureActive()
        // Invocation bound, not a production capacity or persistence contract.
        if (declarations.isEmpty() || declarations.size > 64) return invalid()
        val inputs = declarations.toList()
        val first = inputs.map { capture(it) }
        val second = inputs.map { capture(it) }
        val rows = inputs.indices.map { i ->
            currentCoroutineContext().ensureActive()
            val before = first[i]; val after = second[i]; val input = inputs[i]
            val reasons = linkedSetOf(TargetEvidenceReason.PROTECTION_CONTEXT_UNPROVEN, TargetEvidenceReason.ACCOUNT_BINDING_UNPROVEN)
            fun directory(a: TargetDirectoryWitness?, b: TargetDirectoryWitness?, expected: TargetDirectoryWitness?): TargetEvidenceState = when {
                a == null || b == null -> TargetEvidenceState.UNAVAILABLE.also { reasons += TargetEvidenceReason.DIRECTORY_UNAVAILABLE }
                a != b -> TargetEvidenceState.CONFLICT.also { reasons += TargetEvidenceReason.OBSERVATION_CHANGED }
                expected == null -> TargetEvidenceState.UNKNOWN.also { reasons += TargetEvidenceReason.BASELINE_MISSING }
                !a.sameObject(expected) -> TargetEvidenceState.CONFLICT.also { reasons += TargetEvidenceReason.DIRECTORY_IDENTITY_CHANGED }
                else -> TargetEvidenceState.VERIFIED
            }
            val data = directory(before.data, after.data, input.expectedData)
            val credential = directory(before.credential, after.credential, input.expectedCredential)
            val a = before.snapshot; val b = after.snapshot
            val configuration = when {
                a == null || b == null -> TargetEvidenceState.UNAVAILABLE.also { reasons += TargetEvidenceReason.CONFIGURATION_UNAVAILABLE }
                a.generation < 0 || b.generation < 0 || a.targets.size > 4096 || b.targets.size > 4096 ->
                    TargetEvidenceState.UNKNOWN.also { reasons += TargetEvidenceReason.CONFIGURATION_INVALID }
                a.generation != b.generation || !sameTargets(a.targets, b.targets) ->
                    TargetEvidenceState.CONFLICT.also { reasons += TargetEvidenceReason.OBSERVATION_CHANGED }
                data != TargetEvidenceState.VERIFIED || credential != TargetEvidenceState.VERIFIED -> TargetEvidenceState.UNKNOWN
                else -> TargetEvidenceState.VERIFIED
            }
            Row(data, credential, configuration, if (configuration == TargetEvidenceState.VERIFIED) a!!.targets else emptyList(),
                before.credential, before.data, reasons)
        }
        rows.forEachIndexed { i, row ->
            if (row.root != null && rows.indices.any { it != i && rows[it].root?.sameObject(row.root) == true }) {
                row.configuration = TargetEvidenceState.CONFLICT; row.reasons += TargetEvidenceReason.DUPLICATE_ROOT
            }
            val keyed = row.targets.map { it to key(it) }
            keyed.forEach { (target, key) ->
                if (key == null) {
                    row.configuration = TargetEvidenceState.UNKNOWN
                    row.reasons += if (target.kind == ImportCredentialKind.COMMUNITY) TargetEvidenceReason.NO_PROTECTED_SINK else TargetEvidenceReason.UNKNOWN_KIND
                } else if (!valid(target)) {
                    row.configuration = TargetEvidenceState.CONFLICT; row.reasons += TargetEvidenceReason.CONFIGURATION_INVALID
                } else if (keyed.count { it.second == key } > 1) {
                    row.configuration = TargetEvidenceState.CONFLICT; row.reasons += TargetEvidenceReason.DUPLICATE_KEY
                }
                if (target.kind in globals) row.reasons += TargetEvidenceReason.GLOBAL_KEY_SCOPE_UNKNOWN
            }
        }
        for (i in rows.indices) for (j in i + 1 until rows.size) {
            val a = rows[i]; val b = rows[j]
            if (a.store == null || b.store?.sameObject(a.store) != true) continue
            for (left in a.targets) for (right in b.targets) {
                val leftKey = key(left); val rightKey = key(right)
                val conflict = leftKey != null && leftKey == rightKey && left.account != null && right.account != null &&
                    !left.account.sameAccount(right.account)
                val collision = left.kind == right.kind && left.account?.let { l -> right.account?.let(l::nameCollision) } == true
                if (conflict || collision) {
                    a.configuration = TargetEvidenceState.CONFLICT; b.configuration = TargetEvidenceState.CONFLICT
                    val reason = if (conflict) TargetEvidenceReason.ACCOUNT_OR_ENDPOINT_CONFLICT else TargetEvidenceReason.NAME_COLLISION
                    a.reasons += reason; b.reasons += reason
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return TargetIdentityEvidence(rows.mapIndexed { i, row ->
            TargetConsumerEvidence(i, row.data, row.credential, row.configuration, row.targets, row.reasons)
        })
    }

    private suspend fun capture(input: DeclaredTargetConsumer): Capture {
        suspend fun <T> safe(body: suspend () -> T): T? {
            currentCoroutineContext().ensureActive()
            return try { body().also { currentCoroutineContext().ensureActive() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
        }
        return Capture(safe { directories.observe(input.dataRoot) }, safe { directories.observe(input.credentialRoot) },
            safe { input.configurations.snapshot() })
    }

    private fun sameTargets(a: List<ImportCredentialTarget>, b: List<ImportCredentialTarget>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> x.kind == y.kind &&
            (if (x.account == null) y.account == null else y.account?.let(x.account::sameAccount) == true) }

    private fun valid(target: ImportCredentialTarget): Boolean = when (target.kind) {
        ImportCredentialKind.MODEL, ImportCredentialKind.LEGACY_EMBEDDING, ImportCredentialKind.SINGLETON_EMBEDDING -> target.account?.valid(target.kind) == true
        in globals -> target.account == null
        else -> false
    }
    private fun key(target: ImportCredentialTarget): DesktopCredentialKey? = when (target.kind) {
        ImportCredentialKind.NOVEL_AI -> DesktopCredentialKey.NovelAiToken
        ImportCredentialKind.FISH -> DesktopCredentialKey.FishAudioApiKey
        ImportCredentialKind.SILICON_FLOW -> DesktopCredentialKey.SiliconFlowApiKey
        ImportCredentialKind.MODEL, ImportCredentialKind.LEGACY_EMBEDDING, ImportCredentialKind.SINGLETON_EMBEDDING -> target.account?.key(target.kind)
        else -> null
    }
    private fun invalid() = TargetIdentityEvidence(listOf(TargetConsumerEvidence(0, TargetEvidenceState.UNKNOWN,
        TargetEvidenceState.UNKNOWN, TargetEvidenceState.UNKNOWN, emptyList(), setOf(TargetEvidenceReason.INPUT_INVALID))))
    private val globals = setOf(ImportCredentialKind.NOVEL_AI, ImportCredentialKind.FISH, ImportCredentialKind.SILICON_FLOW)
}
