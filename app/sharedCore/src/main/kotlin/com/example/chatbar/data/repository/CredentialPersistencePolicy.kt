package com.example.chatbar.data.repository

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig

interface SettingsCredentialPersistencePolicy {
    suspend fun hydrate(persisted: AppSettings): AppSettings

    suspend fun persist(
        runtime: AppSettings,
        persistEntity: suspend (AppSettings) -> Unit,
    ): AppSettings
}

object IdentitySettingsCredentialPersistencePolicy : SettingsCredentialPersistencePolicy {
    override suspend fun hydrate(persisted: AppSettings): AppSettings = persisted

    override suspend fun persist(
        runtime: AppSettings,
        persistEntity: suspend (AppSettings) -> Unit,
    ): AppSettings {
        persistEntity(runtime)
        return runtime
    }
}

interface ModelCredentialPersistencePolicy {
    val requireVerifiedEntityDeletion: Boolean

    suspend fun hydrate(persisted: ModelConfig): ModelConfig

    suspend fun persist(
        runtime: ModelConfig,
        persistEntity: suspend (ModelConfig) -> Unit,
    ): ModelConfig

    suspend fun delete(
        logicalModelId: String,
        deleteEntity: suspend () -> Unit,
    )
}

object IdentityModelCredentialPersistencePolicy : ModelCredentialPersistencePolicy {
    override val requireVerifiedEntityDeletion: Boolean = false

    override suspend fun hydrate(persisted: ModelConfig): ModelConfig = persisted

    override suspend fun persist(
        runtime: ModelConfig,
        persistEntity: suspend (ModelConfig) -> Unit,
    ): ModelConfig {
        persistEntity(runtime)
        return runtime
    }

    override suspend fun delete(
        logicalModelId: String,
        deleteEntity: suspend () -> Unit,
    ) = deleteEntity()
}
