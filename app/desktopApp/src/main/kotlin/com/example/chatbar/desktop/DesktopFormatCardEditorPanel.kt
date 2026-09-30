package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.domain.card.FormatCardUserToolValidator
import kotlinx.coroutines.launch

@Composable
internal fun DesktopFormatCardManagementPanel(controller: DesktopFormatCardEditorController) {
    val t = LocalDesktopUiStrings.current
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.load() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormatHeading(t(DesktopUiText.FORMAT_MANAGEMENT))
        FormatField(t(DesktopUiText.SEARCH_FORMATS), state.query) { controller.search(it) }
        BootstrapButton(t(DesktopUiText.NEW_FORMAT)) { scope.launch { controller.openNew() } }
        if (state.visibleCards.isEmpty()) StatusText(t(DesktopUiText.NO_FORMATS))
        state.visibleCards.forEach { card ->
            Row(Modifier.fillMaxWidth().border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
                .padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                StatusText(card.name + if (card.isDefault) " · ${t(DesktopUiText.DEFAULT)}" else "")
                BootstrapButton(t(DesktopUiText.EDIT)) { scope.launch { controller.openExisting(card.id) } }
            }
        }
    }
}

@Composable
internal fun DesktopFormatCardEditorOverlay(controller: DesktopFormatCardEditorController) {
    val state by controller.state.collectAsState()
    val card = state.card ?: return
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val colors = DesktopBootstrapColors
    Column(Modifier.fillMaxSize().background(colors.overlay).padding(16.dp)
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FormatHeading(t(if (state.targetId == null) DesktopUiText.NEW_FORMAT else DesktopUiText.EDIT_FORMAT))
            BootstrapButton(t(DesktopUiText.CLOSE)) { controller.requestLeave(controller::closeClean) }
        }
        if (state.dirty) StatusText(t(if (state.draftPersisted) DesktopUiText.RECOVERED_DRAFT
            else DesktopUiText.DRAFT_SAVING), colors.warning)
        state.problem?.let { problem ->
            StatusText(t(problem.uiText()), colors.destructive)
            if (problem == FormatEditorProblem.INVALID_TOOL) {
                card.userTools.forEachIndexed { index, tool ->
                    FormatCardUserToolValidator.validate(tool).takeIf { !it.isValid }?.let {
                        StatusText("${index + 1}. ${t(toolIssue(tool.type, it.minimumError, it.maximumError))}", colors.destructive)
                    }
                }
            }
            if (problem == FormatEditorProblem.SOURCE_CHANGED) {
                BootstrapButton(t(DesktopUiText.OVERWRITE)) { scope.launch { controller.save(forceOverwrite = true) } }
            }
            if (problem == FormatEditorProblem.SOURCE_CHANGED || problem == FormatEditorProblem.SOURCE_DELETED) {
                BootstrapButton(t(DesktopUiText.FORMAT_SAVE_AS_NEW)) { scope.launch { controller.saveAsNew() } }
            }
            if (problem == FormatEditorProblem.SAVE_COMMITTED_WARNING ||
                problem == FormatEditorProblem.CLEAN_DRAFT_WARNING) {
                BootstrapButton(t(DesktopUiText.CHARACTER_RETRY_CLEANUP)) { scope.launch { controller.retryCleanup() } }
            }
        }
        FormatField(t(DesktopUiText.FORMAT_NAME), card.name) { value -> controller.edit { it.copy(name = value) } }
        FormatField(t(DesktopUiText.FORMAT_CONTENT), card.content, multiline = true) { value ->
            controller.edit { it.copy(content = value) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText(t(DesktopUiText.FORMAT_DEFAULT_FLAG))
            BootstrapButton(t(if (card.isDefault) DesktopUiText.ON else DesktopUiText.OFF), secondary = !card.isDefault) {
                controller.edit { it.copy(isDefault = !it.isDefault) }
            }
        }
        FormatHeading(t(DesktopUiText.FORMAT_TOOLS))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton(t(DesktopUiText.FORMAT_ADD_RANDOM)) { controller.addTool(FormatCardUserToolType.RANDOM_NUMBER) }
            BootstrapButton(t(DesktopUiText.FORMAT_ADD_SUFFIX)) { controller.addTool(FormatCardUserToolType.STRONG_PROMPT_SUFFIX) }
        }
        card.userTools.forEachIndexed { index, tool ->
            Column(Modifier.fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusText("${index + 1}. ${t(if (tool.type == FormatCardUserToolType.RANDOM_NUMBER)
                        DesktopUiText.FORMAT_RANDOM else DesktopUiText.FORMAT_SUFFIX)}")
                    BootstrapButton(t(DesktopUiText.FORMAT_UP), secondary = true) { controller.moveTool(index, -1) }
                    BootstrapButton(t(DesktopUiText.FORMAT_DOWN), secondary = true) { controller.moveTool(index, 1) }
                    BootstrapButton(t(DesktopUiText.REMOVE), secondary = true) { controller.removeTool(index) }
                }
                when (tool.type) {
                    FormatCardUserToolType.RANDOM_NUMBER -> {
                        FormatField(t(DesktopUiText.FORMAT_MIN), tool.minimum) { value ->
                            controller.updateTool(index) { it.copy(minimum = value) }
                        }
                        FormatField(t(DesktopUiText.FORMAT_MAX), tool.maximum) { value ->
                            controller.updateTool(index) { it.copy(maximum = value) }
                        }
                    }
                    FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> FormatField(
                        t(DesktopUiText.FORMAT_SUFFIX_TEXT), tool.text, multiline = true) { value ->
                        controller.updateTool(index) { it.copy(text = value) }
                    }
                }
                val issue = FormatCardUserToolValidator.validate(tool)
                if (!issue.isValid) StatusText(t(toolIssue(tool.type, issue.minimumError, issue.maximumError)), colors.destructive)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BootstrapButton(t(DesktopUiText.SAVE)) { scope.launch { if (controller.save()) controller.closeClean() } }
            BootstrapButton(t(DesktopUiText.CANCEL), secondary = true) { controller.requestLeave(controller::closeClean) }
            BootstrapButton(t(DesktopUiText.DISCARD_DRAFT), secondary = true) { scope.launch { controller.discard() } }
        }
    }
}

@Composable
internal fun DesktopFormatCardLeavePrompt(controller: DesktopFormatCardEditorController) {
    val state by controller.state.collectAsState()
    if (!state.leavePrompt) return
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(DesktopBootstrapColors.dim).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().background(DesktopBootstrapColors.card, RoundedCornerShape(12.dp))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormatHeading(t(DesktopUiText.UNSAVED_CHANGES))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BootstrapButton(t(DesktopUiText.SAVE_AND_LEAVE)) { scope.launch { controller.saveAndLeave() } }
                BootstrapButton(t(DesktopUiText.FORMAT_KEEP_DRAFT_AND_LEAVE)) {
                    scope.launch { controller.keepDraftAndLeave() }
                }
                BootstrapButton(t(DesktopUiText.DISCARD_CHANGES)) { scope.launch { controller.discard() } }
                BootstrapButton(t(DesktopUiText.CONTINUE_EDITING)) { controller.continueEditing() }
            }
        }
    }
}

@Composable
private fun FormatHeading(value: String) { StatusText(value) }

@Composable
private fun FormatField(label: String, value: String, multiline: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusText(label)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = if (multiline) 140.dp else 36.dp)
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(8.dp))
            .background(DesktopBootstrapColors.input, RoundedCornerShape(8.dp)).padding(9.dp),
            textStyle = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 14.sp), singleLine = !multiline)
    }
}

private fun toolIssue(type: FormatCardUserToolType, minimum: String?, maximum: String?): DesktopUiText = when {
    type == FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> DesktopUiText.FORMAT_SUFFIX_REQUIRED
    minimum != null -> DesktopUiText.FORMAT_MIN_INVALID
    maximum?.contains("不能小于") == true -> DesktopUiText.FORMAT_MAX_LESS_THAN_MIN
    else -> DesktopUiText.FORMAT_MAX_INVALID
}

private fun FormatEditorProblem.uiText(): DesktopUiText = when (this) {
    FormatEditorProblem.NAME_REQUIRED -> DesktopUiText.FORMAT_NAME_REQUIRED
    FormatEditorProblem.CONTENT_REQUIRED -> DesktopUiText.FORMAT_CONTENT_REQUIRED
    FormatEditorProblem.DUPLICATE_NAME -> DesktopUiText.FORMAT_DUPLICATE_NAME
    FormatEditorProblem.INVALID_TOOL -> DesktopUiText.FORMAT_INVALID_TOOL
    FormatEditorProblem.SOURCE_CHANGED -> DesktopUiText.FORMAT_SOURCE_CHANGED
    FormatEditorProblem.SOURCE_DELETED -> DesktopUiText.FORMAT_SOURCE_DELETED
    FormatEditorProblem.NEW_DRAFT_EXISTS -> DesktopUiText.FORMAT_NEW_DRAFT_EXISTS
    FormatEditorProblem.DRAFT_FAILED -> DesktopUiText.FORMAT_DRAFT_FAILED
    FormatEditorProblem.SAVE_FAILED -> DesktopUiText.FORMAT_SAVE_FAILED
    FormatEditorProblem.SAVE_COMMITTED_WARNING -> DesktopUiText.FORMAT_SAVE_WARNING
    FormatEditorProblem.CLEAN_DRAFT_WARNING -> DesktopUiText.FORMAT_CLEAN_DRAFT_WARNING
}
