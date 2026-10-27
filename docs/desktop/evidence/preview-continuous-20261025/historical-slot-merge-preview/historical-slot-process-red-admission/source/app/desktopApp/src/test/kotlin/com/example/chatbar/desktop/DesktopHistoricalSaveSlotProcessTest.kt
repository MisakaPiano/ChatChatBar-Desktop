package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

/** Independent JVMs use actual SaveSlot service/journal and strict repositories, no GUI/Provider. */
class DesktopHistoricalSaveSlotProcessTest {
    @Test fun historicalRecordingSurvivesActualImportRestoreProcessExitReopenAndExport() = runBlocking<Unit> {
        val home = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/historical-process").toAbsolutePath()), "case-")
        val root = Files.createDirectory(home.resolve("data"))
        val audio = fishPreviewMp3()
        val voice = GeneratedVoiceMessage(id = "old-voice", sessionId = "s", messageId = "m", messageVersionId = "pruned-version",
            anchorId = null, sourceOrder = 1, sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic",
            sourceText = "Older synthetic recording", taggedText = "Older synthetic recording", characterId = "c", characterName = "Synthetic",
            voice = FishAudioVoiceBinding("fake-reference", "Synthetic"), fishModelId = "synthetic", audioPath = "chatbar-save-slot-audio:audio",
            durationMs = 100, byteLength = audio.size.toLong(), createdAt = 1, updatedAt = 1)
        val source = SyntheticSaveSlotCorpus.archive(home.resolve("source.cbsave"), voices = listOf(voice), media = mapOf("media/audio/audio.mp3" to audio))
        val before = Files.readAllBytes(source)
        suspend fun child(mode: String) {
            val cp = requireNotNull(System.getProperty("ccb.readonly.classpath"))
            val command = listOf(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
                "-cp", cp, HistoricalSaveSlotProcess::class.java.name, mode, home.toString())
            val output = home.resolve("$mode.log")
            val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile())
            listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").forEach(builder.environment()::remove)
            val process = builder.start()
            process.outputStream.close()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "SYNTHETIC_CHILD_TIMEOUT")
                assertEquals(0, process.exitValue(), "SYNTHETIC_CHILD_FAILED_${mode}: " + Files.readString(output).take(500))
                assertTrue(Files.readString(output).contains("HISTORICAL_${mode.uppercase()}_PASS"))
            } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
        }
        child("restore")
        child("reopen")
        child("export")
        val output = home.resolve("export.cbsave")
        assertTrue(Files.isRegularFile(output))
        val json = Json { ignoreUnknownKeys = false }
        java.util.zip.ZipFile(output.toFile()).use { zip ->
            val records = zip.getInputStream(zip.getEntry("voices.jsonl")).bufferedReader().use { it.readLines() }.filter(String::isNotBlank)
            assertEquals(1, records.size)
            val retained = json.decodeFromString(GeneratedVoiceMessage.serializer(), records.single())
            assertEquals("pruned-version", retained.messageVersionId)
            assertEquals(voice.sourceText, retained.sourceText)
            assertEquals(voice.createdAt, retained.createdAt)
            assertEquals(voice.byteLength, retained.byteLength)
        }
        assertContentEquals(before, Files.readAllBytes(source))
        assertTrue(Files.isDirectory(root.resolve("backups/.session-transactions")) ||
            Files.list(root.resolve("backups")).use { it.findAny().isPresent })
    }
}

internal object HistoricalSaveSlotProcess {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        val mode = args[0]; val home = Path.of(args[1]); val root = home.resolve("data")
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, home.resolve("bootstrap.json")))).ownership
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        try {
            DesktopSessionCommitJournal(root).recover()
            val storage = JsonFileStorage(root, gate)
            val chats = ChatRepository(storage)
            val voices = VoiceMessageRepository(storage, strictReads = true)
            val service = DesktopSaveSlotService(root, gate, DesktopAudioMaintenance({ focus }) {},
                object : DesktopSessionReservation { override suspend fun <T> run(sessionId: String, operation: suspend () -> T) = operation() },
                chats, voices, { null }, { raw -> if (Path.of(raw).isAbsolute) DesktopVoiceAudioStore(root).verify(raw) else root.resolve(raw) })
            when (mode) {
                "restore" -> {
                    chats.createSession(ChatSession("s", "c", "Synthetic", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1))
                    chats.createSession(ChatSession("pc", "pc-card", "PC independent", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1))
                    chats.addMessage(ChatMessage("pc-message", "pc", MessageRole.USER, "Independent PC text", createdAt = 1, updatedAt = 1))
                    val imported = service.import(home.resolve("source.cbsave"), "s")
                    service.restore(imported.id, "s")
                }
                "reopen" -> {
                    val message = chats.getMessages("s").single()
                    val voice = voices.listForSession("s").single()
                    assertEquals("pruned-version", voice.messageVersionId)
                    assertTrue(voices.listForCurrentVersion(message).isEmpty())
                    assertContentEquals(fishPreviewMp3(), Files.readAllBytes(DesktopVoiceAudioStore(root).verify(voice.audioPath)))
                    assertEquals("Independent PC text", chats.getMessages("pc").single().content)
                    assertTrue(service.list("s").size >= 2) // Imported archive plus retained before-change safety slot.
                }
                "export" -> service.export(service.list("s").first { it.name == "Synthetic" }.id, home.resolve("export.cbsave"))
                else -> error("SYNTHETIC_MODE")
            }
            println("HISTORICAL_${mode.uppercase()}_PASS")
        } finally { focus.closeAndDrain(); gate.closeAndDrain(); owner.close() }
    }
}
