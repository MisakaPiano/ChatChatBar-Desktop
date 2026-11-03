@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.MessageRole
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopScreenshotBubbleMaskTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `corner masks preserve asymmetric roles and translucent strips do not overlap or leave gaps`() = runBlocking(edt) {
        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
            DesktopScreenshotBubbleMasks().use { masks ->
                val bytes = renderComposeScene(152, 120) {
                    Box(Modifier.fillMaxSize().background(Color.Blue)) {
                        Box(Modifier.padding(12.dp).size(128.dp, 96.dp)
                            .desktopScreenshotBubbleBackground(role, Color.Red.copy(alpha = .5f), masks))
                    }
                }.use { image -> image.encodeToData()!!.use { it.bytes } }
                val image = ImageIO.read(bytes.inputStream())
                try {
                    val background = image.getRGB(1, 1)
                    val inside = image.getRGB(76, 60)
                    assertNotEquals(background, inside)
                    for ((x, y) in listOf(2 to 48, 64 to 2, 64 to 10, 64 to 86, 64 to 94,
                        10 to 20, 117 to 48, 125 to 48)) {
                        assertEquals(inside, image.getRGB(12 + x, 12 + y), "strip $role $x,$y")
                    }
                    if (role == MessageRole.USER) {
                        assertEquals(background, image.getRGB(13, 13))
                        assertEquals(inside, image.getRGB(13, 106))
                    } else {
                        assertEquals(inside, image.getRGB(13, 13))
                        assertEquals(background, image.getRGB(13, 106))
                    }
                    System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { directory ->
                        val target = Path.of(directory).resolve("bubble-mask-${role.name.lowercase()}.png")
                        Files.createDirectories(target.parent); Files.write(target, bytes)
                    }
                } finally { image.flush() }
            }
        }
    }
}
