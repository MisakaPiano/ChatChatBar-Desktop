package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopCharacterCardPngRendererTest {
    @Test
    fun `renderer supports normalized square sizes and fallback`() {
        val root = Files.createTempDirectory("desktop-png-render-")
        try {
            val renderer = DesktopCharacterCardPngRenderer()
            listOf(1024, 1536, 2048).forEach { size ->
                val bytes = renderer.render(card(), CharacterCardPngExportOptions(sizePx = size))
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                assertEquals(size, image.width)
                assertEquals(size, image.height)
                assertTrue(bytes.size > 1000)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cover crop center and zoom affect rendered pixels`() {
        val root = Files.createTempDirectory("desktop-png-crop-")
        try {
            val background = wideImage()
            val renderer = DesktopCharacterCardPngRenderer()
            val left = renderer.render(card(), CharacterCardPngExportOptions(sizePx = 1024, cropCenterX = 0f), background)
            val right = renderer.render(card(), CharacterCardPngExportOptions(sizePx = 1024, cropCenterX = 1f), background)
            val zoomed = renderer.render(card(), CharacterCardPngExportOptions(sizePx = 1024, cropCenterX = 0f, cropZoom = 2f), background)
            assertFalse(left.contentEquals(right))
            assertFalse(left.contentEquals(zoomed))
            val leftImage = ImageIO.read(ByteArrayInputStream(left))
            val rightImage = ImageIO.read(ByteArrayInputStream(right))
            assertNotEquals(leftImage.getRGB(100, 100), rightImage.getRGB(100, 100))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun card(background: String? = null) = CharacterCard(
        id = "card",
        name = "Desktop PNG",
        chatBackground = background,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun wideImage(): ByteArray {
        val image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 100, 200)
        graphics.color = Color.GREEN
        graphics.fillRect(100, 0, 100, 200)
        graphics.color = Color.BLUE
        graphics.fillRect(200, 0, 100, 200)
        graphics.color = Color.YELLOW
        graphics.fillRect(300, 0, 100, 200)
        graphics.dispose()
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }
}
