package com.example.chatbar.ui.chat

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.entity.SaveSlotSummary
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SaveSlotActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val slot = SaveSlotSummary("fixture-slot", "fixture-session", "合成测试存档", null, 59, 1)
    private var loads = 0
    private var exports = 0
    private var deletes = 0

    private fun content(enabled: State<Boolean> = mutableStateOf(true), large: Boolean = false) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.6f else 1f)) {
                ChatBarTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp)) {
                        SaveSlotItem(if (large) slot.copy(name = "合成测试：很长的存档名称也应完整保留可操作的按钮") else slot,
                            enabled = enabled.value, onLoad = { loads++ }, onExport = { exports++ }, onDelete = { deletes++ })
                    }
                }
            }
        }
    }

    @Test fun cancelLoadDoesNotReplaceConversation() {
        content()
        rule.onNodeWithContentDescription("载入存档").performClick()
        rule.onNodeWithText("覆盖并载入").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, loads); assertEquals(0, exports) }
        screenshot("save-slot-confirm", rule.onNode(isDialog()))
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals(0, loads); assertEquals(0, deletes) }
    }

    @Test fun exportNeverLoadsAndOnlyExplicitConfirmationLoadsOnce() {
        content()
        rule.onNodeWithContentDescription("导出存档").performClick()
        rule.onNodeWithText("覆盖并载入").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, exports); assertEquals(0, loads) }
        rule.onNodeWithContentDescription("载入存档").performClick()
        rule.onNodeWithText("覆盖并载入").performClick()
        rule.runOnIdle { assertEquals(1, loads); assertEquals(1, exports); assertEquals(0, deletes) }
        rule.onNodeWithText("覆盖并载入").assertDoesNotExist()
    }

    @Test fun busyStateDisablesConfirmationAndExistingActions() {
        val enabled = mutableStateOf(true)
        content(enabled)
        rule.onNodeWithContentDescription("载入存档").performClick()
        rule.runOnIdle { enabled.value = false }
        rule.onNodeWithText("覆盖并载入").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick()
        for (label in listOf("载入存档", "导出存档", "删除存档")) rule.onNodeWithContentDescription(label).assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, loads); assertEquals(0, exports); assertEquals(0, deletes) }
    }

    @Test fun narrowLargeFontShowsAllThreeCaptionsWithIndependentTouchTargets() {
        content(large = true)
        for (label in listOf("载入", "导出", "删除")) rule.onNodeWithText(label).assertIsDisplayed()
        val bounds = listOf("载入存档", "导出存档", "删除存档").map {
            rule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
        }
        for (i in 0..1) assertTrue(bounds[i].right <= bounds[i + 1].left)
        screenshot("save-slot-a-320dp-font160", rule.onRoot())
        rule.onNodeWithContentDescription("删除存档").performClick()
        rule.runOnIdle { assertEquals(1, deletes); assertEquals(0, loads); assertEquals(0, exports) }
    }

    private fun screenshot(name: String, node: SemanticsNodeInteraction) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.filesDir, "ui-previews/$name.png")
        out.parentFile!!.mkdirs()
        val bitmap = if (Build.VERSION.SDK_INT < 28) {
            // Compose cannot capture dialog windows below API28; retain API26 behavior checks.
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        } else node.captureToImage().asAndroidBitmap()
        out.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
