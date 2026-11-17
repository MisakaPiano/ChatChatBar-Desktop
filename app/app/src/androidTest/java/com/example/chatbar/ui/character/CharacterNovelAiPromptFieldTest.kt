package com.example.chatbar.ui.character

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.kit.ChatBarTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic translator fixture; no preset assertions and no image/LLM requests. */
class CharacterNovelAiPromptFieldTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val visible = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<List<String>>()
    private val raw = "1.2::synthetic_tag::, -0.5::artist:fixture name::, bad_quality"

    private fun fixture(enabled: Boolean = true, body: (ChatBarApp) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        runBlocking { withTimeout(30_000) { app.dependenciesReady.first { it } } }
        val settings = runBlocking { app.settingsRepository.getAppSettings() }
        val field = ChatBarApp::class.java.getDeclaredField("novelAiPromptTranslationService").also { it.isAccessible = true }
        val previous = field.get(app)
        val lookup = object : NovelAiTagLookup {
            override suspend fun search(query: String) = NovelAiTagSearchOutcome(query, emptyList())
            override suspend fun exactChineseTranslations(names: Collection<String>): Map<String, String> {
                requests.add(names.toList())
                return names.associate { it.replace(' ', '_').lowercase() to "合成译名" }
            }
            override suspend fun catalogMetadata() = DanbooruCatalogMetadata("synthetic", "", 0)
        }
        field.set(app, NovelAiPromptTranslationService(NovelAiPromptWordDictionary.fromTsv(
            "synthetic\t合成\nquality\t品质\n".byteInputStream()), lookup))
        runBlocking { app.settingsRepository.saveAppSettings(settings.copy(novelAiPromptTranslationConsent =
            if (enabled) NovelAiPromptTranslationConsent.ENABLED else NovelAiPromptTranslationConsent.DISABLED)) }
        try { body(app) } finally {
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            field.set(app, previous)
            runBlocking { app.settingsRepository.saveAppSettings(settings) }
        }
    }

    @Test fun styleAndNegativeFieldsResolveTranslationsWithoutWritingChineseOrChangingWeights() = fixture {
        var edits = 0
        rule.setContent { if (visible.value) ChatBarTheme { Column {
            CharacterNovelAiPromptField("合成风格", raw, { edits++ }, "", "", { _, _ -> })
            CharacterNovelAiPromptField("合成负面", "bad_quality, synthetic_negative", { edits++ }, "", "", { _, _ -> })
        } } }
        rule.waitUntil(10_000) { requests.any { "synthetic_tag" in it } && requests.any { "synthetic_negative" in it } }
        rule.runOnIdle { assertEquals(0, edits) }
        val texts = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { it.config[SemanticsProperties.EditableText].text }
        assertTrue(texts.contains(raw))
        assertTrue(texts.contains("bad_quality, synthetic_negative"))
    }

    @Test fun fullscreenCancelKeepsRawAndConfirmationUsesOriginalCursor() = fixture {
        val value = mutableStateOf(raw)
        var writes = 0
        harness(value) { writes++ }
        rule.onNode(hasSetTextAction()).performTextInputSelection(TextRange(1))
        rule.onNodeWithContentDescription("全屏编辑").performClick()
        val editor = rule.onAllNodes(hasSetTextAction()).onLast()
        assertEquals(TextRange(1), editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        editor.performTextInput("X")
        rule.onNodeWithContentDescription("退出").performClick()
        rule.runOnIdle { assertEquals(raw, value.value); assertEquals(0, writes) }
        rule.onNodeWithContentDescription("全屏编辑").performClick()
        rule.onAllNodes(hasSetTextAction()).onLast().performTextInput("Y")
        rule.onNodeWithContentDescription("确认").performClick()
        rule.runOnIdle { assertEquals(raw.take(1) + "Y" + raw.drop(1), value.value); assertEquals(1, writes) }
    }

    @Test fun externalPresetChangeDismissesFullscreenAndRebasesTheInlineField() = fixture {
        val value = mutableStateOf(raw)
        var writes = 0
        harness(value) { writes++ }
        rule.onNodeWithContentDescription("全屏编辑").performClick()
        rule.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("draft must not overwrite new preset")
        rule.runOnIdle { value.value = "synthetic_new_preset" }
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("确认").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasSetTextAction()).assertTextEquals("synthetic_new_preset")
        rule.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun translationToggleUsesSharedConsentWithoutEditingTheRawPrompt() = fixture(enabled = false) { app ->
        val value = mutableStateOf(raw)
        var writes = 0
        harness(value) { writes++ }
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNodeWithContentDescription("开启 Prompt 中文翻译").performClick()
        rule.waitUntil(10_000) { requests.any { "synthetic_tag" in it } }
        assertEquals(NovelAiPromptTranslationConsent.ENABLED, app.settingsRepository.currentAppSettings.novelAiPromptTranslationConsent)
        rule.runOnIdle { assertEquals(raw, value.value); assertEquals(0, writes) }
        rule.onNodeWithContentDescription("关闭 Prompt 中文翻译").performClick()
        rule.waitUntil(5_000) { app.settingsRepository.currentAppSettings.novelAiPromptTranslationConsent == NovelAiPromptTranslationConsent.DISABLED }
    }

    private fun harness(value: MutableState<String>, onWrite: () -> Unit) {
        rule.setContent { if (visible.value) ChatBarTheme {
            var request by remember { mutableStateOf<CharacterNaiPromptFullscreen?>(null) }
            Box {
                CharacterNovelAiPromptField("合成风格", value.value, { value.value = it; onWrite() }, "", "",
                    { draft, confirm -> request = CharacterNaiPromptFullscreen(CharacterNaiPromptTarget.STYLE, draft, confirm) })
                request?.let { CharacterNovelAiPromptFullscreenHost(it, { value.value }, { request = null }) }
            }
        } }
    }
}
