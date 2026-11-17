package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.card.CharacterGreetingChoice
import kotlinx.coroutines.launch

/** Mounted once by the shell so both management and chat create through the same chooser. */
@Composable
internal fun DesktopSessionGreetingPicker(controller: DesktopPrimaryChatController, onCreated: (String) -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val created by rememberUpdatedState(onCreated)
    LaunchedEffect(controller, state.completedGreetingSessionId) {
        state.completedGreetingSessionId?.let { id ->
            if (controller.consumeGreetingSessionCreated(id)) created(id)
        }
    }
    state.greetingSelection?.let { selection ->
        DesktopSessionGreetingContent(selection, state.error, controller::selectSessionGreeting,
            controller::cancelSessionGreeting) {
            scope.launch { controller.confirmSessionGreeting() }
        }
    }
}

@Composable
internal fun DesktopSessionGreetingContent(selection: DesktopSessionGreetingSelection, error: String?,
    onSelect: (CharacterGreetingChoice) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    val colors = DesktopBootstrapColors
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(colors.dim).padding(20.dp).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                if (!selection.submitting) onCancel()
                true
            } else false
        }, contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 700.dp).fillMaxWidth().heightIn(max = 650.dp)
                .background(colors.overlay, RoundedCornerShape(12.dp)).border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(16.dp).testTag("new-session-greeting-picker"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusText(t(DesktopUiText.CARD_CHOOSE_GREETING) + " · " + selection.cardName)
                Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    selection.choices.forEach { choice ->
                        val label = if (choice.position == 0) t(DesktopUiText.CARD_MAIN_GREETING)
                        else t(DesktopUiText.CARD_ALTERNATE_GREETING).replace("{number}", choice.position.toString())
                        Box(Modifier.testTag("greeting-choice-${choice.position}")) {
                            BootstrapButton(label, secondary = choice != selection.selected, enabled = !selection.submitting) { onSelect(choice) }
                        }
                    }
                }
                StatusText(t(DesktopUiText.CARD_GREETING_PREVIEW))
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).border(1.dp, colors.border)
                        .padding(10.dp).testTag("greeting-exact-preview")) { StatusText(selection.selected.text) }
                }
                StatusText(t(DesktopUiText.CARD_GREETING_HELP))
                error?.let { StatusText(t.status(it), colors.destructive) }
                StudioActions {
                    BootstrapButton(t(DesktopUiText.CANCEL), secondary = true, enabled = !selection.submitting, onClick = onCancel)
                    BootstrapButton(t(DesktopUiText.CARD_CREATE_WITH_GREETING), enabled = !selection.submitting, onClick = onConfirm)
                }
            }
        }
    }
}
