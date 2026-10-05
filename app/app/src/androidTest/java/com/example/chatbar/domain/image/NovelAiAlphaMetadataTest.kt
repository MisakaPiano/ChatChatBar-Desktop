package com.example.chatbar.domain.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovelAiAlphaMetadataTest {
    @get:Rule val folder = TemporaryFolder()
    private val comment = """{"prompt":"中文 sky","uc":"bad","width":832,"height":1216,"steps":28,"scale":6,"sampler":"k_euler_ancestral","seed":42,"v4_prompt":{"caption":{"base_caption":"中文 sky","char_captions":[{"char_caption":"blue eyes","centers":[{"x":0.3,"y":0.7}]}]}},"v4_negative_prompt":{"caption":{"base_caption":"bad","char_captions":[{"char_caption":"bad hands"}]}}}"""
    private fun payload(objectComment: Boolean) = buildJsonObject {
        put("Source", "NovelAI Diffusion V5 657484A5")
        put("Comment", if (objectComment) Json.parseToJsonElement(comment) else JsonPrimitive(comment))
    }.toString()

    @Test fun alphaOnlyImportsStudioRegenerationAndEnhanceWithoutChangingFile() {
        for (compressed in listOf(false, true)) for (objectComment in listOf(false, true)) {
            val bytes = fixture(payload(objectComment), compressed)
            val file = folder.newFile().apply { writeBytes(bytes) }
            val studio = requireNotNull(NovelAiPngMetadataReader.readStudio(file.path))
            assertEquals("中文 sky", studio.positivePrompt)
            assertEquals("blue eyes", studio.characters.single().prompt)
            assertEquals("bad hands", studio.characters.single().negativePrompt)
            assertEquals(42L, studio.seed)
            assertEquals(NovelAiImageModel.V5_FULL, studio.settings.model)
            assertEquals("中文 sky", NovelAiPngMetadataReader.read(file.path)?.baseCaption)
            assertEquals(NovelAiImageModel.V5_FULL, NovelAiPngMetadataReader.readEnhance(file.path).settings.model)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun validFileSourceWinsAndBadTextDoesNotMaskAlpha() {
        val fileComment = comment.replace("中文 sky", "file sky")
        for (type in listOf("tEXt", "zTXt", "iTXt")) {
            val file = folder.newFile().apply { writeBytes(fixture(payload(true), text = fileComment, textType = type)) }
            assertEquals("file sky", NovelAiPngMetadataReader.readStudio(file.path)?.positivePrompt)
        }
        for (invalid in listOf("broken JSON", "{}")) {
            val file = folder.newFile().apply { writeBytes(fixture(payload(false), text = invalid)) }
            assertEquals("中文 sky", NovelAiPngMetadataReader.readStudio(file.path)?.positivePrompt)
        }
        val brokenZip = folder.newFile().apply { writeBytes(fixture(payload(false), text = "bad", textType = "broken")) }
        assertEquals("中文 sky", NovelAiPngMetadataReader.readStudio(brokenZip.path)?.positivePrompt)
    }

    @Test fun fileOnlyStillWorksAndPrivacyExportRemovesBothSources() {
        val file = folder.newFile().apply { writeBytes(fixture(null, text = comment)) }
        assertEquals("中文 sky", NovelAiPngMetadataReader.readStudio(file.path)?.positivePrompt)
        val both = folder.newFile().apply { writeBytes(fixture(payload(false), text = comment)) }
        val stripped = ImageMetadataStripper.stripToCopy(both, folder.newFolder())
        assertNull(NovelAiPngMetadataReader.readStudio(stripped.path))
        assertNull(NovelAiPngMetadataReader.read(stripped.path))
    }

    @Test fun noMetadataAndCorruptAlphaAreNotImported() {
        for (content in listOf(null, "bad JSON", "{}")) {
            val file = folder.newFile().apply { writeBytes(fixture(content)) }
            assertNull(NovelAiPngMetadataReader.readStudio(file.path))
        }
    }

    private fun fixture(alpha: String?, compressed: Boolean = true, text: String? = null, textType: String = "tEXt"): ByteArray {
        val width = 96
        val height = 128
        fun zip(bytes: ByteArray) = ByteArrayOutputStream().apply { DeflaterOutputStream(this).use { it.write(bytes) } }.toByteArray()
        val message = if (alpha == null) ByteArray(0) else ByteArrayOutputStream().apply {
            val body = if (compressed) ByteArrayOutputStream().apply {
                GZIPOutputStream(this).use { it.write(alpha.toByteArray()) }
            }.toByteArray() else alpha.toByteArray()
            write((if (compressed) "stealth_pngcomp" else "stealth_pnginfo").toByteArray())
            DataOutputStream(this).writeInt(body.size * 8)
            write(body)
        }.toByteArray()
        require(message.size * 8 <= width * height)
        val rows = ByteArrayOutputStream().apply {
            repeat(height) { y ->
                write(0)
                repeat(width) { x ->
                    val bit = x * height + y
                    val low = if (bit < message.size * 8) message[bit / 8].toInt() ushr (7 - bit % 8) and 1 else 1
                    write(byteArrayOf(128.toByte(), 144.toByte(), 160.toByte(), (254 + low).toByte()))
                }
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                val name = type.toByteArray()
                val out = DataOutputStream(this)
                out.writeInt(data.size); out.write(name); out.write(data)
                out.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
            }
            chunk("IHDR", ByteArrayOutputStream().apply {
                DataOutputStream(this).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 6, 0, 0, 0)) }
            }.toByteArray())
            if (text != null) {
                val prefix = "Comment".toByteArray() + byteArrayOf(0)
                when (textType) {
                    "zTXt" -> chunk("zTXt", prefix + byteArrayOf(0) + zip(text.toByteArray()))
                    "iTXt" -> chunk("iTXt", prefix + byteArrayOf(1, 0, 0, 0) + zip(text.toByteArray()))
                    "broken" -> chunk("zTXt", prefix + byteArrayOf(0, 1, 2, 3))
                    else -> chunk("tEXt", prefix + text.toByteArray())
                }
            }
            chunk("IDAT", zip(rows)); chunk("IEND", ByteArray(0))
        }.toByteArray()
    }
}
