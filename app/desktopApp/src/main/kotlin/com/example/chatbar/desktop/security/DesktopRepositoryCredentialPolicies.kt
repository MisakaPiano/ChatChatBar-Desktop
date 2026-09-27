package com.example.chatbar.desktop.security

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ModelCredentialPersistencePolicy
import com.example.chatbar.data.repository.SettingsCredentialPersistencePolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UnsafeDesktopPlaintextCredentialException(
    credentialOwner: String,
) : IllegalStateException(
    "Desktop JSON contains an unsafe plaintext credential for $credentialOwner; explicit recovery is required",
)

class DesktopCredentialConsistencyException(
    message: String,
    operationFailure: Throwable,
    rollbackFailure: Throwable,
) : IllegalStateException(message, operationFailure) {
    init {
        addSuppressed(rollbackFailure)
    }
}

class DesktopSettingsCredentialPersistencePolicy(
    private val secretStore: DesktopSecretStore,
) : SettingsCredentialPersistencePolicy {
    private val mutex = Mutex()

    override suspend fun hydrate(persisted: AppSettings): AppSettings = mutex.withLock {
        if (persisted.siliconFlowApiKey.isNotBlank()) {
            throw UnsafeDesktopPlaintextCredentialException("global SiliconFlow API key")
        }
        persisted.copy(
            siliconFlowApiKey = secretStore.load(DesktopCredentialKey.SiliconFlowApiKey).orEmpty(),
        )
    }

    override suspend fun persist(
        runtime: AppSettings,
        persistEntity: suspend (AppSettings) -> Unit,
    ): AppSettings = mutex.withLock {
        val key = DesktopCredentialKey.SiliconFlowApiKey
        val desired = runtime.siliconFlowApiKey.takeUnless(String::isBlank)
        persistWithRollback(
            key = key,
            desired = desired,
            persistEntity = { persistEntity(runtime.copy(siliconFlowApiKey = "")) },
        )
        runtime.copy(siliconFlowApiKey = desired.orEmpty())
    }

    private suspend fun persistWithRollback(
        key: DesktopCredentialKey,
        desired: String?,
        persistEntity: suspend () -> Unit,
    ) = credentialTransaction(secretStore, key, desired, persistEntity)
}

class DesktopModelCredentialPersistencePolicy(
    private val secretStore: DesktopSecretStore,
) : ModelCredentialPersistencePolicy {
    override val requireVerifiedEntityDeletion: Boolean = true

    private val mutex = Mutex()

    override suspend fun hydrate(persisted: ModelConfig): ModelConfig = mutex.withLock {
        if (persisted.apiKey.isNotBlank()) {
            throw UnsafeDesktopPlaintextCredentialException("model ${persisted.id}")
        }
        persisted.copy(
            apiKey = secretStore.load(DesktopCredentialKey.ModelApiKey(persisted.id)).orEmpty(),
        )
    }

    override suspend fun persist(
        runtime: ModelConfig,
        persistEntity: suspend (ModelConfig) -> Unit,
    ): ModelConfig = mutex.withLock {
        val desired = runtime.apiKey.takeUnless(String::isBlank)
        credentialTransaction(
            secretStore = secretStore,
            key = DesktopCredentialKey.ModelApiKey(runtime.id),
            desired = desired,
            persistEntity = { persistEntity(runtime.copy(apiKey = "")) },
        )
        runtime.copy(apiKey = desired.orEmpty())
    }

    override suspend fun delete(
        logicalModelId: String,
        deleteEntity: suspend () -> Unit,
    ) = mutex.withLock {
        credentialTransaction(
            secretStore = secretStore,
            key = DesktopCredentialKey.ModelApiKey(logicalModelId),
            desired = null,
            persistEntity = deleteEntity,
        )
    }
}

private suspend fun credentialTransaction(
    secretStore: DesktopSecretStore,
    key: DesktopCredentialKey,
    desired: String?,
    persistEntity: suspend () -> Unit,
) {
    val previous = secretStore.load(key)
    val changed = previous != desired
    if (changed) secretStore.replace(key, desired)
    try {
        persistEntity()
    } catch (operationFailure: Throwable) {
        if (!changed) throw operationFailure
        try {
            secretStore.replace(key, previous)
        } catch (rollbackFailure: Throwable) {
            throw DesktopCredentialConsistencyException(
                message = "Credential persistence failed and the previous secure value could not be restored",
                operationFailure = operationFailure,
                rollbackFailure = rollbackFailure,
            )
        }
        throw operationFailure
    }
}

private fun DesktopSecretStore.replace(key: DesktopCredentialKey, value: String?) {
    if (value == null) delete(key) else save(key, value)
}
