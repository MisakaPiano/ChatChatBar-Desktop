package com.example.chatbar.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageBatchOptionsEditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun perBatchTimesBatchesShowsTwelveWithoutBudgetOrTargetFields() {
        rule.setContent {
            var config by remember { mutableStateOf(ImageBatchPreferences(true, 4, 3).configuration()) }
            ChatBarTheme { CbDialog(onDismissRequest = {}, title = "会话生图设置", confirm = { CbButton("保存", {}) }) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ImageBatchOptionsEditor(config, { config = it })
                }
            } }
        }
        rule.onNodeWithContentDescription("每批张数", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("生成批数", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("共 12 张", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("允许消耗 Anlas", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("本次估算Anlas预算", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("连续目标张数", useUnmergedTree = true).assertDoesNotExist()
        screenshot("batch-simple-settings.png")
    }
    @Test fun numericBoundsClampWithoutPublishingOverflow() {
        var actual = ImageBatchPreferences(true, 1, 3).configuration()
        rule.setContent {
            var config by remember { mutableStateOf(actual) }
            ChatBarTheme { CbDialog(onDismissRequest = {}, title = "会话生图设置", confirm = { CbButton("保存", {}) }) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ImageBatchOptionsEditor(config, { config = it; actual = it })
                }
            } }
        }
        input("每批张数").performTextReplacement("99999999999999999999")
        rule.waitForIdle(); assertEquals(4, actual.requestCount)
        input("生成批数").performTextReplacement("1000")
        rule.waitForIdle(); assertEquals(25, actual.numberOfBatches)
        assertFalse(actual.allowAnlasSpending)
        screenshot("batch-bounded-input.png")
    }
    private fun input(description: String) = rule.onNode(
        hasSetTextAction() and hasAnyAncestor(hasContentDescription(description)), useUnmergedTree = true)
    private fun screenshot(name: String) {
        rule.waitForIdle()
        SafeBatchReviewFiles.captureScreen(rule.activity, name)
    }
}
