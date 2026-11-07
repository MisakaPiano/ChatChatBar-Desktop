package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

internal class DesktopReverseSceneEditorPresentation {
    var editor by mutableStateOf<DesktopReverseSceneEdit?>(null)
        private set
    var activationRevision by mutableIntStateOf(0)
        private set

    fun open(source: () -> DesktopReverseSceneEdit?) {
        if (editor == null) editor = source()
        if (editor != null) activationRevision++
    }
    fun close() { editor = null }
}

@Composable
internal fun DesktopReverseSceneEditor(editor: DesktopReverseSceneEdit, busy: Boolean,
    onApply: (String) -> Boolean, onCancel: () -> Unit, activationRevision: Int) {
    DesktopImageToolWindow("编辑反推场景", onCancel, width = 760.dp, height = 620.dp,
        activationRevision = activationRevision) {
        DesktopReverseSceneEditorBody(editor, busy, onApply, onCancel)
    }
}

@Composable
internal fun DesktopReverseSceneEditorBody(editor: DesktopReverseSceneEdit, busy: Boolean,
    onApply: (String) -> Boolean, onCancel: () -> Unit) {
    var value by remember(editor) { mutableStateOf(TextFieldValue(editor.original)) }
    var error by remember(editor) { mutableStateOf("") }
    DesktopReverseSceneEditorContent(value, { value = it }, busy, error,
        onApply = { if (onApply(value.text)) onCancel() else error = "场景已变化，本次编辑未应用；请关闭后重新打开" },
        onCancel = onCancel)
}

@Composable
internal fun DesktopReverseSceneEditorContent(value: TextFieldValue, onChange: (TextFieldValue) -> Unit,
    busy: Boolean, error: String, onApply: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusText("修改场景描述后，返回确认并设计 Prompt。")
        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, DesktopBootstrapColors.border)
            .background(DesktopBootstrapColors.input).verticalScroll(rememberScrollState()).padding(9.dp)) {
            BasicTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 320.dp)
                .semantics { contentDescription = "展开的反推场景描述" }, readOnly = busy,
                cursorBrush = DesktopBootstrapColors.inputCaret, textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
        }
        if (error.isNotBlank()) StatusText(error, DesktopBootstrapColors.destructive)
        StudioActions {
            StudioAction("取消", onClick = onCancel)
            StudioAction("应用场景修改", enabled = !busy, onClick = onApply)
        }
    }
}
