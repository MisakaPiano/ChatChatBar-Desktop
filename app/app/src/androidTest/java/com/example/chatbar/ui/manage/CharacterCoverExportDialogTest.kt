package com.example.chatbar.ui.manage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CharacterCoverExportDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var base: File
    @Before fun setup() {
        base = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "synthetic-cover-ui.png")
        val bitmap = Bitmap.createBitmap(53, 31, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.BLUE); base.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    @After fun cleanup() { base.delete() }

    @Test fun cardPickerMergesRolesAndSelectionPreservesModeThenSavesCapturedBase() {
        val card = CharacterCard.create("synthetic").copy(coverImage = base.absolutePath, avatar = base.absolutePath,
            chatBackgroundImages = listOf(base.absolutePath))
        var saved: CharacterCardPngExportOptions? = null
        rule.setContent { ChatBarTheme {
            var options by remember { mutableStateOf(CharacterCardPngExportOptions()) }
            CharacterPngExportDialog(card, options, { options = it }, {}, {}, onSaveBase = { saved = it })
        } }
        rule.onNodeWithText("从本卡图片选择").performScrollTo().performClick()
        rule.onNodeWithText("选择本卡图片").assertIsDisplayed()
        rule.onAllNodesWithText("选为底图").assertCountEquals(1)
        screenshot("cover-card-picker.png")
        rule.onNodeWithText("选为底图").performClick()
        rule.onNodeWithText("单独保存底图文件").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(base.absolutePath, saved?.coverImagePath)
            assertTrue(requireNotNull(saved).preserveOriginalCover)
        }
        screenshot("cover-export-phone.png")
    }

    @Test fun busyExportCannotChangeSourceOrSaveAndRenderedTitleDoesNotRenameCard() {
        val card = CharacterCard.create("original name").copy(coverImage = base.absolutePath)
        var latest = CharacterCardPngExportOptions(preserveOriginalCover = false)
        var busy by mutableStateOf(true)
        rule.setContent { ChatBarTheme {
            var options by remember { mutableStateOf(latest) }
            CharacterPngExportDialog(card, options, { options = it; latest = it }, {}, {}, baseBusy = busy)
        } }
        rule.onNodeWithText("从本卡图片选择").assertIsNotEnabled()
        rule.onNodeWithText("导出 PNG").assertIsNotEnabled()
        rule.runOnIdle { busy = false }
        rule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("image title only")
        rule.runOnIdle { assertEquals("image title only", latest.displayText); assertEquals("original name", card.name) }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val output = File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "cover-gallery-evidence").apply { mkdirs() }
        val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
