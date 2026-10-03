package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.snapshot.SnapshotPurpose
import kotlinx.coroutines.launch

@Composable
internal fun DesktopBootstrapScreen(
    controller: DesktopDataRootSwitchController,
    transferController: DesktopTypedTransferController,
    promptInspectorController: DesktopPromptInspectorController,
    alphaChatController: DesktopAlphaChatController,
    onExitApplication: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var showPromptInspector by remember { mutableStateOf(false) }
    var showAlphaChat by remember { mutableStateOf(false) }
    val colors = DesktopBootstrapColors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 700.dp)
                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                .background(colors.card, RoundedCornerShape(14.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
            DesktopDataRootPanel(controller, onExitApplication)

            if (state is DesktopDataRootSwitchState.Idle) {
                DesktopTypedTransferPanel(transferController)
                BasicText(
                    text = "Prompt Inspector",
                    style = TextStyle(
                        color = colors.foreground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                StatusText("Inspect persisted chat input as a logical, transport-neutral request.")
                BootstrapButton("Open Prompt Inspector", secondary = true) {
                    showPromptInspector = true
                }
                BasicText(
                    text = "Real Chat Alpha",
                    style = TextStyle(color = colors.foreground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                )
                StatusText("Use an existing configured model and session; tasks remain active when this panel closes.")
                BootstrapButton("Open Real Chat Alpha", secondary = true) {
                    showAlphaChat = true
                }
            }
        }
        if (showPromptInspector) {
            DesktopPromptInspectorOverlay(
                controller = promptInspectorController,
                onClose = { showPromptInspector = false },
            )
        }
        if (showAlphaChat) {
            DesktopAlphaChatOverlay(
                controller = alphaChatController,
                onClose = { showAlphaChat = false },
            )
        }
    }
}

@Composable
internal fun DesktopDataRootPanel(
    controller: DesktopDataRootSwitchController,
    onExitApplication: () -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = DesktopBootstrapColors
    RootValue(t(DesktopUiText.CURRENT_DATA_DIRECTORY), state.currentRoot.toString())
    RootValue(t(DesktopUiText.AUTHORITY), state.provenance.name)

    when (val current = state) {
        is DesktopDataRootSwitchState.Idle -> {
            if (current.supported) {
                BootstrapButton(t(DesktopUiText.CHANGE_DATA_DIRECTORY)) { controller.chooseDestination() }
            } else {
                StatusText(current.unsupportedReason.orEmpty(), colors.mutedForeground)
                BootstrapButton(t(DesktopUiText.CHANGE_DATA_DIRECTORY), enabled = false) {}
            }
        }

        is DesktopDataRootSwitchState.CandidateSelected -> {
            RootValue(t(DesktopUiText.SELECTED_DESTINATION), current.destinationRoot.toString())
            StatusText(
                t(DesktopUiText.MIGRATION_EXPLANATION),
                colors.foreground,
            )
            ActionRow {
                BootstrapButton(t(DesktopUiText.CONFIRM)) { scope.launch { controller.confirmMigration() } }
                BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { controller.cancelCandidate() }
            }
        }

        is DesktopDataRootSwitchState.Migrating -> {
            RootValue(t(DesktopUiText.SELECTED_DESTINATION), current.destinationRoot.toString())
            StatusText(t(DesktopUiText.MIGRATING))
            if (current.closeDeferred) {
                StatusText(t(DesktopUiText.CLOSE_DEFERRED), colors.warning)
            }
            BootstrapButton(t(DesktopUiText.MIGRATION_IN_PROGRESS), enabled = false) {}
        }

        is DesktopDataRootSwitchState.RetryableFailure -> {
            RootValue(retryableDestinationLabel(current.result), current.destinationRoot.toString())
            StatusText(retryableFailureSummary(current.result), colors.destructive)
            materializationEvidence(current.result)?.let { StatusText(it, colors.warning) }
            ActionRow {
                BootstrapButton(t(DesktopUiText.RETRY_DESTINATION)) { controller.retryCandidate() }
                BootstrapButton(t(DesktopUiText.CHOOSE_ANOTHER), secondary = true) { controller.chooseDestination() }
            }
        }

        is DesktopDataRootSwitchState.RestartRequired -> {
            current.nextStartRoot?.let { RootValue(t(DesktopUiText.NEXT_START_DIRECTORY), it.toString()) }
            if (current.nextStartRoot == null) {
                current.destinationRoot?.let {
                    RootValue(t(DesktopUiText.ATTEMPTED_DESTINATION), it.toString())
                }
            }
            StatusText(terminalSummary(current), colors.destructive)
            (current.result as? DesktopDataRootMigrationResult.Failure)?.let { failure ->
                materializationEvidence(failure)?.let { StatusText(it, colors.warning) }
            }
            successDetails(current)?.forEach { StatusText(it, colors.foreground) }
            BootstrapButton(t(DesktopUiText.EXIT_APPLICATION)) { controller.requestExit(onExitApplication) }
        }
    }
}

@Composable
internal fun DesktopTypedTransferPanel(controller: DesktopTypedTransferController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.refresh() }
    DesktopTransferPanel(
        state = state,
        onImportCharacter = { scope.launch { controller.chooseAndImportCharacter() } },
        onImportFormat = { scope.launch { controller.chooseAndImportFormat() } },
        onImportWorldBook = { scope.launch { controller.chooseAndImportWorldBook() } },
        onExportCharacterJson = { scope.launch { controller.exportCharacterJson(it) } },
        onExportCharacterPng = { scope.launch { controller.exportCharacterPng(it) } },
        onExportFormat = { scope.launch { controller.exportFormatJson(it) } },
        onExportWorldBook = { scope.launch { controller.exportWorldBookJson(it) } },
        onExportWorldBookSt = { scope.launch { controller.exportWorldBookSillyTavern(it) } },
        onResolveConflict = { scope.launch { controller.resolveConflict(it) } },
    )
}

@Composable
private fun DesktopTransferPanel(
    state: DesktopTypedTransferState,
    onImportCharacter: () -> Unit,
    onImportFormat: () -> Unit,
    onImportWorldBook: () -> Unit,
    onExportCharacterJson: (String) -> Unit,
    onExportCharacterPng: (String) -> Unit,
    onExportFormat: (String) -> Unit,
    onExportWorldBook: (String) -> Unit,
    onExportWorldBookSt: (String) -> Unit,
    onResolveConflict: (DesktopTransferConflictAction) -> Unit,
) {
    val t = LocalDesktopUiStrings.current
    BasicText(
        text = t(DesktopUiText.TYPED_TRANSFER),
        style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    )
    state.status?.let { StatusText(it, DesktopBootstrapColors.foreground) }
    state.error?.let { StatusText(it, DesktopBootstrapColors.destructive) }
    if (state.busy) StatusText(t(DesktopUiText.WORKING))

    val enabled = !state.busy && state.pendingConflict == null
    TransferSection(t(DesktopUiText.CHARACTERS), t(DesktopUiText.IMPORT_CHARACTER), state.characters, enabled, onImportCharacter) { item ->
        BootstrapButton(t(DesktopUiText.EXPORT_JSON), enabled = enabled, secondary = true) { onExportCharacterJson(item.id) }
        BootstrapButton(t(DesktopUiText.EXPORT_CCB_PNG), enabled = enabled, secondary = true) { onExportCharacterPng(item.id) }
    }
    TransferSection(t(DesktopUiText.FORMATS), t(DesktopUiText.IMPORT_FORMAT), state.formats, enabled, onImportFormat) { item ->
        BootstrapButton(t(DesktopUiText.EXPORT_JSON), enabled = enabled, secondary = true) { onExportFormat(item.id) }
    }
    TransferSection(t(DesktopUiText.WORLD_BOOKS), t(DesktopUiText.IMPORT_WORLD_BOOK), state.worldBooks, enabled, onImportWorldBook) { item ->
        BootstrapButton(t(DesktopUiText.EXPORT_CHATBAR_JSON), enabled = enabled, secondary = true) { onExportWorldBook(item.id) }
        BootstrapButton(t(DesktopUiText.EXPORT_SILLYTAVERN_JSON), enabled = enabled, secondary = true) { onExportWorldBookSt(item.id) }
    }

    state.pendingConflict?.let { conflict ->
        StatusText("${t(DesktopUiText.NAME_CONFLICT)}: ${conflict.existingName} ← ${conflict.incomingName}", DesktopBootstrapColors.warning)
        ActionRow {
            val overwriteAllowed = (conflict as? DesktopPendingTransferConflict.Character)?.overwriteAllowed != false
            BootstrapButton(t(DesktopUiText.OVERWRITE), enabled = overwriteAllowed) {
                onResolveConflict(DesktopTransferConflictAction.OVERWRITE)
            }
            BootstrapButton(t(DesktopUiText.IMPORT_AS_NEW), secondary = true) {
                onResolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            }
            BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) {
                onResolveConflict(DesktopTransferConflictAction.CANCEL)
            }
        }
    }
}

@Composable
private fun TransferSection(
    title: String,
    importLabel: String,
    items: List<DesktopTransferItem>,
    enabled: Boolean,
    onImport: () -> Unit,
    actions: @Composable (DesktopTransferItem) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(title, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium))
        BootstrapButton(importLabel, enabled = enabled) { onImport() }
    }
    if (items.isEmpty()) {
        StatusText(LocalDesktopUiStrings.current(DesktopUiText.NO_ITEMS))
    } else {
        items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(item.name, style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions(item) }
            }
        }
    }
}

@Composable
internal fun RootValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicText(
            text = label,
            style = TextStyle(
                color = DesktopBootstrapColors.foreground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        BasicText(
            text = value,
            style = TextStyle(color = DesktopBootstrapColors.mutedForeground, fontSize = 14.sp),
        )
    }
}

@Composable
internal fun StatusText(
    text: String,
    color: Color = DesktopBootstrapColors.mutedForeground,
) {
    BasicText(text = text, style = TextStyle(color = color, fontSize = 14.sp))
}

@Composable
internal fun ActionRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = { content() })
}

internal enum class DesktopActionVariant { PRIMARY, SECONDARY, GHOST, DESTRUCTIVE }

@Composable
internal fun BootstrapButton(
    label: String,
    enabled: Boolean = true,
    secondary: Boolean = false,
    variant: DesktopActionVariant = if (secondary) DesktopActionVariant.SECONDARY else DesktopActionVariant.PRIMARY,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = DesktopBootstrapColors
    val background = when {
        !enabled -> colors.muted
        variant == DesktopActionVariant.PRIMARY -> colors.primary
        variant == DesktopActionVariant.SECONDARY -> colors.secondary
        variant == DesktopActionVariant.GHOST -> Color.Transparent
        else -> colors.destructive
    }
    val foreground = when {
        !enabled -> colors.mutedForeground
        variant == DesktopActionVariant.PRIMARY -> colors.primaryForeground
        variant == DesktopActionVariant.SECONDARY -> colors.secondaryForeground
        variant == DesktopActionVariant.GHOST -> colors.foreground
        else -> colors.destructiveForeground
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, if (variant == DesktopActionVariant.SECONDARY) colors.border else background, RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let {
                Image(rememberVectorPainter(it), contentDescription = null, modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(foreground))
            }
            BasicText(
                text = label,
                style = TextStyle(color = foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

internal fun retryableDestinationLabel(result: DesktopDataRootMigrationResult.Failure): String =
    if (result.materialization is DesktopMigrationMaterializationResult.Materialized) {
        "Destination copy"
    } else {
        "Attempted destination"
    }

private fun retryableFailureSummary(result: DesktopDataRootMigrationResult.Failure): String =
    buildString {
        append("The switch did not commit (")
        append(result.kind.name)
        append("). The current source remains authoritative and usable.")
        if (result.materialization is DesktopMigrationMaterializationResult.Materialized) {
            append(" A validated destination copy remains, but it was not selected as authority.")
        }
    }

private fun materializationEvidence(result: DesktopDataRootMigrationResult.Failure): String? {
    val failure = (result.materialization as? DesktopMigrationMaterializationResult.Failure)
        ?: result.preflightFailure
        ?: return null
    return buildList {
        if (failure.rollbackIncomplete) add("Rollback incomplete; recovery evidence remains at destination.")
        failure.retainedWorkspace?.let { add("Retained workspace: $it") }
        if (failure.installedEntries.isNotEmpty()) add("Installed entries retained: ${failure.installedEntries.size}")
        if (failure.rollbackFailures.isNotEmpty()) add("Rollback failures: ${failure.rollbackFailures.size}")
        failure.cleanupWarning?.let(::add)
        if (failure.warnings.isNotEmpty()) add("Warnings: ${failure.warnings.size}")
    }.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun terminalSummary(state: DesktopDataRootSwitchState.RestartRequired): String = when {
    state.cancelledAfterRestartSeal ->
        "The root-switch transaction reached a restart-required state. Authority cannot be safely " +
            "reclassified from the cancelled operation. Exit and reopen the application."
    state.unexpectedFailure != null ->
        "The root-switch state could not be proven safe. Exit and reopen the application."
    state.result is DesktopDataRootMigrationResult.Success ->
        "Data was copied and the next-start authority was committed. Exit and reopen the application."
    (state.result as? DesktopDataRootMigrationResult.Failure)?.kind ==
        DesktopDataRootMigrationFailureKind.AUTHORITY_INDETERMINATE ->
        "Root authority could not be confirmed. Do not retry in this process; exit and reopen the application."
    else ->
        "The current process cannot safely continue root switching. Exit and reopen the application."
}

private fun successDetails(state: DesktopDataRootSwitchState.RestartRequired): List<String>? {
    val result = state.result as? DesktopDataRootMigrationResult.Success ?: return null
    return buildList {
        add("Safety snapshot: ${result.preMigrationSnapshot.name} (${SnapshotPurpose.MANUAL})")
        add("Source retained: yes")
        add("Restart required: yes")
        if (result.materialization.warnings.isNotEmpty()) {
            add("Migration warnings: ${result.materialization.warnings.size}")
        }
        result.materialization.cleanupWarning?.let { add("Cleanup warning: $it") }
        result.materialization.retainedWorkspace?.let { add("Retained workspace: $it") }
    }
}

internal object DesktopBootstrapColors {
    val background: Color @Composable get() = LocalDesktopPalette.current.background
    val foreground: Color @Composable get() = LocalDesktopPalette.current.foreground
    val card: Color @Composable get() = LocalDesktopPalette.current.card
    val cardForeground: Color @Composable get() = LocalDesktopPalette.current.cardForeground
    val muted: Color @Composable get() = LocalDesktopPalette.current.muted
    val mutedForeground: Color @Composable get() = LocalDesktopPalette.current.mutedForeground
    val border: Color @Composable get() = LocalDesktopPalette.current.border
    val input: Color @Composable get() = LocalDesktopPalette.current.input
    val primary: Color @Composable get() = LocalDesktopPalette.current.primary
    val primaryForeground: Color @Composable get() = LocalDesktopPalette.current.primaryForeground
    val secondary: Color @Composable get() = LocalDesktopPalette.current.secondary
    val secondaryForeground: Color @Composable get() = LocalDesktopPalette.current.secondaryForeground
    val accent: Color @Composable get() = LocalDesktopPalette.current.accent
    val accentForeground: Color @Composable get() = LocalDesktopPalette.current.accentForeground
    val warning: Color @Composable get() = LocalDesktopPalette.current.warning
    val destructive: Color @Composable get() = LocalDesktopPalette.current.destructive
    val destructiveForeground: Color @Composable get() = LocalDesktopPalette.current.destructiveForeground
    val overlay: Color @Composable get() = LocalDesktopPalette.current.overlay
    val dim: Color @Composable get() = LocalDesktopPalette.current.dim
}
