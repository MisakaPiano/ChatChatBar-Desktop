package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.domain.card.AuthoritativeCharacterTransferPromptPolicy
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterDocumentRagCleanup
import com.example.chatbar.domain.card.CharacterTransferPromptPolicy
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.WorldBookTransferService
import com.example.chatbar.domain.chat.CharacterSessionService
import com.example.chatbar.domain.worldbook.WorldBookRequestPlanner
import java.nio.file.Path
import kotlinx.serialization.json.Json

class DesktopAppContainer(
    val resolvedRoot: DesktopDataRootResolution.Resolved,
) {
    val appDataRoot: Path = resolvedRoot.appDataRoot
    internal val dataOperationCoordinator = DesktopDataOperationCoordinator()
    val jsonFileStorage = JsonFileStorage(appDataRoot, dataOperationCoordinator)
    internal val characterRepository = CharacterRepository(jsonFileStorage)
    internal val chatRepository = ChatRepository(jsonFileStorage)
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
    internal val characterPngRenderer = DesktopCharacterCardPngRenderer()

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
            runtimeClose = { automaticBackupRuntime.close() },
            coordinatorClose = { dataOperationCoordinator.closeAndDrain() },
            migrationServiceClose = { dataRootMigrationService.close() },
        )
    }
}

internal suspend fun closeDesktopDataRuntimes(
    runtimeClose: suspend () -> Unit,
    coordinatorClose: suspend () -> Unit,
    migrationServiceClose: suspend () -> Unit,
) {
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
