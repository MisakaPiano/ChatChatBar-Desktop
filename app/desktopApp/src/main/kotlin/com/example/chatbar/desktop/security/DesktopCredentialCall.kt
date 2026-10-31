package com.example.chatbar.desktop.security

import kotlin.coroutines.*
import kotlinx.coroutines.currentCoroutineContext

internal class DesktopCredentialCall(val owner: DesktopSecretStore, val credentialKey: DesktopCredentialKey,
    val identity: String, private val value: String?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopCredentialCall>
    fun read() = value
    override fun toString() = "DesktopCredentialCall(REDACTED)"
}
internal fun DesktopAccountStore.identity(service: DesktopAccountService): String =
    serviceIdentity(service)

internal suspend fun DesktopSecretStore.credentialForCall(key: DesktopCredentialKey): String? {
    val frozen = currentCoroutineContext()[DesktopCredentialCall]
    return if (frozen != null && frozen.owner === this && frozen.credentialKey == key) frozen.read() else load(key)
}
