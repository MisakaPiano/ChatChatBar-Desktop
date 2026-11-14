package com.example.chatbar.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NovelAiImageRegenerationDialogTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fullscreenEdit_cancelDiscardsAndConfirmCommitsDraft() {
        val initialDraft = NovelAiImageRegenerationDraft(
            baseCaption = "masterpiece, 1girl",
            characterPrompts = listOf(
                GeneratedImageCharacterPrompt(
                    prompt = "black hair",
                    centerX = 0.5f,
                    centerY = 0.5f
                )
            ),
            negativePrompt = "lowres",
            sizePreset = "PORTRAIT",
            width = 832,
            height = 1216
        )

        composeTestRule.setContent {
            var draft by remember { mutableStateOf(initialDraft) }
            ChatBarTheme {
                NovelAiImageRegenerationDialog(
                    draft = draft,
                    loading = false,
                    onDraftChange = { draft = it },
                    onDismiss = {},
                    onRegenerate = {}
                )
            }
        }

        scrollUntilVisible(composeTestRule.onAllNodesWithContentDescription("全屏编辑")[1])
        recordGeometry("fullscreen-before-click", composeTestRule.onAllNodesWithContentDescription("全屏编辑")[1])
        composeTestRule.onAllNodesWithContentDescription("全屏编辑")[1].performClick()

        try {
            composeTestRule.waitUntil(3000) {
                composeTestRule.onAllNodesWithText("图片操作").fetchSemanticsNodes().isEmpty()
            }
        } finally {
            SafeBatchReviewFiles.captureScreen(composeTestRule.activity, "regeneration-legacy-fullscreen-handoff.png")
        }
        composeTestRule.onNodeWithText("编辑基础 Prompt").assertIsDisplayed()
        composeTestRule.onNodeWithText("masterpiece, 1girl")
            .performTextReplacement("discarded prompt")

        composeTestRule.onNodeWithContentDescription("退出").performClick()

        composeTestRule.onNodeWithText("图片操作").assertIsDisplayed()
        composeTestRule.onNodeWithText("masterpiece, 1girl").performScrollTo().assertIsDisplayed()

        scrollUntilVisible(composeTestRule.onAllNodesWithContentDescription("全屏编辑")[1])
        composeTestRule.onAllNodesWithContentDescription("全屏编辑")[1].performClick()
        composeTestRule.onNodeWithText("masterpiece, 1girl")
            .performTextReplacement("confirmed prompt")
        composeTestRule.onNodeWithContentDescription("确认").performClick()

        composeTestRule.onNodeWithText("图片操作").assertIsDisplayed()
        composeTestRule.onNodeWithText("confirmed prompt").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun promptEditor_editsNegativePromptAndAddsOrRemovesCharacters() {
        val initialDraft = regenerationDraft()

        composeTestRule.setContent {
            var draft by remember { mutableStateOf(initialDraft) }
            ChatBarTheme {
                NovelAiImageRegenerationDialog(
                    draft = draft,
                    loading = false,
                    onDraftChange = { draft = it },
                    onDismiss = {},
                    onRegenerate = {}
                )
            }
        }

        composeTestRule.onNodeWithText("lowres").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("lowres").performTextReplacement("bad anatomy")
        composeTestRule.onNodeWithText("bad anatomy").assertIsDisplayed()

        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scrollUntilVisible(composeTestRule.onNodeWithText("添加角色"))
        recordGeometry("add-role-before-click", composeTestRule.onNodeWithText("添加角色"))
        composeTestRule.onNodeWithText("添加角色").performClick()
        try {
            composeTestRule.waitUntil(3000) {
                composeTestRule.onAllNodesWithText("角色提示词 2").fetchSemanticsNodes().size == 1
            }
        } finally {
            SafeBatchReviewFiles.captureScreen(composeTestRule.activity, "regeneration-legacy-added-role.png")
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("角色提示词 2").assertExists()

        composeTestRule.onNodeWithContentDescription("删除角色提示词 2")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertTrue(
            composeTestRule.onAllNodesWithText("角色提示词 2").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun imageRatioSelection_replacesOriginalDimensionsWithSelectedPreset() {
        val initialDraft = regenerationDraft().copy(
            sizePreset = "PORTRAIT",
            width = 1344,
            height = 768
        )

        composeTestRule.setContent {
            var draft by remember { mutableStateOf(initialDraft) }
            ChatBarTheme {
                NovelAiImageRegenerationDialog(
                    draft = draft,
                    loading = false,
                    onDraftChange = { draft = it },
                    onDismiss = {},
                    onRegenerate = {}
                )
            }
        }

        composeTestRule.onNodeWithText("原图尺寸（1344×768）").performClick()
        composeTestRule.onNodeWithText("方图（1:1，1024×1024）").performClick()

        composeTestRule
            .onNodeWithText("当前生成尺寸 1024×1024；不修改时保留原图尺寸。")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("方图（1:1，1024×1024）").assertIsDisplayed()
    }

    /** ScrollTo can report success for a nested child whose clipped touch bounds are still empty. */
    private fun scrollUntilVisible(target: SemanticsNodeInteraction) {
        repeat(24) {
            composeTestRule.waitForIdle()
            val bounds = target.fetchSemanticsNode().boundsInRoot
            if (bounds.width > 0f && bounds.height > 0f) {
                target.assertIsDisplayed()
                return
            }
            composeTestRule.onNode(hasScrollAction() and hasAnyDescendant(hasText("添加角色")))
                .performTouchInput { swipeUp() }
        }
        target.assertIsDisplayed()
    }

    private fun recordGeometry(name: String, target: SemanticsNodeInteraction) {
        composeTestRule.waitForIdle()
        val text = "target=" + target.fetchSemanticsNode().boundsInRoot + "\n" +
            composeTestRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n") {
                composeTestRule.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString()
            }
        val file = java.io.File(composeTestRule.activity.getExternalFilesDir(null), "$name.txt")
        file.writeText(text)
        SafeBatchReviewFiles.retain(composeTestRule.activity, file)
        SafeBatchReviewFiles.captureScreen(composeTestRule.activity, "$name.png")
    }

    private fun regenerationDraft() = NovelAiImageRegenerationDraft(
        baseCaption = "masterpiece, 1girl",
        characterPrompts = listOf(
            GeneratedImageCharacterPrompt(
                prompt = "black hair",
                centerX = 0.5f,
                centerY = 0.5f
            )
        ),
        negativePrompt = "lowres",
        sizePreset = "PORTRAIT",
        width = 832,
        height = 1216
    )
}
