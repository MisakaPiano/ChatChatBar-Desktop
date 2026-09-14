package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.kit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class TagEditorAssistance(
    val enabled: Boolean = false,
    val annotations: List<NovelAiPromptAnnotation> = emptyList(),
    val candidates: List<NovelAiTagCandidate> = emptyList(),
    val loading: Boolean = false,
    val warning: String? = null
)

@Composable
private fun rememberTagEditorAssistance(value: TextFieldValue, focused: Boolean): TagEditorAssistance {
    val app = ChatBarApp.instance
    val settings by app.settingsRepository.appSettings.collectAsState(app.settingsRepository.currentAppSettings)
    val enabled = settings.novelAiPromptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED
    var translation by remember { mutableStateOf(NovelAiPromptTranslationResult(emptyList())) }
    var candidates by remember { mutableStateOf(emptyList<NovelAiTagCandidate>()) }
    var loading by remember { mutableStateOf(false) }
    var lookupWarning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.novelAiTagSuggestionService.warmUp() }
    LaunchedEffect(value.text, enabled) {
        if (!enabled) {
            translation = NovelAiPromptTranslationResult(emptyList())
            return@LaunchedEffect
        }
        delay(250)
        try {
            translation = app.novelAiPromptTranslationService.resolve(
                NovelAiPromptTranslationParser.parse(value.text, naturalLanguage = false)
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            translation = NovelAiPromptTranslationResult(emptyList(), warning = "翻译失败：${error.message}")
        }
    }
    val query = if (focused && value.selection.collapsed) {
        NovelAiTagCompletion.activeFragment(value.text, value.selection.end)?.query
    } else null
    LaunchedEffect(query?.let(::completionQueryKey), focused) {
        candidates = emptyList()
        loading = false
        lookupWarning = null
        if (query == null) return@LaunchedEffect
        loading = true
        app.novelAiTagSuggestionService.observe(query).collect { update ->
            awaitTagSuggestionFrame()
            if (app.novelAiTagSuggestionService.isCurrent(update)) {
                candidates = update.candidates
                loading = update.loading
                lookupWarning = update.error
            }
        }
    }
    return TagEditorAssistance(
        enabled = enabled,
        annotations = if (enabled) translation.annotations.filter {
            it.start >= 0 && it.end <= value.text.length &&
                value.text.substring(it.start, it.end) == it.source
        } else emptyList(),
        candidates = candidates,
        loading = loading,
        warning = lookupWarning ?: translation.warning
    )
}

@Composable
internal fun NovelAiTranslationToggle() {
    val repository = ChatBarApp.instance.settingsRepository
    val settings by repository.appSettings.collectAsState(repository.currentAppSettings)
    val scope = rememberCoroutineScope()
    val enabled = settings.novelAiPromptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED
    CbButton(
        text = if (enabled) "实时翻译：开" else "实时翻译：关",
        onClick = {
            scope.launch {
                repository.saveAppSettings(repository.currentAppSettings.copy(
                    novelAiPromptTranslationConsent = if (enabled) NovelAiPromptTranslationConsent.DISABLED
                    else NovelAiPromptTranslationConsent.ENABLED
                ))
            }
        },
        variant = ButtonVariant.Outline
    )
}

@Composable
private fun TagEditorSuggestions(assistance: TagEditorAssistance, onInsert: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        assistance.warning?.let { CbText(it, color = ChatBarTheme.colors.destructive, maxLines = 1) }
        if (assistance.loading) CbText("正在匹配 Tag…", style = ChatBarTheme.typography.caption)
        if (assistance.candidates.isNotEmpty()) LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(assistance.candidates, key = { it.name }) { candidate ->
                CbButton(
                    text = buildString {
                        append(candidate.name)
                        if (candidate.translatedName.isNotBlank()) append(" · ${candidate.translatedName}")
                        append(if (candidate.fromDictionary) " · 内置词典" else " · ${candidate.count} 张")
                    },
                    onClick = { onInsert(candidate.name) },
                    size = ButtonSize.Xs,
                    variant = ButtonVariant.Outline
                )
            }
        }
    }
}

private fun insertCandidate(value: TextFieldValue, tag: String): TextFieldValue {
    val inserted = NovelAiTagCompletion.insert(value.text, value.selection.end, tag)
    return TextFieldValue(inserted.text, TextRange(inserted.cursor))
}

@Composable
internal fun NovelAiTagInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isError: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val assistance = rememberTagEditorAssistance(value, focused)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CbInput(
            value = value,
            onValueChange = onValueChange,
            singleLine = false,
            minLines = 3,
            modifier = Modifier.height(150.dp).then(
                if (isError) Modifier.border(1.dp, ChatBarTheme.colors.destructive, RoundedCornerShape(8.dp))
                else Modifier
            ),
            expand = true,
            onFocusChanged = { focused = it },
            textStyle = promptEditorTextStyle(assistance.enabled),
            outputTransformation = promptTagWrappingOutputTransformation(false),
            textOverlay = { layout, scroll -> PromptTranslationOverlay(layout, assistance.annotations, scroll) }
        )
        if (focused) TagEditorSuggestions(assistance) { onValueChange(insertCandidate(value, it)) }
    }
}

@Composable
internal fun NovelAiFullscreenTagEditor(
    title: String,
    initialValue: TextFieldValue,
    onConfirm: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit
) {
    val openingText = remember { initialValue.text }
    LaunchedEffect(initialValue.text) {
        if (initialValue.text != openingText) onDismiss()
    }
    val state = rememberFullscreenTextEditorState(initialValue)
    var value by remember { mutableStateOf(initialValue) }
    val assistance = rememberTagEditorAssistance(value, focused = true)
    FullscreenTextEditor(
        state = state,
        title = title,
        visible = true,
        onConfirm = {
            if (initialValue.text == openingText) onConfirm(it)
            onDismiss()
        },
        onDismiss = onDismiss,
        onDraftValueChange = { value = it },
        textStyle = promptEditorTextStyle(assistance.enabled),
        outputTransformation = promptTagWrappingOutputTransformation(false),
        textOverlay = { layout, scroll, raw ->
            PromptTranslationOverlay(layout, assistance.annotations.filter {
                it.end <= raw.length && raw.substring(it.start, it.end) == it.source
            }, scroll)
        },
        topContent = {
            NovelAiTranslationToggle()
            TagEditorSuggestions(assistance) {
                val inserted = insertCandidate(state.value, it)
                state.replace(inserted)
                value = inserted
            }
        }
    )
}
