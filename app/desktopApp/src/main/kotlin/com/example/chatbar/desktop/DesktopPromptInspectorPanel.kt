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
    Box(
        modifier = Modifier.fillMaxSize().background(DesktopBootstrapColors.dim)
            .clickable(onClick = {}).padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        DesktopPromptInspectorPanel(controller = controller, onClose = onClose)
    }
}

@Composable
internal fun DesktopPromptInspectorPanel(
    controller: DesktopPromptInspectorController,
    onClose: (() -> Unit)? = null,
) {
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.refresh() }

        Column(
            modifier = Modifier
                .fillMaxSize()
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
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(
                        t(DesktopUiText.PROMPT_INSPECTOR),
                        style = TextStyle(
                            color = DesktopBootstrapColors.foreground,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    StatusText(t(DesktopUiText.LOGICAL_REQUEST))
                    StatusText(t(DesktopUiText.NOT_PROVIDER_REQUEST))
                }
                if (onClose != null) BootstrapButton(t(DesktopUiText.CLOSE), secondary = true, onClick = onClose)
            }

            StudioActions {
                BootstrapButton(t(DesktopUiText.REFRESH), secondary = true) { scope.launch { controller.refresh() } }
                BootstrapButton(
                    t(DesktopUiText.INSPECT_USER),
                    enabled = state.selectedSessionId != null && state.selectedUserMessageId != null,
                ) { scope.launch { controller.inspect() } }
            }

            InspectorHeading(t(DesktopUiText.PERSISTED_SESSION))
            if (state.sessions.isEmpty()) {
                StatusText(t(DesktopUiText.NO_PERSISTED_SESSIONS))
            } else {
                state.sessions.forEach { session ->
                    BootstrapButton(
                        label = "${session.title} · ${session.id.take(8)}",
                        secondary = state.selectedSessionId != session.id,
                    ) { scope.launch { controller.selectSession(session.id) } }
                }
            }

            InspectorHeading(t(DesktopUiText.PERSISTED_USER_MESSAGE))
            if (state.selectedSessionId != null && state.userMessages.isEmpty()) {
                StatusText(t(DesktopUiText.NO_USER_MESSAGES))
            } else {
                state.userMessages.forEach { message ->
                    BootstrapButton(
                        label = "${message.content.ifBlank { "(blank USER)" }.take(100)} · ${message.id.take(8)}",
                        secondary = state.selectedUserMessageId != message.id,
                    ) { controller.selectUserMessage(message.id) }
                }
            }

            InspectorHeading(t(DesktopUiText.INSPECTION_INPUTS))
            InspectorTextField(
                label = t(DesktopUiText.EFFECTIVE_CONTEXT_REQUIRED),
                value = state.inputs.effectiveContextWindowSize,
            ) { value ->
                controller.updateInputs {
                    it.copy(effectiveContextWindowSize = value.filter(Char::isDigit))
                }
            }
            InspectorTextField(t(DesktopUiText.GLOBAL_PLAYER_NAME), state.inputs.globalPlayerName) { value ->
                controller.updateInputs { it.copy(globalPlayerName = value) }
            }
            InspectorTextField(t(DesktopUiText.GLOBAL_PLAYER_PERSONA), state.inputs.globalPlayerSetting) { value ->
                controller.updateInputs { it.copy(globalPlayerSetting = value) }
            }
            InspectorTextField(t(DesktopUiText.DEFAULT_FORMAT_ID), state.inputs.defaultFormatCardId) { value ->
                controller.updateInputs { it.copy(defaultFormatCardId = value) }
            }
            InspectorTextField(t(DesktopUiText.RAG_MODE), state.inputs.ragInjectionMode) { value ->
                controller.updateInputs { it.copy(ragInjectionMode = value) }
            }
            BasicText(
                t(DesktopUiText.FORMAT_PROMPT_POSITION),
                style = TextStyle(color = DesktopBootstrapColors.foreground, fontWeight = FontWeight.Medium),
            )
            StudioActions {
                FormatPromptPosition.entries.forEach { position ->
                    BootstrapButton(
                        label = position.name,
                        secondary = state.inputs.formatPromptPosition != position,
                    ) {
                        controller.updateInputs { it.copy(formatPromptPosition = position) }
                    }
                }
            }
            StudioActions {
                BootstrapButton(
                    label = "${t(DesktopUiText.EXCLUDE_ASSISTANT_STATUS)}: ${state.inputs.excludeAssistantStatusFromHistory.onOff()}",
                    secondary = !state.inputs.excludeAssistantStatusFromHistory,
                ) {
                    controller.updateInputs {
                        it.copy(excludeAssistantStatusFromHistory = !it.excludeAssistantStatusFromHistory)
                    }
                }
                BootstrapButton(
                    label = "${t(DesktopUiText.SEGMENTED_BUBBLES)}: ${state.inputs.assistantSegmentedBubblesEnabled.onOff()}",
                    secondary = !state.inputs.assistantSegmentedBubblesEnabled,
                ) {
                    controller.updateInputs {
                        it.copy(assistantSegmentedBubblesEnabled = !it.assistantSegmentedBubblesEnabled)
                    }
                }
            }

            when (val status = state.status) {
                DesktopPromptInspectorStatus.Idle -> Unit
                DesktopPromptInspectorStatus.Loading -> StatusText(t(DesktopUiText.LOADING))
                is DesktopPromptInspectorStatus.Error ->
                    StatusText(status.message, DesktopBootstrapColors.destructive)
                is DesktopPromptInspectorStatus.Ready -> InspectorResult(status.result)
            }
    }
}

@Composable
private fun InspectorResult(result: DesktopPromptInspectionResult) {
    val t = LocalDesktopUiStrings.current
    InspectorHeading(t(DesktopUiText.CACHE))
    RootValue(t(DesktopUiText.CACHEABLE_PREFIX), t(if (result.stablePrefixCacheable) DesktopUiText.YES else DesktopUiText.NO))
    RootValue(t(DesktopUiText.PREFIX_MESSAGE_COUNT), result.stablePrefixMessages.size.toString())
    RootValue(t(DesktopUiText.LOGICAL_CACHE_KEY), result.promptCacheKey ?: "null")

    InspectorHeading(t(DesktopUiText.WORLD_BOOK_EVIDENCE))
    if (result.worldBookEvidence.isEmpty()) {
        StatusText(t(DesktopUiText.NO_WORLD_BOOK_DIAGNOSTICS))
    } else {
        SelectionContainer {
            BasicText(
                result.worldBookEvidence.joinToString("\n"),
                style = inspectorCodeStyle(),
            )
        }
    }
    result.worldBookPrompt?.let { RootValue(t(DesktopUiText.WORLD_BOOK_PROMPT), it) }
    if (result.worldBookOutlets.isNotEmpty()) {
        RootValue(t(DesktopUiText.WORLD_BOOK_OUTLETS), result.worldBookOutlets.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    RootValue(t(DesktopUiText.PERSISTED_TIMED_STATE), result.persistedTimedWorldInfo.keys.sorted().joinToString().ifBlank { t(DesktopUiText.EMPTY) })
    RootValue(t(DesktopUiText.PROPOSED_TIMED_STATE), result.proposedTimedWorldInfo.keys.sorted().joinToString().ifBlank { t(DesktopUiText.EMPTY) })

    InspectorHeading(t(DesktopUiText.ORDERED_MESSAGES))
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
                BasicText(content, style = inspectorCodeStyle())
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
                .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp),
            singleLine = true,
        )
    }
}

@Composable
private fun Boolean.onOff(): String = LocalDesktopUiStrings.current(
    if (this) DesktopUiText.ON else DesktopUiText.OFF,
)

@Composable
private fun inspectorCodeStyle() = TextStyle(
    color = DesktopBootstrapColors.foreground,
    fontSize = 12.sp,
    fontFamily = FontFamily.Monospace,
)
