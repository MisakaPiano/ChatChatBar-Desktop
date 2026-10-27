package com.example.chatbar.desktop.backup

internal enum class TargetObservationStatus { SOURCE_INCOMPLETE, SOURCE_CLOSED, CONTEXT_UNVERIFIED, GENERATION_OR_CONTEXT_CHANGED, OBSERVED }
internal data class CredentialTargetObservationResult(val status: TargetObservationStatus, val plan: DesktopCredentialImportPlan? = null)

/** Test-injected read-only snapshot contract. No production store or ambient environment adapter exists. */
internal interface SyntheticCredentialTargetReader {
    fun stamp(): SyntheticObservationStamp
    fun observe(source: ImportCredentialSource, comparison: ScopedCredentialComparison): ImportCredentialTarget?
}

/** Opaque short-lived comparator. Retention cannot retain access after its scope closes. */
internal class ScopedCredentialComparison internal constructor(private var source: CharArray?) : AutoCloseable {
    private var active = true
    fun matches(syntheticTarget: CharArray): Boolean {
        check(active) { "CREDENTIAL_COMPARISON_CLOSED" }
        return source?.contentEquals(syntheticTarget) == true
    }
    override fun close() { active = false; source = null }
    override fun toString(): String = "ScopedCredentialComparison(REDACTED)"
}
