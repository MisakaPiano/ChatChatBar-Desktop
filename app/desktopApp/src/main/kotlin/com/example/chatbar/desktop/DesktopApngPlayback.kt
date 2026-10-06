package com.example.chatbar.desktop

import com.example.chatbar.domain.image.ApngDisguiseCodec
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.*

/** Shared codec owns frame validation/extraction; AWT only applies PNG blend/disposal operations. */
internal suspend fun playDesktopApng(bytes: ByteArray, maxPlays: Int? = null,
    onFrame: suspend (BufferedImage, Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
    if (!bytes.take(8).toByteArray().contentEquals(ApngDisguiseCodec.signatureBytes())) return@withContext false
    require(bytes.size <= ApngDisguiseCodec.MAX_OUTPUT_BYTES)
    val source = Files.createTempFile("ccb-apng-playback-", ".png")
    try {
        Files.write(source, bytes)
        if (!ApngDisguiseCodec.containsAnimationControl(source.toFile())) return@withContext false
        val sequence = ApngDisguiseCodec.openAnimation(source.toFile())
        require(sequence.width.toLong() * sequence.height <= DesktopImageEditing.MAX_PIXELS)
        require(sequence.controls.size <= 1000)
        var loops = 0
        do {
            val canvas = BufferedImage(sequence.width, sequence.height, BufferedImage.TYPE_INT_ARGB)
            sequence.controls.forEachIndexed { index, control ->
                currentCoroutineContext().ensureActive()
                val previous = if (control.disposeOperation == 2) canvas.pixels() else null
                val frame = DesktopImageEditing.decode(sequence.readFramePng(index))
                canvas.createGraphics().let { graphics -> try {
                    graphics.composite = if (control.blendOperation == 0) AlphaComposite.Src else AlphaComposite.SrcOver
                    graphics.drawImage(frame, control.xOffset, control.yOffset, null)
                } finally { graphics.dispose() } }
                val denominator = control.delayDenominator.takeIf { it != 0 } ?: 100
                onFrame(canvas, (control.delayNumerator * 1000L / denominator).coerceAtLeast(10))
                when (control.disposeOperation) {
                    1 -> canvas.createGraphics().let { graphics -> try {
                        graphics.composite = AlphaComposite.Clear
                        graphics.fillRect(control.xOffset, control.yOffset, control.width, control.height)
                    } finally { graphics.dispose() } }
                    2 -> canvas.setRGB(0, 0, canvas.width, canvas.height, requireNotNull(previous), 0, canvas.width)
                }
            }
            loops++
        } while ((maxPlays == null || loops < maxPlays) && (sequence.playCount == 0 || loops < sequence.playCount))
        true
    } finally { Files.deleteIfExists(source) }
}
