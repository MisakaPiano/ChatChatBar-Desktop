@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.example.chatbar.desktop.security

import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

@Serializable enum class DesktopAccountService { LLM, NOVELAI, FISH }
@Serializable internal data class DesktopApiAccount(val id: String, val name: String, val provider: String,
    val endpoint: String = "", internal val credential: String) {
    override fun toString() = "DesktopApiAccount($id, REDACTED)"
}
@Serializable internal data class DesktopAccountBundle(val version: Int = 1,
    val accounts: List<DesktopApiAccount> = emptyList(), val selected: String? = null,
    val models: Map<String, String> = emptyMap(), val legacy: Map<String, String> = emptyMap(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val defaultLlm: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val managesLegacyDefault: Boolean = false) {
    override fun toString() = "DesktopAccountBundle(REDACTED)"
}
internal data class DesktopAccountSummary(val id: String, val name: String, val provider: String, val endpoint: String)

/** All metadata and keys form one atomic DPAPI value per service. Legacy slots are left intact.
 * The adapter routes existing callers; no transport is allowed to rotate accounts on failure. */
internal class DesktopAccountStore(private val underlying: DesktopSecretStore) : DesktopSecretStore {
    private val lock = Any()
    private val changes = MutableStateFlow(0L)
    private val serviceRevisions = DesktopAccountService.entries.associateWith { 0L }.toMutableMap()
    val revision = changes.asStateFlow()
    fun hasUnmanagedDefault(service: DesktopAccountService): Boolean = synchronized(lock) {
        !read(service).managesLegacyDefault && !underlying.load(defaultKey(service)).isNullOrBlank()
    }
    /** Explicit account-management entry adopts the existing main key without deleting its old slot. */
    fun adoptExistingDefault(service: DesktopAccountService) = synchronized(lock) {
        val before = read(service)
        if (before.managesLegacyDefault) return@synchronized
        val key = defaultKey(service)
        val secret = underlying.load(key)?.takeIf { it.isNotBlank() }
        val endpoint = if (service == DesktopAccountService.LLM) SILICONFLOW_ENDPOINT else ""
        val provider = when (service) { DesktopAccountService.LLM -> "硅基流动"; DesktopAccountService.NOVELAI -> "NovelAI"; DesktopAccountService.FISH -> "Fish Audio" }
        val account = secret?.let { value -> before.accounts.firstOrNull { it.endpoint == endpoint && it.credential == value }
            ?: DesktopApiAccount(UUID.randomUUID().toString(), "原有账户", provider, endpoint, value) }
        write(service, before.copy(accounts = before.accounts + listOfNotNull(account).filter { added -> before.accounts.none { it.id == added.id } },
            defaultLlm = if (service == DesktopAccountService.LLM) before.defaultLlm ?: account?.id else null,
            selected = if (service == DesktopAccountService.LLM) null else before.selected ?: account?.id, managesLegacyDefault = true))
    }
    fun selectDefaultLlm(id: String?) = synchronized(lock) {
        val before = read(DesktopAccountService.LLM)
        require(id == null || endpointMatches(before.accounts.single { it.id == id }.endpoint, SILICONFLOW_ENDPOINT)) {
            "默认连接沿用硅基流动接口；其他接口请在对应模型中选择账户"
        }
        write(DesktopAccountService.LLM, before.copy(defaultLlm = id, managesLegacyDefault = true))
    }
    internal fun serviceIdentity(service: DesktopAccountService): String = synchronized(lock) {
        val bundle = read(service)
        "${(if (service == DesktopAccountService.LLM) bundle.defaultLlm else bundle.selected) ?: "legacy"}:${serviceRevisions.getValue(service)}"
    }
    private fun signal(service: DesktopAccountService?) {
        if (service != null) serviceRevisions[service] = serviceRevisions.getValue(service) + 1
        changes.value++
    }
    internal fun captureCall(key: DesktopCredentialKey, service: DesktopAccountService): DesktopCredentialCall = synchronized(lock) {
        val bundle = read(service)
        val id = if (key is DesktopCredentialKey.ModelApiKey) bundle.models[key.modelId]
            else if (service == DesktopAccountService.LLM) bundle.defaultLlm else bundle.selected
        DesktopCredentialCall(this, key, "${id ?: "legacy"}:${serviceRevisions.getValue(service)}", load(key))
    }
    private fun encoded(service: DesktopAccountService): String? {
        val value = underlying.load(DesktopCredentialKey.AccountProfiles(service))
        val required = underlying.load(DesktopCredentialKey.AccountProfilesRequired(service))
        check(required == null || required == "v1") { "账户存储标记无法识别" }
        check(value != null || required == null) { "命名账户文件缺失；不会改用旧密钥" }
        return value
    }
    private fun read(service: DesktopAccountService): DesktopAccountBundle =
        encoded(service)?.let { decode(it, service) } ?: DesktopAccountBundle()
    internal fun bundle(service: DesktopAccountService): DesktopAccountBundle = synchronized(lock) { read(service) }
    internal fun legacyValue(key: DesktopCredentialKey): String? = synchronized(lock) { underlying.load(key) }
    internal fun exportBundle(service: DesktopAccountService, modelIds: Set<String>, includeAll: Boolean = false): DesktopAccountBundle = synchronized(lock) {
        val bundle = read(service)
        if (service == DesktopAccountService.LLM) {
            val bindings = bundle.models.filterKeys { it in modelIds }
            bundle.copy(accounts = bundle.accounts.filter { includeAll || it.id in bindings.values || it.id == bundle.defaultLlm }, selected = null, models = bindings,
                legacy = bindings.keys.mapNotNull { id -> underlying.load(DesktopCredentialKey.ModelApiKey(id))?.let { id to it } }.toMap())
        } else bundle.copy(legacy = underlying.load(if (service == DesktopAccountService.NOVELAI) DesktopCredentialKey.NovelAiToken else DesktopCredentialKey.FishAudioApiKey)
            ?.let { mapOf("default" to it) } ?: emptyMap())
    }
    fun accounts(service: DesktopAccountService): List<DesktopAccountSummary> = synchronized(lock) {
        read(service).accounts.map { DesktopAccountSummary(it.id, it.name, it.provider, it.endpoint) }
    }
    fun selected(service: DesktopAccountService, modelId: String? = null): String? = synchronized(lock) {
        val bundle = read(service); if (service == DesktopAccountService.LLM) if (modelId == null) bundle.defaultLlm else bundle.models[modelId] else bundle.selected
    }
    fun saveAccount(service: DesktopAccountService, id: String?, name: String, provider: String, endpoint: String,
        credential: String?): String = synchronized(lock) {
        val before = read(service)
        val existing = id?.let { expected -> before.accounts.single { it.id == expected } }
        require(existing == null || existing.id !in before.models.values || endpointMatches(existing.endpoint, endpoint)) {
            "先解除模型绑定，再更换账户服务地址"
        }
        val account = DesktopApiAccount(existing?.id ?: UUID.randomUUID().toString(), name.trim(), provider.trim(),
            if (service == DesktopAccountService.LLM) endpoint.trim().trimEnd('/') else "",
            credential?.trim() ?: existing?.credential.orEmpty())
        val after = before.copy(accounts = before.accounts.filterNot { it.id == account.id } + account)
        write(service, after); account.id
    }
    fun select(service: DesktopAccountService, accountId: String?, model: ModelConfig? = null) = synchronized(lock) {
        val before = read(service)
        val account = accountId?.let { chosen -> before.accounts.single { it.id == chosen } }
        val after = if (service == DesktopAccountService.LLM) {
            requireNotNull(model)
            require(account == null || endpointMatches(account.endpoint, model.baseUrl)) { "账户服务地址与模型不一致" }
            before.copy(models = before.models - model.id + if (accountId == null) emptyMap() else mapOf(model.id to accountId))
        } else before.copy(selected = accountId)
        write(service, after)
    }
    fun referencingModels(id: String): Set<String> = synchronized(lock) {
        read(DesktopAccountService.LLM).models.filterValues { it == id }.keys.toSet()
    }
    fun detachMissingModel(id: String) = synchronized(lock) {
        val before = read(DesktopAccountService.LLM)
        if (id in before.models) write(DesktopAccountService.LLM, before.copy(models = before.models - id))
    }
    fun deleteAccount(service: DesktopAccountService, id: String, verifiedMissingModels: Set<String> = emptySet()) = synchronized(lock) {
        val before = read(service)
        require(before.selected != id && before.defaultLlm != id && before.models.none { (model, account) -> account == id && model !in verifiedMissingModels }) {
            "请先解除账户选择，再删除账户"
        }
        write(service, before.copy(accounts = before.accounts.filterNot { it.id == id },
            models = before.models.filterNot { (model, account) -> account == id && model in verifiedMissingModels }))
    }
    fun hydrate(model: ModelConfig): ModelConfig = synchronized(lock) {
        val bundle = read(DesktopAccountService.LLM)
        val account = bundle.models[model.id]?.let { id -> bundle.accounts.single { it.id == id } }
        if (account == null) model.copy(apiKey = underlying.load(DesktopCredentialKey.ModelApiKey(model.id)).orEmpty(), credentialAccount = null)
        else {
            require(endpointMatches(account.endpoint, model.baseUrl)) { "账户服务地址与模型不一致" }
            model.copy(apiKey = account.credential, credentialAccount = com.example.chatbar.data.local.entity.ModelCredentialAccount(
                account.id, account.name, account.provider))
        }
    }
    fun requireSameAccount(model: ModelConfig) = synchronized(lock) {
        val bundle = read(DesktopAccountService.LLM)
        val selected = bundle.models[model.id]
        require(selected == model.credentialAccount?.id) { "账户选择已改变，请重新打开模型编辑器" }
        selected?.let { id -> require(endpointMatches(bundle.accounts.single { it.id == id }.endpoint, model.baseUrl)) }
    }
    override fun load(key: DesktopCredentialKey): String? = synchronized(lock) {
        if (key is DesktopCredentialKey.AccountProfiles) encoded(key.service)
        else {
            val service = service(key)
            if (service != null && key == defaultKey(service) && read(service).managesLegacyDefault) routed(key)?.credential
            else routed(key)?.credential ?: underlying.load(key)
        }
    }
    internal suspend fun deleteModelSlot(id: String, deleteEntity: suspend () -> Unit) =
        credentialTransaction(underlying, DesktopCredentialKey.ModelApiKey(id), null, deleteEntity)
    override fun save(key: DesktopCredentialKey, secret: String) = synchronized(lock) {
        val service = service(key)
        val selected = routed(key)
        if (selected == null && service != null && key == defaultKey(service) && read(service).managesLegacyDefault) {
            val before = read(service)
            val endpoint = if (service == DesktopAccountService.LLM) SILICONFLOW_ENDPOINT else ""
            val account = DesktopApiAccount(UUID.randomUUID().toString(), "新账户", when (service) {
                DesktopAccountService.LLM -> "硅基流动"; DesktopAccountService.NOVELAI -> "NovelAI"; DesktopAccountService.FISH -> "Fish Audio"
            }, endpoint, secret)
            write(service, before.copy(accounts = before.accounts + account,
                defaultLlm = if (service == DesktopAccountService.LLM) account.id else null,
                selected = if (service == DesktopAccountService.LLM) null else account.id))
            return@synchronized
        }
        if (selected == null || service == null) {
            if (key is DesktopCredentialKey.AccountProfiles) decode(secret, key.service)
            underlying.save(key, secret)
            signal((key as? DesktopCredentialKey.AccountProfiles)?.service ?: service)
        } else write(service, read(service).let { before -> before.copy(accounts = before.accounts.map {
            if (it.id == selected.id) it.copy(credential = secret) else it }) })
        Unit
    }
    override fun delete(key: DesktopCredentialKey) = synchronized(lock) {
        require(routed(key) == null) { "请在账户管理中解除选择或删除命名账户" }
        underlying.delete(key); signal((key as? DesktopCredentialKey.AccountProfiles)?.service ?: service(key)); Unit
    }
    private fun service(key: DesktopCredentialKey): DesktopAccountService? = when (key) {
        is DesktopCredentialKey.ModelApiKey -> DesktopAccountService.LLM
        DesktopCredentialKey.NovelAiToken -> DesktopAccountService.NOVELAI
        DesktopCredentialKey.FishAudioApiKey -> DesktopAccountService.FISH
        DesktopCredentialKey.SiliconFlowApiKey -> DesktopAccountService.LLM
        else -> null
    }
    private fun routed(key: DesktopCredentialKey): DesktopApiAccount? {
        val service = service(key) ?: return null
        val bundle = read(service)
        val id = if (key is DesktopCredentialKey.ModelApiKey) bundle.models[key.modelId]
            else if (service == DesktopAccountService.LLM) bundle.defaultLlm else bundle.selected
        return id?.let { selected -> bundle.accounts.single { it.id == selected } }
    }
    private fun write(service: DesktopAccountService, bundle: DesktopAccountBundle) {
        val encoded = encode(bundle, service)
        val key = DesktopCredentialKey.AccountProfiles(service)
        val before = encoded(service)
        val marker = DesktopCredentialKey.AccountProfilesRequired(service)
        val oldMarker = underlying.load(marker)
        try {
            if (oldMarker == null) underlying.save(marker, "v1")
            check(underlying.load(marker) == "v1")
            underlying.save(key, encoded)
            check(underlying.load(key) == encoded) { "账户保存未确认" }
        } catch (failure: Exception) {
            try {
                if (before == null) underlying.delete(key) else underlying.save(key, before)
                if (oldMarker == null) underlying.delete(marker) else underlying.save(marker, oldMarker)
                check(underlying.load(key) == before)
                check(underlying.load(marker) == oldMarker)
            } catch (_: Exception) { throw IllegalStateException("账户存储与恢复均未确认，请保留现有资料并检查受保护存储") }
            throw IllegalStateException("账户未保存，原配置已恢复")
        }
        signal(service)
    }
    companion object {
        const val SILICONFLOW_ENDPOINT = "https://api.siliconflow.cn/v1"
        private fun defaultKey(service: DesktopAccountService): DesktopCredentialKey = when (service) {
            DesktopAccountService.LLM -> DesktopCredentialKey.SiliconFlowApiKey
            DesktopAccountService.NOVELAI -> DesktopCredentialKey.NovelAiToken
            DesktopAccountService.FISH -> DesktopCredentialKey.FishAudioApiKey
        }
        private val json = Json { encodeDefaults = true }
        internal fun decode(value: String, service: DesktopAccountService): DesktopAccountBundle {
            require(value.length <= 65536)
            return json.decodeFromString<DesktopAccountBundle>(value).also { validate(it, service) }
        }
        internal fun encode(bundle: DesktopAccountBundle, service: DesktopAccountService): String {
            validate(bundle, service)
            return json.encodeToString(bundle).also { require(it.length <= 65536) }
        }
        private fun validate(bundle: DesktopAccountBundle, service: DesktopAccountService) {
            require(bundle.version == 1 && bundle.accounts.size <= 32 && bundle.models.size <= 1024)
            require(bundle.accounts.map { it.id }.distinct().size == bundle.accounts.size)
            require(bundle.legacy.size <= 1024 && bundle.legacy.all { it.key.length in 1..256 && it.value.length in 1..8192 })
            bundle.accounts.forEach {
                require(it.id.matches(Regex("[A-Za-z0-9-]{1,80}")))
                require(it.name.isNotBlank() && it.name.length <= 80 && it.provider.isNotBlank() && it.provider.length <= 80)
                require(it.name.none(Char::isISOControl) && it.provider.none(Char::isISOControl))
                require(it.credential.length in 1..8192 && it.credential.none(Char::isWhitespace))
                if (service == DesktopAccountService.LLM) {
                    val uri = URI(it.endpoint)
                    require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                } else require(it.endpoint.isEmpty())
            }
            val ids = bundle.accounts.map { it.id }.toSet()
            require(bundle.selected == null || bundle.selected in ids)
            require(bundle.defaultLlm == null || service == DesktopAccountService.LLM && bundle.defaultLlm in ids &&
                endpointMatches(bundle.accounts.single { it.id == bundle.defaultLlm }.endpoint, SILICONFLOW_ENDPOINT))
            require(bundle.models.all { it.key.isNotBlank() && it.key.length <= 256 && it.value in ids })
            if (service != DesktopAccountService.LLM) require(bundle.models.isEmpty())
        }
        private fun endpointMatches(account: String, model: String) = account.trimEnd('/') == model.trim().trimEnd('/')
    }
}
