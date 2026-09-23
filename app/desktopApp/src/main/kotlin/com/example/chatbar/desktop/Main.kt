package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.nio.file.Path
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
                    Window(
                        onCloseRequest = ::exitApplication,
                        state = WindowState(width = 800.dp, height = 520.dp),
                        title = "ChatChatBar Desktop",
                    ) {
                        DesktopBootstrapScreen(appContainer.appDataRoot)
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

@Composable
private fun DesktopBootstrapScreen(dataDirectory: Path) {
    val colors = DesktopBootstrapColors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                .background(colors.card, RoundedCornerShape(14.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(
                text = "ChatChatBar Desktop",
                style = TextStyle(
                    color = colors.foreground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            BasicText(
                text = "Desktop bootstrap",
                style = TextStyle(
                    color = colors.mutedForeground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    text = "Data directory",
                    style = TextStyle(
                        color = colors.foreground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                BasicText(
                    text = dataDirectory.toString(),
                    style = TextStyle(
                        color = colors.mutedForeground,
                        fontSize = 14.sp,
                    ),
                )
            }
        }
    }
}

private object DesktopBootstrapColors {
    val background = Color(0xFFF8FAFC)
    val foreground = Color(0xFF0F172A)
    val card = Color(0xFFFFFFFF)
    val mutedForeground = Color(0xFF64748B)
    val border = Color(0xFFE2E8F0)
}
