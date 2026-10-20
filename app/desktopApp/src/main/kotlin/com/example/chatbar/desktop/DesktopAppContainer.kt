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

class DesktopAppContainer internal constructor(
    val resolvedRoot: DesktopDataRootResolution.Resolved,
    private val secretStoreFactory: (Path) -> DesktopSecretStore = WindowsSecretStore::create,
    private val bundledAssets: (String) -> ByteArray = DesktopBundledAssetReader(),
    private val filePicker: DesktopFilePicker = UnconfiguredDesktopFilePicker,
    private val novelAiDesigner: com.example.chatbar.domain.image.NovelAiPromptDesigner? = null,
) {
    val appDataRoot: Path = resolvedRoot.appDataRoot
    internal val dataOperationCoordinator = DesktopDataOperationCoordinator()
    val jsonFileStorage = JsonFileStorage(appDataRoot, dataOperationCoordinator)
    internal val documentRagReader by lazy {
        DesktopDocumentRagReader(appDataRoot, jsonFileStorage, dataOperationCoordinator, maintenance = audioMaintenance)
    }
    /** Explicit offline gateway only. No HTTP/SecretStore/default embedding runner is installed. */
    internal fun createDocumentIndexPreparation(gateway: DesktopIndexEmbeddingGateway) =
        DesktopDocumentRagIndexPreparation(appDataRoot, jsonFileStorage, dataOperationCoordinator, taskRuntime, gateway)
    private val embeddingGatewayOwner = lazy { DesktopEmbeddingGateway(
        config = { requireNotNull(modelRepository.getEmbeddingModel()) { "请先配置向量模型" } },
        allowCleartext = { settingsRepository.currentAppSettings.allowCleartextModelApi }) }
    internal val embeddingGateway by embeddingGatewayOwner
    private val ragOwner = lazy { DesktopRagController(modelRepository, settingsRepository, historyIndex = { session ->
        taskRuntime.withDocumentPreparation("chat-memory:$session", "history") {
            taskRuntime.withMessageMutation(session) {
                chatMemoryIndex.rebuild(session, settingsRepository.getAppSettings().defaultContextWindowSize)
            }
        }
    }) { character, document, progress ->
        val config = requireNotNull(modelRepository.getEmbeddingModel()) { "请先配置向量模型" }
        DesktopDocumentIndexService(appDataRoot, jsonFileStorage, characterRepository, dataOperationCoordinator,
            taskRuntime, embeddingGateway.pinned(config), audioMaintenance,
            verifyConfiguration = { embeddingGateway.verify(config) }).index(character, document, progress)
    } }
    internal val ragController by ragOwner
    private val memoryRagTransportOwner = lazy { com.example.chatbar.domain.chat.OpenAiStreamingTransport(
        allowCleartextHttp = { settingsRepository.currentAppSettings.allowCleartextModelApi }) }
    private val memoryRagTextClient by lazy { DesktopMemoryRagTextClient(memoryRagTransportOwner.value) }
    private val memoryRepository by lazy { com.example.chatbar.data.repository.MemoryRepository(jsonFileStorage,
        strictReads = true, dataGate = dataOperationCoordinator, validatePath = DesktopDocumentRagSource(appDataRoot)::safeWindowsPath) }
    private val memoryService by lazy { com.example.chatbar.domain.memory.LongTermMemoryService(chatRepository,
        memoryRepository, settingsRepository, com.example.chatbar.domain.memory.MemoryAiGateway(memoryRagTextClient),
        contextWindowManager, dataGate = dataOperationCoordinator,
        ensureSourceTurns = DesktopMemorySources(appDataRoot, jsonFileStorage, chatRepository)::ensure) }
    private val memoryOwner = lazy { DesktopMemoryController(jsonFileStorage, chatRepository, settingsRepository,
        effectiveModelResolver, memoryService, memoryRepository, dataOperationCoordinator) }
    internal val memoryController by memoryOwner
    private val chatMemoryIndex by lazy { DesktopChatMemoryIndex(appDataRoot, jsonFileStorage,
        dataOperationCoordinator, audioMaintenance,
        configuration = { requireNotNull(modelRepository.getEmbeddingModel()) }, gateway = embeddingGateway) }
    private val chatRetrieval by lazy { DesktopChatRetrieval(
        configuration = { modelRepository.getEmbeddingModel() }, settings = { settingsRepository.getAppSettings() },
        documents = documentRagReader::candidates, memories = chatMemoryIndex::candidates,
        embed = { config, text -> embeddingGateway.pinned(config).embed(DesktopIndexEmbeddingBatch(
            DesktopRagEmbeddingIdentity.from(config), 0, listOf(text to emptyMap()))).single() },
        textClient = memoryRagTextClient) }
    internal val saveSlotInspectionPicker by lazy { DesktopSaveSlotInspectionPicker(filePicker) }
    internal val desktopSecretStore: DesktopSecretStore by lazy {
        secretStoreFactory(appDataRoot)
    }
    internal val fishAudioCredentials by lazy { DesktopFishAudioCredentials(desktopSecretStore) }
    private val fishAudioLibraryOwner = lazy { DesktopFishAudioLibrary(fishAudioCredentials) }
    internal val fishAudioLibrary by fishAudioLibraryOwner
    private val fishCredentialOwner = lazy { DesktopFishCredentialController(fishAudioCredentials) }
    internal val fishCredentialController by fishCredentialOwner
    private val audioFocusOwner = lazy { DesktopAudioFocus() }
    private val audioMaintenance = DesktopAudioMaintenance(
        focus = { audioFocusOwner.value },
        stopPlayback = { if (chatVoiceOwner.isInitialized()) chatVoiceRuntime.stopForMaintenance() },
    )
    private val voiceRepositoryOwner = lazy { com.example.chatbar.data.repository.VoiceMessageRepository(jsonFileStorage, strictReads = true) }
    internal val voiceRepository by voiceRepositoryOwner
    internal val voiceAudioStore by lazy { DesktopVoiceAudioStore(appDataRoot) }
    private val chatVoiceOwner = lazy { DesktopChatVoiceRuntime(chatRepository, characterRepository, settingsRepository,
        effectiveModelResolver, voiceRepository, voiceAudioStore, fishAudioCredentials, audioFocusOwner.value.port(formal = true),
        picker = filePicker, gate = dataOperationCoordinator,
        textBusy = { sessionId -> taskRuntime.tasks.value.any { it.sessionId == sessionId && it.status == DesktopTaskStatus.RUNNING } }) }
    internal val chatVoiceRuntime by chatVoiceOwner
    private val fishPreviewOwner = lazy {
        DesktopFishPreviewRuntime(DesktopFishPreviewTransport(fishAudioCredentials), output = audioFocusOwner.value.port(),
            model = { settingsRepository.currentAppSettings.fishAudioTtsModelId })
    }
    internal val fishPreviewRuntime by fishPreviewOwner
    internal val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            storage = jsonFileStorage,
            credentialPersistencePolicy = DesktopSettingsCredentialPersistencePolicy(desktopSecretStore),
        )
    }
    internal val novelAiSettingsController by lazy {
        DesktopNovelAiSettingsController(desktopSecretStore, settingsRepository)
    }
    internal val novelAiRuntime by lazy {
        DesktopNovelAiRuntime(desktopSecretStore,
            DesktopNovelAiLiveFuse(com.example.chatbar.desktop.security.WindowsSecretRootResolver.resolve(appDataRoot)
                .resolve("phase7-live-safety")),
            DesktopNovelAiSmokeStore(jsonFileStorage, characterResourceStore, dataOperationCoordinator)::persist)
    }

    private val novelAiInfrastructureOwner = lazy {
        DesktopNovelAiInfrastructure(appDataRoot, { bundledAssetReader(it).inputStream() }, effectiveModelResolver,
            allowCleartextHttp = { settingsRepository.currentAppSettings.allowCleartextModelApi }, designerOverride = novelAiDesigner)
    }
    internal val novelAiInfrastructure by novelAiInfrastructureOwner
    internal val novelAiGenerationRuntime by lazy {
        DesktopNovelAiGenerationRuntime(desktopSecretStore,
            DesktopNovelAiGenerationStore(jsonFileStorage, characterResourceStore, dataOperationCoordinator)::persist)
    }

    internal val novelAiStudioController by lazy {
        DesktopNovelAiStudioController(jsonFileStorage, characterResourceStore, filePicker,
            dataOperationCoordinator, ownedImageCleanup::deleteUnreferenced, novelAiGenerationRuntime,
            DesktopNovelAiGuidance(appDataRoot, characterResourceStore, desktopSecretStore), taskRuntime,
            novelAiInfrastructure, settingsRepository, effectiveModelResolver, characterRepository,
            account = { com.example.chatbar.domain.image.NovelAiAccountService(secureNovelAiClient()).fetchCancellable(
                requireNotNull(desktopSecretStore.load(com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken))) },
            credentialConfigured = { !desktopSecretStore.load(com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken).isNullOrBlank() },
            postProcessor = DesktopNovelAiPostProcessor(desktopSecretStore))
    }

    internal val appearanceController by lazy { DesktopAppearanceController(settingsRepository, desktopSettingsStore) }
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
    private val formatCardEditorOwner = lazy {
        DesktopFormatCardEditorController(formatCardRepository, editorDraftRepository)
    }
    internal val formatCardEditorController by formatCardEditorOwner
    internal val worldBookRepository = WorldBookRepository(jsonFileStorage)
    private val bundledAssetReader = bundledAssets
    internal val characterResourceStore = DesktopCharacterResourceStore(
        appDataRoot,
        assetReader = bundledAssetReader,
    )
    private val ownedImageCleanup = DesktopOwnedImageCleanup(appDataRoot, characterResourceStore, dataOperationCoordinator)
    internal val chatImages = DesktopChatImages(characterResourceStore, chatRepository, dataOperationCoordinator,
        ownedImageCleanup::deleteUnreferenced)
    private val characterEditorOwner = lazy {
        DesktopCharacterEditorController(
            characters = characterRepository,
            drafts = editorDraftRepository,
            worlds = worldBookRepository,
            formats = formatCardRepository,
            chats = chatRepository,
            resources = DesktopCharacterDraftResources(appDataRoot, characterResourceStore),
            fishLibrary = { fishAudioLibrary },
            fishCredentials = fishCredentialController,
            fishPreview = { fishPreviewRuntime },
            dataGate = dataOperationCoordinator,
            documentIndex = ragController,
            json = jsonFileStorage.json,
            filePicker = filePicker,
            discardObsoleteResources = { previous, _ ->
                ownedImageCleanup.deleteUnreferenced(buildList {
                    previous.avatar?.let(::add); previous.chatBackground?.let(::add)
                    previous.characters.mapNotNullTo(this) { it.appearanceImage }
                    previous.customDocuments.mapTo(this) { it.filePath }
                })
            },
        )
    }
    internal val characterEditorController by characterEditorOwner
    internal val transferJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val worldBookEditorOwner = lazy {
        DesktopWorldBookEditorController(worldBookRepository, editorDraftRepository,
            characterRepository, transferJson)
    }
    internal val worldBookEditorController by worldBookEditorOwner
    internal val presetModelCatalogSource by lazy {
        DesktopPresetModelCatalogSource(
            assetReader = bundledAssetReader,
            json = transferJson,
        )
    }
    internal val presetSource by lazy {
        DesktopPresetSource(bundledAssetReader, transferJson, characterTransfers, formatTransfers, worldBookTransfers)
    }
    internal val presetBootstrap by lazy {
        DesktopPresetBootstrap(presetSource, jsonFileStorage, characterRepository, formatCardRepository,
            worldBookRepository, characterTransfers, formatTransfers, worldBookTransfers, characterResourceStore,
            operationGate = dataOperationCoordinator)
    }

    suspend fun initializePersistentState() {
        automaticBackupRuntime.initialize()
        presetBootstrap.initialize()
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
            presets = presetModelCatalogSource,
            fishCredentials = fishCredentialController,
            ragSettings = ragController,
        )
    }
    private val appDataSnapshotService = AppDataSnapshotService(appDataRoot)
    internal val coordinatedSnapshotService = DesktopCoordinatedSnapshotService(
        delegate = appDataSnapshotService,
        coordinator = dataOperationCoordinator,
        audioMaintenance = audioMaintenance,
    )
    private val desktopSettingsStore = DesktopSettingsStore(appDataRoot, dataOperationCoordinator)
    internal val uiLanguageController = DesktopUiLanguageController(desktopSettingsStore)
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
        audioMaintenance = audioMaintenance,
    )

    private val characterDocumentRagCleanup = DesktopCharacterDocumentRagCleanup(jsonFileStorage)
    internal val characterTransfers: CharacterCardTransferCore = createCharacterTransferCore(characterDocumentRagCleanup)
    internal val formatTransfers = FormatCardTransferService(formatCardRepository, transferJson)
    internal val worldBookTransfers = WorldBookTransferService(worldBookRepository, transferJson)
    internal val presetSuiteRestore by lazy {
        DesktopPresetSuiteRestoreService(presetSource, characterRepository, worldBookRepository,
            characterTransfers, worldBookTransfers, dataOperationCoordinator)
    }
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
        imageEncoder = chatImages::jpegBase64,
        retrieveKnowledge = { card, session, user, context, inputs, readOnly ->
            chatRetrieval.retrieve(card, session, user, context, inputs, readOnly) },
        memoryView = { session, readOnly -> memoryController.promptView(session, readOnly) },
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
            images = chatImages,
        )

    private val automaticChatImages by lazy {
        DesktopAutomaticChatImages(chatRepository, characterRepository, settingsRepository, effectiveModelResolver,
            novelAiInfrastructure::promptDesigner, characterResourceStore, dataOperationCoordinator, desktopSecretStore,
            launch = { sessionId, messageId, work -> taskRuntime.launchNovelAi("自动聊天图片", sessionId, messageId, retryable = true, work = work) })
    }

    internal val taskRuntime: DesktopTaskRuntime by lazy {
        DesktopTaskRuntime(createRealChatRuntime(), onChatCompleted = { result, stopped ->
            memoryController.afterReply(result.assistant.sessionId)
            val imageNotice = automaticChatImages.completed(result, stopped)
            val prefs = settingsRepository.getAppSettings()
            var indexNotice: String? = null
            if (!stopped() && prefs.ragInjectionMode != "OFF" && prefs.memoryRagTopK > 0 && modelRepository.getEmbeddingModel() != null) {
                try { chatMemoryIndex.rebuild(result.assistant.sessionId, prefs.defaultContextWindowSize) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: DesktopAudioMaintenanceBusyException) { indexNotice = "历史索引暂缓：音频正在使用；下一次回复后重试" }
                catch (_: Exception) { indexNotice = "历史索引未完成；旧数据保留，请检查向量模型后重试" }
            }
            listOfNotNull(imageNotice, indexNotice).joinToString("；").takeIf(String::isNotBlank)
        })
    }

    // First production subset proves absence again inside the final exclusive/CAS scope.
    internal val messageFormatRepairController by lazy {
        createMessageFormatRepairController()
    }

    /** Transport-only seam: tests exercise the same production ownership, inventory and CAS scope. */
    internal fun createMessageFormatRepairController(
        stream: ((DesktopRepairRequest) -> kotlinx.coroutines.flow.Flow<com.example.chatbar.domain.chat.StreamEvent>)? = null,
    ): DesktopMessageFormatRepairController {
        val safety = DesktopFormatRepairDerivedSafety(appDataRoot, jsonFileStorage, dataOperationCoordinator, taskRuntime)
        return if (stream == null) DesktopMessageFormatRepairController(chatRepository, settingsRepository,
            characterRepository, formatCardRepository, effectiveModelResolver, taskRuntime, safety)
        else DesktopMessageFormatRepairController(chatRepository, settingsRepository, characterRepository,
            formatCardRepository, effectiveModelResolver, taskRuntime, safety, stream)
    }

    private val connectionTestOwner = lazy { DesktopConnectionTestController(settingsRepository, effectiveModelResolver) }
    internal val connectionTestController by connectionTestOwner

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

    private val primaryChatControllerOwner = lazy {
        DesktopPrimaryChatController(
            characters = characterRepository,
            chats = chatRepository,
            settings = settingsRepository,
            models = effectiveModelResolver,
            formats = formatCardRepository,
            worldBooks = worldBookRepository,
            sessionService = characterSessionService,
            characterResources = characterResourceStore,
            modelRepository = modelRepository,
            catalogProvider = { key ->
                presetModelCatalogSource.catalog.takeIf { catalog ->
                    key != null && catalog.chatModels.any { it.modelKey == key }
                }?.provider
            },
            taskRuntime = taskRuntime,
            imageStore = chatImages,
            voices = chatVoiceRuntime,
            memory = memoryController,
            rag = ragController,
            formatRepair = messageFormatRepairController,
            imagePicker = filePicker,
            backgroundLibrary = DesktopCharacterBackgrounds(jsonFileStorage, characterResourceStore,
                dataOperationCoordinator, ownedImageCleanup::deleteUnreferenced),
            imageRegeneration = DesktopChatImageRegeneration(chatRepository, characterRepository, settingsRepository,
                characterResourceStore, dataOperationCoordinator, desktopSecretStore, taskRuntime, novelAiInfrastructure, effectiveModelResolver),
        )
    }
    internal val primaryChatController: DesktopPrimaryChatController by primaryChatControllerOwner

    internal fun createPromptInspectorController(): DesktopPromptInspectorController =
        DesktopPromptInspectorController(
            chatRepository = chatRepository,
            inspector = promptInspector,
        )

    internal fun createTypedTransferController(
        filePicker: DesktopFilePicker = this.filePicker,
        afterCharacterCommit: (com.example.chatbar.domain.card.CharacterTransferPostCommitOperation, String) -> Unit = { _, _ -> },
        refreshCommittedCharacters: suspend () -> Unit = characterRepository::refreshFromStorage,
        afterTypedPrepared: (DesktopTransferKind, String) -> Unit = { _, _ -> },
        afterTypedCommit: suspend (DesktopTransferKind, DesktopTransferConflictAction) -> Unit = { _, _ -> },
        readFormatDurable: suspend (String) -> JsonFileStorage.EntityReadResult<com.example.chatbar.data.local.entity.FormatCard> = formatCardRepository::readDurable,
        readWorldDurable: suspend (String) -> JsonFileStorage.EntityReadResult<com.example.chatbar.data.local.entity.WorldBook> = worldBookRepository::readDurable,
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
        afterCharacterCommit = afterCharacterCommit,
        refreshCommittedCharacters = refreshCommittedCharacters,
        afterTypedPrepared = afterTypedPrepared,
        afterTypedCommit = afterTypedCommit,
        readFormatDurable = readFormatDurable,
        readWorldDurable = readWorldDurable,
        presetSource = presetSource,
    )

    internal fun createManagementController(
        transfer: DesktopTypedTransferController,
        suiteRestore: DesktopPresetSuiteRestoreService = presetSuiteRestore,
    ) = DesktopManagementController(
        characterRepository, formatCardRepository, worldBookRepository, chatRepository, editorDraftRepository,
        characterTransfers, formatTransfers, worldBookTransfers, transfer,
        characterEditorController, formatCardEditorController, worldBookEditorController,
        DesktopCharacterDraftResources(appDataRoot, characterResourceStore)::discardSession,
        afterReconcile = { modelSettingsController.refreshFormatChoices() },
        presetSource = presetSource,
        presetSuiteRestore = suiteRestore,
    )

    internal fun createModelTemplateTransferController(
        filePicker: DesktopFilePicker = this.filePicker,
    ) = DesktopModelTemplateTransferController(
        models = modelRepository,
        templates = com.example.chatbar.domain.card.ModelTemplateTransferService(modelRepository, transferJson),
        picker = filePicker,
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
            taskRuntimeClose = {
                if (chatVoiceOwner.isInitialized()) chatVoiceRuntime.closeAndDrain()
                if (fishPreviewOwner.isInitialized()) fishPreviewRuntime.closeAndDrain()
                if (audioFocusOwner.isInitialized()) audioFocusOwner.value.closeAndDrain()
                if (fishAudioLibraryOwner.isInitialized()) fishAudioLibrary.closeAndDrain()
                if (fishCredentialOwner.isInitialized()) fishCredentialController.closeAndDrain()
                if (connectionTestOwner.isInitialized()) connectionTestController.closeAndDrain()
                if (novelAiInfrastructureOwner.isInitialized()) novelAiInfrastructure.closeAndDrain()
                if (ragOwner.isInitialized()) ragController.closeAndDrain()
                taskRuntime.closeAndDrain()
                if (memoryOwner.isInitialized()) memoryController.closeAndDrain()
                if (embeddingGatewayOwner.isInitialized()) embeddingGateway.closeAndDrain()
                if (memoryRagTransportOwner.isInitialized()) memoryRagTransportOwner.value.closeAndDrain()
            },
            draftRuntimeClose = {
                if (primaryChatControllerOwner.isInitialized()) primaryChatController.closeDraftPersistence()
                if (characterEditorOwner.isInitialized()) characterEditorController.closeAndDrain()
                if (formatCardEditorOwner.isInitialized()) formatCardEditorController.closeAndDrain()
                if (worldBookEditorOwner.isInitialized()) worldBookEditorController.closeAndDrain()
            },
            runtimeClose = { automaticBackupRuntime.close() },
            coordinatorClose = { dataOperationCoordinator.closeAndDrain() },
            migrationServiceClose = { dataRootMigrationService.close() },
        )
    }
}

internal suspend fun closeDesktopDataRuntimes(
    taskRuntimeClose: suspend () -> Unit = {},
    draftRuntimeClose: suspend () -> Unit = {},
    runtimeClose: suspend () -> Unit,
    coordinatorClose: suspend () -> Unit,
    migrationServiceClose: suspend () -> Unit,
) {
    // An incomplete task drain keeps the data coordinator open for feature persistence cleanup.
    DesktopShutdownTrace.record(DesktopShutdownStage.TASK_DRAIN_BEGIN)
    taskRuntimeClose()
    DesktopShutdownTrace.record(DesktopShutdownStage.TASK_DRAIN_END)
    var primaryFailure: Throwable? = null
    try {
        DesktopShutdownTrace.record(DesktopShutdownStage.DRAFT_DRAIN_BEGIN)
        draftRuntimeClose()
        DesktopShutdownTrace.record(DesktopShutdownStage.DRAFT_DRAIN_END)
    } catch (timeout: DesktopDraftDrainTimeoutException) {
        // The tracked writer may still be using storage. Retain the same root ownership as S6.
        throw timeout
    } catch (timeout: DesktopReadingPositionDrainTimeoutException) {
        throw timeout
    } catch (error: Throwable) {
        // Ordinary draft failure is reported only after the worker has joined.
        primaryFailure = error
    }
    try {
        DesktopShutdownTrace.record(DesktopShutdownStage.BACKUP_CLOSE_BEGIN)
        runtimeClose()
        DesktopShutdownTrace.record(DesktopShutdownStage.BACKUP_CLOSE_END)
    } catch (error: Throwable) {
        if (primaryFailure == null) primaryFailure = error else primaryFailure.addSuppressed(error)
    }
    try {
        DesktopShutdownTrace.record(DesktopShutdownStage.COORDINATOR_CLOSE_BEGIN)
        coordinatorClose()
        DesktopShutdownTrace.record(DesktopShutdownStage.COORDINATOR_CLOSE_END)
    } catch (error: Throwable) {
        if (primaryFailure == null) {
            primaryFailure = error
        } else {
            primaryFailure.addSuppressed(error)
        }
    }
    try {
        DesktopShutdownTrace.record(DesktopShutdownStage.MIGRATION_CLOSE_BEGIN)
        migrationServiceClose()
        DesktopShutdownTrace.record(DesktopShutdownStage.MIGRATION_CLOSE_END)
    } catch (error: Throwable) {
        if (primaryFailure == null) {
            primaryFailure = error
        } else {
            primaryFailure.addSuppressed(error)
        }
    }
    primaryFailure?.let { throw it }
}
