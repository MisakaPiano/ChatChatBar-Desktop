package com.example.chatbar.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/** An incomplete writer drain leaves storage live, so retain its root lock until process exit. */
private val unsafeShutdownOwnerships = ConcurrentHashMap.newKeySet<DesktopDataRootOwnership>()

fun main() {
    val rootResolution = runBlocking { DesktopDataDirectory.resolveRoot() }
    val resolvedRoot = when (rootResolution) {
        is DesktopDataRootResolution.Resolved -> rootResolution
        is DesktopDataRootResolution.Failed -> throw DesktopDataRootResolutionException(rootResolution)
    }

    runDesktopApplicationWithDataRootOwnership(resolvedRoot) {
        val appContainer = DesktopAppContainer(resolvedRoot)
        runDesktopApplicationLifecycle(
            initialize = { appContainer.initializePersistentState() },
            applicationBody = {
                application(exitProcessOnExit = false) {
                    val rootSwitchController = remember {
                        DesktopDataRootSwitchController(
                            resolvedRoot = resolvedRoot,
                            directoryPicker = SwingDesktopDirectoryPicker(),
                            isRestartRequired = {
                                appContainer.dataOperationCoordinator.isRestartRequired
                            },
                            migrate = appContainer.dataRootMigrationService::migrate,
                        )
                    }
                    val transferController = remember { appContainer.createTypedTransferController() }
                    val promptInspectorController = remember {
                        appContainer.createPromptInspectorController()
                    }
                    val primaryChatController = remember { appContainer.primaryChatController }
                    val modelSettingsController = remember { appContainer.modelSettingsController }
                    val uiLanguageController = remember { appContainer.uiLanguageController }
                    LaunchedEffect(uiLanguageController) { uiLanguageController.load() }
                    val uiLanguage by uiLanguageController.language.collectAsState()
                    val appearanceController = remember { appContainer.appearanceController }
                    LaunchedEffect(appearanceController) { appearanceController.load() }
                    val appearance by appearanceController.state.collectAsState()
                    val navigation = remember { DesktopPrimaryNavigationController() }
                    Window(
                        onCloseRequest = {
                            if (rootSwitchController.requestWindowClose()) exitApplication()
                        },
                        state = WindowState(width = 1240.dp, height = 800.dp),
                        title = "ChatChatBar Desktop",
                        icon = painterResource(DesktopBrandResources.LOGO_RESOURCE),
                    ) {
                        val systemDark = isSystemInDarkTheme()
                        val palette = remember(appearance.themeMode, appearance.themeColor, appearance.colorStyle, systemDark) {
                            desktopSemanticColors(appearance.themeMode, appearance.themeColor, systemDark, appearance.colorStyle)
                        }
                        CompositionLocalProvider(
                            LocalDesktopUiStrings provides DesktopUiStrings(uiLanguage),
                            LocalDesktopPalette provides palette,
                        ) {
                            DesktopPrimaryShell(
                                navigation = navigation,
                                rootSwitchController = rootSwitchController,
                                transferController = transferController,
                                promptInspectorController = promptInspectorController,
                                primaryChatController = primaryChatController,
                                modelSettingsController = modelSettingsController,
                                characterEditorController = appContainer.characterEditorController,
                                formatCardEditorController = appContainer.formatCardEditorController,
                                worldBookEditorController = appContainer.worldBookEditorController,
                                uiLanguageController = uiLanguageController,
                                appearanceController = appearanceController,
                                formatPresetController = appContainer.formatPresetController,
                                connectionTestController = appContainer.connectionTestController,
                                onExitApplication = ::exitApplication,
                            )
                        }
                    }
                }
            },
            close = { appContainer.close() },
        )
    }
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
    applicationBody: () -> Unit,
) {
    val ownership = when (val result = acquireOwnership(resolvedRoot)) {
        is DesktopDataRootOwnershipResult.Acquired -> result.ownership
        is DesktopDataRootOwnershipResult.AlreadyInUse ->
            throw DesktopDataRootOwnershipException(result)
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
        } else {
            try {
                closeOwnership(ownership)
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
    this is DesktopTaskDrainTimeoutException || this is DesktopDraftDrainTimeoutException ||
        suppressed.any { it.requiresDataRootOwnershipRetention() } ||
        cause?.requiresDataRootOwnershipRetention() == true

internal fun runDesktopApplicationLifecycle(
    initialize: suspend () -> Unit,
    applicationBody: () -> Unit,
    close: suspend () -> Unit,
) {
    runBlocking { initialize() }
    var applicationFailure: Throwable? = null
    try {
        applicationBody()
    } catch (failure: Throwable) {
        applicationFailure = failure
        throw failure
    } finally {
        try {
            runBlocking { close() }
        } catch (closeFailure: Throwable) {
            applicationFailure?.addSuppressed(closeFailure) ?: throw closeFailure
        }
    }
}
