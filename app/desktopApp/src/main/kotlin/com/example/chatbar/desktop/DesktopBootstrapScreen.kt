package com.example.chatbar.desktop

import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
    onExitApplication: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
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
                .heightIn(max = 472.dp)
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
            RootValue("Current running data directory", state.currentRoot.toString())
            RootValue("Authority", state.provenance.name)

            when (val current = state) {
                is DesktopDataRootSwitchState.Idle -> {
                    if (current.supported) {
                        BootstrapButton("Change data directory…") {
                            controller.chooseDestination()
                        }
                    } else {
                        StatusText(current.unsupportedReason.orEmpty(), colors.mutedForeground)
                        BootstrapButton("Change data directory…", enabled = false) {}
                    }
                }

                is DesktopDataRootSwitchState.CandidateSelected -> {
                    RootValue("Selected destination", current.destinationRoot.toString())
                    StatusText(
                        "CCB will copy the current data after creating a safety snapshot. " +
                            "The source is retained, the destination must pass safety checks, " +
                            "and a successful switch requires an application restart. " +
                            "This operation does not move and delete the source.",
                        colors.foreground,
                    )
                    ActionRow {
                        BootstrapButton("Confirm") {
                            scope.launch { controller.confirmMigration() }
                        }
                        BootstrapButton("Cancel", secondary = true) { controller.cancelCandidate() }
                    }
                }

                is DesktopDataRootSwitchState.Migrating -> {
                    RootValue("Selected destination", current.destinationRoot.toString())
                    StatusText("Migrating data… The application will remain open until stabilization finishes.")
                    if (current.closeDeferred) {
                        StatusText(
                            "Close is deferred while migration is in progress.",
                            colors.warning,
                        )
                    }
                    BootstrapButton("Migration in progress", enabled = false) {}
                }

                is DesktopDataRootSwitchState.RetryableFailure -> {
                    RootValue("Destination copy", current.destinationRoot.toString())
                    StatusText(retryableFailureSummary(current.result), colors.destructive)
                    materializationEvidence(current.result)?.let {
                        StatusText(it, colors.warning)
                    }
                    ActionRow {
                        BootstrapButton("Retry this destination") { controller.retryCandidate() }
                        BootstrapButton("Choose another…", secondary = true) {
                            controller.chooseDestination()
                        }
                    }
                }

                is DesktopDataRootSwitchState.RestartRequired -> {
                    current.nextStartRoot?.let { RootValue("Next-start data directory", it.toString()) }
                    if (current.nextStartRoot == null) {
                        current.destinationRoot?.let {
                            RootValue("Attempted destination (authority not confirmed)", it.toString())
                        }
                    }
                    StatusText(terminalSummary(current), colors.destructive)
                    (current.result as? DesktopDataRootMigrationResult.Failure)?.let { failure ->
                        materializationEvidence(failure)?.let { StatusText(it, colors.warning) }
                    }
                    successDetails(current)?.forEach { StatusText(it, colors.foreground) }
                    BootstrapButton("Exit application") {
                        controller.requestExit(onExitApplication)
                    }
                }
            }
        }
    }
}

@Composable
private fun RootValue(label: String, value: String) {
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
private fun StatusText(
    text: String,
    color: Color = DesktopBootstrapColors.mutedForeground,
) {
    BasicText(text = text, style = TextStyle(color = color, fontSize = 14.sp))
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = { content() })
}

@Composable
private fun BootstrapButton(
    label: String,
    enabled: Boolean = true,
    secondary: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = DesktopBootstrapColors
    val background = when {
        !enabled -> colors.muted
        secondary -> colors.secondary
        else -> colors.primary
    }
    val foreground = when {
        !enabled -> colors.mutedForeground
        secondary -> colors.foreground
        else -> colors.primaryForeground
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, if (secondary) colors.border else background, RoundedCornerShape(8.dp))
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
    }
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

private object DesktopBootstrapColors {
    val background = Color(0xFFF8FAFC)
    val foreground = Color(0xFF0F172A)
    val card = Color(0xFFFFFFFF)
    val muted = Color(0xFFF1F5F9)
    val mutedForeground = Color(0xFF64748B)
    val border = Color(0xFFE2E8F0)
    val primary = Color(0xFF0F172A)
    val primaryForeground = Color(0xFFFFFFFF)
    val secondary = Color(0xFFF8FAFC)
    val destructive = Color(0xFFB91C1C)
    val warning = Color(0xFFB45309)
}
