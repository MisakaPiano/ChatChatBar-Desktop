package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.desktop.security.DesktopEmbeddingCredentialPersistencePolicy
import com.example.chatbar.desktop.security.DesktopModelCredentialPersistencePolicy
import com.example.chatbar.desktop.security.DesktopSecretStore
import com.example.chatbar.desktop.security.DesktopSettingsCredentialPersistencePolicy
import com.example.chatbar.desktop.security.WindowsSecretStore
import com.example.chatbar.domain.card.AuthoritativeCharacterTransferPromptPolicy
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterDocumentRagCleanup
import com.example.chatbar.domain.card.CharacterTransferPromptPolicy
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.WorldBookTransferService
import com.example.chatbar.domain.chat.CharacterSessionService
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.MainChatRequestAssembler
import com.example.chatbar.domain.chat.PromptAssembler
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.ModelDiscoveryService
import com.example.chatbar.domain.worldbook.WorldBookRequestPlanner
import java.nio.file.Path
import kotlinx.serialization.json.Json

class DesktopAppContainer(
    val resolvedRoot: DesktopDataRootResolution.Resolved,
    private val secretStoreFactory: (Path) -> DesktopSecretStore = WindowsSecretStore::create,
) {
    val appDataRoot: Path = resolvedRoot.appDataRoot
    internal val dataOperationCoordinator = DesktopDataOperationCoordinator()
    val jsonFileStorage = JsonFileStorage(appDataRoot, dataOperationCoordinator)
    internal val desktopSecretStore: DesktopSecretStore by lazy {
        secretStoreFactory(appDataRoot)
    }
    internal val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            storage = jsonFileStorage,
            credentialPersistencePolicy = DesktopSettingsCredentialPersistencePolicy(desktopSecretStore),
        )
    }
    internal val modelRepository: ModelRepository by lazy {
        ModelRepository(
            storage = jsonFileStorage,
            modelStorageKeyPolicy = WindowsSafeModelStorageKeyPolicy,
            credentialPersistencePolicy = DesktopModelCredentialPersistencePolicy(desktopSecretStore),
            embeddingCredentialPersistencePolicy =
                DesktopEmbeddingCredentialPersistencePolicy(desktopSecretStore),
        )
    }
    internal val characterRepository = CharacterRepository(jsonFileStorage)
    internal val chatRepository = ChatRepository(jsonFileStorage)
    internal val editorDraftRepository = EditorDraftRepository(jsonFileStorage)
    internal val formatCardRepository = FormatCardRepository(jsonFileStorage)
    internal val worldBookRepository = WorldBookRepository(jsonFileStorage)
    private val bundledAssetReader = DesktopBundledAssetReader()
    internal val characterResourceStore = DesktopCharacterResourceStore(
        appDataRoot,
        assetReader = bundledAssetReader,
    )
    internal val transferJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    internal val presetModelCatalogSource by lazy {
        DesktopPresetModelCatalogSource(
            assetReader = bundledAssetReader,
            json = transferJson,
        )
    }
    internal val effectiveModelResolver by lazy {
        EffectiveModelResolver(
            models = modelRepository,
            settings = settingsRepository,
            presets = presetModelCatalogSource,
        )
    }
    internal val modelDiscoveryService by lazy { ModelDiscoveryService() }
    internal val modelSettingsController by lazy {
        DesktopModelSettingsController(
            models = modelRepository,
            settings = settingsRepository,
            formats = formatCardRepository,
            resolver = effectiveModelResolver,
            discovery = modelDiscoveryService,
        )
    }
    private val appDataSnapshotService = AppDataSnapshotService(appDataRoot)
    internal val coordinatedSnapshotService = DesktopCoordinatedSnapshotService(
        delegate = appDataSnapshotService,
        coordinator = dataOperationCoordinator,
    )
    private val desktopSettingsStore = DesktopSettingsStore(appDataRoot, dataOperationCoordinator)
    val automaticBackupRuntime = DesktopAutomaticBackupRuntime(
        settingsStore = desktopSettingsStore,
        snapshotService = coordinatedSnapshotService,
        coordinator = dataOperationCoordinator,
    )
    internal val dataRootMigrationService = DesktopDataRootMigrationService(
        sourceResolution = resolvedRoot,
        runtime = automaticBackupRuntime,
        coordinator = dataOperationCoordinator,
        snapshotService = appDataSnapshotService,
    )

    private val characterDocumentRagCleanup = DesktopCharacterDocumentRagCleanup(jsonFileStorage)
    internal val characterTransfers: CharacterCardTransferCore = createCharacterTransferCore(characterDocumentRagCleanup)
    internal val formatTransfers = FormatCardTransferService(formatCardRepository, transferJson)
    internal val worldBookTransfers = WorldBookTransferService(worldBookRepository, transferJson)
    internal val characterSessionService = CharacterSessionService(
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        formatCardRepository = formatCardRepository,
    )
    internal val worldBookRequestPlanner = WorldBookRequestPlanner(
        chatRepository = chatRepository,
        worldBookRepository = worldBookRepository,
    )
    internal val promptAssembler = PromptAssembler()
    internal val mainChatRequestAssembler = MainChatRequestAssembler()
    internal val contextWindowManager = ContextWindowManager()
    internal val chatRequestPlanner = DesktopChatRequestPlanner(
        characterRepository = characterRepository,
        chatRepository = chatRepository,
        formatCardRepository = formatCardRepository,
        contextWindowManager = contextWindowManager,
        worldBookRequestPlanner = worldBookRequestPlanner,
        promptAssembler = promptAssembler,
        mainChatRequestAssembler = mainChatRequestAssembler,
    )
    private val promptInspector = DesktopPromptInspector(chatRequestPlanner)
    internal val characterPngRenderer = DesktopCharacterCardPngRenderer()

    internal fun createFakeChatRuntime(driver: DesktopFakeChatDriver): DesktopFakeChatRuntime =
        DesktopFakeChatRuntime(
            characterRepository = characterRepository,
            chatRepository = chatRepository,
            characterSessionService = characterSessionService,
            requestPlanner = chatRequestPlanner,
            driver = driver,
        )

    internal fun createRealChatRuntime(): DesktopRealChatRuntime =
        DesktopRealChatRuntime(
            characterRepository = characterRepository,
            chatRepository = chatRepository,
            settingsRepository = settingsRepository,
            characterSessionService = characterSessionService,
            modelResolver = effectiveModelResolver,
            requestPlanner = chatRequestPlanner,
        )

    internal val taskRuntime: DesktopTaskRuntime by lazy {
        DesktopTaskRuntime(createRealChatRuntime())
    }

    internal val alphaChatController: DesktopAlphaChatController by lazy {
        DesktopAlphaChatController(
            characterRepository = characterRepository,
            chatRepository = chatRepository,
            settingsRepository = settingsRepository,
            modelResolver = effectiveModelResolver,
            realChat = createRealChatRuntime(),
            taskRuntime = taskRuntime,
        )
    }

    internal fun createPromptInspectorController(): DesktopPromptInspectorController =
        DesktopPromptInspectorController(
            chatRepository = chatRepository,
            inspector = promptInspector,
        )

    internal fun createTypedTransferController(
        filePicker: DesktopFilePicker = SwingDesktopFilePicker(),
    ): DesktopTypedTransferController = DesktopTypedTransferController(
        characterRepository = characterRepository,
        formatRepository = formatCardRepository,
        worldBookRepository = worldBookRepository,
        characterTransfers = characterTransfers,
        formatTransfers = formatTransfers,
        worldBookTransfers = worldBookTransfers,
        characterPngRenderer = characterPngRenderer,
        json = transferJson,
        filePicker = filePicker,
    )

    /** Production path 使用 shared Prompt-domain authority 与真实 Desktop RAG cleanup。 */
    internal fun createCharacterTransferCore(
        ragCleanup: CharacterDocumentRagCleanup,
    ): CharacterCardTransferCore = createCharacterTransferCore(
        promptPolicy = AuthoritativeCharacterTransferPromptPolicy,
        ragCleanup = ragCleanup,
    )

    /** 保留窄注入 seam，供 focused tests 验证 transfer 其余边界。 */
    internal fun createCharacterTransferCore(
        promptPolicy: CharacterTransferPromptPolicy,
        ragCleanup: CharacterDocumentRagCleanup,
    ): CharacterCardTransferCore = CharacterCardTransferCore(
        characterRepository = characterRepository,
        worldBookRepository = worldBookRepository,
        formatCardRepository = formatCardRepository,
        resources = characterResourceStore,
        promptPolicy = promptPolicy,
        ragCleanup = ragCleanup,
        json = transferJson,
        operationGate = dataOperationCoordinator,
    )

    suspend fun close() {
        closeDesktopDataRuntimes(
            taskRuntimeClose = { taskRuntime.closeAndDrain() },
            runtimeClose = { automaticBackupRuntime.close() },
            coordinatorClose = { dataOperationCoordinator.closeAndDrain() },
            migrationServiceClose = { dataRootMigrationService.close() },
        )
    }
}

internal suspend fun closeDesktopDataRuntimes(
    taskRuntimeClose: suspend () -> Unit = {},
    runtimeClose: suspend () -> Unit,
    coordinatorClose: suspend () -> Unit,
    migrationServiceClose: suspend () -> Unit,
) {
    // An incomplete task drain keeps the data coordinator open for feature persistence cleanup.
    taskRuntimeClose()
    var primaryFailure: Throwable? = null
    try {
        runtimeClose()
    } catch (error: Throwable) {
        primaryFailure = error
    }
    try {
        coordinatorClose()
    } catch (error: Throwable) {
        if (primaryFailure == null) {
            primaryFailure = error
        } else {
            primaryFailure.addSuppressed(error)
        }
    }
    try {
        migrationServiceClose()
    } catch (error: Throwable) {
        if (primaryFailure == null) {
            primaryFailure = error
        } else {
            primaryFailure.addSuppressed(error)
        }
    }
    primaryFailure?.let { throw it }
}
