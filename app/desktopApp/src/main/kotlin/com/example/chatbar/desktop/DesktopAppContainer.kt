package com.example.chatbar.desktop

import kotlinx.coroutines.flow.map
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
import com.example.chatbar.desktop.security.credentialForCall
import com.example.chatbar.desktop.security.identity
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
    private val directoryPicker: DesktopDirectoryPicker = DesktopDirectoryPicker { null },
    private val freshImportAuthority: (Path) -> DesktopFreshRootAuthority = { DesktopFreshRootAuthority(it) },
) {
    val appDataRoot: Path = resolvedRoot.appDataRoot
    internal val dataOperationCoordinator = DesktopDataOperationCoordinator()
    val jsonFileStorage = JsonFileStorage(appDataRoot, dataOperationCoordinator)
    private val lanSyncOwner = lazy { DesktopLanSync(this,
        exclusive = { work ->
            if (primaryChatControllerOwner.isInitialized()) primaryChatController.flushComposerForSnapshot()
            val reserved: suspend () -> Unit = { withBackupIoMaintenance(work = {
                dataOperationCoordinator.withExclusiveMaintenance { work() }
            }) }
            if (momentsOwner.isInitialized() || "moment_tasks" in com.example.chatbar.domain.lanSync.LanSyncScope.schemas) momentsController.withLanSyncMaintenance(
                synchronizeTasks = "moment_tasks" in com.example.chatbar.domain.lanSync.LanSyncScope.schemas, work = reserved)
            else taskRuntime.withIdleMaintenance(reserved)
        },
        reload = {
            characterRepository.refreshFromStorage(); formatCardRepository.refreshFromStorage(); worldBookRepository.refreshFromStorage()
            chatRepository.reloadAfterLanSync(); voiceRepository.reload()
            if (primaryChatControllerOwner.isInitialized()) primaryChatController.refresh()
            if (momentsOwner.isInitialized()) momentsController.refresh()
        }, resolve = { reference ->
            val path = java.nio.file.Path.of(reference)
            if (path.isAbsolute) path else characterResourceStore.resolveOwnedReference(reference)
        }, asset = characterResourceStore::readBytes) }
    internal val lanSync get() = lanSyncOwner.value.runtime
    internal val imageToolPreferences by lazy { DesktopImageToolPreferences(jsonFileStorage, dataOperationCoordinator) }
    internal val documentRagReader by lazy {
        DesktopDocumentRagReader(appDataRoot, jsonFileStorage, dataOperationCoordinator, maintenance = audioMaintenance)
    }
    /** Explicit offline gateway only. No HTTP/SecretStore/default embedding runner is installed. */
    internal fun createDocumentIndexPreparation(gateway: DesktopIndexEmbeddingGateway) =
        DesktopDocumentRagIndexPreparation(appDataRoot, jsonFileStorage, dataOperationCoordinator, taskRuntime, gateway)
    private val embeddingGatewayOwner = lazy { DesktopEmbeddingGateway(
        config = { requireNotNull(modelRepository.getEmbeddingModel()) { "请先配置向量模型" } },
        allowCleartext = { settingsRepository.currentAppSettings.allowCleartextModelApi },
        usageSink = { providerUsage.submit(it) }) }
    internal val embeddingGateway by embeddingGatewayOwner
    private val ragOwner = lazy { DesktopRagController(modelRepository, settingsRepository, historyIndex = { session ->
        taskRuntime.withDocumentPreparation("chat-memory:$session", "history",
            usageContext = DesktopUsageContext(DesktopUsageSource.CHAT, sessionId = session)) {
            taskRuntime.withMessageMutation(session) {
                chatMemoryIndex.rebuild(session, settingsRepository.getAppSettings().defaultContextWindowSize)
            }
        }
    }, index = ::indexDocument) }
    private suspend fun indexDocument(character: String, document: String, progress: (Int, Int) -> Unit): DesktopDocumentIndexOutcome {
        val config = requireNotNull(modelRepository.getEmbeddingModel()) { "请先配置向量模型" }
        return DesktopDocumentIndexService(appDataRoot, jsonFileStorage, characterRepository, dataOperationCoordinator,
            taskRuntime, embeddingGateway.pinned(config), audioMaintenance,
            verifyConfiguration = { embeddingGateway.verify(config) }).index(character, document, progress)
    }
    internal val ragController by ragOwner
    private val memoryRagTransportOwner = lazy { com.example.chatbar.domain.chat.OpenAiStreamingTransport(
        allowCleartextHttp = { settingsRepository.currentAppSettings.allowCleartextModelApi },
        experimentalApiModeEnabled = { settingsRepository.currentAppSettings.experimentalApiModeEnabled }) }
    private val memoryRagTextClient by lazy { DesktopMemoryRagTextClient(memoryRagTransportOwner.value,
        diagnostics = { taskRuntime.diagnostics }) }
    private val memoryRepository by lazy { com.example.chatbar.data.repository.MemoryRepository(jsonFileStorage,
        strictReads = true, dataGate = dataOperationCoordinator, validatePath = DesktopDocumentRagSource(appDataRoot)::safeWindowsPath) }
    private val memoryService by lazy { com.example.chatbar.domain.memory.LongTermMemoryService(chatRepository,
        memoryRepository, settingsRepository, com.example.chatbar.domain.memory.MemoryAiGateway(memoryRagTextClient),
        contextWindowManager, dataGate = dataOperationCoordinator,
        ensureSourceTurns = DesktopMemorySources(appDataRoot, jsonFileStorage, chatRepository)::ensure,
        inspectSourceTurns = DesktopMemorySources(appDataRoot, jsonFileStorage, chatRepository)::read) }
    private val memoryOwner = lazy { DesktopMemoryController(jsonFileStorage, chatRepository, settingsRepository,
        effectiveModelResolver, memoryService, memoryRepository, dataOperationCoordinator).also { taskCenter.watchMemory(it) } }
    internal val memoryController by memoryOwner
    private val chatMemoryIndex by lazy { DesktopChatMemoryIndex(appDataRoot, jsonFileStorage,
        dataOperationCoordinator, audioMaintenance,
        configuration = { requireNotNull(modelRepository.getEmbeddingModel()) }, gateway = embeddingGateway) }
    private val chatMaintenanceOwner = lazy { DesktopChatMaintenanceController(chatRepository, dataOperationCoordinator,
        object : DesktopSessionReservation {
            override suspend fun <T> run(sessionId: String, operation: suspend () -> T): T =
                taskRuntime.withMessageMutation(sessionId) {
                    chatVoiceRuntime.withSessionMaintenance(sessionId) {
                        memoryController.withSessionQuiesced(sessionId, operation)
                    }
                }
        }, rebuildDocuments = { sessionId, progress ->
            taskRuntime.withMessageMutation(sessionId) {
                val card = dataOperationCoordinator.withImmediateNormalOperation {
                    val session = requireNotNull(chatRepository.getSession(sessionId))
                    requireNotNull(characterRepository.getById(requireNotNull(session.characterCardId)))
                }
                check(card.customDocuments.size <= 64)
                progress(0, card.customDocuments.size)
                card.customDocuments.forEachIndexed { index, document ->
                    indexDocument(card.id, document.id) { _, _ -> }
                    progress(index + 1, card.customDocuments.size)
                }
                card.customDocuments.size
            }
        }, detectSourceChanges = { sessionId ->
            if (chatRepository.getSession(sessionId)?.longTermMemoryEnabled == true)
                memoryService.refreshForCurrentConditions(sessionId)
        }, refreshChat = { sessionId ->
            if (primaryChatControllerOwner.isInitialized() && primaryChatController.state.value.selectedSession?.id == sessionId)
                primaryChatController.refresh()
        }) }
    internal val chatMaintenanceController: DesktopChatMaintenanceController by chatMaintenanceOwner
    private val chatRetrieval by lazy { DesktopChatRetrieval(
        configuration = { modelRepository.getEmbeddingModel() }, settings = { settingsRepository.getAppSettings() },
        documents = documentRagReader::candidates, memories = chatMemoryIndex::candidates,
        embed = { config, text -> embeddingGateway.pinned(config).embed(DesktopIndexEmbeddingBatch(
            DesktopRagEmbeddingIdentity.from(config), 0, listOf(text to emptyMap()))).single() },
        textClient = memoryRagTextClient) }
    internal val saveSlotInspectionPicker by lazy { DesktopSaveSlotInspectionPicker(filePicker) }
    private val saveSlotService by lazy { DesktopSaveSlotService(appDataRoot, dataOperationCoordinator, audioMaintenance,
        object : DesktopSessionReservation {
            override suspend fun <T> run(sessionId: String, operation: suspend () -> T): T =
                chatVoiceRuntime.withSessionMaintenance(sessionId) {
                    taskRuntime.withMessageMutation(sessionId) { memoryController.withSessionQuiesced(sessionId, operation) }
                }
        }, chatRepository, voiceRepository, memoryService::snapshot, mediaSource = { reference ->
            when {
                reference.startsWith("asset:") -> error("SAVE_SLOT_ASSET_REQUIRES_CAPTURE_OWNER")
                java.nio.file.Path.of(reference).isAbsolute -> voiceAudioStore.verify(reference)
                else -> characterResourceStore.resolveOwnedReference(reference).also { DesktopArchiveFiles.safe(it, false) }
            }
        }, cleanupDeletedMedia = ownedImageCleanup::deleteUnreferenced, temporaryAsset = { reference ->
            if (!reference.startsWith("asset:")) null else {
                val bytes = characterResourceStore.readScreenshotImageBytes(reference)
                val extension = reference.substringAfterLast('.', "png").lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "gif", "webp") } ?: "png"
                DesktopSaveSlotTemporaryAsset.create(appDataRoot, bytes, extension)
            }
        }) }
    private val saveSlotOwner = lazy { DesktopSaveSlotController(saveSlotService, filePicker) { id -> primaryChatController.selectSession(id) }
        .also { taskCenter.watchSaveSlots(it) } }
    internal val saveSlotController by saveSlotOwner
    private val fullBackupOwner = lazy { DesktopFullBackupController(DesktopFullBackupService(
        appDataRoot, dataOperationCoordinator, audioMaintenance,
        beforeSnapshot = { if (primaryChatControllerOwner.isInitialized()) primaryChatController.flushComposerForSnapshot() },
        pauseScheduler = { check(automaticBackupRuntime.pauseForMaintenance() is DesktopMaintenancePauseResult.Paused) },
        resumeScheduler = { check(automaticBackupRuntime.resumeAfterMaintenance() !is DesktopMaintenanceResumeResult.Failed) },
        asset = bundledAssets,
        secretStore = { desktopSecretStore },
    ), filePicker, operationJournal = DesktopBackupOperationJournal(appDataRoot, DesktopBackupOperationJournal.Lane.BACKUP)).also { taskCenter.watchBackup(it) } }
    internal val fullBackupController by fullBackupOwner
    private fun createMergeService(workspace: Path = appDataRoot.resolve("backups/.merge-import-work"),
        quiesce: suspend (suspend () -> Unit) -> Unit = { work -> taskRuntime.withMaintenance { withBackupIoMaintenance(work) } },
    ) = DesktopMergeImportService(resolvedRoot, dataOperationCoordinator, desktopSecretStore,
        beforeSnapshot = { if (primaryChatControllerOwner.isInitialized()) primaryChatController.flushComposerForSnapshot() },
        quiesce = quiesce, freshAuthority = freshImportAuthority, asset = bundledAssets, workspace = workspace)
    private val mergeServiceOwner = lazy { createMergeService() }

    private suspend fun <T> withBackupIoMaintenance(work: suspend () -> T,
        observed: (DesktopMaintenanceResumeResult) -> Unit = {}): T {
        check(automaticBackupRuntime.pauseForMaintenance() is DesktopMaintenancePauseResult.Paused)
        try {
            return audioMaintenance.withMaintenance(true, { dataOperationCoordinator.isRestartRequired }) { work() }
        } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val result = automaticBackupRuntime.resumeAfterMaintenance()
            observed(result)
            check(result !is DesktopMaintenanceResumeResult.Failed)
            if (dataOperationCoordinator.isRestartRequired) taskRuntime.stopAccepting()
        } }
    }
    private val mergeImportOwner = lazy { DesktopMergeImportController(mergeServiceOwner.value, filePicker, directoryPicker,
        operationJournal = DesktopBackupOperationJournal(appDataRoot, DesktopBackupOperationJournal.Lane.MERGE))
        .also { taskCenter.watchMerge(it) } }
    internal val mergeImportController by mergeImportOwner

    internal val desktopSecretStore: DesktopSecretStore by lazy {
        com.example.chatbar.desktop.security.DesktopAccountStore(secretStoreFactory(appDataRoot))
    }
    internal val apiAccounts get() = desktopSecretStore as com.example.chatbar.desktop.security.DesktopAccountStore
    internal val apiAccountController by lazy { DesktopApiAccountController(apiAccounts, modelRepository, dataOperationCoordinator, modelCredentialPolicy,
        reloadSettings = { settingsRepository.initialize(forceReload = true) }) }
    internal val fishAudioCredentials by lazy { DesktopFishAudioCredentials(desktopSecretStore) }
    private val fishAudioLibraryOwner = lazy { DesktopFishAudioLibrary(fishAudioCredentials) }
    internal val fishAudioLibrary by fishAudioLibraryOwner
    private val fishCredentialOwner = lazy { DesktopFishCredentialController(fishAudioCredentials, dataOperationCoordinator) }
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
        picker = filePicker, gate = dataOperationCoordinator, uiPreferences = chatUiController.state,
        diagnostics = taskRuntime.diagnostics,
        sourceBusy = taskRuntime::hasActiveVoiceSourceMutation)
            .also { taskCenter.watchFish(it.coordinator) } }
    internal val chatVoiceRuntime by chatVoiceOwner
    private val fishPreviewOwner = lazy {
        DesktopFishPreviewRuntime(DesktopFishPreviewTransport(fishAudioCredentials, diagnostics = taskRuntime.diagnostics), output = audioFocusOwner.value.port(),
            model = { settingsRepository.currentAppSettings.fishAudioTtsModelId })
    }
    internal val fishPreviewRuntime by fishPreviewOwner
    internal val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            storage = jsonFileStorage,
            credentialPersistencePolicy = DesktopSettingsCredentialPersistencePolicy(desktopSecretStore, dataOperationCoordinator),
        )
    }
    internal val novelAiSettingsController by lazy {
        DesktopNovelAiSettingsController(desktopSecretStore, settingsRepository, dataOperationCoordinator)
    }
    internal val novelAiRuntime by lazy {
        DesktopNovelAiRuntime(desktopSecretStore,
            DesktopNovelAiLiveFuse(com.example.chatbar.desktop.security.WindowsSecretRootResolver.resolve(appDataRoot)
                .resolve("phase7-live-safety")),
            DesktopNovelAiSmokeStore(jsonFileStorage, characterResourceStore, dataOperationCoordinator)::persist)
    }

    private val novelAiInfrastructureOwner = lazy {
        DesktopNovelAiInfrastructure(appDataRoot, { bundledAssetReader(it).inputStream() }, effectiveModelResolver,
            allowCleartextHttp = { settingsRepository.currentAppSettings.allowCleartextModelApi }, designerOverride = novelAiDesigner,
            diagnostics = taskRuntime.diagnostics,
            experimentalApiModeEnabled = { settingsRepository.currentAppSettings.experimentalApiModeEnabled },
            operationGate = dataOperationCoordinator,
            onCatalogUpdatesCreated = { taskCenter.watchCatalog(it) },
            synchronousCatalogAccess = { work -> dataOperationCoordinator.withSynchronousNormalOperation(work) })
    }
    internal val novelAiInfrastructure by novelAiInfrastructureOwner
    internal val novelAiGenerationRuntime by lazy {
        DesktopNovelAiGenerationRuntime(desktopSecretStore,
            DesktopNovelAiGenerationStore(jsonFileStorage, characterResourceStore, dataOperationCoordinator)::persist,
            diagnostics = taskRuntime.diagnostics)
    }

    private val novelAiStudioOwner = lazy {
        DesktopNovelAiStudioController(jsonFileStorage, characterResourceStore, filePicker,
            dataOperationCoordinator, ownedImageCleanup::deleteUnreferenced, novelAiGenerationRuntime,
            DesktopNovelAiGuidance(appDataRoot, characterResourceStore, desktopSecretStore, dataOperationCoordinator,
                diagnostics = taskRuntime.diagnostics), taskRuntime,
            novelAiInfrastructure, settingsRepository, effectiveModelResolver, characterRepository,
            account = { com.example.chatbar.domain.image.NovelAiAccountService(secureNovelAiClient()).fetchCancellable(
                requireNotNull(desktopSecretStore.credentialForCall(com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken))) },
            credentialConfigured = { !desktopSecretStore.load(com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken).isNullOrBlank() },
            postProcessor = DesktopNovelAiPostProcessor(desktopSecretStore, diagnostics = taskRuntime.diagnostics),
            credentialIdentity = { apiAccounts.identity(com.example.chatbar.desktop.security.DesktopAccountService.NOVELAI) },
            freezeCredential = { apiAccounts.captureCall(com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken,
                com.example.chatbar.desktop.security.DesktopAccountService.NOVELAI) },
            presetArchive = DesktopGenerationPresetArchive(appDataRoot, characterResourceStore, dataOperationCoordinator),
            directoryPicker = directoryPicker)
    }
    internal val novelAiStudioController by novelAiStudioOwner

    internal val appearanceController by lazy { DesktopAppearanceController(settingsRepository, desktopSettingsStore) }
    private val modelCredentialPolicy by lazy {
        DesktopModelCredentialPersistencePolicy(desktopSecretStore, dataOperationCoordinator) { id ->
            when (modelRepository.readDurableModel(id)) {
                is JsonFileStorage.EntityReadResult.Valid -> true
                JsonFileStorage.EntityReadResult.Missing -> false
                else -> error("MODEL_CREDENTIAL_ENTITY_UNAVAILABLE")
            }
        }
    }
    internal val modelRepository: ModelRepository by lazy {
        ModelRepository(
            storage = jsonFileStorage,
            modelStorageKeyPolicy = WindowsSafeModelStorageKeyPolicy,
            credentialPersistencePolicy = modelCredentialPolicy,
            embeddingCredentialPersistencePolicy =
                DesktopEmbeddingCredentialPersistencePolicy(desktopSecretStore, dataOperationCoordinator),
        )
    }
    internal val characterRepository = CharacterRepository(jsonFileStorage)
    internal val chatRepository = ChatRepository(jsonFileStorage)
    internal val speakerTagHistoryService by lazy {
        com.example.chatbar.domain.chat.SpeakerTagHistoryService(characterRepository, chatRepository,
            rewrite = { task, persistPlan ->
                dataOperationCoordinator.withNormalOperation {
                    val ids = chatRepository.getAllSessions().filter { it.characterCardId == task.characterCardId }.map { it.id }.sorted()
                    suspend fun reserve(index: Int): Int = if (index == ids.size)
                        chatVoiceRuntime.withSpeakerHistoryMutation(ids.toSet()) {
                            chatRepository.rewriteSpeakerTagsForTask(task, persistPlan)
                        }
                    else taskRuntime.withMessageMutation(ids[index]) {
                        memoryController.withSessionQuiesced(ids[index]) { reserve(index + 1) }
                    }
                    audioMaintenance.withMaintenance(false, { dataOperationCoordinator.isRestartRequired }) { reserve(0) }
                }
            }, sourcesChanged = { sessions -> sessions.forEach { memoryController.sourceChanged(it) } })
    }
    internal val editorDraftRepository = EditorDraftRepository(jsonFileStorage)
    internal val formatCardRepository = FormatCardRepository(jsonFileStorage)
    private val editorBudgetOwner = lazy { DesktopEditorBudgetController(settingsRepository, effectiveModelResolver, dataOperationCoordinator) }
    private val formatCardAiTransportOwner = lazy { com.example.chatbar.domain.chat.OpenAiStreamingTransport(
        experimentalApiModeEnabled = { settingsRepository.currentAppSettings.experimentalApiModeEnabled },
        allowCleartextHttp = { settingsRepository.currentAppSettings.allowCleartextModelApi }) }
    private val characterResearchSourcesOwner = lazy {
        DesktopCharacterResearchSources(settingsRepository, effectiveModelResolver) {
            settingsRepository.currentAppSettings.allowCleartextModelApi
        }
    }
    private val characterImageGenerationOwner = lazy { DesktopCharacterImageGeneration(desktopSecretStore, diagnostics = taskRuntime.diagnostics) }
    private val formatCardEditorOwner = lazy {
        DesktopFormatCardEditorController(formatCardRepository, editorDraftRepository).also { editor ->
            editor.promptBudget = editorBudgetOwner.value
            editor.autoFill = DesktopFormatCardAutoFillController(editor, characterRepository, settingsRepository,
                effectiveModelResolver, taskRuntime, dataOperationCoordinator,
                stream = { _, messages, model, diagnostics ->
                    formatCardAiTransportOwner.value.streamFormatCardAutoFill(messages, model, diagnostics)
                })
        }
    }
    internal val formatCardEditorController by formatCardEditorOwner
    internal val worldBookRepository = WorldBookRepository(jsonFileStorage)
    private val bundledAssetReader = bundledAssets
    internal val characterResourceStore = DesktopCharacterResourceStore(
        appDataRoot,
        assetReader = bundledAssetReader,
    )
    private val ownedImageCleanup = DesktopOwnedImageCleanup(appDataRoot, characterResourceStore, dataOperationCoordinator,
        deleteAudio = { voiceAudioStore.deleteIfOwned(it) })
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
            manualResourceAccess = { work -> dataOperationCoordinator.withSynchronousNormalOperation(work) },
            documentIndex = ragController,
            speakerHistory = speakerTagHistoryService,
            imageModelSettings = settingsRepository.appSettings.map { it.novelAiImageModel },
            json = jsonFileStorage.json,
            filePicker = filePicker,
            discardObsoleteResources = { previous, current ->
                val retained = current.flatMap(::desktopCharacterResourceReferences).toSet()
                ownedImageCleanup.deleteUnreferenced(desktopCharacterResourceReferences(previous).filterNot(retained::contains))
            },
        ).also { editor ->
            editor.promptBudget = editorBudgetOwner.value
            editor.imagePromptTools = {
                DesktopCharacterPromptTools(novelAiInfrastructure.suggestions, novelAiInfrastructure.translations,
                    settingsRepository.appSettings.map { it.novelAiPromptTranslationConsent == com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.ENABLED },
                    { enabled -> settingsRepository.updateAppSettings { it.copy(novelAiPromptTranslationConsent =
                        if (enabled) com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.ENABLED
                        else com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent.DISABLED) } })
            }
            editor.cardAi = DesktopCharacterCardAiController(editor, settingsRepository, effectiveModelResolver,
                taskRuntime, dataOperationCoordinator, stream = { _, stage, messages, model, diagnostics ->
                    formatCardAiTransportOwner.value.streamCharacterCardCandidate(stage, messages, model, diagnostics)
                }, streamResearch = { stage, messages, model, diagnostics ->
                    formatCardAiTransportOwner.value.streamCharacterResearch(stage, messages, model, diagnostics)
                }, prepareResearch = { request, card, model, options, documents, resume, checkpoint, visible, status, gateway ->
                    characterResearchSourcesOwner.value.prepare(request, card, model, options, documents,
                        resume, checkpoint, visible, status, gateway)
                }, filePicker = filePicker, describeImage = { encoded, model, delta, diagnostics ->
                    DesktopAuxiliaryImageUnderstanding(formatCardAiTransportOwner.value).describe(encoded, model, delta, diagnostics)
                })
            editor.appearanceAi = DesktopCharacterAppearanceController(editor, settingsRepository, effectiveModelResolver,
                taskRuntime, dataOperationCoordinator, filePicker, stream = { messages, model, diagnostics ->
                    formatCardAiTransportOwner.value.streamCharacterAppearance(messages, model, diagnostics)
                })
            editor.cardImages = DesktopCharacterImageController(editor, settingsRepository, effectiveModelResolver,
                taskRuntime, dataOperationCoordinator,
                captureCredential = { apiAccounts.captureCall(com.example.chatbar.desktop.security.DesktopCredentialKey.NovelAiToken,
                    com.example.chatbar.desktop.security.DesktopAccountService.NOVELAI) },
                loadRequirement = { com.example.chatbar.data.repository.NovelAiStudioStateRepository(jsonFileStorage).loadDraft().extraRequirement },
                design = { card, model, hint, requirement, progress ->
                    novelAiInfrastructure.promptDesigner().designForCharacterCard(card, model,
                        finalPromptRequirement = requirement, imageContentHint = hint,
                        playerName = settingsRepository.getPlayerSetting().playerName, onDelta = progress)
                },
                generateImage = { plan, size, settings, preview, status ->
                    characterImageGenerationOwner.value.generate(plan, size, settings, preview, status)
                })
        }
    }
    internal val characterEditorController by characterEditorOwner
    internal val transferJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val worldBookEditorOwner = lazy {
        DesktopWorldBookEditorController(worldBookRepository, editorDraftRepository,
            characterRepository, transferJson).also { editor ->
            editor.promptBudget = editorBudgetOwner.value
            editor.ai = DesktopWorldBookAiController(editor, settingsRepository, effectiveModelResolver,
                taskRuntime, dataOperationCoordinator,
                stream = { operation, _, messages, model, diagnostics ->
                    formatCardAiTransportOwner.value.streamWorldBookAi(operation, messages, model, diagnostics)
                }, research = { gateway -> characterResearchSourcesOwner.value.worldBook(gateway) }, filePicker = filePicker)
        }
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
        lanSyncOwner.value.recoverBeforeRepositories()
        automaticBackupRuntime.initialize()
        presetBootstrap.initialize()
        saveSlotService.resumeSessionDeletionCleanup()
        dataOperationCoordinator.withNormalOperation { speakerTagHistoryService.resumePending() }
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
            playerImages = DesktopPlayerImages(settingsRepository, characterResourceStore, filePicker,
                dataOperationCoordinator, ownedImageCleanup::deleteUnreferenced),
        )
    }
    private val appDataSnapshotService = AppDataSnapshotService(appDataRoot)
    internal val coordinatedSnapshotService: DesktopCoordinatedSnapshotService = DesktopCoordinatedSnapshotService(
        delegate = appDataSnapshotService,
        coordinator = dataOperationCoordinator,
        audioMaintenance = audioMaintenance,
    )
    private val desktopSettingsStore = DesktopSettingsStore(appDataRoot, dataOperationCoordinator)
    internal val networkProxyController = DesktopNetworkProxyController(desktopSettingsStore)
    internal val chatUiController by lazy { DesktopChatUiController(desktopSettingsStore) }
    internal val uiLanguageController = DesktopUiLanguageController(desktopSettingsStore)
    internal val guideReminderController = DesktopGuideReminderController(desktopSettingsStore)
    val automaticBackupRuntime: DesktopAutomaticBackupRuntime = DesktopAutomaticBackupRuntime(
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
        taskMaintenance = { work -> taskRuntime.withMaintenance { work() } },
        boundMigrationFactory = {
            DesktopBoundRootMigration(resolvedRoot, dataOperationCoordinator,
                // The outer root-migration operation already holds task, backup and audio maintenance.
                createService = { workspace -> createMergeService(workspace, quiesce = { it() }) },
                maintenance = { work, observed -> withBackupIoMaintenance(work, observed) },
                beforeCapture = { if (primaryChatControllerOwner.isInitialized()) primaryChatController.flushComposerForSnapshot() },
                validateBinding = { root ->
                    val authority = freshImportAuthority(root)
                    if (root == appDataRoot && DesktopFreshRootAuthority.exists(root)) authority.verifyAndResolve()
                }, asset = bundledAssets)
        },
    )

    private val characterDocumentRagCleanup = DesktopCharacterDocumentRagCleanup(jsonFileStorage)
    internal val characterBackgrounds by lazy { DesktopCharacterBackgrounds(jsonFileStorage, characterResourceStore,
        dataOperationCoordinator, ownedImageCleanup::deleteUnreferenced, characterRepository) }
    internal val characterTransfers: CharacterCardTransferCore = createCharacterTransferCore(characterDocumentRagCleanup)
    internal val formatTransfers = FormatCardTransferService(formatCardRepository, transferJson)
    internal val worldBookTransfers = WorldBookTransferService(worldBookRepository, transferJson)
    private val communityLock = Any()
    private var communityOwner: Pair<DesktopTypedTransferController, DesktopCommunityController>? = null
    internal fun createCommunityController(typed: DesktopTypedTransferController,
        configuration: com.example.chatbar.domain.community.CommunityConfiguration = com.example.chatbar.domain.community.CommunityConfiguration(
            "https://rrlxofrrgfnhjfyrqvry.supabase.co", "sb_publishable_J56aQ_JJLnank-dv1olMdg_d87xNQjD"),
        httpFactory: () -> okhttp3.OkHttpClient = { com.example.chatbar.domain.ProxyAwareClient.builder()
            .followRedirects(false).followSslRedirects(false).connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build() },
        browse: (java.net.URI) -> Unit = { java.awt.Desktop.getDesktop().browse(it) },
        loginReturn: DesktopCommunityLoginReturn = UnavailableCommunityLoginReturn,
    ): DesktopCommunityController = synchronized(communityLock) {
        communityOwner?.let { check(it.first === typed); return@synchronized it.second }
        val http = httpFactory()
        val packages = DesktopCommunityLocalPackages(characterRepository, formatCardRepository, worldBookRepository,
            characterTransfers, formatTransfers, worldBookTransfers, characterResourceStore, dataOperationCoordinator)
        lateinit var owner: DesktopCommunityController
        val client = com.example.chatbar.domain.community.CommunityClient(configuration, packages,
            DesktopCommunitySessionStore(desktopSecretStore, transferJson), transferJson, http, loadSessionAtStartup = false,
            warn = { _, _ -> owner.reportWarning() })
        owner = DesktopCommunityController(client, http, typed, characterRepository, characterTransfers,
            formatTransfers, worldBookTransfers, dataOperationCoordinator, browse, loginReturn = loginReturn)
        communityOwner = typed to owner
        owner
    }
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
            val parent = kotlinx.coroutines.currentCoroutineContext()[DesktopUsageParent]?.value
                ?: DesktopUsageContext(DesktopUsageSource.CHAT)
            kotlinx.coroutines.withContext(DesktopUsageParent(parent.copy(source = DesktopUsageSource.CHAT,
                sessionId = session.id, cardId = card.id, subjectId = user.id))) {
                chatRetrieval.retrieve(card, session, user, context, inputs, readOnly)
            } },
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
            diagnostics = { taskRuntime.diagnostics },
            launch = { sessionId, messageId, work -> taskRuntime.launchNovelAi("自动聊天图片", sessionId, messageId, retryable = true, work = work) })
    }

    internal val characterResidency by lazy {
        DesktopCharacterResidency(chatRepository, characterRepository, dataOperationCoordinator) { _, change ->
            chatVoiceRuntime.withAutomaticParticipationUpdate(change)
        }
    }

    private val providerUsageOwner = lazy { DesktopProviderUsageOwner(DesktopProviderUsageStore(appDataRoot, dataOperationCoordinator)) }
    internal val providerUsage by providerUsageOwner
    private val taskCenterOwner: Lazy<DesktopTaskCenter> = lazy { DesktopTaskCenter(describe = { target ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when {
                target.sessionId != null -> chatRepository.getSession(target.sessionId)?.title
                target.cardId != null -> characterRepository.getById(target.cardId)?.let { card ->
                    val document = target.subjectId?.let { id -> card.customDocuments.firstOrNull { it.id == id } }
                    if (document != null) "${card.name} · ${document.fileName}" else card.name
                }
                target.draftSessionId != null -> when (target.detail) {
                    DesktopWorkDetail.FORMAT_DRAFT -> if (formatCardEditorOwner.isInitialized()) formatCardEditorController.state.value
                        .takeIf { it.draftSessionId == target.draftSessionId }?.card?.name else null
                    DesktopWorkDetail.CHARACTER_DRAFT, DesktopWorkDetail.APPEARANCE_DRAFT, DesktopWorkDetail.CHARACTER_IMAGE_DRAFT ->
                        if (characterEditorOwner.isInitialized()) characterEditorController.state.value
                            .takeIf { it.draftSessionId == target.draftSessionId }?.card?.name else null
                    DesktopWorkDetail.WORLD_BOOK_DRAFT -> if (worldBookEditorOwner.isInitialized()) worldBookEditorController.state.value
                        .takeIf { it.draftSessionId == target.draftSessionId }?.book?.name else null
                    else -> null
                }?.takeIf { it.isNotBlank() }?.let { "$it · 创作草稿" }
                else -> null
            }
        }
    }).also { it.watchAutomaticBackups(automaticBackupRuntime) } }
    internal val taskCenter by taskCenterOwner
    private val trayOwner = lazy { DesktopTrayController(taskCenter, desktopSettingsStore) }
    internal val taskTray by trayOwner
    internal val taskRuntime: DesktopTaskRuntime by lazy {
        DesktopTaskRuntime(createRealChatRuntime(), diagnostics = DesktopTransportDiagnosticsOwner(usageSink = { providerUsage.submit(it) }),
            onChatCompleted = { result, stopped ->
            val residencyNotice = try { characterResidency.completed(result, stopped) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    characterResidency.reportFailure(result.assistant.sessionId, result.assistant.id)
                    "人物驻场更新未完成，请检查会话人物设置"
                }
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
            listOfNotNull(residencyNotice, imageNotice, indexNotice).joinToString("；").takeIf(String::isNotBlank)
        }, postProcessReply = { id, result, control -> automaticFormatRepair.completed(id, result, control) })
            .also { taskCenter.watchRuntime(it) }
    }

    private val formatRepairSafety by lazy {
        DesktopFormatRepairSourceSafety(appDataRoot, jsonFileStorage, dataOperationCoordinator, taskRuntime,
            audioMaintenance, { chatVoiceRuntime }, { memoryController.sourceChanged(it) })
    }
    private val automaticFormatRepair by lazy {
        DesktopAutomaticFormatRepair(settingsRepository, { messageFormatRepairController }, { formatRepairSafety })
    }

    internal val messageFormatRepairController by lazy {
        createMessageFormatRepairController()
    }

    /** Transport-only seam: tests exercise the same production source ownership and CAS scope. */
    internal fun createMessageFormatRepairController(
        stream: ((DesktopRepairRequest) -> kotlinx.coroutines.flow.Flow<com.example.chatbar.domain.chat.StreamEvent>)? = null,
    ): DesktopMessageFormatRepairController {
        val safety = formatRepairSafety
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
            chatUi = chatUiController,
            maintenance = chatMaintenanceController,
            sessionDeletion = saveSlotService::deleteSession,
            sessionDeletionNotice = { saveSlotService.sessionDeletionCleanupNotice },
            voices = chatVoiceRuntime,
            residency = characterResidency,
            memory = memoryController,
            rag = ragController,
            saveSlots = saveSlotController,
            formatRepair = messageFormatRepairController,
            imagePicker = filePicker,
            backgroundLibrary = characterBackgrounds,
            imageRegeneration = DesktopChatImageRegeneration(chatRepository, characterRepository, settingsRepository,
                characterResourceStore, dataOperationCoordinator, desktopSecretStore, taskRuntime, novelAiInfrastructure, effectiveModelResolver),
        )
    }
    internal val primaryChatController: DesktopPrimaryChatController by primaryChatControllerOwner

    private val momentDiagnostics = DesktopTransportDiagnosticsOwner(usageSink = { providerUsage.submit(it) })
    private val momentTransportOwner = lazy { com.example.chatbar.domain.chat.OpenAiStreamingTransport(
        experimentalApiModeEnabled = { settingsRepository.currentAppSettings.experimentalApiModeEnabled },
        allowCleartextHttp = { settingsRepository.currentAppSettings.allowCleartextModelApi }) }
    private val momentImageCleanup by lazy { DesktopMomentImageCleanup(appDataRoot, jsonFileStorage,
        dataOperationCoordinator, ownedImageCleanup) }
    private val momentStore by lazy { DesktopMomentStore(jsonFileStorage, dataOperationCoordinator, momentImageCleanup) }
    private val momentAdmission by lazy { DesktopMomentAdmissionStore(appDataRoot,
        com.example.chatbar.desktop.security.WindowsSecretRootResolver.resolve(appDataRoot).resolve("moment-runtime")) }
    private val momentGenerationOwner = lazy { DesktopMomentGeneration(
        DesktopMomentTextGateway(momentTransportOwner.value, momentDiagnostics), effectiveModelResolver,
        settingsRepository, desktopSecretStore, { novelAiInfrastructure }, characterResourceStore, momentImageCleanup,
        { session -> if (session.longTermMemoryEnabled) memoryService.promptView(session.id).fullText else "" },
        diagnostics = momentDiagnostics) }
    private val momentsOwner = lazy { DesktopMomentsController(momentStore, settingsRepository,
        characterRepository, chatRepository, dataOperationCoordinator, taskRuntime,
        DesktopMomentGenerator { card, session, messages, latest, settings, task, checkpoint, save, progress, cleanup ->
            momentGenerationOwner.value.generate(card, session, messages, latest, settings, task, checkpoint, save, progress, cleanup)
        }, momentImageCleanup::finish, momentAdmission, characterResourceStore, filePicker,
        momentDiagnostics, momentImageCleanup::resume,
        DesktopMomentImageGenerator { post, card, session, settings, draft, cleanup, progress ->
            momentGenerationOwner.value.generate(post, card, session, settings, draft, cleanup, progress)
        }, DesktopMomentDebugGenerator { card, session, messages, latest, settings, task, cleanup ->
            momentGenerationOwner.value.generate(card, session, messages, latest, settings, task, cleanup)
        }, momentImageCleanup::prepareCandidate).also { taskCenter.watchMoments(it) } }
    internal val momentsController by momentsOwner

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
        guardCharacterOutput = characterResourceStore::requireDifferentCoverOutput,
        presetSource = presetSource,
    )

    internal fun createManagementController(
        transfer: DesktopTypedTransferController,
        suiteRestore: DesktopPresetSuiteRestoreService = presetSuiteRestore,
        discardDraftAssets: (String) -> Unit = DesktopCharacterDraftResources(appDataRoot, characterResourceStore)::discardSession,
    ) = DesktopManagementController(
        characterRepository, formatCardRepository, worldBookRepository, chatRepository, editorDraftRepository,
        characterTransfers, formatTransfers, worldBookTransfers, transfer,
        characterEditorController, formatCardEditorController, worldBookEditorController,
        discardDraftAssets,
        afterReconcile = { modelSettingsController.refreshFormatChoices() },
        presetSource = presetSource,
        presetSuiteRestore = suiteRestore,
        draftDataGate = dataOperationCoordinator,
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
        postCommitResourceCleanup = ownedImageCleanup::deleteUnreferenced,
        additionalBackgrounds = { card -> characterBackgrounds.exportBackgrounds(card) },
    )

    suspend fun close() {
        if (lanSyncOwner.isInitialized()) lanSyncOwner.value.closeAndDrain()
        taskRuntime.stopAccepting()
        closeDesktopDataRuntimes(
            taskRuntimeClose = {
                synchronized(communityLock) { communityOwner?.second }?.closeAndDrain()
                if (momentsOwner.isInitialized()) momentsController.closeAndDrain()
                if (momentGenerationOwner.isInitialized()) momentGenerationOwner.value.closeAndDrain()
                if (momentTransportOwner.isInitialized()) momentTransportOwner.value.closeAndDrain()
                if (chatMaintenanceOwner.isInitialized()) chatMaintenanceController.closeAndDrain()
                if (mergeImportOwner.isInitialized()) mergeImportController.closeAndDrain()
                if (fullBackupOwner.isInitialized()) fullBackupController.closeAndDrain()
                if (saveSlotOwner.isInitialized()) saveSlotController.closeAndDrain()
                if (chatVoiceOwner.isInitialized()) chatVoiceRuntime.closeAndDrain()
                if (fishPreviewOwner.isInitialized()) fishPreviewRuntime.closeAndDrain()
                if (audioFocusOwner.isInitialized()) audioFocusOwner.value.closeAndDrain()
                if (fishAudioLibraryOwner.isInitialized()) fishAudioLibrary.closeAndDrain()
                if (fishCredentialOwner.isInitialized()) fishCredentialController.closeAndDrain()
                if (connectionTestOwner.isInitialized()) connectionTestController.closeAndDrain()
                if (ragOwner.isInitialized()) ragController.closeAndDrain()
                taskRuntime.closeAndDrain()
                if (memoryOwner.isInitialized()) memoryController.closeAndDrain()
                if (embeddingGatewayOwner.isInitialized()) embeddingGateway.closeAndDrain()
                if (memoryRagTransportOwner.isInitialized()) memoryRagTransportOwner.value.closeAndDrain()
            },
            draftRuntimeClose = {
                if (novelAiStudioOwner.isInitialized()) novelAiStudioController.closePresetExchange()
                if (primaryChatControllerOwner.isInitialized()) primaryChatController.closeDraftPersistence()
                if (characterEditorOwner.isInitialized()) characterEditorController.closeAndDrain()
                if (worldBookEditorOwner.isInitialized()) worldBookEditorController.closeAndDrain()
                if (characterResearchSourcesOwner.isInitialized()) characterResearchSourcesOwner.value.closeAndDrain()
                if (characterImageGenerationOwner.isInitialized()) characterImageGenerationOwner.value.closeAndDrain()
                if (formatCardEditorOwner.isInitialized()) formatCardEditorController.closeAndDrain()
                if (editorBudgetOwner.isInitialized()) editorBudgetOwner.value.closeAndDrain()
                if (formatCardAiTransportOwner.isInitialized()) formatCardAiTransportOwner.value.closeAndDrain()
                if (novelAiInfrastructureOwner.isInitialized()) novelAiInfrastructure.closeAndDrain()
                if (providerUsageOwner.isInitialized()) providerUsage.closeAndDrain()
                if (trayOwner.isInitialized()) taskTray.closeAndDrain()
                if (taskCenterOwner.isInitialized()) taskCenter.closeAndDrain()
            },
            runtimeClose = { automaticBackupRuntime.close() },
            coordinatorClose = { dataOperationCoordinator.closeAndDrain() },
            migrationServiceClose = {
                if (mergeServiceOwner.isInitialized()) mergeServiceOwner.value.close()
                dataRootMigrationService.close()
            },
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
    } catch (pending: DesktopUsageDrainException) {
        // Keep the gate and root ownership alive for the exact retained numeric tail to retry.
        throw pending
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
