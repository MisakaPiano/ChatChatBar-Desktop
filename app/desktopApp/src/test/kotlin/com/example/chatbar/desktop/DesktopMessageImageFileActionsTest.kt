@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopMessageImageFileActionsTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `message viewer saves and copies actual 48 MP JPEG and announces bounded PNG and bitmap sizes`(): Unit = runBlocking(edt) {
        val work = Files.createTempDirectory("message-image-exports-").toRealPath()
        val data = Files.createDirectories(work.resolve("data/images")).parent
        val original = data.resolve("images/camera.png") // File type must come from content.
        val full = work.resolve("actual-original.jpeg")
        val png = work.resolve("bounded-copy.png")
        val requestedTitles = mutableListOf<String>()
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? = error("unexpected open picker")
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("requires new file picker")
            override fun pickNewSaveFile(type: DesktopFileType, suggestedName: String, title: String): Path {
                requestedTitles += title
                return when (type.defaultExtension) {
                    "png" -> png
                    "jpg" -> {
                        assertEquals("camera.jpg", suggestedName)
                        assertEquals("JPEG", type.description)
                        full
                    }
                    else -> error("wrong type $type")
                }
            }
        }
        val clipboard = Clipboard("local test clipboard")
        val cache = DesktopImageClipboardFiles(work.resolve("clipboard"))
        try {
            val camera = BufferedImage(8000, 6000, BufferedImage.TYPE_BYTE_GRAY).apply {
                raster.setSample(4000, 3000, 0, 190)
            }
            try { assertTrue(ImageIO.write(camera, "jpeg", original.toFile())) } finally { camera.flush() }
            val originalHash = hash(original)
            val byteCount = Files.size(original)
            val store = DesktopCharacterResourceStore(data)
            val scene = ImageComposeScene(820, 200) {
                DesktopMessageImageFileActions("images/camera.png", store, picker, { clipboard }, { cache })
            }
            try {
                val expected = "2000 × 1500 px"
                suspend fun await(label: String, isButton: Boolean = false): SemanticsNode = withTimeout(30_000) {
                    var node = scene.find(label, isButton)
                    while (node == null) {
                        scene.render().close()
                        delay(15)
                        node = scene.find(label, isButton)
                    }
                    node
                }
                suspend fun click(label: String) {
                    val node = await(label, isButton = true)
                    assertTrue(node.boundsInRoot.width > 0 && node.boundsInRoot.bottom <= 200f)
                    assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                }

                await("原文件 · JPEG · 8000 × 6000 px · $byteCount 字节")
                click("另存 PNG 副本 · $expected")
                await("已保存 PNG 副本 · $expected")
                ImageIO.read(png.toFile()).let { raster ->
                    try { assertEquals(2000 to 1500, raster.width to raster.height) } finally { raster.flush() }
                }

                click("复制位图 · $expected")
                await("已复制位图 · $expected")
                val bitmap = clipboard.getData(DataFlavor.imageFlavor) as BufferedImage
                assertEquals(2000 to 1500, bitmap.width to bitmap.height)

                click("另存原文件")
                await("原文件已保存")
                assertContentEquals(originalHash, hash(full))
                click("复制原文件")
                await("原文件已复制，可粘贴为文件")
                assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
                val clip = (clipboard.getData(DataFlavor.javaFileListFlavor) as List<*>).single() as File
                assertTrue(clip.toPath().startsWith(work.resolve("clipboard")))
                assertEquals("image.jpg", clip.name)
                assertContentEquals(originalHash, hash(clip.toPath()))
                assertContentEquals(originalHash, hash(original))
                assertEquals(listOf("另存 PNG 副本 · $expected", "另存原文件"), requestedTitles)
            } finally { scene.close() }
        } finally { work.toFile().deleteRecursively() }
    }

    @Test fun `original export protects the data root foreign destinations and changed sources`() {
        val work = Files.createTempDirectory("message-original-custody-").toRealPath()
        try {
            val data = Files.createDirectories(work.resolve("data/images")).parent
            val source = data.resolve("images/small.png")
            val raster = BufferedImage(10, 12, BufferedImage.TYPE_INT_RGB)
            try { assertTrue(ImageIO.write(raster, "png", source.toFile())) } finally { raster.flush() }
            val originalHash = hash(source)
            val opened = DesktopCharacterResourceStore(data).openMessageImageFile("images/small.png")
            val foreign = Files.write(work.resolve("foreign.png"), byteArrayOf(4, 2))
            assertFails { opened.saveOriginal(foreign) }
            assertContentEquals(byteArrayOf(4, 2), Files.readAllBytes(foreign))
            val inData = data.resolve("images/new.png")
            assertFails { opened.saveOriginal(inData) }
            assertFalse(Files.exists(inData))
            assertFails { opened.saveOriginal(work.resolve("wrong.jpg")) }
            assertContentEquals(originalHash, hash(source))

            Files.write(source, byteArrayOf(1, 2, 3, 4))
            val target = work.resolve("rejected.png")
            assertFails { opened.saveOriginal(target) }
            assertFalse(Files.exists(target))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(source))
            assertTrue(Files.list(work).use { entries -> entries.noneMatch { it.toString().endsWith(".part") } })
        } finally { work.toFile().deleteRecursively() }
    }

    private fun hash(file: Path): ByteArray = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))

    private fun ImageComposeScene.find(label: String, isButton: Boolean): SemanticsNode? {
        fun walk(node: SemanticsNode): Sequence<SemanticsNode> = sequenceOf(node) + node.children.asSequence().flatMap(::walk)
        return semanticsOwners.asSequence().flatMap { walk(it.rootSemanticsNode) }.firstOrNull { node ->
            if (isButton) label in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                node.config.contains(SemanticsActions.OnClick)
            else node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }
    }
}
