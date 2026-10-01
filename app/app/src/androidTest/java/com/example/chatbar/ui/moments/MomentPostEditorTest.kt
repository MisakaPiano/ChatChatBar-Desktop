package com.example.chatbar.ui.moments

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.moment.MomentSender
import com.example.chatbar.domain.moment.MomentSenderOption
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MomentPostEditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val post = MomentPost(id = "p", characterCardId = "card", sessionId = "s",
        senderName = "原人物", text = "原文案", scheduledAt = 1, generatedAt = 2)

    @Test fun editSenderAndTextKeepsDraftOnSaveFailure() {
        var saved: Pair<String, String?>? = null
        var dismissed = false
        rule.setContent { ChatBarTheme {
            MomentPostEditor(post, listOf(MomentSenderOption("character:new", MomentSender("new", "新人物", null))),
                { dismissed = true }, { text, sender, result -> saved = text to sender; result("写入失败") })
        } }
        rule.onNodeWithText("原文案").assertIsDisplayed().performTextReplacement("新文案")
        rule.onNodeWithText("保留原人物：原人物").performClick()
        rule.onNodeWithText("新人物").performClick()
        rule.onNodeWithText("保存").performClick()
        rule.onNodeWithText("写入失败").assertIsDisplayed()
        rule.runOnIdle { assertEquals("新文案" to "character:new", saved); assertFalse(dismissed) }
        rule.onNodeWithText("新文案").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun fullscreenCancelRetainsOriginalAndBlankCannotSave() {
        rule.setContent { ChatBarTheme { MomentPostEditor(post, emptyList(), {}, { _, _, _ -> error("Must not save") }) } }
        rule.onNodeWithContentDescription("全屏编辑").performClick()
        rule.onNodeWithText("原文案").performTextReplacement("放弃修改")
        rule.onNodeWithContentDescription("退出").performClick()
        rule.onNodeWithText("原文案").performScrollTo().assertIsDisplayed().performTextReplacement("")
        rule.onNodeWithText("保存").assertIsNotEnabled()
    }

    @Test fun deletedSelectionRequiresReselectionWithoutLosingText() {
        val options = mutableStateOf(listOf(MomentSenderOption("character:new", MomentSender("new", "新人物", null))))
        rule.setContent { ChatBarTheme { MomentPostEditor(post, options.value, {}, { _, _, _ -> }) } }
        rule.onNodeWithText("保留原人物：原人物").performClick()
        rule.onNodeWithText("新人物").performClick()
        rule.runOnIdle { options.value = emptyList() }
        rule.onNodeWithText("人物已删除，请重新选择").assertIsDisplayed().performClick()
        rule.onNodeWithText("保留原人物：原人物").performClick()
        rule.onNodeWithText("保存").assertIsEnabled()
        rule.onNodeWithText("原文案").assertExists()
    }
}
