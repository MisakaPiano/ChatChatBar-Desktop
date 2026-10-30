package com.example.chatbar.desktop.backup

import com.example.chatbar.desktop.security.*
import kotlinx.serialization.json.*

internal fun validateAccountPreferences(value: JsonObject) {
    mapOf("desktopLlmAccounts" to DesktopAccountService.LLM, "desktopNovelAiAccounts" to DesktopAccountService.NOVELAI,
        "desktopFishAccounts" to DesktopAccountService.FISH).forEach { (field, service) ->
        value[field]?.takeUnless { it == JsonNull }?.let { element ->
            require(element is JsonPrimitive && element.isString)
            if (element.content.isNotEmpty()) DesktopAccountStore.decode(element.content, service)
        }
    }
}

internal fun accountKind(service: DesktopAccountService) = when (service) {
    DesktopAccountService.LLM -> ImportCredentialKind.DESKTOP_LLM_ACCOUNTS
    DesktopAccountService.NOVELAI -> ImportCredentialKind.DESKTOP_NOVELAI_ACCOUNTS
    DesktopAccountService.FISH -> ImportCredentialKind.DESKTOP_FISH_ACCOUNTS
}

internal data class MergedNamedAccounts(val bundle: DesktopAccountBundle, val legacy: Map<String, String>) {
    override fun toString() = "MergedNamedAccounts(REDACTED)"
}

/** Keep both account collections; data choices govern model bindings, the credential choice
 * governs the default NovelAI/Fish selection. Same-ID different accounts get a fresh identity. */
internal fun mergeNamedAccounts(computer: DesktopAccountBundle?, incoming: DesktopAccountBundle?,
    service: DesktopAccountService, takeIncoming: Boolean, modelIds: Set<String>,
    remap: (String) -> String, incomingModelTaken: (String) -> Boolean): MergedNamedAccounts {
    val old = computer ?: DesktopAccountBundle()
    val phone = incoming ?: DesktopAccountBundle()
    val accounts = old.accounts.toMutableList()
    val renamed = linkedMapOf<String, String>()
    phone.accounts.forEach { source ->
        val existing = accounts.firstOrNull { it.id == source.id }
        val id = if (existing == null || existing == source) source.id else java.util.UUID.randomUUID().toString()
        renamed[source.id] = id
        if (existing != source) accounts += source.copy(id = id,
            name = if (id == source.id) source.name else source.name.take(72) + "（导入）")
    }
    // Replacing a model with an incoming legacy/single-key model must also remove the
    // PC binding. BOTH remaps the incoming ID, so its original PC binding stays intact.
    val bindings = old.models.filterKeys {
        it in modelIds && !(incomingModelTaken(it) && remap(it) == it)
    }.toMutableMap()
    val legacy = linkedMapOf<String, String>()
    if (service == DesktopAccountService.LLM) phone.models.forEach { (model, account) ->
        val target = remap(model)
        if (incomingModelTaken(model) && target in modelIds) {
            bindings[target] = renamed.getValue(account)
            phone.legacy[model]?.let { legacy[target] = it }
        }
    } else if (takeIncoming) phone.legacy["default"]?.let { legacy["default"] = it }
    val selected = if (takeIncoming && incoming != null) phone.selected?.let(renamed::getValue) else old.selected
    return MergedNamedAccounts(DesktopAccountBundle(accounts = accounts, selected = selected,
        models = if (service == DesktopAccountService.LLM) bindings else emptyMap()), legacy)
}
