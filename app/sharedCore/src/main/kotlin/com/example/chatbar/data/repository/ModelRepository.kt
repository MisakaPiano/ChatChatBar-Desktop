package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PRESET_MODEL_ID_PREFIX
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetEmbeddingModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import com.example.chatbar.domain.card.NamePolicy
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 模型配置仓库 - 管理LLM和Embedding模型配置
 */
class ModelRepository(
    private val storage: JsonFileStorage,
    private val modelStorageKeyPolicy: ModelStorageKeyPolicy = IdentityModelStorageKeyPolicy,
    private val credentialPersistencePolicy: ModelCredentialPersistencePolicy =
        IdentityModelCredentialPersistencePolicy,
    private val embeddingCredentialPersistencePolicy: EmbeddingCredentialPersistencePolicy =
        IdentityEmbeddingCredentialPersistencePolicy,
) {

    companion object {
        private const val MODEL_TYPE = "model_configs"
        private const val EMBEDDING_TYPE = "embedding_configs"
        private const val EMBEDDING_SINGLETON_TYPE = "embedding_model_config"
        private const val EMBEDDING_SINGLETON_ID = "default"
    }

    private val _models = MutableStateFlow<List<ModelConfig>>(emptyList())
    val models: Flow<List<ModelConfig>> = _models.asStateFlow()

    private val _embeddings = MutableStateFlow<List<EmbeddingConfig>>(emptyList())
    val embeddings: Flow<List<EmbeddingConfig>> = _embeddings.asStateFlow()
    private val _embeddingModel = MutableStateFlow<EmbeddingConfig?>(null)
    val embeddingModel: Flow<EmbeddingConfig?> = _embeddingModel.asStateFlow()

    private val stateMutex = Mutex()
    private var initialized = false

    suspend fun initialize() = storage.withNormalOperation {
        stateMutex.withLock {
            if (initialized) return@withLock
            migrateLegacyPlanningModel()
            refreshModelCache()
            refreshEmbeddingCache()
            refreshEmbeddingModelCache()
            initialized = true
        }
    }

    /** Keep the existing cancellable credential/storage transaction, then publish its durable
     * outcome before another mutation can overtake it. Admission must precede the state mutex. */
    private suspend fun <T> mutate(publish: suspend () -> Unit, operation: suspend () -> T): T =
        storage.withNormalOperation {
            initialize()
            stateMutex.withLock {
                currentCoroutineContext().ensureActive()
                var operationFailure: Throwable? = null
                try {
                    operation()
                } catch (failure: Throwable) {
                    operationFailure = failure
                    throw failure
                } finally {
                    try {
                        withContext(NonCancellable) { publish() }
                    } catch (publicationFailure: Throwable) {
                        val original = operationFailure
                        if (original == null) throw publicationFailure
                        if (publicationFailure !== original) original.addSuppressed(publicationFailure)
                    }
                }
            }
        }

    private suspend fun refreshModelCache() {
        _models.value = storage.loadAll(MODEL_TYPE, ModelConfig.serializer())
            .map { credentialPersistencePolicy.hydrate(it) }
            .sortedBy { it.displayName }
    }

    private suspend fun loadModelEntity(logicalId: String): ModelConfig? =
        storage.loadEntity(
            MODEL_TYPE,
            modelStorageKeyPolicy.storageKey(logicalId),
            ModelConfig.serializer()
        )?.let { credentialPersistencePolicy.hydrate(it) }

    private suspend fun saveModelEntity(model: ModelConfig, onCommitted: (() -> Unit)? = null): ModelConfig =
        credentialPersistencePolicy.persist(model) { persisted ->
            val key = modelStorageKeyPolicy.storageKey(persisted.id)
            if (onCommitted == null) storage.saveEntity(MODEL_TYPE, key, persisted, ModelConfig.serializer())
            else storage.saveEntityObserved(MODEL_TYPE, key, persisted, ModelConfig.serializer(), onCommitted)
        }

    private suspend fun deleteModelEntity(logicalId: String) {
        credentialPersistencePolicy.delete(logicalId) {
            storage.deleteEntity<ModelConfig>(
                MODEL_TYPE,
                modelStorageKeyPolicy.storageKey(logicalId),
                requireSuccess = credentialPersistencePolicy.requireVerifiedEntityDeletion,
            )
        }
    }

    private suspend fun refreshEmbeddingCache() {
        _embeddings.value = storage.loadAll(EMBEDDING_TYPE, EmbeddingConfig.serializer())
            .map { embedding ->
                embeddingCredentialPersistencePolicy.hydrate(
                    EmbeddingCredentialScope.Legacy(embedding.id),
                    embedding,
                )
            }
            .sortedBy { it.displayName }
    }

    private suspend fun refreshEmbeddingModelCache() {
        _embeddingModel.value = storage.loadEntity(
            EMBEDDING_SINGLETON_TYPE,
            EMBEDDING_SINGLETON_ID,
            EmbeddingConfig.serializer()
        )?.let { embedding ->
            embeddingCredentialPersistencePolicy.hydrate(
                EmbeddingCredentialScope.Singleton,
                embedding,
            )
        }
    }

    /** Preserve old explicit auxiliary bindings while retiring the dedicated model slot. */
    private suspend fun migrateLegacyPlanningModel() {
        val legacyType = "retrieval_model_config"
        val persistedLegacy = storage.loadEntity(legacyType, "default", ModelConfig.serializer()) ?: return
        val legacy = credentialPersistencePolicy.hydrate(persistedLegacy)
        val migrated = legacy.copy(
            displayName = if (legacy.displayName == "检索规划模型") legacy.modelName else legacy.displayName,
            selectableForChat = true,
            sourcePresetKey = null,
            sourcePresetVersion = null
        )
        val existing = loadModelEntity(legacy.id)
        if (existing == null) {
            saveModelEntity(migrated)
        } else if (existing != migrated) {
            // Ordinary models already took precedence for colliding IDs. Keep that binding,
            // and preserve the retired configuration under a stable separate ID.
            val preserved = migrated.copy(id = "legacy-planning-${legacy.id}")
            if (loadModelEntity(preserved.id) == null) {
                saveModelEntity(preserved)
            }
        }
        // A restart after saving can safely repeat this without overwriting an edited model.
        storage.deleteEntity<ModelConfig>(legacyType, "default")
    }

    // ===== LLM模型 =====

    suspend fun getAllModels(): List<ModelConfig> {
        initialize()
        return _models.value
    }

    suspend fun getModel(id: String): ModelConfig? {
        initialize()
        return loadModelEntity(id)
    }

    suspend fun saveModel(model: ModelConfig): Unit = mutate(::refreshModelCache) {
        saveModelEntity(model)
        Unit
    }

    /** Opt-in observation at the durable storage boundary; regular saveModel callers are unchanged. */
    suspend fun saveModelObserved(model: ModelConfig, onCommitted: () -> Unit): Unit = mutate(::refreshModelCache) {
        saveModelEntity(model, onCommitted)
        Unit
    }

    suspend fun readDurableModel(logicalId: String): JsonFileStorage.EntityReadResult<ModelConfig> =
        storage.readEntityStrict(MODEL_TYPE, modelStorageKeyPolicy.storageKey(logicalId), ModelConfig.serializer())

    suspend fun refreshModelsFromStorage(): Unit = mutate(::refreshModelCache) { Unit }

    suspend fun duplicateModel(id: String): ModelConfig = mutate(::refreshModelCache) {
        val source = loadModelEntity(id) ?: error("Model not found")
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            displayName = NamePolicy.nextCopyName(source.displayName, _models.value.map { it.displayName }),
            credentialAccount = null,
            sourcePresetKey = null,
            sourcePresetVersion = null,
            createdAt = System.currentTimeMillis()
        )
        saveModelEntity(copy)
    }

    suspend fun ensurePresetChatModels(catalog: PresetModelCatalog, catalogVersion: Int): List<ModelConfig> =
        upsertPresetChatModels(catalog, catalogVersion, overwriteExisting = false)

    suspend fun restorePresetChatModels(catalog: PresetModelCatalog, catalogVersion: Int): List<ModelConfig> =
        upsertPresetChatModels(catalog, catalogVersion, overwriteExisting = true)

    suspend fun ensurePresetEmbeddingModel(catalog: PresetModelCatalog) {
        upsertPresetEmbeddingModel(catalog, overwriteExisting = false)
    }

    suspend fun restorePresetEmbeddingModel(catalog: PresetModelCatalog) {
        upsertPresetEmbeddingModel(catalog, overwriteExisting = true)
    }

    private suspend fun upsertPresetChatModels(
        catalog: PresetModelCatalog,
        catalogVersion: Int,
        overwriteExisting: Boolean
    ): List<ModelConfig> = mutate(::refreshModelCache) {
        val current = _models.value
        val saved = mutableListOf<ModelConfig>()
        catalog.chatModels.forEach { preset ->
            val stableId = PRESET_MODEL_ID_PREFIX + preset.modelKey
            val existing = current.firstOrNull { it.sourcePresetKey == preset.modelKey || it.id == stableId }
            val next = if (existing != null && !overwriteExisting) {
                existing
            } else {
                preset.toModelConfig(
                    catalog = catalog,
                    catalogVersion = catalogVersion,
                    existing = existing
                )
            }
            saved += if (existing == null || next != existing) saveModelEntity(next) else next
        }
        saved
    }

    private fun PresetChatModel.toModelConfig(
        catalog: PresetModelCatalog,
        catalogVersion: Int,
        existing: ModelConfig?
    ): ModelConfig = ModelConfig(
        id = existing?.id ?: PRESET_MODEL_ID_PREFIX + modelKey,
        displayName = displayName,
        baseUrl = catalog.baseUrl,
        apiKey = existing?.apiKey.orEmpty(),
        modelName = modelName,
        selectableForChat = selectableForChat,
        isMultimodal = isMultimodal,
        visionModelId = visionModelKey?.let { PRESET_MODEL_ID_PREFIX + it },
        templateType = templateType,
        customParams = customParams,
        reasoningEffort = reasoningEffort,
        enableThinking = enableThinking,
        maxOutputTokens = maxOutputTokens,
        formatPromptPosition = existing?.formatPromptPosition ?: FormatPromptPosition.BOTH,
        sourcePresetKey = modelKey,
        sourcePresetVersion = catalogVersion,
        createdAt = existing?.createdAt ?: 0L
    )

    private suspend fun upsertPresetEmbeddingModel(
        catalog: PresetModelCatalog,
        overwriteExisting: Boolean
    ): Unit = mutate(::refreshEmbeddingModelCache) {
        catalog.embeddingModel?.let { preset ->
            val existing = _embeddingModel.value
            if (existing == null || overwriteExisting) {
                val next = preset.toEmbeddingConfig(catalog, existing)
                if (next != existing) saveEmbeddingModelEntity(next)
            }
        }
        Unit
    }

    private fun PresetEmbeddingModel.toEmbeddingConfig(
        catalog: PresetModelCatalog,
        existing: EmbeddingConfig?
    ): EmbeddingConfig = EmbeddingConfig(
        id = existing?.id ?: EMBEDDING_SINGLETON_ID,
        displayName = displayName,
        baseUrl = catalog.baseUrl,
        apiKey = existing?.apiKey.orEmpty(),
        modelName = modelName,
        dimensions = dimensions
    )

    suspend fun deleteModel(id: String): Unit = mutate(::refreshModelCache) {
        deleteModelEntity(id)
    }

    // ===== Embedding模型 =====

    suspend fun getAllEmbeddings(): List<EmbeddingConfig> {
        initialize()
        return _embeddings.value
    }

    suspend fun getEmbedding(id: String): EmbeddingConfig? {
        return storage.loadEntity(EMBEDDING_TYPE, id, EmbeddingConfig.serializer())
            ?.let { embedding ->
                embeddingCredentialPersistencePolicy.hydrate(
                    EmbeddingCredentialScope.Legacy(embedding.id),
                    embedding,
                )
            }
    }

    suspend fun saveEmbedding(config: EmbeddingConfig): Unit = mutate(::refreshEmbeddingCache) {
        embeddingCredentialPersistencePolicy.persist(
            scope = EmbeddingCredentialScope.Legacy(config.id),
            runtime = config,
        ) { persisted ->
            storage.saveEntity(EMBEDDING_TYPE, persisted.id, persisted, EmbeddingConfig.serializer())
        }
    }

    suspend fun deleteEmbedding(id: String): Unit = mutate(::refreshEmbeddingCache) {
        deleteEmbeddingEntity(id)
    }

    private suspend fun deleteEmbeddingEntity(id: String) =
        embeddingCredentialPersistencePolicy.delete(EmbeddingCredentialScope.Legacy(id)) {
            storage.deleteEntity<EmbeddingConfig>(
                EMBEDDING_TYPE,
                id,
                requireSuccess = embeddingCredentialPersistencePolicy.requireVerifiedEntityDeletion,
            )
        }

    suspend fun getEmbeddingModel(): EmbeddingConfig? {
        initialize()
        return _embeddingModel.value
    }

    suspend fun saveEmbeddingModel(config: EmbeddingConfig): Unit = mutate(::refreshEmbeddingModelCache) {
        saveEmbeddingModelEntity(config)
    }

    private suspend fun saveEmbeddingModelEntity(config: EmbeddingConfig) {
        val singleton = config.copy(id = EMBEDDING_SINGLETON_ID)
        embeddingCredentialPersistencePolicy.persist(
            scope = EmbeddingCredentialScope.Singleton,
            runtime = singleton,
        ) { persisted ->
            storage.saveEntity(
                EMBEDDING_SINGLETON_TYPE,
                EMBEDDING_SINGLETON_ID,
                persisted,
                EmbeddingConfig.serializer()
            )
        }
    }

    suspend fun deleteEmbeddingModel(): Unit = mutate(::refreshEmbeddingModelCache) {
        embeddingCredentialPersistencePolicy.delete(EmbeddingCredentialScope.Singleton) {
            storage.deleteEntity<EmbeddingConfig>(
                EMBEDDING_SINGLETON_TYPE,
                EMBEDDING_SINGLETON_ID,
                requireSuccess = embeddingCredentialPersistencePolicy.requireVerifiedEntityDeletion,
            )
        }
    }

    suspend fun migrateEmbeddingsToSingleton(preferredId: String?): Unit = mutate({
        refreshEmbeddingCache()
        refreshEmbeddingModelCache()
    }) {
        val legacy = _embeddings.value
        if (_embeddingModel.value == null) {
            val selected = legacy.firstOrNull { it.id == preferredId } ?: legacy.firstOrNull()
            if (selected != null) saveEmbeddingModelEntity(selected)
        }
        legacy.forEach { deleteEmbeddingEntity(it.id) }
    }

}
