package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.domain.image.NovelAiPromptTranslationService
import com.example.chatbar.domain.image.NovelAiTagSuggestionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Local catalog/editor services plus the existing global display preference; no model call. */
internal class DesktopCharacterPromptTools(
    val suggestions: NovelAiTagSuggestionService,
    val translations: NovelAiPromptTranslationService,
    val translated: Flow<Boolean>,
    val setTranslated: suspend (Boolean) -> Unit,
)

@Composable
internal fun DesktopCharacterImagePromptField(entry: CharacterInfo, controller: DesktopCharacterEditorController,
    toolsFactory: () -> DesktopCharacterPromptTools) {
    val state by controller.state.collectAsState()
    // Capture plain values now: a callback from an old native window must not read a new delegated state.
    val sessionId = state.draftSessionId ?: return
    val cardId = state.card?.id ?: return
    val personId = entry.id
    val tools = remember(controller, toolsFactory) { toolsFactory() }
    val translated by tools.translated.collectAsState(false)
    val scope = rememberCoroutineScope()
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(controller, sessionId, cardId, personId) {
        attached = true
        onDispose { attached = false }
    }
    val label = LocalDesktopUiStrings.current(DesktopUiText.IMAGE_PROMPT)
    CompositionLocalProvider(LocalDesktopStudioFieldScope provides "character:$sessionId:$cardId:$personId") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioToggle("中文注释", translated) { scope.launch { tools.setTranslated(!translated) } }
            StudioPromptField(label, entry.imagePrompt, tools.suggestions, tools.translations, translated) { text ->
                if (attached) controller.updateCharacterImagePrompt(sessionId, cardId, personId, text)
            }
        }
    }
}
