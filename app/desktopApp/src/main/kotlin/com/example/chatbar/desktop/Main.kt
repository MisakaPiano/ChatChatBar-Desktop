package com.example.chatbar.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.runBlocking

fun main() {
    val rootResolution = runBlocking { DesktopDataDirectory.resolveRoot() }
    val resolvedRoot = when (rootResolution) {
        is DesktopDataRootResolution.Resolved -> rootResolution
        is DesktopDataRootResolution.Failed -> throw DesktopDataRootResolutionException(rootResolution)
    }

    runDesktopApplicationWithDataRootOwnership(resolvedRoot) {
        val appContainer = DesktopAppContainer(resolvedRoot)
        runDesktopApplicationLifecycle(
            initialize = { appContainer.automaticBackupRuntime.initialize() },
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
                    Window(
                        onCloseRequest = {
                            if (rootSwitchController.requestWindowClose()) exitApplication()
                        },
                        state = WindowState(width = 800.dp, height = 520.dp),
                        title = "ChatChatBar Desktop",
                    ) {
                        DesktopBootstrapScreen(
                            controller = rootSwitchController,
                            onExitApplication = ::exitApplication,
                        )
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
