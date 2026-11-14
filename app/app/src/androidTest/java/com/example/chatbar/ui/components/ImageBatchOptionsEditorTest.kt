package com.example.chatbar.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.kit.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageBatchOptionsEditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun batchRiskBudgetAndGroupingAreVisibleWithoutAnyAccountCall() {
        rule.setContent {
            var config by remember { mutableStateOf(ImageBatchConfiguration(4, true, 10)) }
            ChatBarTheme { CbDialog(onDismissRequest = {}, title = "本次生图设置", confirm = { CbButton("确认", {}) }) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ImageBatchOptionsEditor(config, { config = it })
                }
            } }
        }
        rule.onNodeWithContentDescription("批量生图数量").assertExists()
        rule.onNodeWithContentDescription("本次估算Anlas预算").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("预计 10 张", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("4 + 4 + 2", substring = true).assertIsDisplayed()
        rule.onNodeWithText("一次请求多张通常", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("batch-cost-risk.png")
    }
    @Test fun reversedRangeCannotBeAppliedAndCorrectedRangeBecomesValid() {
        var valid = true
        var actual = ImageBatchConfiguration(1, true, 3, ImageBatchPacing(ImageBatchInterval.RANGE))
        rule.setContent {
            var config by remember { mutableStateOf(actual) }
            ChatBarTheme { CbDialog(onDismissRequest = {}, title = "范围随机", confirm = { CbButton("确认", {}) }) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ImageBatchOptionsEditor(config, { config = it; actual = it }, { valid = it })
                }
            } }
        }
        rule.onNodeWithContentDescription("随机最小秒数").performScrollTo().performTextReplacement("46")
        rule.waitForIdle(); assertFalse(valid); assertEquals(15, actual.pacing.minSeconds)
        rule.onNodeWithContentDescription("随机最大秒数").performScrollTo().performTextReplacement("60")
        rule.waitForIdle(); assertTrue(valid); assertEquals(46, actual.pacing.minSeconds); assertEquals(60, actual.pacing.maxSeconds)
        screenshot("batch-random-range.png")
    }
    private fun screenshot(name: String) {
        val path = File(rule.activity.getExternalFilesDir(null), "safe-batch-review/$name")
        path.parentFile!!.mkdirs()
        path.outputStream().use { rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
