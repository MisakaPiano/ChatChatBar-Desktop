package com.example.chatbar.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/** An incomplete writer drain leaves storage live, so retain its root lock until process exit. */
private val unsafeShutdownOwnerships = ConcurrentHashMap.newKeySet<DesktopDataRootOwnership>()

fun main() {
    runDesktopProcessLifecycle(::runDesktopMain)
}

private fun runDesktopMain() {
    val rootResolution = runBlocking { DesktopDataDirectory.resolveRoot() }
    val resolvedRoot = when (rootResolution) {
        is DesktopDataRootResolution.Resolved -> rootResolution
        is DesktopDataRootResolution.Failed -> throw DesktopDataRootResolutionException(rootResolution)
    }

    runDesktopApplicationWithDataRootOwnership(resolvedRoot) {
        val dialogOwner = DesktopDialogOwner()
        DesktopPlatformPickers.create(dialogOwner).use { pickers ->
            val appContainer = DesktopAppContainer(resolvedRoot, filePicker = pickers.files)
            runDesktopApplicationLifecycle(
                initialize = { appContainer.initializePersistentState() },
                applicationBody = {
                    application(exitProcessOnExit = false) {
                        val rootSwitchController = remember {
                            DesktopDataRootSwitchController(
                                resolvedRoot = resolvedRoot,
                                directoryPicker = pickers.directories,
                                isRestartRequired = {
                                    appContainer.dataOperationCoordinator.isRestartRequired
                                },
                                migrate = appContainer.dataRootMigrationService::migrate,
                            )
                        }
                        val transferController = remember { appContainer.createTypedTransferController() }
                        val modelTemplateController = remember { appContainer.createModelTemplateTransferController() }
                        val managementController = remember { appContainer.createManagementController(transferController) }
                        val promptInspectorController = remember {
                            appContainer.createPromptInspectorController()
                        }
                        val primaryChatController = remember { appContainer.primaryChatController }
                        val modelSettingsController = remember { appContainer.modelSettingsController }
                        val applicationScope = rememberCoroutineScope()
                        val backupSettingsController = remember { DesktopAutomaticBackupSettingsController(appContainer.automaticBackupRuntime, applicationScope) }
                        LaunchedEffect(backupSettingsController) { backupSettingsController.observe() }
                        val uiLanguageController = remember { appContainer.uiLanguageController }
                        LaunchedEffect(uiLanguageController) { uiLanguageController.load() }
                        val uiLanguage by uiLanguageController.language.collectAsState()
                        val appearanceController = remember { appContainer.appearanceController }
                        LaunchedEffect(appearanceController) { appearanceController.load() }
                        val appearance by appearanceController.state.collectAsState()
                        val navigation = remember { DesktopPrimaryNavigationController() }
                        val windowState = rememberWindowState(width = 1240.dp, height = 800.dp)
                        val requestClose: () -> Unit = {
                            DesktopShutdownTrace.record(DesktopShutdownStage.CLOSE_REQUESTED)
                            if (rootSwitchController.requestWindowClose()) exitApplication()
                            else DesktopShutdownTrace.record(DesktopShutdownStage.CLOSE_DEFERRED)
                        }
                        Window(
                            onCloseRequest = requestClose,
                            state = windowState,
                            undecorated = DesktopWindowChrome.isWindows,
                            title = "ChatChatBar",
                            icon = painterResource(DesktopBrandResources.LOGO_RESOURCE),
                        ) {
                            val chrome = rememberDesktopWindowChrome(window, requestClose)
                            DisposableEffect(window, dialogOwner) {
                                dialogOwner.attach(window)
                                onDispose { dialogOwner.detach(window) }
                            }
                            val systemDark = isSystemInDarkTheme()
                            val palette = remember(appearance.themeMode, appearance.themeColor, appearance.colorStyle, systemDark) {
                                desktopSemanticColors(appearance.themeMode, appearance.themeColor, systemDark, appearance.colorStyle)
                            }
                            SideEffect { chrome.appearance(palette.background.luminance() < 0.5f) }
                            CompositionLocalProvider(
                                LocalDesktopUiStrings provides DesktopUiStrings(uiLanguage),
                                LocalDesktopPalette provides palette,
                            ) {
                                DesktopPrimaryShell(
                                    chrome = chrome,
                                    navigation = navigation,
                                    rootSwitchController = rootSwitchController,
                                    transferController = transferController,
                                    modelTemplateController = modelTemplateController,
                                    managementController = managementController,
                                    promptInspectorController = promptInspectorController,
                                    primaryChatController = primaryChatController,
                                    modelSettingsController = modelSettingsController,
                                    backupSettingsController = backupSettingsController,
                                    characterEditorController = appContainer.characterEditorController,
                                    formatCardEditorController = appContainer.formatCardEditorController,
                                    worldBookEditorController = appContainer.worldBookEditorController,
                                    uiLanguageController = uiLanguageController,
                                    appearanceController = appearanceController,
                                    connectionTestController = appContainer.connectionTestController,
                                    novelAiSettingsController = appContainer.novelAiSettingsController,
                                    novelAiStudioController = appContainer.novelAiStudioController,
                                    onExitApplication = ::exitApplication,
                                )
                            }
                        }
                    }
                    DesktopShutdownTrace.record(DesktopShutdownStage.COMPOSE_RETURNED)
                },
                close = {
                    closeDesktopWithRecovery(close = { appContainer.close() })
                    DesktopShutdownTrace.record(DesktopShutdownStage.CONTAINER_CLOSED)
                },
            )
        }
        DesktopShutdownTrace.record(DesktopShutdownStage.PICKERS_CLOSED)
    }
    DesktopShutdownTrace.record(DesktopShutdownStage.MAIN_RETURNED)
}

/**
 * Root ownership 是 application lifetime 的最外层边界：只有 acquisition 成功后才能构造
 * container；shutdown 时先关闭并 drain container，再最后释放 cross-process ownership。
 */
internal fun runDesktopApplicationWithDataRootOwnership(
    resolvedRoot: DesktopDataRootResolution.Resolved,
    acquireOwnership: (DesktopDataRootResolution.Resolved) -> DesktopDataRootOwnershipResult =
        DesktopDataRootOwnership::acquire,
    closeOwnership: (DesktopDataRootOwnership) -> Unit = DesktopDataRootOwnership::close,
    alreadyInUsePresenter: DesktopAlreadyInUsePresenter = PlatformAlreadyInUsePresenter,
    applicationBody: () -> Unit,
) {
    val ownership = when (val result = acquireOwnership(resolvedRoot)) {
        is DesktopDataRootOwnershipResult.Acquired -> result.ownership
        is DesktopDataRootOwnershipResult.AlreadyInUse -> {
            try {
                alreadyInUsePresenter.showAlreadyInUse(result.appDataRoot)
            } catch (presentationFailure: Throwable) {
                // A failed native notification must not turn an expected refusal into a launcher failure.
                System.err.println("ChatChatBar data directory is already in use: ${result.appDataRoot}; " +
                    "startup notification failed: $presentationFailure")
            }
            return
        }
        is DesktopDataRootOwnershipResult.Failure ->
            throw DesktopDataRootOwnershipException(result)
    }

    var applicationFailure: Throwable? = null
    try {
        applicationBody()
    } catch (failure: Throwable) {
        applicationFailure = failure
        throw failure
    } finally {
        if (applicationFailure?.requiresDataRootOwnershipRetention() == true) {
            unsafeShutdownOwnerships += ownership
            DesktopShutdownTrace.record(DesktopShutdownStage.OWNERSHIP_RETAINED)
        } else {
            try {
                closeOwnership(ownership)
                DesktopShutdownTrace.record(DesktopShutdownStage.OWNERSHIP_RELEASED)
            } catch (closeFailure: Throwable) {
                if (applicationFailure == null) {
                    throw closeFailure
                }
                if (closeFailure !== applicationFailure) {
                    applicationFailure.addSuppressed(closeFailure)
                }
            }
        }
    }
}

private fun Throwable.requiresDataRootOwnershipRetention(): Boolean =
    this is DesktopLifecycleCloseIncomplete || this is DesktopLifecycleCloseUnconfirmed || this is DesktopUnsafeShutdownException || this is DesktopTaskDrainTimeoutException || this is DesktopDraftDrainTimeoutException ||
        this is DesktopReadingPositionDrainTimeoutException ||
        suppressed.any { it.requiresDataRootOwnershipRetention() } ||
        cause?.requiresDataRootOwnershipRetention() == true

/** Diagnostic marker where the original exception supports suppression. */
private class DesktopLifecycleCloseIncomplete : IllegalStateException("Desktop lifecycle close did not complete")

/**
 * Explicit close outcome for a primary Throwable that cannot carry suppressed diagnostics.
 * Ownership retention recognizes this type directly, independently of suppression or thread.
 * Preserve the exact original as both cause and primaryFailure, and the exact close failure as
 * a separate field (also when it is the same object). Never interpret this as a completed drain.
 */
internal class DesktopLifecycleCloseUnconfirmed(
    val primaryFailure: Throwable,
    val closeFailure: Throwable,
) : IllegalStateException("Desktop lifecycle close did not complete; root ownership retained", primaryFailure)

internal fun runDesktopApplicationLifecycle(
    initialize: suspend () -> Unit,
    applicationBody: () -> Unit,
    close: suspend () -> Unit,
) {
    var applicationFailure: Throwable? = null
    try {
        runBlocking { initialize() }
        applicationBody()
    } catch (failure: Throwable) {
        applicationFailure = failure
        throw failure
    } finally {
        try {
            runBlocking { close() }
        } catch (closeFailure: Throwable) {
            if (!closeFailure.requiresDataRootOwnershipRetention()) {
                closeFailure.addSuppressed(DesktopLifecycleCloseIncomplete())
            }
            if (applicationFailure != null && applicationFailure !== closeFailure) {
                applicationFailure.addSuppressed(closeFailure)
            }
            val primary = applicationFailure ?: closeFailure
            // A suppression-disabled close may not carry the marker; prefer retaining the
            // original primary identity and existing secondary order when it can carry one.
            if (!primary.requiresDataRootOwnershipRetention()) {
                primary.addSuppressed(DesktopLifecycleCloseIncomplete())
            }
            // addSuppressed can silently do nothing. Verify the propagated outcome and use an
            // explicit carrier if the primary cannot express it; never release an unconfirmed root.
            if (!primary.requiresDataRootOwnershipRetention()) {
                throw DesktopLifecycleCloseUnconfirmed(primary, closeFailure)
            }
            if (applicationFailure == null) throw closeFailure
        }
    }
}
