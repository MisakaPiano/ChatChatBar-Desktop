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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun DesktopAlphaChatOverlay(
    controller: DesktopAlphaChatController,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(onClick = {}).padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        DesktopAlphaChatPanel(controller = controller, onClose = onClose)
    }
}

@Composable
internal fun DesktopAlphaChatPanel(
    controller: DesktopAlphaChatController,
    onClose: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val tasks by controller.taskRuntime.tasks.collectAsState()
    val diagnostics by controller.taskRuntime.diagnostics.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var selectedDiagnosticTaskId by remember { mutableStateOf<String?>(null) }
    val terminalSignature = tasks.filter { it.status != DesktopTaskStatus.RUNNING }
        .joinToString("|") { "${it.taskId}:${it.completedAt}" }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(terminalSignature) {
        if (terminalSignature.isNotEmpty()) controller.refresh()
    }
    val selectedTask = tasks.firstOrNull {
        it.sessionId == state.selectedSessionId && it.status == DesktopTaskStatus.RUNNING
    }
    val selectedDiagnostic = diagnostics.firstOrNull { it.taskId == selectedDiagnosticTaskId }
    val colors = DesktopBootstrapColors

        Column(
            modifier = Modifier.fillMaxSize()
                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                .background(colors.card, RoundedCornerShape(14.dp))
                .padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AlphaHeading("Real Chat Alpha · Task Center")
                    StatusText("Existing sessions and models · application-owned tasks")
                }
                if (onClose != null) BootstrapButton("Close overlay", secondary = true, onClick = onClose)
            }
            ActionRow {
                BootstrapButton("Refresh", secondary = true) { scope.launch { controller.refresh() } }
                selectedTask?.let { task ->
                    BootstrapButton("Stop active reply", secondary = true) { controller.stop(task.taskId) }
                }
            }

            AlphaHeading("Create from existing character")
            if (state.characters.isEmpty()) StatusText("No existing characters")
            state.characters.forEach { character ->
                BootstrapButton("New session · ${character.name.take(60)}", secondary = true) {
                    scope.launch { controller.createSession(character.id) }
                }
            }

            AlphaHeading("Persisted sessions")
            if (state.sessions.isEmpty()) StatusText("No sessions")
            state.sessions.forEach { session ->
                BootstrapButton(
                    label = "${session.title.take(70)} · ${session.id.take(8)}",
                    secondary = state.selectedSessionId != session.id,
                ) { scope.launch { controller.selectSession(session.id) } }
            }
            state.configurationMessage?.let { StatusText(it, colors.warning) }
            state.error?.let { StatusText(it, colors.destructive) }

            AlphaHeading("Persisted messages")
            if (state.messages.isEmpty()) StatusText("No persisted messages")
            state.messages.forEach { message ->
                Column(
                    modifier = Modifier.fillMaxWidth().background(colors.muted, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusText("${message.role.name} · ${message.id.take(8)}")
                    SelectionContainer {
                        BasicText(
                            message.content.take(2_000),
                            style = TextStyle(color = colors.foreground, fontSize = 14.sp),
                        )
                    }
                    message.reasoningContent?.takeIf(String::isNotBlank)?.let { reasoning ->
                        StatusText("Reasoning: ${reasoning.take(1_000)}")
                    }
                }
            }

            AlphaHeading("Text request")
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp)
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(12.dp),
            ) {
                if (draft.isEmpty()) StatusText("Type a message, or use Continue for a blank request")
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = colors.foreground, fontSize = 14.sp),
                )
            }
            val canSend = state.selectedSessionId != null && state.modelUsable && selectedTask == null
            ActionRow {
                BootstrapButton("Send", enabled = canSend && draft.isNotBlank()) {
                    if (controller.send(draft) != null) draft = ""
                }
                BootstrapButton("Continue", enabled = canSend, secondary = true) {
                    controller.send("")
                }
            }

            AlphaHeading("Task Center · current and recent")
            if (tasks.isEmpty()) StatusText("No tasks in this process")
            tasks.forEach { task ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusText("${task.status} · ${task.kind} · ${task.taskId.take(8)} · ${task.message}")
                    if (task.contentPreview.isNotBlank()) StatusText("Content: ${task.contentPreview.takeLast(500)}")
                    if (task.reasoningPreview.isNotBlank()) StatusText("Reasoning: ${task.reasoningPreview.takeLast(500)}")
                    ActionRow {
                        if (task.status == DesktopTaskStatus.RUNNING) {
                            BootstrapButton("Stop", secondary = true) { controller.stop(task.taskId) }
                        }
                        BootstrapButton(
                            "Transport Diagnostics",
                            secondary = true,
                            enabled = diagnostics.any { it.taskId == task.taskId },
                        ) { selectedDiagnosticTaskId = task.taskId }
                    }
                }
            }

            selectedDiagnostic?.let { entry ->
                AlphaHeading("Transport Diagnostics · serialized provider request")
                StatusText("Prompt Inspector shows the logical request; this view shows the provider body.")
                RootValue("Task / model", "${entry.taskId.take(8)} · ${entry.modelDisplayName} (${entry.modelName})")
                RootValue("Endpoint", entry.requestUrl ?: "Waiting for request")
                RootValue("Result", "${entry.status} · finish=${entry.finishReason ?: "pending"} · transportFailed=${entry.transportFailed}")
                entry.error?.let { StatusText(it, colors.destructive) }
                if (entry.retryEvents.isNotEmpty()) RootValue("Retries", entry.retryEvents.joinToString("\n"))
                entry.serializedRequestBody?.let { body ->
                    AlphaDiagnosticText("Final serialized request JSON", body)
                }
                if (entry.chunks.isNotEmpty()) {
                    AlphaDiagnosticText(
                        "Raw / parsed SSE evidence (${entry.droppedChunkCount} older chunks dropped)",
                        entry.chunks.joinToString("\n") { chunk ->
                            "raw=${chunk.rawPreview}\nparsed content=${chunk.contentPreview ?: "—"}; " +
                                "reasoning=${chunk.reasoningPreview ?: "—"}; " +
                                "finish=${chunk.finishReason ?: "—"}; refused=${chunk.refused}"
                        },
                    )
                }
            }
    }
}

@Composable
private fun AlphaHeading(text: String) {
    BasicText(
        text,
        style = TextStyle(
            color = DesktopBootstrapColors.foreground,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

@Composable
private fun AlphaDiagnosticText(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        SelectionContainer {
            BasicText(
                body,
                style = TextStyle(
                    color = DesktopBootstrapColors.foreground,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}
