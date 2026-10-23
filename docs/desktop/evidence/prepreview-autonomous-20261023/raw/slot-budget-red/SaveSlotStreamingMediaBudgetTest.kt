package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotPackageRef
import com.example.chatbar.data.local.entity.ChatMessage
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Test
import kotlin.test.*

/** Large synthetic media is written/read with fixed buffers. No images, users or providers. */
class SaveSlotStreamingMediaBudgetTest {
    private suspend fun inspect(mediaCount: Int, mediaBytes: Int): DesktopSaveSlotInspection {
        val dir = Files.createTempDirectory("slot-stream-budget-")
        val file = dir.resolve("synthetic.cbsave")
        try {
            val message = SyntheticSaveSlotCorpus.message().copy(images = (0 until mediaCount).map { "chatbar-save-slot-image:m$it" })
            val slot = SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("synthetic.cbsave", 1, imageCount = mediaCount))
            val block = ByteArray(65536) { (it % 251).toByte() }
            ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                for ((name, text) in mapOf("manifest.json" to SyntheticSaveSlotCorpus.json.encodeToString(SaveSlot.serializer(), slot),
                    "messages.jsonl" to SyntheticSaveSlotCorpus.json.encodeToString(ChatMessage.serializer(), message),
                    "rag.jsonl" to "", "voices.jsonl" to "")) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                val crc = CRC32()
                repeat(mediaBytes / block.size) { crc.update(block) }
                for (index in 0 until mediaCount) {
                    zip.putNextEntry(ZipEntry("media/images/m$index.png").apply {
                        method = ZipEntry.STORED; size = mediaBytes.toLong(); compressedSize = size; this.crc = crc.value
                    })
                    repeat(mediaBytes / block.size) { zip.write(block) }
                    zip.closeEntry()
                }
            }
            val before = DesktopArchiveFiles.hash(file, 300L * 1024 * 1024)
            val result = DesktopSaveSlotInspector().inspect(file)
            assertEquals(before, DesktopArchiveFiles.hash(file, 300L * 1024 * 1024))
            assertFalse(result.desktopCanRestore); assertFalse(result.imported)
            return result
        } finally {
            // Only this test's exact new regular file/directory, never recursive deletion.
            Files.deleteIfExists(file); Files.delete(dir)
        }
    }

    @Test fun `streamed media above old aggregate limit does not enlarge JSON or record buffers`() = runBlocking<Unit> {
        val result = inspect(9, 8 * 1024 * 1024)
        assertEquals(SlotInspectStatus.STRUCTURE_VALID, result.status)
        assertEquals(9, result.counts?.mediaFiles)
        assertTrue("RESOURCE_CODECS_NOT_VERIFIED" in result.limitations)
    }
    @Test fun `single entry budget remains unchanged`() = runBlocking<Unit> {
        val result = inspect(1, 17 * 1024 * 1024)
        assertEquals(SlotInspectStatus.UNSUPPORTED, result.status); assertEquals(SlotInspectCode.BUDGET, result.code)
    }
    @Test fun `total source budget remains unchanged`() = runBlocking<Unit> {
        val result = inspect(17, 16 * 1024 * 1024)
        assertEquals(SlotInspectStatus.UNSUPPORTED, result.status); assertEquals(SlotInspectCode.BUDGET, result.code)
    }
}
