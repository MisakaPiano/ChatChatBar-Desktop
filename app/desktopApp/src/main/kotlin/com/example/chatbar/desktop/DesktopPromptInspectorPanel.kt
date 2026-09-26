package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.FormatPromptPosition
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun DesktopPromptInspectorOverlay(
    controller: DesktopPromptInspectorController,
    onClose: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.refresh() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClick = {})
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(14.dp))
                .background(DesktopBootstrapColors.card, RoundedCornerShape(14.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(
                        "Prompt Inspector",
                        style = TextStyle(
                            color = DesktopBootstrapColors.foreground,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    StatusText("Logical request / transport-neutral")
                    StatusText("This is not a serialized provider HTTP request.")
                }
                BootstrapButton("Close", secondary = true, onClick = onClose)
            }

            ActionRow {
                BootstrapButton("Refresh", secondary = true) { scope.launch { controller.refresh() } }
                BootstrapButton(
                    "Inspect selected USER",
                    enabled = state.selectedSessionId != null && state.selectedUserMessageId != null,
                ) { scope.launch { controller.inspect() } }
            }

            InspectorHeading("Persisted session")
            if (state.sessions.isEmpty()) {
                StatusText("No persisted sessions.")
            } else {
                state.sessions.forEach { session ->
                    BootstrapButton(
                        label = "${session.title} · ${session.id.take(8)}",
                        secondary = state.selectedSessionId != session.id,
                    ) { scope.launch { controller.selectSession(session.id) } }
                }
            }

            InspectorHeading("Persisted USER message")
            if (state.selectedSessionId != null && state.userMessages.isEmpty()) {
                StatusText("The selected session has no persisted USER messages.")
            } else {
                state.userMessages.forEach { message ->
                    BootstrapButton(
                        label = "${message.content.ifBlank { "(blank USER)" }.take(100)} · ${message.id.take(8)}",
                        secondary = state.selectedUserMessageId != message.id,
                    ) { controller.selectUserMessage(message.id) }
                }
            }

            InspectorHeading("Inspection inputs")
            InspectorTextField(
                label = "Effective context-window size (required)",
                value = state.inputs.effectiveContextWindowSize,
            ) { value ->
                controller.updateInputs {
                    it.copy(effectiveContextWindowSize = value.filter(Char::isDigit))
                }
            }
            InspectorTextField("Global player name", state.inputs.globalPlayerName) { value ->
                controller.updateInputs { it.copy(globalPlayerName = value) }
            }
            InspectorTextField("Global player persona", state.inputs.globalPlayerSetting) { value ->
                controller.updateInputs { it.copy(globalPlayerSetting = value) }
            }
            InspectorTextField("Default FormatCard ID", state.inputs.defaultFormatCardId) { value ->
                controller.updateInputs { it.copy(defaultFormatCardId = value) }
            }
            InspectorTextField("RAG injection mode", state.inputs.ragInjectionMode) { value ->
                controller.updateInputs { it.copy(ragInjectionMode = value) }
            }
            BasicText(
                "Format prompt position",
                style = TextStyle(color = DesktopBootstrapColors.foreground, fontWeight = FontWeight.Medium),
            )
            ActionRow {
                FormatPromptPosition.entries.forEach { position ->
                    BootstrapButton(
                        label = position.name,
                        secondary = state.inputs.formatPromptPosition != position,
                    ) {
                        controller.updateInputs { it.copy(formatPromptPosition = position) }
                    }
                }
            }
            ActionRow {
                BootstrapButton(
                    label = "Exclude assistant status: ${state.inputs.excludeAssistantStatusFromHistory.onOff()}",
                    secondary = !state.inputs.excludeAssistantStatusFromHistory,
                ) {
                    controller.updateInputs {
                        it.copy(excludeAssistantStatusFromHistory = !it.excludeAssistantStatusFromHistory)
                    }
                }
                BootstrapButton(
                    label = "Segmented bubbles: ${state.inputs.assistantSegmentedBubblesEnabled.onOff()}",
                    secondary = !state.inputs.assistantSegmentedBubblesEnabled,
                ) {
                    controller.updateInputs {
                        it.copy(assistantSegmentedBubblesEnabled = !it.assistantSegmentedBubblesEnabled)
                    }
                }
            }

            when (val status = state.status) {
                DesktopPromptInspectorStatus.Idle -> Unit
                DesktopPromptInspectorStatus.Loading -> StatusText("Loading…")
                is DesktopPromptInspectorStatus.Error ->
                    StatusText(status.message, DesktopBootstrapColors.destructive)
                is DesktopPromptInspectorStatus.Ready -> InspectorResult(status.result)
            }
        }
    }
}

@Composable
private fun InspectorResult(result: DesktopPromptInspectionResult) {
    InspectorHeading("Cache")
    RootValue("Cacheable stable prefix", if (result.stablePrefixCacheable) "yes" else "no")
    RootValue("Stable-prefix message count", result.stablePrefixMessages.size.toString())
    RootValue("Logical promptCacheKey", result.promptCacheKey ?: "null")

    InspectorHeading("WorldBook evidence")
    if (result.worldBookEvidence.isEmpty()) {
        StatusText("No WorldBook diagnostics.")
    } else {
        SelectionContainer {
            BasicText(
                result.worldBookEvidence.joinToString("\n"),
                style = InspectorCodeStyle,
            )
        }
    }
    result.worldBookPrompt?.let { RootValue("WorldBook prompt", it) }
    if (result.worldBookOutlets.isNotEmpty()) {
        RootValue("WorldBook outlets", result.worldBookOutlets.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    RootValue("Persisted timed state", result.persistedTimedWorldInfo.keys.sorted().joinToString().ifBlank { "empty" })
    RootValue("Proposed timed state", result.proposedTimedWorldInfo.keys.sorted().joinToString().ifBlank { "empty" })

    InspectorHeading("Ordered logical messages")
    result.logicalMessages.forEachIndexed { index, trace ->
        val content = (trace.message.content as? JsonPrimitive)?.content ?: trace.message.content.toString()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                "#$index  role=${trace.message.role}  source=${trace.source.name}  " +
                    "stable-cache-prefix=${if (trace.inStableCachePrefix) "yes" else "no"}",
                style = TextStyle(
                    color = DesktopBootstrapColors.foreground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            SelectionContainer {
                BasicText(content, style = InspectorCodeStyle)
            }
        }
    }
}

@Composable
private fun InspectorHeading(text: String) {
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
private fun InspectorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicText(
            label,
            style = TextStyle(
                color = DesktopBootstrapColors.foreground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .background(DesktopBootstrapColors.background, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
            singleLine = true,
        )
    }
}

private fun Boolean.onOff(): String = if (this) "on" else "off"

private val InspectorCodeStyle = TextStyle(
    color = DesktopBootstrapColors.foreground,
    fontSize = 12.sp,
    fontFamily = FontFamily.Monospace,
)
