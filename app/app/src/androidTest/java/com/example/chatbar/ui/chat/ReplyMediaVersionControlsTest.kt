package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy
import com.example.chatbar.domain.moment.MomentImageVersions
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.moments.MomentImageVersionControls
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReplyMediaVersionControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun undoThenRedoUsesRetainedResultWithoutGeneration() {
        val original = ChatMessage("m", "s", MessageRole.ASSISTANT, "before", createdAt = 1, updatedAt = 1)
        val state = mutableStateOf(MessageFormatRepairPolicy.replaceCurrentDisplayContent(original, "after",
            MessageFormatRepairPolicy.completedRepairNotice("before", "after")))
        rule.setContent { ChatBarTheme {
            MessageFormatRepairNoticeRow(state.value, false,
                { state.value = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(state.value)) },
                { state.value = requireNotNull(MessageFormatRepairPolicy.redoRepair(state.value)) })
        } }
        rule.onNodeWithText("撤销修复").performClick()
        rule.runOnIdle { assertEquals("before", state.value.displayContent) }
        rule.onNodeWithText("重做修复").assertIsDisplayed()
        capture("repair-redo-after-undo.png")
        rule.onNodeWithText("重做修复").performClick()
        rule.runOnIdle { assertEquals("after", state.value.displayContent) }
        rule.onNodeWithText("撤销修复").assertIsDisplayed()
    }

    @Test fun previousNextSwitchesImagePromptWithoutLosingEitherCandidate() {
        val first = MomentPost("p", "card", "s", senderName = "name", text = "post",
            imagePath = "first.png", imagePrompt = "first prompt", scheduledAt = 1, generatedAt = 2)
        val state = mutableStateOf(MomentImageVersions.append(first, "second.png", "second prompt", null))
        rule.setContent { ChatBarTheme {
            MomentImageVersionControls(state.value) { id -> state.value = MomentImageVersions.select(state.value, id) }
        } }
        rule.onNodeWithText("2/2").assertIsDisplayed()
        rule.onNodeWithText("›").assertIsNotEnabled()
        rule.onNodeWithText("‹").performClick()
        rule.onNodeWithText("1/2").assertIsDisplayed()
        capture("moment-image-candidates.png")
        rule.runOnIdle { assertEquals("first prompt", state.value.imagePrompt); assertEquals(2, state.value.imageVersions.size) }
        rule.onNodeWithText("›").performClick()
        rule.runOnIdle { assertEquals("second.png", state.value.imagePath) }
    }
    @Test fun actualReplyButtonsAcceptMultipleAndDuplicateChoicesWithoutGeneration() {
        val selected = mutableListOf<String>()
        val source = ChatMessage("choices", "s", MessageRole.ASSISTANT,
            "已经发生的正文。\n---\n【行动选项】\n1. 靠近窗边\n2. 留在原地\n---",
            createdAt = 1, updatedAt = 1)
        rule.setContent { ChatBarTheme {
            com.example.chatbar.ui.components.ChatBubble(source, assistantSegmentedBubblesEnabled = false,
                onActionOption = { selected += it }, onGenerateImage = { error("Choice must never generate") })
        } }
        rule.onNodeWithText("1. 靠近窗边").performClick()
        rule.onNodeWithText("2. 留在原地").performClick().performClick()
        rule.runOnIdle { assertEquals(listOf("靠近窗边", "留在原地", "留在原地"), selected) }
        capture("action-option-buttons.png")
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/ccb-version-review")
        }
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

}
