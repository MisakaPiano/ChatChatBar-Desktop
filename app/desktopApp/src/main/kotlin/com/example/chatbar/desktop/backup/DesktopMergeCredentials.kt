package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.desktop.DesktopFreshRootAuthority
import com.example.chatbar.desktop.security.*
import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Volatile known-key snapshot and explicit selection; writes only a capability-bound NEW namespace. */
internal class DesktopMergeCredentials private constructor(
    private val oldStore: DesktopSecretStore,
    private val pc: Map<DesktopCredentialKey, Captured>,
    private val phone: Map<DesktopCredentialKey, Captured>,
    private val phoneConsumers: Map<DesktopCredentialKey, Consumer>,
) : AutoCloseable {
    enum class Choice { KEEP_COMPUTER, TAKE_PHONE }
    class GlobalConflict internal constructor(val kind: ImportCredentialKind, val sessionUncompared: Boolean) {
        override fun toString() = "CredentialConflict($kind)"
    }
    class SourceState internal constructor(val kind: ImportCredentialKind, val state: CredentialFieldState) {
        override fun toString() = "CredentialSourceState($kind,$state)"
    }
    private class Captured(val kind: ImportCredentialKind, val account: ImportAccountIdentity?, val value: CharArray?,
        val state: CredentialFieldState = if (value == null) CredentialFieldState.MISSING else CredentialFieldState.NONEMPTY) {
        override fun toString() = "CapturedCredential(REDACTED)"
    }
    private class Consumer(val kind: ImportCredentialKind, val account: ImportAccountIdentity, val units: MutableSet<String>)
    private var closed = false
    private fun open() = check(!closed) { "MERGE_CREDENTIALS_CLOSED" }
    val incomingStates: List<SourceState> get() { open(); return phone.values.map { SourceState(it.kind, it.state) } }

    val dataConflictKeys: Set<String> get() {
        open()
        val oldAccounts = pc[DesktopCredentialKey.AccountProfiles(DesktopAccountService.LLM)]?.value
            ?.concatToString()?.let { DesktopAccountStore.decode(it, DesktopAccountService.LLM) }
        val incomingAccounts = phone[DesktopCredentialKey.AccountProfiles(DesktopAccountService.LLM)]?.value
            ?.concatToString()?.let { DesktopAccountStore.decode(it, DesktopAccountService.LLM) }
        fun binding(bundle: DesktopAccountBundle?, id: String): DesktopApiAccount? =
            bundle?.models?.get(id)?.let { selected -> bundle.accounts.single { it.id == selected } }
        return phoneConsumers.filter { (key, _) ->
            val incoming = phone[key]?.value; val old = pc[key]?.value
            (incoming != null && old != null && !incoming.contentEquals(old)) ||
                (key is DesktopCredentialKey.ModelApiKey && key in pc &&
                    (incoming != null || binding(incomingAccounts, key.modelId) != null) &&
                    binding(oldAccounts, key.modelId) != binding(incomingAccounts, key.modelId))
        }.values.flatMap { it.units }.toSet()
    }
    val globalConflicts: List<GlobalConflict> get() {
        open()
        return globals.mapNotNull { (key, kind) ->
            val old = pc[key]?.value; val incoming = phone[key]?.value
            // Community is dormant session custody, never API-key equality classification.
            if (old != null && incoming != null && (kind == ImportCredentialKind.COMMUNITY || !old.contentEquals(incoming)))
                GlobalConflict(kind, kind == ImportCredentialKind.COMMUNITY) else null
        }
    }

    /** Unavailable/malformed reads never mean absent. No value/digest leaves this object. */
    fun verifyComputerUnchanged() {
        open()
        for ((key, entry) in pc) {
            val now = read(oldStore, key)
            try { check(equal(entry.value, now)) { "MERGE_COMPUTER_CREDENTIAL_CHANGED" } }
            finally { now?.fill('\u0000') }
        }
    }

    suspend fun writeNewRoot(root: Path, authority: DesktopFreshRootAuthority, pending: DesktopFreshRootAuthority.Pending,
        plan: DesktopMergeDestinationPlan, selection: DesktopMergePreview.Selection,
        globalChoices: Map<ImportCredentialKind, Choice>, confirmed: Boolean): Int {
        open(); check(confirmed) { "MERGE_CREDENTIAL_CONFIRMATION_REQUIRED" }
        authority.requirePreparing(pending, root)
        require(globalChoices.keys == globalConflicts.mapTo(hashSetOf()) { it.kind }) { "MERGE_CREDENTIAL_EXPLICIT_CHOICES_REQUIRED" }
        val consumers = consumers(plan.entities)
        val chosen = linkedMapOf<DesktopCredentialKey, Captured>()
        for ((key, entry) in pc) if (key in globals || consumers[key]?.account?.let { entry.account?.sameAccount(it) } == true) {
            if (entry.value != null) chosen[key] = entry
        }
        for ((key, kind) in globals) {
            val incoming = phone[key] ?: continue
            if (incoming.value == null) continue // Missing/null/empty export fields never clear a PC key.
            val conflict = globalConflicts.any { it.kind == kind }
            if (!conflict || globalChoices.getValue(kind) == Choice.TAKE_PHONE) chosen[key] = incoming
        }
        for ((oldKey, sourceConsumer) in phoneConsumers) {
            val incoming = phone[oldKey] ?: continue
            if (incoming.value == null) continue
            val relevant = sourceConsumer.units.filter { it !in selection.ignoredIncomingUnits }
            if (relevant.isEmpty()) continue
            val targetKey = when (oldKey) {
                is DesktopCredentialKey.ModelApiKey -> DesktopCredentialKey.ModelApiKey(plan.identity.value("model_configs", oldKey.modelId))
                is DesktopCredentialKey.LegacyEmbeddingApiKey -> DesktopCredentialKey.LegacyEmbeddingApiKey(plan.identity.value("embedding_configs", oldKey.embeddingId))
                DesktopCredentialKey.SingletonEmbeddingApiKey -> oldKey
                else -> error("MERGE_CREDENTIAL_KIND_UNMAPPED")
            }
            val oldId = when (oldKey) {
                is DesktopCredentialKey.ModelApiKey -> oldKey.modelId
                is DesktopCredentialKey.LegacyEmbeddingApiKey -> oldKey.embeddingId
                else -> (sourceConsumer.account.key(ImportCredentialKind.LEGACY_EMBEDDING) as DesktopCredentialKey.LegacyEmbeddingApiKey).embeddingId
            }
            val newId = plan.identity.value(when (incoming.kind) {
                ImportCredentialKind.MODEL -> "model_configs"
                ImportCredentialKind.SINGLETON_EMBEDDING -> "embedding_model_config"
                else -> "embedding_configs"
            }, oldId)
            val account = sourceConsumer.account.withLogicalId(newId)
            val target = consumers[targetKey] ?: error("MERGE_CREDENTIAL_TARGET_CONSUMER_MISSING")
            check(account.sameAccount(target.account)) { "MERGE_CREDENTIAL_TARGET_ACCOUNT_CONFLICT" }
            val old = pc[oldKey]
            if (targetKey == oldKey && old?.value != null && !old.value.contentEquals(incoming.value)) {
                check(sourceConsumer.units.none { it in selection.ignoredIncomingUnits }) { "MERGE_CREDENTIAL_SHARED_CHOICE_CONFLICT" }
                check(relevant.any { unit -> selection.phone.any { it.key == unit } }) { "MERGE_CREDENTIAL_DATA_CHOICE_REQUIRED" }
            }
            chosen[targetKey] = incoming
        }
        // All mapping/choices are checked before the first protected write.
        val accountBuffers = mutableListOf<CharArray>()
        try {
        DesktopAccountService.entries.forEach { service ->
            val key = DesktopCredentialKey.AccountProfiles(service)
            val computer = pc[key]?.value?.concatToString()?.let { DesktopAccountStore.decode(it, service) }
            var incoming = phone[key]?.value?.concatToString()?.let { DesktopAccountStore.decode(it, service) }
            val defaultKey = when (service) {
                DesktopAccountService.LLM -> DesktopCredentialKey.SiliconFlowApiKey
                DesktopAccountService.NOVELAI -> DesktopCredentialKey.NovelAiToken
                DesktopAccountService.FISH -> DesktopCredentialKey.FishAudioApiKey
            }
            val incomingDefault = phone[defaultKey]
            // Android has singleton credentials, not Desktop account profiles. An explicit
            // choice to take that key must not be shadowed by the retained PC named default.
            val takeLegacyDefault = incoming == null && computer?.managesLegacyDefault == true &&
                incomingDefault?.value != null && chosen[defaultKey] === incomingDefault
            if (takeLegacyDefault) {
                val id = java.util.UUID.randomUUID().toString()
                val account = DesktopApiAccount(id, "导入账户", when (service) {
                    DesktopAccountService.LLM -> "硅基流动"; DesktopAccountService.NOVELAI -> "NovelAI"; DesktopAccountService.FISH -> "Fish Audio"
                }, if (service == DesktopAccountService.LLM) DesktopAccountStore.SILICONFLOW_ENDPOINT else "",
                    checkNotNull(incomingDefault?.value).concatToString())
                incoming = DesktopAccountBundle(accounts = listOf(account),
                    selected = id.takeUnless { service == DesktopAccountService.LLM },
                    defaultLlm = id.takeIf { service == DesktopAccountService.LLM }, managesLegacyDefault = true)
            }
            if (computer != null || incoming != null) {
                val kind = accountKind(service)
                val takeIncoming = takeLegacyDefault || computer == null || globalChoices[kind] == Choice.TAKE_PHONE
                // Missing/empty imported credentials do not clear an existing compatible PC
                // credential. This also preserves account bindings during a byte-preserving
                // data-directory relocation, whose internal transport intentionally has no keys.
                fun taken(id: String) = selection.phone.any { it.key in setOf("model_configs:$id", "retrieval_model_config:$id") } &&
                    (incoming?.models?.containsKey(id) == true || phone[DesktopCredentialKey.ModelApiKey(id)]?.value != null)
                val modelEndpoints = plan.entities.filterKeys { DesktopBackupPolicy.entityType(it) in setOf("model_configs", "retrieval_model_config") }
                    .values.associate { it.getValue("id").jsonPrimitive.content to it.getValue("baseUrl").jsonPrimitive.content.trim().trimEnd('/') }
                val compatibleComputer = if (service != DesktopAccountService.LLM) computer else computer?.copy(
                    models = computer.models.filter { (id, account) -> modelEndpoints[id] == computer.accounts.single { it.id == account }.endpoint.trimEnd('/') })
                val result = mergeNamedAccounts(compatibleComputer, incoming, service, takeIncoming,
                    plan.entities.filterKeys { DesktopBackupPolicy.entityType(it) in setOf("model_configs", "retrieval_model_config") }
                        .values.mapNotNull { it["id"]?.jsonPrimitive?.content }.toSet(),
                    { plan.identity.value("model_configs", it) }, ::taken)
                val encoded = DesktopAccountStore.encode(result.bundle, service).toCharArray().also(accountBuffers::add)
                chosen[key] = Captured(kind, null, encoded)
                result.legacy.forEach { (id, value) ->
                    val legacyKey = when (service) {
                        DesktopAccountService.LLM -> DesktopCredentialKey.ModelApiKey(id)
                        DesktopAccountService.NOVELAI -> DesktopCredentialKey.NovelAiToken
                        DesktopAccountService.FISH -> DesktopCredentialKey.FishAudioApiKey
                    }
                    chosen[legacyKey] = Captured(if (service == DesktopAccountService.LLM) ImportCredentialKind.MODEL else
                        if (service == DesktopAccountService.NOVELAI) ImportCredentialKind.NOVEL_AI else ImportCredentialKind.FISH,
                        null, value.toCharArray().also(accountBuffers::add))
                }
            }
        }
        verifyComputerUnchanged()
            for ((key, entry) in chosen) {
                currentCoroutineContext().ensureActive()
                authority.requirePreparing(pending, root)
                val value = requireNotNull(entry.value)
                try {
                    if (key is DesktopCredentialKey.AccountProfiles) {
                        pending.store.save(DesktopCredentialKey.AccountProfilesRequired(key.service), "v1")
                        check(pending.store.load(DesktopCredentialKey.AccountProfilesRequired(key.service)) == "v1")
                    }
                    pending.store.save(key, value.concatToString())
                    val loaded = read(pending.store, key)
                    try { check(equal(value, loaded)) { "MERGE_CREDENTIAL_READBACK_FAILED" } }
                    finally { loaded?.fill('\u0000') }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error("MERGE_CREDENTIAL_PROTECTED_WRITE_FAILED") }
            }
            currentCoroutineContext().ensureActive()
            verifyComputerUnchanged()
            return chosen.size
        } catch (failure: Throwable) {
            // No new persistent schema: restart already refuses PREPARING. This invocation also
            // cannot later reuse the capability after an interrupted/failed protected write.
            pending.invalidate()
            throw failure
        } finally { accountBuffers.forEach { it.fill('\u0000') } }
    }

    override fun close() {
        pc.values.forEach { it.value?.fill('\u0000') }; phone.values.forEach { it.value?.fill('\u0000') }; closed = true
    }
    override fun toString() = "DesktopMergeCredentials(REDACTED)"

    companion object {
        private val globals = linkedMapOf<DesktopCredentialKey, ImportCredentialKind>(DesktopCredentialKey.NovelAiToken to ImportCredentialKind.NOVEL_AI,
            DesktopCredentialKey.FishAudioApiKey to ImportCredentialKind.FISH, DesktopCredentialKey.SiliconFlowApiKey to ImportCredentialKind.SILICON_FLOW,
            DesktopCredentialKey.DormantCommunitySession to ImportCredentialKind.COMMUNITY).also { keys ->
                DesktopAccountService.entries.forEach { keys[DesktopCredentialKey.AccountProfiles(it)] = accountKind(it) }
            }
        fun capture(computerEntities: Map<String, JsonObject>, incoming: DesktopFullBackupStaging, oldStore: DesktopSecretStore): DesktopMergeCredentials {
            val pc = linkedMapOf<DesktopCredentialKey, Captured>(); val phone = linkedMapOf<DesktopCredentialKey, Captured>()
            try {
                val pcConsumers = consumers(computerEntities); val phoneConsumers = consumers(incoming.entities)
                for ((key, kind) in globals) pc[key] = Captured(kind, null, read(oldStore, key))
                for ((key, consumer) in pcConsumers) pc[key] = Captured(consumer.kind, consumer.account, read(oldStore, key))
                for (entry in incoming.secrets.metadata) phone[entry.key] = Captured(entry.kind, entry.account, entry.withValue { it?.copyOf() }, entry.state)
                return DesktopMergeCredentials(oldStore, pc.toMap(), phone.toMap(), phoneConsumers)
            } catch (failure: Exception) {
                pc.values.forEach { it.value?.fill('\u0000') }; phone.values.forEach { it.value?.fill('\u0000') }
                if (failure is CancellationException) throw failure
                error("MERGE_CREDENTIAL_OBSERVATION_UNAVAILABLE")
            }
        }

        private fun consumers(records: Map<String, JsonObject>): Map<DesktopCredentialKey, Consumer> {
            val result = linkedMapOf<DesktopCredentialKey, Consumer>()
            for ((path, value) in records) {
                val type = DesktopBackupPolicy.entityType(path)
                val kind = when (type) {
                    "model_configs", "retrieval_model_config" -> ImportCredentialKind.MODEL
                    "embedding_configs" -> ImportCredentialKind.LEGACY_EMBEDDING
                    "embedding_model_config" -> ImportCredentialKind.SINGLETON_EMBEDDING
                    else -> continue
                }
                fun string(field: String) = value.getValue(field).jsonPrimitive.also { require(it.isString) }.content
                val id = string("id")
                val account = ImportAccountIdentity(id, string("displayName"), string("baseUrl"), string("modelName"),
                    if (kind == ImportCredentialKind.MODEL) ModelTemplate.valueOf(value["templateType"]?.jsonPrimitive?.content ?: "OPENAI") else null)
                require(account.valid(kind)) { "MERGE_CREDENTIAL_CONSUMER_IDENTITY" }
                val key = requireNotNull(account.key(kind))
                val unit = "$type:$id"
                val old = result[key]
                if (old == null) result[key] = Consumer(kind, account, linkedSetOf(unit))
                else { require(old.account.sameAccount(account)) { "MERGE_CREDENTIAL_CONSUMER_ALIAS_CONFLICT" }; old.units += unit }
            }
            return result
        }
        private fun read(store: DesktopSecretStore, key: DesktopCredentialKey): CharArray? = try {
            (if (store is DesktopAccountStore && key !is DesktopCredentialKey.AccountProfiles) store.legacyValue(key) else store.load(key))
                ?.let { value -> require(value.length in 1..65536); value.toCharArray() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error("MERGE_CREDENTIAL_READ_UNAVAILABLE") }
        private fun equal(a: CharArray?, b: CharArray?) = if (a == null || b == null) a == null && b == null else a.contentEquals(b)
    }
}
