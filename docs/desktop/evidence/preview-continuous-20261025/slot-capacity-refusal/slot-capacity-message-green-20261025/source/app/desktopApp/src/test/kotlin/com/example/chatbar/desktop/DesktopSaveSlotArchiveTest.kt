package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopSaveSlotArchiveTest {
    @Test fun `oversized input reports capacity before creating a preparation copy`(): Unit = runBlocking { fixture { root, archive ->
        val source = root.resolve("oversized-synthetic.cbsave")
        java.nio.channels.FileChannel.open(source, java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.SPARSE).use { channel ->
            channel.position(DesktopSaveSlotArchive.MAX_ARCHIVE); channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
        val failure = assertFailsWith<IllegalStateException> { archive.prepare(source) }
        assertTrue(failure.message.orEmpty().startsWith("SAVE_SLOT_CAPACITY_"))
        assertEquals(DesktopSaveSlotArchive.MAX_ARCHIVE + 1, Files.size(source))
        assertTrue(Files.list(root.resolve("work")).use { it.findAny().isEmpty })
    } }
    @Test fun `native archive evidence supports long local paths without weakening identity checks`(): Unit = runBlocking { fixture { root, _ ->
        var directory = root
        repeat(6) { directory = Files.createDirectory(directory.resolve("synthetic-long-path-${it}-" + "a".repeat(30))) }
        val source = directory.resolve("synthetic.txt")
        Files.writeString(source, "synthetic archive identity")
        val target = directory.resolve("copied.txt")
        val copied = DesktopArchiveFiles.copyNew(source, target, 1024)
        assertEquals(DesktopArchiveFiles.hash(source, 1024), copied)
        assertEquals(copied, DesktopArchiveFiles.hash(target, 1024))
    } }
    private suspend fun fixture(test: suspend (Path, DesktopSaveSlotArchive) -> Unit) {
        val root = Files.createTempDirectory("desktop-save-slot-archive-")
        try { test(root, DesktopSaveSlotArchive(Files.createDirectory(root.resolve("work")))) }
        finally { root.toFile().deleteRecursively() }
    }

    @Test fun `official codec streams v8 and omitted images are never read`(): Unit = runBlocking { fixture { root, archive ->
        val session = ChatSession.create("card", "Synthetic").copy(id = "s", playerName = "Synthetic player", novelAiNaturalLanguageMode = true)
        val slot = SaveSlotSessionPolicy.create(session, "Synthetic", null, null, SaveSlotImagePolicy.NONE, false)
        val message = SyntheticSaveSlotCorpus.message(images = listOf("missing-image.png"))
        val prepared = archive.create(slot, SaveSlotImagePolicy.NONE, false, { emit -> emit(message) }, {}, emptyList(),
            mediaSource = { error("omitted media must not be read") })
        assertEquals(SlotInspectFormat.ZIP_V8, prepared.format)
        assertEquals(1, prepared.slot.packageRef!!.messageCount)
        assertTrue(prepared.slot.messages.isEmpty())
        assertTrue(prepared.slot.novelAiNaturalLanguageMode)
        val exported = root.resolve("export.cbsave")
        archive.export(prepared, exported)
        val imported = archive.prepare(exported)
        assertEquals(prepared.sha256, imported.sha256)
        val codec = SaveSlotPackageStorage(root.resolve("reader").toFile(), { _, _ -> error("not encoding") })
        val local = Files.newInputStream(imported.file).use { codec.importPackage(it, "target") { name -> name } }.slot
        codec.openReader(local).use { reader ->
            reader.validate()
            val messages = mutableListOf<ChatMessage>()
            reader.streamMessages("target") { messages += it }
            assertEquals("target", messages.single().sessionId)
            assertEquals(message.sourceTurnId, messages.single().sourceTurnId)
            assertTrue(messages.single().images.single().startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX))
        }
        val before = Files.readAllBytes(exported)
        assertFailsWith<IllegalStateException> { archive.export(prepared, exported) }
        assertContentEquals(before, Files.readAllBytes(exported))
    } }

    @Test fun `all legacy versions remain independent validated copies`(): Unit = runBlocking { fixture { root, archive ->
        for (version in 1..7) {
            val file = SyntheticSaveSlotCorpus.legacy(root.resolve("legacy-$version.json"),
                SyntheticSaveSlotCorpus.slot(version).copy(messages = listOf(SyntheticSaveSlotCorpus.message())))
            val before = Files.readAllBytes(file)
            val prepared = archive.prepare(file)
            assertEquals(version, prepared.slot.schemaVersion)
            assertEquals(SlotInspectFormat.LEGACY_JSON, prepared.format)
            assertContentEquals(before, Files.readAllBytes(file))
            Files.writeString(prepared.file, "changed staged archive")
            assertFailsWith<IllegalStateException> { archive.verify(prepared) }
        }
    } }

    @Test fun `compressed image is bounded JPEG and exported package keeps the same pixels`(): Unit = runBlocking { fixture { root, archive ->
        val image = BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB)
        val source = root.resolve("image.png")
        try { ImageIO.write(image, "png", source.toFile()) } finally { image.flush() }
        val prepared = archive.create(SyntheticSaveSlotCorpus.slot(), SaveSlotImagePolicy.COMPRESSED, false,
            { emit -> emit(SyntheticSaveSlotCorpus.message(images = listOf("image"))) }, {}, emptyList(), { source })
        ZipFile(prepared.file.toFile()).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.startsWith("media/images/") }
            assertTrue(entry.name.endsWith(".jpg"))
            val decoded = zip.getInputStream(entry).use(ImageIO::read)
            try { assertTrue(decoded.width <= 1600); assertEquals(2, decoded.width / decoded.height) }
            finally { decoded.flush() }
        }
        val output = root.resolve("compressed.cbsave")
        archive.export(prepared, output)
        assertEquals(prepared.sha256, DesktopArchiveFiles.hash(output, DesktopSaveSlotArchive.MAX_ARCHIVE))
    } }

    @Test fun `session restore changes only official archive settings`(): Unit = runBlocking { fixture { _, _ ->
        val target = ChatSession.create("keep-card", "Keep title").copy(contextWindowSize = 20)
        val slot = SyntheticSaveSlotCorpus.slot(7).copy(playerName = "Restored", contextWindowSize = null,
            novelAiNaturalLanguageMode = true, automaticImageGenerationEnabled = true)
        val restored = SaveSlotSessionPolicy.restore(target, slot, null)
        assertEquals(target.id, restored.id); assertEquals(target.characterCardId, restored.characterCardId)
        assertEquals(target.createdAt, restored.createdAt)
        assertEquals(20, restored.contextWindowSize); assertEquals("Restored", restored.playerName)
        assertTrue(restored.novelAiNaturalLanguageMode); assertTrue(restored.automaticImageGenerationEnabled)
    } }
}
