package com.example.chatbar.ui.components

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.debug.SafeBatchOfflineActivity
import org.junit.Rule
import org.junit.Test

class ImageBatchOfflineRunTest {
    @get:Rule val rule = createAndroidComposeRule<SafeBatchOfflineActivity>()
    @Test fun tenOfflineImagesUseThreeProductionHttpBatchesAndSurviveActivityReopenWithoutRestart() {
        rule.onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        rule.onNodeWithText("开始离线任务").performScrollTo().performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithText("任务完成；重启不会自动继续请求", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("实际模拟请求 n_samples：[4, 4, 2]").assertExists()
        rule.onNodeWithText("磁盘保留：会话 [4, 4, 2]", substring = true).assertExists()
        rule.onNodeWithText("实际模拟请求 n_samples：[4, 4, 2]").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("磁盘保留：会话 [4, 4, 2]", substring = true).performScrollTo().assertIsDisplayed()
        rule.waitForIdle()
        SafeBatchReviewFiles.captureScreen(rule.activity, "offline-actual-http-and-persistence.png")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5000) { rule.onAllNodesWithText("磁盘保留：会话 [4, 4, 2]", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("实际模拟请求 n_samples：[]").assertExists()
        rule.onNodeWithText("未启动；不读取账号、不联网、不扣费").assertExists()
    }
}
