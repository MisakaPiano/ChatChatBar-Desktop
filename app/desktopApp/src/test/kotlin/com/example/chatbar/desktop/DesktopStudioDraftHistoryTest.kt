package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopStudioDraftHistoryTest {
    private fun NovelAiStudioDraft.content() = copy(contentRevision = 0, promptContentRevision = 0, updatedAt = 0)

    @Test fun `mixed draft operations undo and redo many steps persist cursor and truncate only the new branch`() = runBlocking {
        val root = Files.createTempDirectory("studio-multistep-")
        try {
            val storage = JsonFileStorage(root)
            val repository = DesktopStudioHistoryRepository(storage)
            val snapshots = mutableListOf(repository.loadDraft().content())
            repository.initializeHistory()
            val a = NovelAiCharacterPromptDraft(id = "a", prompt = "person A")
            val b = NovelAiCharacterPromptDraft(id = "b", prompt = "person B")
            val changes = listOf<(NovelAiStudioDraft) -> NovelAiStudioDraft>(
                { it.copy(basePrompt = "synthetic source text") },
                { it.withActiveSettings(it.activeSettings.copy(steps = 19)) },
                { it.copy(characters = listOf(a, b)) },
                { it.copy(characters = it.characters.reversed()) },
                { it.copy(characters = it.characters.filterNot { person -> person.id == "a" }) },
                { it.copy(basePrompt = "", negativePrompt = "", characters = emptyList()) },
            )
            for (change in changes) snapshots += repository.updateDraft(transform = change).content()
            for (expected in snapshots.dropLast(1).reversed()) {
                assertTrue(repository.replay(false)); assertEquals(expected, repository.loadDraft().content())
            }
            assertFalse(repository.replay(false))
            val restarted = DesktopStudioHistoryRepository(JsonFileStorage(root))
            restarted.initializeHistory()
            for (expected in snapshots.drop(1)) {
                assertTrue(restarted.replay(true)); assertEquals(expected, restarted.loadDraft().content())
            }
            assertFalse(restarted.replay(true))
            assertTrue(restarted.replay(false)); assertTrue(restarted.replay(false))
            restarted.updateDraft { it.copy(extraPrompt = "new branch") }
            assertFalse(restarted.replay(true)); assertFalse(restarted.historyAvailability.value.redo)
            val reopened = DesktopStudioHistoryRepository(JsonFileStorage(root))
            reopened.initializeHistory(); assertFalse(reopened.replay(true))
            assertTrue(reopened.replay(false)); assertEquals(snapshots[4], reopened.loadDraft().content())
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `typing merges only adjacent same field steps and respects explicit local replay boundaries`() = runBlocking {
        val root = Files.createTempDirectory("studio-typing-")
        var nanos = 1_000_000_000L
        try {
            val r = DesktopStudioHistoryRepository(JsonFileStorage(root)) { nanos }
            r.loadDraft(); r.initializeHistory()
            r.updateDraft { it.copy(basePrompt = "a") }; nanos += 30_000_000
            r.updateDraft { it.copy(basePrompt = "ab") }
            r.updateDraft { it.copy(stylePrompt = "x") }
            assertTrue(r.replay(false)); assertEquals("ab", r.loadDraft().basePrompt); assertEquals("", r.loadDraft().stylePrompt)
            assertTrue(r.replay(false)); assertEquals("", r.loadDraft().basePrompt)
            r.replay(true); r.breakTypingGroup()
            r.updateDraft { it.copy(basePrompt = "a") }
            assertTrue(r.replay(false)); assertEquals("ab", r.loadDraft().basePrompt)
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `write ahead recovery chooses actual committed draft and retains interrupted alternatives`() = runBlocking {
        for (written in listOf(false, true)) {
            val root = Files.createTempDirectory("studio-history-intent-")
            try {
                val storage = JsonFileStorage(root)
                val r = DesktopStudioHistoryRepository(storage); val before = r.loadDraft().content()
                r.initializeHistory()
                val after = before.copy(basePrompt = "pending user edit")
                val old = DesktopStudioHistoryTimeline(listOf(before))
                val next = DesktopStudioHistoryTimeline(listOf(before, after), 1)
                storage.saveSingleton(DesktopStudioHistoryRepository.ENTITY, DesktopStudioHistoryJournal(old, next), DesktopStudioHistoryJournal.serializer())
                if (written) storage.saveSingleton("novelai_studio_draft", after, NovelAiStudioDraft.serializer())
                val reopened = DesktopStudioHistoryRepository(JsonFileStorage(root)); reopened.initializeHistory()
                assertEquals(written, reopened.historyAvailability.value.undo)
                assertEquals(if (written) after else before, reopened.loadDraft().content())
                assertNull(storage.loadSingleton(DesktopStudioHistoryRepository.ENTITY, DesktopStudioHistoryJournal.serializer())!!.pending)
            } finally { check(root.toFile().deleteRecursively()) }
        }
    }

    @Test fun `equal length paste and explicit replace cannot merge into adjacent typing`() = runBlocking {
        val root = Files.createTempDirectory("studio-paste-boundary-")
        try {
            val r = DesktopStudioHistoryRepository(JsonFileStorage(root)) { 1_000_000_000L }
            r.loadDraft(); r.initializeHistory()
            r.updateDraft { it.copy(basePrompt = "a".repeat(200)) }
            r.updateDraft { it.copy(basePrompt = it.basePrompt + "b") }
            r.updateDraft { it.copy(basePrompt = "z".repeat(201)) }
            r.updateDraft(resetPromptEditors = true) { it.copy(basePrompt = it.basePrompt + "x") }
            assertTrue(r.replay(false)); assertEquals("z".repeat(201), r.loadDraft().basePrompt)
            assertTrue(r.replay(false)); assertEquals("a".repeat(200) + "b", r.loadDraft().basePrompt)
            assertTrue(r.replay(false)); assertEquals("a".repeat(200), r.loadDraft().basePrompt)
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `corrupt history refuses edit and preserves draft and original journal`() = runBlocking {
        val root = Files.createTempDirectory("studio-history-corrupt-")
        try {
            val storage = JsonFileStorage(root); val r = DesktopStudioHistoryRepository(storage)
            val before = r.loadDraft()
            val journal = root.resolve("entities/${DesktopStudioHistoryRepository.ENTITY}.json")
            Files.writeString(journal, "{broken")
            assertFails { r.updateDraft { it.copy(basePrompt = "must not publish") } }
            assertEquals(before, r.loadDraft()); assertEquals("{broken", Files.readString(journal))
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `bounded history retains only metadata and resource references through cleanup and reopen`() = runBlocking {
        val root = Files.createTempDirectory("studio-history-custody-")
        try {
            val storage = JsonFileStorage(root); val r = DesktopStudioHistoryRepository(storage)
            r.loadDraft(); r.initializeHistory()
            Files.createDirectory(root.resolve("images")); val image = root.resolve("images/ref.png")
            Files.write(image, byteArrayOf(1, 2, 3))
            r.updateDraft { it.copy(imageGuidance = it.imageGuidance.copy(baseImage = NovelAiStudioAssetRef(path = "images/ref.png"))) }
            r.updateDraft { it.copy(imageGuidance = it.imageGuidance.copy(baseImage = null)) }
            val cleaner = DesktopOwnedImageCleanup(root, DesktopCharacterResourceStore(root), DesktopDataOperationCoordinator())
            cleaner.deleteUnreferenced(listOf("images/ref.png")); assertTrue(Files.exists(image))
            assertTrue(r.replay(false)); assertEquals("images/ref.png", r.loadDraft().imageGuidance.baseImage!!.path)
            r.replay(true)
            repeat(110) { i -> r.updateDraft { it.withActiveSettings(it.activeSettings.copy(seed = i.toLong())) } }
            val stored = storage.loadSingleton(DesktopStudioHistoryRepository.ENTITY, DesktopStudioHistoryJournal.serializer())!!
            assertTrue(stored.committed.entries.size <= DesktopStudioHistoryRepository.MAX_ENTRIES)
            cleaner.deleteUnreferenced(listOf("images/ref.png")); assertFalse(Files.exists(image))
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `large Vibe encoding stays content addressed across durable undo redo`() = runBlocking {
        val root = Files.createTempDirectory("studio-history-vibe-")
        try {
            val storage = JsonFileStorage(root); val r = DesktopStudioHistoryRepository(storage)
            r.loadDraft(); r.initializeHistory()
            val encoding = "synthetic-encoding-".repeat(400)
            r.updateDraft { it.copy(imageGuidance = it.imageGuidance.copy(vibes = listOf(NovelAiVibeReferenceDraft(encodedVibe = encoding)))) }
            val reference = assertNotNull(r.loadDraft().imageGuidance.vibes.single().encodedVibe)
            assertTrue(reference.startsWith(NovelAiInlinePayloadStore.PREFIX))
            val journal = Files.readString(root.resolve("entities/${DesktopStudioHistoryRepository.ENTITY}.json"))
            assertFalse(journal.contains(encoding)); assertTrue(journal.contains(reference))
            assertTrue(r.replay(false)); assertTrue(r.replay(true))
            assertEquals(encoding, NovelAiInlinePayloadStore(root.toFile()).resolve(r.loadDraft().imageGuidance.vibes.single().encodedVibe!!))
        } finally { check(root.toFile().deleteRecursively()) }
    }

    @Test fun `history budget never becomes a smaller input limit for an existing valid large text draft`() = runBlocking {
        val root = Files.createTempDirectory("studio-history-large-text-")
        try {
            val storage = JsonFileStorage(root); val r = DesktopStudioHistoryRepository(storage)
            r.loadDraft(); r.initializeHistory()
            val large = "a".repeat(3 * 1024 * 1024 + 64)
            r.updateDraft { it.copy(basePrompt = large) }
            r.updateDraft { it.copy(extraPrompt = "small separate edit") }
            assertEquals(large, r.loadDraft().basePrompt)
            assertTrue(r.replay(false)); assertEquals(large, r.loadDraft().basePrompt)
            assertEquals("", r.loadDraft().extraPrompt)
        } finally { check(root.toFile().deleteRecursively()) }
    }
}
