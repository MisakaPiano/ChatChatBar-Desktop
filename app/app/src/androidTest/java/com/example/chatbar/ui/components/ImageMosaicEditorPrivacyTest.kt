package com.example.chatbar.ui.components

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.chatbar.domain.image.privacyPngFixture
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImageMosaicEditorPrivacyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun metadataButtonExportsCleanCopy() = checkEditorExport(edited = false)

    @Test
    fun rotatedImageCompletionExportsCleanCopy() = checkEditorExport(edited = true)

    private fun checkEditorExport(edited: Boolean) {
        val bytes = privacyPngFixture()
        val source = File(rule.activity.cacheDir, "privacy-editor-${System.nanoTime()}.png").apply { writeBytes(bytes) }
        var result: String? = null
        rule.setContent {
            ChatBarTheme {
                ImageMosaicEditor(source.path, onDismiss = {}, onComplete = { result = it })
            }
        }
        rule.waitForIdle()
        // Inspection runs off the UI thread; wait for the static-image controls to become enabled.
        rule.waitUntil(10_000) {
            rule.onNodeWithText("完成").fetchSemanticsNode().config
                .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()
        }
        if (edited) {
            rule.onNodeWithText("旋转 90°").performClick()
            rule.onNodeWithText("完成").performClick()
        } else {
            rule.onNodeWithText("去除元数据与像素隐写").performClick()
        }
        rule.waitUntil(15_000) { result != null }
        val output = File(checkNotNull(result))
        try {
            assertTrue(output.path != source.path)
            assertArrayEquals(bytes, source.readBytes())
            val bitmap = checkNotNull(BitmapFactory.decodeFile(output.path, BitmapFactory.Options().apply { inPremultiplied = false }))
            try {
                assertEquals(64, bitmap.width)
                assertEquals(64, bitmap.height)
                val pixels = IntArray(4096)
                bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                assertTrue(pixels.all { it ushr 24 == 255 && it and 0x00010101 == 0 })
            } finally {
                bitmap.recycle()
            }
        } finally {
            output.delete()
            source.delete()
        }
    }
}
