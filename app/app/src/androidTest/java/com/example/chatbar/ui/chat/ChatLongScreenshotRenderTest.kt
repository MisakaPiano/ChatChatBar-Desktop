package com.example.chatbar.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatLongScreenshotRenderTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longScreenshot_rendersPngWithExpectedWidth() = runBlocking {
        val activity = composeTestRule.activity
        val imageFile = createBitmapFile("message-image.png", 160, 80)
        val backgroundFile = createBitmapFile("chat-background.png", 120, 180)
        val request = ChatLongScreenshotRequest(
            title = "测试会话",
            messages = listOf(
                ChatMessage(
                    id = "user",
                    sessionId = "session",
                    role = MessageRole.USER,
                    content = "**你好**",
                    createdAt = 1,
                    updatedAt = 1
                ),
                ChatMessage(
                    id = "assistant",
                    sessionId = "session",
                    role = MessageRole.ASSISTANT,
                    content = "收到",
                    images = listOf(imageFile.absolutePath),
                    reasoningContent = "隐藏思考",
                    createdAt = 2,
                    updatedAt = 2
                )
            ),
            backgroundPath = backgroundFile.absolutePath,
            widthPx = 720,
            fontScale = 1f,
            fileName = "ChatBar_test_long_screenshot.png"
        )

        val output = renderChatLongScreenshot(activity, request)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)

        assertTrue(output.exists())
        assertEquals(720, options.outWidth)
        assertTrue(options.outHeight > 200)
    }

    @Test fun longScreenshotKeepsPlayerAvatarPixelsWithoutEditingSource() = runBlocking {
        val avatar = File(composeTestRule.activity.cacheDir, "synthetic-player-magenta.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(android.graphics.Color.MAGENTA); avatar.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val before = avatar.readBytes()
        try {
            val request = ChatLongScreenshotRequest(title = "synthetic", messages = listOf(
                ChatMessage(id = "synthetic-message", sessionId = "synthetic-session", role = MessageRole.USER,
                    content = "synthetic message", createdAt = 1, updatedAt = 1)),
                backgroundPath = null, widthPx = 720, fontScale = 1f, fileName = "synthetic-player-screenshot.png",
                playerName = "synthetic player", playerAvatarPath = avatar.absolutePath)
            val output = renderChatLongScreenshot(composeTestRule.activity, request)
            val decoded = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                var magenta = 0
                for (y in 0 until decoded.height) for (x in 0 until decoded.width) {
                    val color = decoded.getPixel(x, y)
                    if (android.graphics.Color.red(color) > 220 && android.graphics.Color.blue(color) > 220 && android.graphics.Color.green(color) < 30) magenta++
                }
                assertTrue("Expected actual player avatar pixels in exported screenshot", magenta > 200)
            } finally { decoded.recycle() }
            org.junit.Assert.assertArrayEquals(before, avatar.readBytes())
            val folder = File(composeTestRule.activity.getExternalFilesDir(null), "cover-gallery-evidence").apply { mkdirs() }
            output.copyTo(File(folder, "player-avatar-long-screenshot.png"), overwrite = true)
        } finally { avatar.delete() }
    }

    private fun createBitmapFile(name: String, width: Int, height: Int): File {
        val file = File(composeTestRule.activity.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
