package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.desktop.security.DesktopCredentialKey
import java.net.URI

/** G2A metadata only. A future authenticated, strict decoder must supply these facts.
 * This module neither decodes archives nor receives credential values or accesses a store.
 * Code84/v1 is the pinned official 1.4.5 contract (1198a13c AppBackupModels/build source).
 */
internal enum class ImportCredentialKind { NOVEL_AI, FISH, SILICON_FLOW, MODEL, LEGACY_EMBEDDING, SINGLETON_EMBEDDING, COMMUNITY, UNKNOWN }
internal enum class ImportCredentialLocation { PREFERENCES_NOVEL_AI, PREFERENCES_FISH, APP_SETTINGS, MODEL_CONFIG, LEGACY_EMBEDDING_CONFIG, SINGLETON_EMBEDDING_CONFIG, PREFERENCES_COMMUNITY, UNKNOWN }
internal enum class ImportSecretPresence { ABSENT, EMPTY, NONEMPTY }
internal enum class ExistingSecretRelation { ABSENT, SAME, DIFFERENT, UNKNOWN }
internal enum class CredentialPlanStatus { CANDIDATE, NO_SECRET, UNMAPPED, CONFLICT, REQUIRES_CONFIRMATION }
internal enum class CredentialPlanReason {
    MAPPED, NO_INCOMING_SECRET, SOURCE_IDENTITY_INVALID, SOURCE_FORMAT_UNSUPPORTED,
    UNKNOWN_KIND, LOCATION_MISMATCH, NO_PROTECTED_SINK, TARGET_STATE_UNKNOWN,
    DUPLICATE_SOURCE, DUPLICATE_TARGET, ENDPOINT_OR_PROVIDER_CONFLICT, NAME_COLLISION,
    EXISTING_SECRET_DIFFERS,
}

/** Identity is retained for exact comparison, never used as diagnostic text or a value digest. */
internal class ImportAccountIdentity(
    private val id: String,
    private val displayName: String,
    private val endpoint: String,
    private val remoteModel: String,
    private val template: ModelTemplate? = null,
) {
    internal fun valid(kind: ImportCredentialKind): Boolean = try {
        val uri = URI(endpoint)
        id.isNotBlank() && displayName.isNotBlank() && remoteModel.isNotBlank() &&
            listOf(id, displayName, remoteModel).none { value -> value.any(Char::isISOControl) } &&
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            !uri.path.orEmpty().split('/').any { it == "." || it == ".." } &&
            ((kind == ImportCredentialKind.MODEL) == (template != null))
    } catch (_: Exception) { false }

    internal fun key(kind: ImportCredentialKind): DesktopCredentialKey? = when (kind) {
        ImportCredentialKind.MODEL -> if (id.isNotBlank()) DesktopCredentialKey.ModelApiKey(id) else null
        ImportCredentialKind.LEGACY_EMBEDDING -> if (id.isNotBlank()) DesktopCredentialKey.LegacyEmbeddingApiKey(id) else null
        ImportCredentialKind.SINGLETON_EMBEDDING -> DesktopCredentialKey.SingletonEmbeddingApiKey
        else -> null
    }
    internal fun sameAccount(other: ImportAccountIdentity): Boolean =
        id == other.id && endpoint == other.endpoint && template == other.template && remoteModel == other.remoteModel
    internal fun nameCollision(other: ImportAccountIdentity): Boolean = displayName == other.displayName && id != other.id
    override fun toString(): String = "ImportAccountIdentity(REDACTED)"
}

/** No serializable DTO, raw token, JSON, path or password is accepted. */
internal class ImportCredentialSource(
    val kind: ImportCredentialKind,
    val location: ImportCredentialLocation,
    val presence: ImportSecretPresence,
    val account: ImportAccountIdentity? = null,
) {
    override fun toString(): String = "ImportCredentialSource($kind,$location,$presence)"
}

internal class ImportCredentialTarget(
    val kind: ImportCredentialKind,
    val relation: ExistingSecretRelation,
    val account: ImportAccountIdentity? = null,
) {
    override fun toString(): String = "ImportCredentialTarget($kind,$relation)"
}

/** Wrap the existing key to prevent its generated toString from emitting an imported ID. */
internal class ImportCredentialCandidate internal constructor(private val key: DesktopCredentialKey) {
    internal fun matches(expected: DesktopCredentialKey): Boolean = key == expected
    override fun equals(other: Any?): Boolean = other is ImportCredentialCandidate && key == other.key
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "ImportCredentialCandidate(REDACTED)"
}

internal data class CredentialMappingDecision(
    val ordinal: Int,
    val kind: ImportCredentialKind,
    val status: CredentialPlanStatus,
    val reason: CredentialPlanReason,
    val candidate: ImportCredentialCandidate? = null,
)

internal class DesktopCredentialImportPlan private constructor(
    decisions: List<CredentialMappingDecision>,
    val sourceProblem: CredentialPlanReason?,
    private val sourceSha256: String?,
) {
    val decisions: List<CredentialMappingDecision> = decisions.toList()
    val candidateConsistent: Boolean get() = sourceProblem == null && decisions.all {
        it.status in setOf(CredentialPlanStatus.CANDIDATE, CredentialPlanStatus.NO_SECRET)
    }
    // This is never a restore authorization, even for a consistent empty plan.
    val restoreAuthorized: Boolean get() = false
    internal fun belongsTo(source: String?): Boolean = sourceProblem == null && sourceSha256 == source
    override fun toString(): String = "DesktopCredentialImportPlan($sourceProblem,$decisions)"

    companion object {
        fun classify(
            envelopeVersion: Int, manifestVersion: Int, appVersionCode: Int, sourceSha256: String,
            sources: List<ImportCredentialSource>?, targets: List<ImportCredentialTarget>,
            protectedSinkAvailable: Boolean,
        ): DesktopCredentialImportPlan {
            val problem = when {
                envelopeVersion != 1 || manifestVersion != 1 || appVersionCode != 84 -> CredentialPlanReason.SOURCE_FORMAT_UNSUPPORTED
                !sourceSha256.matches(Regex("[a-f0-9]{64}")) || sources == null -> CredentialPlanReason.SOURCE_IDENTITY_INVALID
                else -> null
            }
            if (problem != null) return DesktopCredentialImportPlan(emptyList(), problem, null)
            val records = requireNotNull(sources)
            val decisions = records.mapIndexed { ordinal, source ->
                fun result(status: CredentialPlanStatus, reason: CredentialPlanReason, key: DesktopCredentialKey? = null) =
                    CredentialMappingDecision(ordinal, source.kind, status, reason, key?.let(::ImportCredentialCandidate))
                val key = key(source.kind, source.account)
                val matching = targets.filter { key(it.kind, it.account) == key && key != null }
                when {
                    source.kind == ImportCredentialKind.UNKNOWN -> result(CredentialPlanStatus.UNMAPPED, CredentialPlanReason.UNKNOWN_KIND)
                    source.location != location(source.kind) -> result(CredentialPlanStatus.CONFLICT, CredentialPlanReason.LOCATION_MISMATCH)
                    !validAccount(source.kind, source.account) -> result(CredentialPlanStatus.UNMAPPED, CredentialPlanReason.SOURCE_IDENTITY_INVALID)
                    records.count { it.kind == source.kind && (key(it.kind, it.account) == key) } > 1 ->
                        result(CredentialPlanStatus.CONFLICT, CredentialPlanReason.DUPLICATE_SOURCE)
                    source.presence != ImportSecretPresence.NONEMPTY -> result(CredentialPlanStatus.NO_SECRET, CredentialPlanReason.NO_INCOMING_SECRET)
                    source.kind == ImportCredentialKind.COMMUNITY || !protectedSinkAvailable -> result(CredentialPlanStatus.UNMAPPED, CredentialPlanReason.NO_PROTECTED_SINK)
                    matching.size > 1 -> result(CredentialPlanStatus.CONFLICT, CredentialPlanReason.DUPLICATE_TARGET)
                    matching.isEmpty() || matching.single().relation == ExistingSecretRelation.UNKNOWN -> result(CredentialPlanStatus.UNMAPPED, CredentialPlanReason.TARGET_STATE_UNKNOWN)
                    !validAccount(matching.single().kind, matching.single().account) -> result(CredentialPlanStatus.UNMAPPED, CredentialPlanReason.SOURCE_IDENTITY_INVALID)
                    source.account != null && !source.account.sameAccount(requireNotNull(matching.single().account)) ->
                        result(CredentialPlanStatus.CONFLICT, CredentialPlanReason.ENDPOINT_OR_PROVIDER_CONFLICT)
                    source.account != null && (targets.any { it.kind == source.kind && it.account?.nameCollision(source.account) == true } ||
                        records.any { it !== source && it.kind == source.kind && it.account?.nameCollision(source.account) == true }) ->
                        result(CredentialPlanStatus.REQUIRES_CONFIRMATION, CredentialPlanReason.NAME_COLLISION)
                    matching.single().relation == ExistingSecretRelation.DIFFERENT -> result(CredentialPlanStatus.REQUIRES_CONFIRMATION, CredentialPlanReason.EXISTING_SECRET_DIFFERS)
                    else -> result(CredentialPlanStatus.CANDIDATE, CredentialPlanReason.MAPPED, requireNotNull(key))
                }
            }
            return DesktopCredentialImportPlan(decisions, null, sourceSha256)
        }

        private fun validAccount(kind: ImportCredentialKind, account: ImportAccountIdentity?): Boolean = when (kind) {
            ImportCredentialKind.MODEL, ImportCredentialKind.LEGACY_EMBEDDING, ImportCredentialKind.SINGLETON_EMBEDDING -> account?.valid(kind) == true
            else -> account == null
        }
        private fun key(kind: ImportCredentialKind, account: ImportAccountIdentity?): DesktopCredentialKey? = when (kind) {
            ImportCredentialKind.NOVEL_AI -> DesktopCredentialKey.NovelAiToken
            ImportCredentialKind.FISH -> DesktopCredentialKey.FishAudioApiKey
            ImportCredentialKind.SILICON_FLOW -> DesktopCredentialKey.SiliconFlowApiKey
            else -> account?.key(kind)
        }
        private fun location(kind: ImportCredentialKind): ImportCredentialLocation = when (kind) {
            ImportCredentialKind.NOVEL_AI -> ImportCredentialLocation.PREFERENCES_NOVEL_AI
            ImportCredentialKind.FISH -> ImportCredentialLocation.PREFERENCES_FISH
            ImportCredentialKind.SILICON_FLOW -> ImportCredentialLocation.APP_SETTINGS
            ImportCredentialKind.MODEL -> ImportCredentialLocation.MODEL_CONFIG
            ImportCredentialKind.LEGACY_EMBEDDING -> ImportCredentialLocation.LEGACY_EMBEDDING_CONFIG
            ImportCredentialKind.SINGLETON_EMBEDDING -> ImportCredentialLocation.SINGLETON_EMBEDDING_CONFIG
            ImportCredentialKind.COMMUNITY -> ImportCredentialLocation.PREFERENCES_COMMUNITY
            ImportCredentialKind.UNKNOWN -> ImportCredentialLocation.UNKNOWN
        }
    }
}
