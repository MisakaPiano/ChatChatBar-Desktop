package com.example.chatbar.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.chatbar.domain.image.*

@Composable
internal fun StudioPromptField(label: String, value: String, infrastructure: DesktopNovelAiInfrastructure,
    translated: Boolean, onChange: (String) -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var openingValue by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (!fullscreen) input = desktopComposerDraftEcho(input, value)
        else if (value != openingValue) { fullscreen = false; input = TextFieldValue(value) }
    }
    val fragment = NovelAiTagCompletion.activeFragment(input.text, input.selection.end)
    val suggestions by produceState(TagSuggestionUpdate(loading = false), focused, fragment?.query) {
        if (focused && fragment != null) infrastructure.suggestions.observe(fragment.query).collect { this@produceState.value = it }
        else this@produceState.value = TagSuggestionUpdate(loading = false)
    }
    val sourceText = value
    val annotations by produceState<NovelAiPromptTranslationResult?>(null, value, translated) {
        this.value = if (translated) infrastructure.translations.resolve(NovelAiPromptTranslationParser.parse(sourceText, false)) else null
    }
    @Composable fun Editor() {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusText(label)
            BasicTextField(input, { input = it; if (!fullscreen) onChange(it.text) }, Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .onFocusChanged { focused = it.isFocused }.border(1.dp, DesktopBootstrapColors.border)
                .background(DesktopBootstrapColors.input).padding(9.dp), textStyle = TextStyle(color = DesktopBootstrapColors.foreground))
            annotations?.let { result ->
                StatusText(result.annotations.joinToString(" · ") { "${it.source}：${it.translation}" })
                result.warning?.let { StatusText(it) }
            }
            if (focused && fragment != null) {
                if (suggestions.loading) StatusText("查询本地词库…")
                suggestions.error?.let { StatusText(it) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                    items(suggestions.candidates, key = { it.name }) { tag ->
                        Box(Modifier.clickable {
                            if (infrastructure.suggestions.isCurrent(suggestions)) {
                                val result = NovelAiTagCompletion.insert(input.text, input.selection.end, tag.name)
                                input = TextFieldValue(result.text, TextRange(result.cursor)); if (!fullscreen) onChange(result.text)
                            }
                        }.padding(5.dp)) { StatusText("${tag.name} · ${tag.translatedName}") }
                    }
                }
            }
        }
    }
    if (!fullscreen) {
        Editor()
        BootstrapButton("展开编辑") { openingValue = value; fullscreen = true }
    } else DialogWindow(onCloseRequest = { fullscreen = false; input = TextFieldValue(value) }, title = label,
        state = rememberDialogState(width = 850.dp, height = 650.dp)) {
        Column(Modifier.fillMaxSize().background(DesktopBootstrapColors.background).padding(12.dp)) {
            Editor()
            BootstrapButton("确认") { if (value == openingValue) onChange(input.text); fullscreen = false }
            BootstrapButton("取消") { fullscreen = false; input = TextFieldValue(value) }
        }
    }
}
