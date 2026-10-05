package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

internal class DesktopCharacterCardPngRenderer(
    private val logoBytes: () -> ByteArray = DesktopBrandResources::logoBytes,
) {
    fun render(
        card: CharacterCard,
        options: CharacterCardPngExportOptions,
        backgroundBytes: ByteArray? = null,
    ): ByteArray {
        val normalized = options.normalized()
        val size = normalized.sizePx
        val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            val background = backgroundBytes?.let { DesktopImageEditing.decode(it) }
            if (background != null) {
                drawCover(graphics, background, size, normalized)
            } else {
                graphics.paint = GradientPaint(0f, 0f, Color(18, 24, 29), size.toFloat(), size.toFloat(), Color(30, 91, 82))
                graphics.fillRect(0, 0, size, size)
            }
            val startY = (size * (1f - normalized.gradientHeight)).roundToInt()
            graphics.paint = GradientPaint(
                0f,
                startY.toFloat(),
                Color(0, 0, 0, 0),
                0f,
                size.toFloat(),
                Color(0, 0, 0, (255 * normalized.gradientStrength).roundToInt()),
            )
            graphics.fillRect(0, startY, size, size - startY)

            val margin = (size * 0.055f).roundToInt()
            val markSize = (size * normalized.logoScale).roundToInt()
            val markTop = size - margin - markSize
            val logo = checkNotNull(ImageIO.read(ByteArrayInputStream(logoBytes()))) { "Invalid CCB brand image" }
            graphics.drawImage(logo, margin, markTop, markSize, markSize, null)

            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, (size * normalized.titleScale).roundToInt())
            val title = card.name.ifBlank { "未命名角色" }
            graphics.drawString(title, margin + markSize + (size * 0.024f).roundToInt(), size - margin - markSize / 3)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { bytes ->
            check(ImageIO.write(output, "png", bytes)) { "Desktop PNG encoder 不可用" }
            bytes.toByteArray()
        }
    }

    private fun drawCover(
        graphics: java.awt.Graphics2D,
        source: BufferedImage,
        size: Int,
        options: CharacterCardPngExportOptions,
    ) {
        val crop = desktopImageCrop(source.width, source.height, size, size,
            DesktopImageTransform(options.cropCenterX, options.cropCenterY, options.cropZoom))
        graphics.drawImage(source, 0, 0, size, size, crop.left, crop.top,
            crop.left + crop.width, crop.top + crop.height, null)
    }
}
