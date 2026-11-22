package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.desktop.backup.AppBackupSelection
import com.example.chatbar.desktop.backup.BackupSessionChoice
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.*

class DesktopAlternativeUnlinkRecoveryTest {
    private enum class LockedRow { VOICE, ANCHOR, MESSAGE, INDEX, SESSION }

    @Test fun `locked voice row rolls back both versions and can be retried through Primary`() = runBlocking {
        metadataRetry(LockedRow.VOICE)
    }

    @Test fun `locked anchor row rolls back earlier voice unlink and the last segment edit can be retried`() = runBlocking {
        metadataRetry(LockedRow.ANCHOR)
    }

    @Test fun `locked source replacement leaves no partial message bytes and can be retried`() = runBlocking {
        metadataRetry(LockedRow.MESSAGE)
    }

    @Test fun `locked message index and session preview are transactional with the source`() = runBlocking {
        metadataRetry(LockedRow.INDEX)
        metadataRetry(LockedRow.SESSION)
    }

    @Test fun `last alternative keeps the whole reply if its recording is locked and preserves retained media on retry`() = runBlocking {
        wholeReplyRetry(lastOnly = true)
    }

    @Test fun `delete all alternatives keeps the whole reply if its recording is locked and can be retried`() = runBlocking {
        wholeReplyRetry(lastOnly = false)
    }

    private suspend fun wholeReplyRetry(lastOnly: Boolean) =
        useRoot { fixture ->
            val app = fixture.app()
            try {
                val (message, voice) = fixture.seed(app, wholeReply = true, lastOnly = lastOnly)
                val controller = app.primaryChatController
                suspend fun remove() = if (lastOnly) controller.deleteAssistantAlternative(message, "a")
                    else controller.deleteMessage(message.id, message)
                denyDelete(fixture.root.resolve("entities/generated_voice_messages/${voice.id}.json")) {
                    assertFalse(remove())
                }
                assertEquals(message, ChatRepository(JsonFileStorage(fixture.root)).getMessage(message.id, message.sessionId))
                assertEquals(voice, VoiceMessageRepository(JsonFileStorage(fixture.root), strictReads = true).get(voice.id))
                fixture.backupBoth(app, "last-locked")
                assertTrue(remove(), controller.state.value.error)
                val cold = ChatRepository(JsonFileStorage(fixture.root))
                assertEquals(listOf("Preceding user"), cold.getMessagesReadOnly("s").map { it.displayContent })
                val summary = assertNotNull(cold.getSession("s"))
                assertEquals("Preceding user", summary.lastMessagePreview)
                assertTrue(summary.sourceTurnTombstones.any { it.sourceTurnId == "removed-source" })
                assertFalse(Files.exists(Path.of(voice.audioPath)))
                assertTrue(Files.isRegularFile(fixture.root.resolve(SHARED_IMAGE)))
                assertFalse(Files.exists(fixture.root.resolve(UNIQUE_IMAGE)))
                fixture.assertNoReplyJournalWork()
                fixture.backupBoth(app, "last-retried")
            } finally { app.close() }
        }

    @Test fun `locked MP3 remains pending until actual app startup retries after unlocking`() = runBlocking {
        audioRetry(restoreVoice = false)
    }

    @Test fun `pending audio retry respects a restored recording owner`() = runBlocking {
        audioRetry(restoreVoice = true)
    }

    @Test fun `failed strict staging releases isolated work without publishing source or audio receipt`() = runBlocking {
        useRoot { fixture ->
            val app = fixture.app()
            try {
                val (message, voice) = fixture.seed(app)
                val corrupt = fixture.root.resolve("entities/generated_voice_messages/z-invalid.json")
                Files.writeString(corrupt, "synthetic invalid JSON")
                try { assertFalse(app.primaryChatController.deleteAssistantAlternative(message, "a")) }
                finally { Files.delete(corrupt) }
                val work = fixture.root.resolve("backups/.save-slot-work")
                Files.newDirectoryStream(work).use { assertFalse(it.iterator().hasNext()) }
                assertTrue(fixture.receipts().isEmpty())
                assertEquals(message, app.chatRepository.getMessage(message.id, message.sessionId))
                assertEquals(voice, app.voiceRepository.get(voice.id))
                fixture.backupBoth(app, "stage-refusal")
            } finally { app.close() }
        }
    }

    @Test fun `a busy normal operation refuses without waiting and confirmed retry later commits`() = runBlocking {
        useRoot { fixture ->
            val app = fixture.app()
            try {
                val (message, _) = fixture.seed(app)
                val controller = app.primaryChatController
                coroutineScope {
                    val active = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val normal = launch {
                        app.dataOperationCoordinator.withNormalOperation {
                            active.complete(Unit)
                            release.await()
                        }
                    }
                    active.await()
                    val attempted = async { controller.deleteAssistantAlternative(message, "a") }
                    try { assertFalse(withTimeout(3_000) { attempted.await() }) }
                    finally { release.complete(Unit) }
                    normal.join()
                }
                assertEquals(message, app.chatRepository.getMessage(message.id, message.sessionId))
                assertTrue(controller.deleteAssistantAlternative(message, "a"), controller.state.value.error)
                fixture.backupBoth(app, "busy-retry")
            } finally { app.close() }
        }
    }

    private suspend fun metadataRetry(row: LockedRow) = useRoot { fixture ->
        val app = fixture.app()
        try {
            val (message, voice) = fixture.seed(app)
            val root = fixture.root
            val controller = app.primaryChatController
            suspend fun remove() = if (row == LockedRow.ANCHOR) {
                controller.editMessageSegment(message.id, 0, message.displayContent.length, "", message.displayContent, message)
            } else controller.deleteAssistantAlternative(message, "a")
            val lockPath = when (row) {
                LockedRow.VOICE -> root.resolve("entities/generated_voice_messages/${voice.id}.json")
                LockedRow.ANCHOR -> fixture.anchorPath
                LockedRow.MESSAGE -> root.resolve("entities/chat_messages/s_m.json")
                LockedRow.INDEX -> root.resolve("entities/chat_message_indexes/s.json")
                LockedRow.SESSION -> root.resolve("entities/chat_sessions/s.json")
            }
            denyDelete(lockPath) {
                assertFalse(remove())
                assertTrue(Files.exists(lockPath))
            }
            val coldStorage = JsonFileStorage(root)
            assertEquals(message, ChatRepository(coldStorage).getMessage(message.id, message.sessionId))
            assertEquals(message, controller.state.value.messages.single())
            assertEquals(voice, VoiceMessageRepository(coldStorage, strictReads = true).get(voice.id))
            assertEquals(voice, app.voiceRepository.get(voice.id))
            assertTrue(Files.exists(fixture.anchorPath))
            assertTrue(Files.exists(Path.of(voice.audioPath)))
            Files.newDirectoryStream(lockPath.parent, "*.part").use { assertFalse(it.iterator().hasNext()) }
            assertFalse(DesktopSessionCommitJournal(root).hasPending())
            fixture.assertNoReplyJournalWork()
            assertTrue(fixture.receipts().isEmpty())
            fixture.backupBoth(app, "rolled-back")

            assertTrue(remove(), controller.state.value.error)
            val afterStorage = JsonFileStorage(root)
            val after = assertNotNull(ChatRepository(afterStorage).getMessage(message.id, message.sessionId))
            assertEquals(listOf("b"), after.alternativeVersionIds)
            assertNull(VoiceMessageRepository(afterStorage, strictReads = true).get(voice.id))
            assertNull(app.voiceRepository.get(voice.id))
            assertFalse(Files.exists(fixture.anchorPath))
            assertFalse(Files.exists(Path.of(voice.audioPath)))
            assertEquals(after.displayContent, assertNotNull(ChatRepository(afterStorage).getSession("s")).lastMessagePreview)
            val index = assertNotNull(afterStorage.loadEntity("chat_message_indexes", "s", ChatMessageIndex.serializer()))
            assertEquals(after.displayContent.length, index.entries.single().contentChars)
            assertFalse(DesktopSessionCommitJournal(root).hasPending())
            fixture.assertNoReplyJournalWork()
            assertTrue(fixture.receipts().isEmpty())
            fixture.backupBoth(app, "retried")
        } finally { app.close() }
    }

    private suspend fun audioRetry(restoreVoice: Boolean) = useRoot { fixture ->
        val app = fixture.app()
        val oldVoice: GeneratedVoiceMessage
        try {
            val (message, voice) = fixture.seed(app)
            oldVoice = voice
            val controller = app.primaryChatController
            val audio = Path.of(voice.audioPath)
            denyDelete(audio) {
                assertTrue(controller.deleteAssistantAlternative(message, "a"), controller.state.value.error)
                assertTrue(controller.state.value.status.orEmpty().contains("重试"))
                assertTrue(Files.exists(audio))
                assertEquals(1, fixture.receipts().size)
            }
            assertEquals(listOf("b"), assertNotNull(app.chatRepository.getMessage(message.id, message.sessionId)).alternativeVersionIds)
            assertNull(VoiceMessageRepository(JsonFileStorage(fixture.root), strictReads = true).get(voice.id))
            assertFalse(Files.exists(fixture.anchorPath))
            fixture.backupBoth(app, "pending-audio")
            if (restoreVoice) {
                val restored = voice.copy(id = "restored-take", messageVersionId = "b", anchorId = null)
                app.voiceRepository.restoreForSession("s", listOf(restored), listOf(assertNotNull(app.chatRepository.getMessage("m", "s"))))
            }
        } finally { app.close() }

        val reopened = fixture.app()
        try {
            reopened.initializePersistentState()
            assertEquals(restoreVoice, Files.exists(Path.of(oldVoice.audioPath)))
            assertEquals(restoreVoice, reopened.voiceRepository.get("restored-take") != null)
            if (restoreVoice) {
                assertContentEquals(fishPreviewMp3(), Files.readAllBytes(Path.of(oldVoice.audioPath)))
            }
            assertTrue(fixture.receipts().isEmpty())
            fixture.backupBoth(reopened, "startup-retry")
        } finally { reopened.close() }
    }

    private suspend fun denyDelete(path: Path, block: suspend () -> Unit) {
        val api = Kernel32.INSTANCE
        val handle = api.CreateFile(path.toString(), WinNT.GENERIC_READ, WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE,
            null, WinNT.OPEN_EXISTING, WinNT.FILE_ATTRIBUTE_NORMAL, null)
        assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle)
        try { block() } finally { assertTrue(api.CloseHandle(handle)) }
    }

    private suspend fun useRoot(block: suspend (Fixture) -> Unit) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val parent = Files.createTempDirectory("voice-unlink-recovery-")
        val root = Files.createDirectory(parent.resolve("data"))
        val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json"))
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        try { block(Fixture(parent, resolution)) }
        finally { owner.close(); parent.toFile().deleteRecursively() }
    }

    private class Fixture(val parent: Path, val resolution: DesktopDataRootResolution.Resolved) {
        val root get() = resolution.appDataRoot
        val anchorPath: Path get() {
            val id = UUID.nameUUIDFromBytes("m\u0000a".toByteArray(Charsets.UTF_8))
            return root.resolve("entities/voice_anchor_states/$id.json")
        }
        fun app() = DesktopAppContainer(resolution, secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        fun receipts(): List<Path> {
            val home = root.resolve("backups/.session-deletion-cleanup")
            return if (Files.isDirectory(home)) Files.newDirectoryStream(home, "*.json").use { it.toList() } else emptyList()
        }
        fun assertNoReplyJournalWork() {
            val home = root.resolve("backups/.restore-session.tmp")
            Files.newDirectoryStream(home, "*-reply-*").use { assertFalse(it.iterator().hasNext()) }
        }
        suspend fun seed(app: DesktopAppContainer, wholeReply: Boolean = false, lastOnly: Boolean = false): Pair<ChatMessage, GeneratedVoiceMessage> {
            app.characterRepository.save(CharacterCard("card", "Synthetic card", createdAt = 1, updatedAt = 1))
            app.chatRepository.createSession(ChatSession("s", "card", "Synthetic session", createdAt = 1, updatedAt = 1))
            if (wholeReply) {
                for (reference in listOf(SHARED_IMAGE, UNIQUE_IMAGE)) {
                    val path = root.resolve(reference)
                    Files.createDirectories(path.parent)
                    check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "PNG", path.toFile()))
                }
                app.chatRepository.addMessage(ChatMessage("user", "s", MessageRole.USER, "Preceding user",
                    images = listOf(SHARED_IMAGE), createdAt = 1, updatedAt = 1, orderKey = 1))
            }
            val a = "Synthetic voice A"
            val message = app.chatRepository.addMessage(ChatMessage("m", "s", MessageRole.ASSISTANT, a,
                alternatives = if (lastOnly) listOf(a) else listOf(a, "Sibling B"),
                alternativeVersionIds = if (lastOnly) listOf("a") else listOf("a", "b"), currentAlternativeIndex = 0,
                images = if (wholeReply) listOf(SHARED_IMAGE, UNIQUE_IMAGE) else emptyList(),
                sourceTurnId = if (wholeReply) "removed-source" else null, sourceTurnOrder = if (wholeReply) 2 else null,
                currentAlternativeVersionId = "a", createdAt = 2, updatedAt = 2, orderKey = 2))
            val anchor = app.voiceRepository.ensureAnchorsForVersion(message, "a", a).single().anchor
            val audio = app.voiceAudioStore.persist("s", "delete-test", fishPreviewMp3().inputStream())
            val voice = GeneratedVoiceMessage("v", "s", message.id, "a", anchor.id, 0, "NARRATION", "", a,
                taggedText = a, characterId = "card", characterName = "Synthetic card",
                voice = FishAudioVoiceBinding("fixed", "Synthetic"), fishModelId = "synthetic",
                audioPath = audio.path, durationMs = audio.durationMs, byteLength = audio.byteLength, createdAt = 1, updatedAt = 1,
                desktopVersionOwnershipUncertain = false)
            app.voiceRepository.save(voice)
            app.primaryChatController.refresh()
            app.primaryChatController.selectSession("s")
            return message to voice
        }
        suspend fun backupBoth(app: DesktopAppContainer, stage: String) {
            val backup = DesktopFullBackupService(root, app.dataOperationCoordinator, DesktopAudioMaintenance({ DesktopAudioFocus() }) {})
            val selected = AppBackupSelection(characterIds = setOf("card"), sessions = mapOf("s" to BackupSessionChoice()))
            for ((name, selection) in listOf("full" to null, "selected" to selected)) {
                val archive = parent.resolve("$stage-$name.cbbackup")
                backup.exportModern(archive, null, selection = selection)
                assertTrue(Files.size(archive) > 0L)
            }
        }
    }

    private companion object {
        const val SHARED_IMAGE = "images/card_1_p7aa.png"
        const val UNIQUE_IMAGE = "images/card_1_p7bb.png"
    }
}
