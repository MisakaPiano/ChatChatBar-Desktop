package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.domain.card.AuthoritativeCharacterTransferPromptPolicy
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterDocumentRagCleanup
import com.example.chatbar.domain.card.CharacterTransferPromptPolicy
import java.nio.file.Path
import kotlinx.serialization.json.Json

class DesktopAppContainer(
    val resolvedRoot: DesktopDataRootResolution.Resolved,
) {
    val appDataRoot: Path = resolvedRoot.appDataRoot
    internal val dataOperationCoordinator = DesktopDataOperationCoordinator()
    val jsonFileStorage = JsonFileStorage(appDataRoot, dataOperationCoordinator)
    internal val characterRepository = CharacterRepository(jsonFileStorage)
    internal val formatCardRepository = FormatCardRepository(jsonFileStorage)
    internal val worldBookRepository = WorldBookRepository(jsonFileStorage)
    internal val characterResourceStore = DesktopCharacterResourceStore(appDataRoot)
    private val transferJson = Json {
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

    /** Production path 使用 shared Prompt-domain authority；RAG cleanup 仍由后续 Desktop adapter 提供。 */
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
