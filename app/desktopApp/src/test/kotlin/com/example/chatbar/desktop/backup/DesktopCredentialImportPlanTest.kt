package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.desktop.security.DesktopCredentialKey
import kotlin.test.*

internal object G2AMetadata {
    const val SOURCE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    fun account(kind: ImportCredentialKind, id: String = "synthetic-id", name: String = "Synthetic", endpoint: String = "https://synthetic.invalid/v1", model: String = "synthetic", template: ModelTemplate = ModelTemplate.OPENAI) =
        ImportAccountIdentity(id, name, endpoint, model, if (kind == ImportCredentialKind.MODEL) template else null)
    fun location(kind: ImportCredentialKind) = when (kind) {
        ImportCredentialKind.NOVEL_AI -> ImportCredentialLocation.PREFERENCES_NOVEL_AI
        ImportCredentialKind.FISH -> ImportCredentialLocation.PREFERENCES_FISH
        ImportCredentialKind.SILICON_FLOW -> ImportCredentialLocation.APP_SETTINGS
        ImportCredentialKind.MODEL -> ImportCredentialLocation.MODEL_CONFIG
        ImportCredentialKind.LEGACY_EMBEDDING -> ImportCredentialLocation.LEGACY_EMBEDDING_CONFIG
        ImportCredentialKind.SINGLETON_EMBEDDING -> ImportCredentialLocation.SINGLETON_EMBEDDING_CONFIG
        ImportCredentialKind.COMMUNITY -> ImportCredentialLocation.PREFERENCES_COMMUNITY
        ImportCredentialKind.UNKNOWN -> ImportCredentialLocation.UNKNOWN
    }
    fun source(kind: ImportCredentialKind, presence: ImportSecretPresence = ImportSecretPresence.NONEMPTY,
        account: ImportAccountIdentity? = if (kind in scoped) account(kind) else null) =
        ImportCredentialSource(kind, location(kind), presence, account)
    fun target(source: ImportCredentialSource, relation: ExistingSecretRelation = ExistingSecretRelation.ABSENT) =
        ImportCredentialTarget(source.kind, relation, source.account)
    fun plan(sources: List<ImportCredentialSource> = listOf(source(ImportCredentialKind.NOVEL_AI), source(ImportCredentialKind.FISH)),
        targets: List<ImportCredentialTarget> = sources.map { target(it) }, sink: Boolean = true, sha: String = SOURCE,
        envelope: Int = 1, manifest: Int = 1, code: Int = 84) =
        DesktopCredentialImportPlan.classify(envelope, manifest, code, sha, sources, targets, sink)
    val scoped = setOf(ImportCredentialKind.MODEL, ImportCredentialKind.LEGACY_EMBEDDING, ImportCredentialKind.SINGLETON_EMBEDDING)
}

class DesktopCredentialImportPlanTest {
    @Test fun `six protected families map to existing disjoint key contracts`() {
        val expected = linkedMapOf(
            ImportCredentialKind.NOVEL_AI to DesktopCredentialKey.NovelAiToken,
            ImportCredentialKind.FISH to DesktopCredentialKey.FishAudioApiKey,
            ImportCredentialKind.SILICON_FLOW to DesktopCredentialKey.SiliconFlowApiKey,
            ImportCredentialKind.MODEL to DesktopCredentialKey.ModelApiKey("synthetic-id"),
            ImportCredentialKind.LEGACY_EMBEDDING to DesktopCredentialKey.LegacyEmbeddingApiKey("synthetic-id"),
            ImportCredentialKind.SINGLETON_EMBEDDING to DesktopCredentialKey.SingletonEmbeddingApiKey,
        )
        val plan = G2AMetadata.plan(expected.keys.map { G2AMetadata.source(it) })
        assertTrue(plan.candidateConsistent); assertFalse(plan.restoreAuthorized)
        plan.decisions.zip(expected.values).forEach { (decision, key) ->
            assertEquals(CredentialPlanStatus.CANDIDATE, decision.status)
            assertTrue(requireNotNull(decision.candidate).matches(key))
        }
        assertEquals(6, plan.decisions.map { it.candidate }.toSet().size)
    }
    @Test fun `community has no protected sink and blocks whole candidate`() {
        val sources = listOf(G2AMetadata.source(ImportCredentialKind.NOVEL_AI), G2AMetadata.source(ImportCredentialKind.COMMUNITY))
        val plan = G2AMetadata.plan(sources)
        assertEquals(CredentialPlanReason.NO_PROTECTED_SINK, plan.decisions.last().reason)
        assertFalse(plan.candidateConsistent); assertNull(plan.decisions.last().candidate)
    }
    @Test fun `null and empty credentials mean no incoming change never target clearing`() {
        for (presence in listOf(ImportSecretPresence.ABSENT, ImportSecretPresence.EMPTY)) {
            val records = ImportCredentialKind.entries.filter { it != ImportCredentialKind.UNKNOWN }.map { G2AMetadata.source(it, presence) }
            val plan = G2AMetadata.plan(records, targets = emptyList(), sink = false)
            assertTrue(plan.candidateConsistent)
            assertTrue(plan.decisions.all { it.status == CredentialPlanStatus.NO_SECRET && it.candidate == null })
        }
    }
    @Test fun `unknown family cannot be discarded even when empty`() {
        for (presence in ImportSecretPresence.entries) {
            val plan = G2AMetadata.plan(listOf(G2AMetadata.source(ImportCredentialKind.UNKNOWN, presence)))
            assertEquals(CredentialPlanReason.UNKNOWN_KIND, plan.decisions.single().reason); assertFalse(plan.candidateConsistent)
        }
    }
    @Test fun `contradictory location and double source fail closed`() {
        val source = G2AMetadata.source(ImportCredentialKind.NOVEL_AI)
        val mismatch = ImportCredentialSource(source.kind, ImportCredentialLocation.PREFERENCES_FISH, source.presence)
        assertEquals(CredentialPlanReason.LOCATION_MISMATCH, G2AMetadata.plan(listOf(mismatch)).decisions.single().reason)
        val duplicate = G2AMetadata.plan(listOf(source, source))
        assertTrue(duplicate.decisions.all { it.reason == CredentialPlanReason.DUPLICATE_SOURCE })
        assertFalse(duplicate.candidateConsistent)
    }
    @Test fun `double empty or absent source is still structurally invalid`() {
        val sources = listOf(G2AMetadata.source(ImportCredentialKind.FISH, ImportSecretPresence.ABSENT), G2AMetadata.source(ImportCredentialKind.FISH, ImportSecretPresence.EMPTY))
        assertFalse(G2AMetadata.plan(sources).candidateConsistent)
    }
    @Test fun `same model ID with changed endpoint provider or remote model is a conflict`() {
        val source = G2AMetadata.source(ImportCredentialKind.MODEL)
        val changed = listOf(
            G2AMetadata.account(source.kind, endpoint = "https://other.invalid/v1"),
            G2AMetadata.account(source.kind, template = ModelTemplate.CLAUDE),
            G2AMetadata.account(source.kind, model = "other-model"),
            G2AMetadata.account(source.kind, endpoint = "https://synthetic.invalid/v1/"),
        )
        for (identity in changed) {
            val plan = G2AMetadata.plan(listOf(source), listOf(ImportCredentialTarget(source.kind, ExistingSecretRelation.SAME, identity)))
            assertEquals(CredentialPlanReason.ENDPOINT_OR_PROVIDER_CONFLICT, plan.decisions.single().reason)
            assertFalse(plan.candidateConsistent)
        }
    }
    @Test fun `same display name different IDs never auto binds or overrides`() {
        val source = G2AMetadata.source(ImportCredentialKind.MODEL)
        val other = ImportCredentialTarget(source.kind, ExistingSecretRelation.SAME, G2AMetadata.account(source.kind, id = "other"))
        val plan = G2AMetadata.plan(listOf(source), listOf(G2AMetadata.target(source), other))
        assertEquals(CredentialPlanStatus.REQUIRES_CONFIRMATION, plan.decisions.single().status)
        assertEquals(CredentialPlanReason.NAME_COLLISION, plan.decisions.single().reason)
        assertFalse(plan.candidateConsistent)
    }
    @Test fun `colliding source names are also explicit`() {
        val sources = listOf(G2AMetadata.source(ImportCredentialKind.MODEL),
            G2AMetadata.source(ImportCredentialKind.MODEL, account = G2AMetadata.account(ImportCredentialKind.MODEL, id = "other")))
        assertTrue(G2AMetadata.plan(sources).decisions.all { it.reason == CredentialPlanReason.NAME_COLLISION })
    }
    @Test fun `same ID legacy and singleton embedding remain separate`() {
        val sources = listOf(G2AMetadata.source(ImportCredentialKind.LEGACY_EMBEDDING), G2AMetadata.source(ImportCredentialKind.SINGLETON_EMBEDDING))
        val plan = G2AMetadata.plan(sources)
        assertTrue(plan.candidateConsistent); assertNotEquals(plan.decisions[0].candidate, plan.decisions[1].candidate)
        assertFalse(G2AMetadata.plan(listOf(sources[0]), listOf(G2AMetadata.target(sources[1]))).candidateConsistent)
    }
    @Test fun `existing different secrets require explicit future conflict resolution`() {
        val source = G2AMetadata.source(ImportCredentialKind.FISH)
        val plan = G2AMetadata.plan(listOf(source), listOf(G2AMetadata.target(source, ExistingSecretRelation.DIFFERENT)))
        assertEquals(CredentialPlanReason.EXISTING_SECRET_DIFFERS, plan.decisions.single().reason)
        assertFalse(plan.candidateConsistent)
        assertTrue(G2AMetadata.plan(listOf(source), listOf(G2AMetadata.target(source, ExistingSecretRelation.SAME))).candidateConsistent)
    }
    @Test fun `duplicate target and unknown target state are not guessed`() {
        val source = G2AMetadata.source(ImportCredentialKind.FISH); val target = G2AMetadata.target(source)
        assertEquals(CredentialPlanReason.DUPLICATE_TARGET, G2AMetadata.plan(listOf(source), listOf(target, target)).decisions.single().reason)
        for (targets in listOf(emptyList(), listOf(G2AMetadata.target(source, ExistingSecretRelation.UNKNOWN)))) {
            assertEquals(CredentialPlanReason.TARGET_STATE_UNKNOWN, G2AMetadata.plan(listOf(source), targets).decisions.single().reason)
        }
    }
    @Test fun `unavailable protected store cannot yield a mapped candidate`() {
        assertTrue(G2AMetadata.plan(sink = false).decisions.all { it.reason == CredentialPlanReason.NO_PROTECTED_SINK })
    }
    @Test fun `absent malformed and misplaced account metadata fails closed with safe diagnostics`() {
        val kind = ImportCredentialKind.MODEL
        val cases = listOf(null, G2AMetadata.account(kind, id = ""), G2AMetadata.account(kind, endpoint = "PRIVATE_BAD_ENDPOINT"),
            G2AMetadata.account(kind, endpoint = "file:///PRIVATE_PATH"), G2AMetadata.account(kind, endpoint = "https://user:PRIVATE_KEY@synthetic.invalid"),
            G2AMetadata.account(kind, endpoint = "https://synthetic.invalid?PRIVATE_KEY"), G2AMetadata.account(kind, endpoint = "https://synthetic.invalid#PRIVATE_KEY"),
            G2AMetadata.account(kind, endpoint = "https://synthetic.invalid/a/../b"), G2AMetadata.account(kind, id = "bad\nID"))
        for (account in cases) {
            val plan = G2AMetadata.plan(listOf(G2AMetadata.source(kind, account = account)))
            assertEquals(CredentialPlanReason.SOURCE_IDENTITY_INVALID, plan.decisions.single().reason)
            assertFalse(plan.toString().contains("PRIVATE")); assertFalse(plan.candidateConsistent)
        }
        val misplaced = G2AMetadata.source(ImportCredentialKind.FISH, account = G2AMetadata.account(kind))
        assertFalse(G2AMetadata.plan(listOf(misplaced)).candidateConsistent)
    }
    @Test fun `source format and provenance cannot be forged through empty successful plan`() {
        for (plan in listOf(G2AMetadata.plan(envelope = 2), G2AMetadata.plan(manifest = 2), G2AMetadata.plan(code = 85), G2AMetadata.plan(code = 86), G2AMetadata.plan(sha = "PRIVATE_INVALID"),
            DesktopCredentialImportPlan.classify(1, 1, 84, G2AMetadata.SOURCE, null, emptyList(), true))) {
            assertNotNull(plan.sourceProblem); assertFalse(plan.candidateConsistent); assertFalse(plan.restoreAuthorized)
        }
    }
    @Test fun `summaries never expose account strings target key IDs endpoints or value digests`() {
        val identity = G2AMetadata.account(ImportCredentialKind.MODEL, id = "PRIVATE_ID", name = "PRIVATE_NAME", endpoint = "https://private.invalid/PRIVATE_PATH", model = "PRIVATE_MODEL")
        val source = G2AMetadata.source(ImportCredentialKind.MODEL, account = identity)
        val target = G2AMetadata.target(source); val plan = G2AMetadata.plan(listOf(source), listOf(target))
        val summary = listOf(identity, source, target, plan, plan.decisions, plan.decisions.single().candidate).joinToString()
        for (private in listOf("PRIVATE_", "private.invalid", G2AMetadata.SOURCE)) assertFalse(summary.contains(private))
        assertTrue(plan.candidateConsistent)
    }
    @Test fun `caller list mutation cannot rewrite a frozen plan`() {
        val sources = mutableListOf(G2AMetadata.source(ImportCredentialKind.FISH))
        val plan = G2AMetadata.plan(sources); sources.clear()
        assertEquals(1, plan.decisions.size); assertTrue(plan.candidateConsistent)
    }
}
