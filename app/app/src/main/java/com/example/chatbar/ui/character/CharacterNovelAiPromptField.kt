package com.example.chatbar.ui.character

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.components.NovelAiTagInput
import com.example.chatbar.ui.components.NovelAiFullscreenTagEditor
import com.example.chatbar.ui.kit.CbField

internal enum class CharacterNaiPromptTarget { STYLE, NEGATIVE }
internal data class CharacterNaiPromptFullscreen(
    val target: CharacterNaiPromptTarget,
    val initialValue: TextFieldValue,
    val onConfirm: (TextFieldValue) -> Unit
)

/** Display annotations are shared with Studio; only explicit raw-text edits reach the card draft. */
@Composable
internal fun CharacterNovelAiPromptField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
    placeholder: String,
    onFullscreenEdit: (TextFieldValue, (TextFieldValue) -> Unit) -> Unit,
    beforeInput: (@Composable () -> Unit)? = null
) {
    var editorValue by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (editorValue.text != value) editorValue = TextFieldValue(value, TextRange(value.length))
    }
    fun update(next: TextFieldValue) {
        editorValue = next
        if (next.text != value) onValueChange(next.text)
    }
    CbField(title, description = description, onFullscreenEdit = { onFullscreenEdit(editorValue, ::update) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            beforeInput?.invoke()
            NovelAiTagInput(editorValue, ::update, placeholder = placeholder)
        }
    }
}

/** Host at the screen root, outside its lazy form, so fullscreen gets bounded viewport constraints. */
@Composable
internal fun CharacterNovelAiPromptFullscreenHost(
    request: CharacterNaiPromptFullscreen,
    currentSource: () -> String,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val source = currentSource()
    if (source != request.initialValue.text) {
        LaunchedEffect(source) { onDismiss() }
    } else NovelAiFullscreenTagEditor(
        title = when (request.target) {
            CharacterNaiPromptTarget.STYLE -> "NovelAI 默认风格提示词"
            CharacterNaiPromptTarget.NEGATIVE -> "NovelAI 默认负面提示词"
        },
        initialValue = request.initialValue,
        onConfirm = {
            if (currentSource() == request.initialValue.text) request.onConfirm(it)
            onDismiss()
        },
        onDismiss = onDismiss
    )
}
