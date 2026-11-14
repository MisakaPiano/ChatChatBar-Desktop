package com.example.chatbar.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.domain.budget.EditorPromptFieldEstimate
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Rule
import org.junit.Test

class PromptFieldEstimatePanelTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun worldBookEstimateLabelsCandidatesRatherThanActualRequest() {
        rule.setContent { ChatBarTheme {
            PromptFieldEstimatePanel(worldBook=true) { listOf(EditorPromptFieldEstimate("a","合成条目 · 正文",12,"仅候选")) }
        } }
        rule.onNodeWithText("字段 Token 估算").performClick()
        rule.onNodeWithText("启用正文候选合计 ≈ 12 Token").assertIsDisplayed()
        rule.onNodeWithText("合成条目 · 正文 ≈ 12 Token").assertIsDisplayed()
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithText("启用正文候选合计 ≈ 12 Token").assertDoesNotExist()
    }
}
