@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopHistoryImageAdmissionTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }

    private suspend fun frame(scene: ImageComposeScene) {
        scene.render(System.nanoTime()).close()
        delay(15)
    }

    private fun visible(scene: ImageComposeScene, reference: String): Boolean =
        nodes(scene).any { it.config.getOrNull(DesktopDecodedImageIdentity) == desktopDecodedImageIdentity(reference) }

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(24, 16, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()

    @Test fun `actual gallery thumbnails admit before reading and cancel queued work while full previews remain independent`() = runBlocking(edt) {
        val admission = DesktopImageDecodeAdmission()
        val released = CountDownLatch(1)
        val active = AtomicInteger(); val peak = AtomicInteger(); val reads = AtomicInteger()
        val bytes = png(); val before = bytes.copyOf()
        val scene = ImageComposeScene(640, 400, coroutineContext = edt) {
            Column {
                Row {
                    repeat(8) { index -> key(index) {
                        DesktopHistoryThumbnail(NovelAiGenerationHistoryImage("gallery-$index"), {
                            reads.incrementAndGet()
                            val count = active.incrementAndGet()
                            peak.updateAndGet { maxOf(it, count) }
                            try {
                                check(released.await(15, TimeUnit.SECONDS))
                                bytes
                            } finally { active.decrementAndGet() }
                        }, Modifier.size(48.dp), admission)
                    } }
                }
                DesktopOwnedImage("independent-detail", { bytes }, Modifier.size(80.dp))
            }
        }
        try {
            withTimeout(10_000) { while (reads.get() < 2 || !visible(scene, "independent-detail")) frame(scene) }
            repeat(8) { frame(scene) }
            assertEquals(2, reads.get())
            assertEquals(2, peak.get())
            assertFalse((0..7).any { visible(scene, "gallery-$it") })
        } finally {
            scene.close()
            released.countDown()
        }
        withTimeout(5_000) { while (active.get() != 0) delay(10) }
        delay(80)
        assertEquals(2, reads.get(), "Disposed queued thumbnails must never start reading")

        val later = ImageComposeScene(320, 200, coroutineContext = edt) {
            Row {
                DesktopHistoryThumbnail(NovelAiGenerationHistoryImage("bad"), { error("synthetic read failure") }, Modifier.size(48.dp), admission)
                repeat(3) { index ->
                    DesktopHistoryThumbnail(NovelAiGenerationHistoryImage("reopened-$index"), { bytes }, Modifier.size(48.dp), admission)
                }
            }
        }
        try {
            withTimeout(10_000) { while ((0..2).any { !visible(later, "reopened-$it") }) frame(later) }
            assertFalse(visible(later, "bad"))
        } finally { later.close() }
        withTimeout(5_000) { admission.load { admission.load { Unit } } }
        assertContentEquals(before, bytes)
    }

    @Test fun `actual animated thumbnails release gallery permits during frame waits and on disposal`() = runBlocking(edt) {
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                writer.prepareWriteSequence(null)
                for (color in listOf(0xffff0000.toInt(), 0xff0000ff.toInt())) {
                    val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0..1) for (x in 0..2) image.setRGB(x, y, color)
                    val metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), writer.defaultWriteParam)
                    val tree = metadata.getAsTree("javax_imageio_gif_image_1.0") as javax.imageio.metadata.IIOMetadataNode
                    val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as javax.imageio.metadata.IIOMetadataNode
                    control.setAttribute("delayTime", "1000")
                    control.setAttribute("disposalMethod", "restoreToBackgroundColor")
                    metadata.setFromTree("javax_imageio_gif_image_1.0", tree)
                    writer.writeToSequence(javax.imageio.IIOImage(image, null, metadata), writer.defaultWriteParam)
                }
                writer.endWriteSequence()
            }
        } finally { writer.dispose() }
        val animation = DesktopImageTools.restore(DesktopImageTools.disguise(output.toByteArray()))
        val before = animation.copyOf(); val still = png()
        val admission = DesktopImageDecodeAdmission()
        var showStill by mutableStateOf(false)
        val scene = ImageComposeScene(400, 200, coroutineContext = edt) {
            Row {
                repeat(2) { index ->
                    DesktopHistoryThumbnail(NovelAiGenerationHistoryImage("animation-$index"), { animation }, Modifier.size(64.dp), admission)
                }
                if (showStill) DesktopHistoryThumbnail(NovelAiGenerationHistoryImage("still"), { still }, Modifier.size(64.dp), admission)
            }
        }
        try {
            // Each animation waits ten seconds on its first frame. A held lease would starve the still image.
            withTimeout(5_000) { while ((0..1).any { !visible(scene, "animation-$it") }) frame(scene) }
            showStill = true
            withTimeout(5_000) {
                while (!visible(scene, "still") || (0..1).any { !visible(scene, "animation-$it") }) frame(scene)
            }
        } finally { scene.close() }
        withTimeout(5_000) { admission.load { admission.load { Unit } } }
        assertContentEquals(before, animation)
    }
}
