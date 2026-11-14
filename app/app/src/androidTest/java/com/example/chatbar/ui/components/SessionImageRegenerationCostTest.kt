package com.example.chatbar.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.domain.image.ImageBatchPreferences
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SessionImageRegenerationCostTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val draft = NovelAiImageRegenerationDraft("synthetic garden", emptyList(), "lowres",
        "PORTRAIT", 832, 1216)

    @Test fun paidSourceShowsEstimateButCannotSubmitWithSpendingDisabled() {
        var submitted = false
        rule.setContent {
            ChatBarTheme {
                NovelAiImageRegenerationDialog(draft, false, onDraftChange = {}, onDismiss = {},
                    onRegenerate = { submitted = true }, batchSettings = ImageBatchPreferences(),
                    estimatedAnlas = 30)
            }
        }
        rule.onNodeWithText("重新生成 · 预计 30 Anlas").assertIsNotEnabled()
        rule.onNodeWithText("synthetic garden", substring = true).assertDoesNotExist()
        rule.onNodeWithText("使用已保存批量设置").assertIsDisplayed()
        assertFalse(submitted)
        SafeBatchReviewFiles.captureScreen(rule.activity, "regeneration-paid-disabled.png")
    }

    @Test fun freeSingleShowsCompactDefaultsAndSourceEditorIsOptional() {
        rule.setContent {
            ChatBarTheme {
                NovelAiImageRegenerationDialog(draft, false, onDraftChange = {}, onDismiss = {},
                    onRegenerate = {}, batchSettings = ImageBatchPreferences(false, 4, 3),
                    estimatedAnlas = 0)
            }
        }
        rule.onNodeWithText("1 张 × 1 批").assertIsDisplayed()
        rule.onNodeWithText("重新生成 · 估算免 Anlas").assertIsEnabled()
        rule.onNodeWithText("编辑提示词与画面参数").performClick()
        rule.onNodeWithText("synthetic garden").performScrollTo().assertIsDisplayed()
        SafeBatchReviewFiles.captureScreen(rule.activity, "regeneration-editor-expanded.png")
    }
}
