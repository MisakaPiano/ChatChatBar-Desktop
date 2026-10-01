package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.draft.WorldBookEntryModalState
import com.example.chatbar.domain.draft.WorldBookEditorDraftUiStateCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWorldBookEditorControllerTest {
    @Test fun `book lifecycle keeps identity provenance and validated settings across restart`() = runBlocking {
        fixture { root, app, editor ->
            editor.openNew()
            editor.edit { it.copy(name = "  Lore  ", description = "Details", recursiveScanning = true,
                caseSensitive = true, matchWholeWords = true) }
            editor.editScanDepth("bad")
            assertFalse(editor.save())
            assertEquals(WorldBookEditorProblem.SCAN_DEPTH_INVALID, editor.state.value.problem)
            editor.editScanDepth("7")
            editor.editTokenBudget("bad")
            assertFalse(editor.save())
            assertEquals(WorldBookEditorProblem.TOKEN_BUDGET_INVALID, editor.state.value.problem)
            editor.editTokenBudget("120")
            assertTrue(editor.save())
            val first = app.worldBookRepository.getAll().single()
            assertEquals("Lore", first.name)
            assertEquals(7, first.scanDepth)
            assertEquals(120, first.tokenBudget)
            assertTrue(first.recursiveScanning && first.caseSensitive && first.matchWholeWords)
            kotlinx.coroutines.delay(10)
            editor.openExisting(first.id)
            editor.edit { it.copy(description = "Revised") }
            assertTrue(editor.save())
            val restarted = container(root)
            try {
                val after = restarted.worldBookRepository.getById(first.id)!!
                assertEquals(first.id, after.id)
                assertEquals(first.createdAt, after.createdAt)
                assertTrue(after.updatedAt > first.updatedAt)
                assertEquals("Revised", after.description)
            } finally { restarted.close() }
            val preset = WorldBook.create("Preset").copy(sourcePresetKey = "source", sourcePresetVersion = 3)
            app.worldBookRepository.save(preset)
            editor.openExisting(preset.id)
            editor.edit { it.copy(description = "Manual") }
            assertTrue(editor.save())
            assertEquals("source", app.worldBookRepository.getById(preset.id)?.sourcePresetKey)
            assertEquals(3, app.worldBookRepository.getById(preset.id)?.sourcePresetVersion)
        }
    }

    @Test fun `entry modal materializes fields and preserves hidden imported metadata`() = runBlocking {
        fixture { _, app, editor ->
            val sourceEntry = WorldBookEntry.create(listOf("before"), "Original").copy(
                name = "Entry", priority = 9, role = "system", recursionLevel = 4,
                originalPosition = "anchor", characterFilter = listOf("Alice"),
                characterFilterExclude = true, extensions = "opaque")
            val untouched = WorldBookEntry.create(listOf("other"), "Untouched")
            val source = WorldBook.create("Fields").copy(entries = listOf(sourceEntry, untouched))
            app.worldBookRepository.save(source)
            editor.openExisting(source.id)
            editor.openEntry(0)
            editor.updateEntryModal { it.copy(name = "Edited", keys = "one, two", secondary = "third",
                content = "New content", order = "42", position = WorldBookPosition.OUTLET,
                enabled = false, constant = true, useRegex = true, wholeWords = null,
                caseSensitive = false, matchCharacterDescription = true,
                matchCharacterPersonality = true, matchScenario = true, matchCreatorNotes = true,
                matchPersonaDescription = true, ignoreBudget = true, excludeRecursion = true,
                preventRecursion = true, delayUntilRecursion = true, logic = 3,
                probability = "110", group = "group", groupWeight = "-1", scanDepth = "5",
                sticky = "2", cooldown = "3", delay = "4", outlet = "outlet") }
            assertTrue(editor.saveEntry())
            assertTrue(editor.save())
            val entry = app.worldBookRepository.getById(source.id)!!.entries.first()
            assertEquals(sourceEntry.id, entry.id)
            assertEquals(listOf("one", "two"), entry.keys)
            assertEquals(listOf("third"), entry.secondaryKeys)
            assertEquals(WorldBookPosition.OUTLET, entry.position)
            assertEquals(100, entry.probability)
            assertEquals(0, entry.groupWeight)
            assertNull(entry.matchWholeWords)
            assertEquals(false, entry.caseSensitive)
            assertTrue(entry.selective && entry.constant && entry.useRegex && entry.ignoreBudget)
            assertTrue(entry.excludeRecursion && entry.preventRecursion && entry.delayUntilRecursion)
            assertEquals(42, entry.insertionOrder)
            assertEquals(3, entry.selectiveLogic)
            assertEquals("group", entry.group)
            assertEquals(5, entry.scanDepth)
            assertEquals(2, entry.sticky)
            assertEquals(3, entry.cooldown)
            assertEquals(4, entry.delay)
            assertEquals("outlet", entry.outletName)
            assertTrue(entry.matchCharacterDescription && entry.matchCharacterPersonality &&
                entry.matchScenario && entry.matchCreatorNotes && entry.matchPersonaDescription)
            assertEquals(9, entry.priority)
            assertEquals("system", entry.role)
            assertEquals(4, entry.recursionLevel)
            assertEquals("anchor", entry.originalPosition)
            assertEquals(listOf("Alice"), entry.characterFilter)
            assertTrue(entry.characterFilterExclude)
            assertEquals("opaque", entry.extensions)
            editor.openEntry(0)
            editor.updateEntryModal { it.copy(caseSensitive = true, wholeWords = false) }
            assertTrue(editor.saveEntry())
            editor.toggleEntry(0)
            assertTrue(editor.save())
            val toggled = app.worldBookRepository.getById(source.id)!!.entries.first()
            assertTrue(toggled.enabled)
            assertEquals(true, toggled.caseSensitive)
            assertEquals(false, toggled.matchWholeWords)
            editor.deleteEntry(0)
            assertTrue(editor.save())
            assertEquals(untouched, app.worldBookRepository.getById(source.id)!!.entries.single())
        }
    }

    @Test fun `open entry modal recovers exactly and closes without persisting rendered state`() = runBlocking {
        fixture { root, app, editor ->
            val source = WorldBook.create("Modal")
            app.worldBookRepository.save(source)
            editor.openExisting(source.id)
            editor.openEntry(null)
            editor.updateEntryModal { it.copy(name = "Unfinished", keys = "foo", content = "Body",
                probability = "37", caseSensitive = null) }
            editor.flushDraft()
            assertNotNull(app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, source.id)?.openModalState)
            val restarted = container(root)
            try {
                val recovered = restarted.worldBookEditorController
                recovered.openExisting(source.id)
                assertEquals("Unfinished", recovered.state.value.modal?.name)
                assertEquals("37", recovered.state.value.modal?.probability)
                assertEquals(null, recovered.state.value.modal?.caseSensitive)
                assertTrue(recovered.saveEntry())
                assertTrue(recovered.save())
                assertNull(restarted.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, source.id))
                assertEquals("Unfinished", restarted.worldBookRepository.getById(source.id)?.entries?.single()?.name)
            } finally { restarted.close() }
        }
    }

    @Test fun `search name collision and draft leave are presentation only`() = runBlocking {
        fixture { _, app, editor ->
            app.worldBookRepository.save(WorldBook.create("Alpha"))
            app.worldBookRepository.save(WorldBook.create("Beta"))
            editor.load()
            editor.search("alp")
            assertEquals(listOf("Alpha"), editor.state.value.visibleBooks.map { it.name })
            editor.search("")
            assertEquals(2, editor.state.value.visibleBooks.size)
            editor.openNew()
            editor.edit { it.copy(name = " alpha ") }
            assertFalse(editor.save())
            assertEquals(WorldBookEditorProblem.DUPLICATE_NAME, editor.state.value.problem)
            editor.edit { it.copy(name = "Later") }
            var left = false
            editor.requestLeave { left = true }
            assertTrue(editor.state.value.leavePrompt)
            editor.keepDraftAndLeave()
            assertTrue(left)
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, null))
            editor.openNew()
            assertEquals("Later", editor.state.value.book?.name)
            editor.discard()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, null))
        }
    }

    @Test fun `conflict deletion save as new and new draft collision protect sources`() = runBlocking {
        fixture { _, app, editor ->
            val source = WorldBook.create("Source").copy(sourcePresetKey = "preset", sourcePresetVersion = 2)
            app.worldBookRepository.save(source)
            editor.openExisting(source.id)
            editor.edit { it.copy(description = "Local") }
            editor.flushDraft()
            app.worldBookRepository.save(source.copy(description = "External"))
            assertFalse(editor.save())
            assertEquals(WorldBookEditorProblem.SOURCE_CHANGED, editor.state.value.problem)
            assertTrue(editor.save(forceOverwrite = true))
            assertEquals("Local", app.worldBookRepository.getById(source.id)?.description)
            editor.openExisting(source.id)
            editor.edit { it.copy(description = "Copy") }
            assertTrue(editor.saveAsNew())
            assertTrue(editor.save())
            val copy = app.worldBookRepository.getById(editor.state.value.book!!.id)!!
            assertNotEquals(source.id, copy.id)
            assertNull(copy.sourcePresetKey)
            assertNull(copy.sourcePresetVersion)
            assertEquals("Local", app.worldBookRepository.getById(source.id)?.description)
            editor.openExisting(source.id)
            editor.edit { it.copy(description = "Uncommitted") }
            app.worldBookRepository.delete(source.id)
            assertFalse(editor.save())
            assertEquals(WorldBookEditorProblem.SOURCE_DELETED, editor.state.value.problem)
        }
    }

    @Test fun `entity save failure and confirmed cleanup warning retain recoverable draft`() = runBlocking {
        fixture { _, app, _ ->
            val failed = DesktopWorldBookEditorController(app.worldBookRepository,
                app.editorDraftRepository, app.characterRepository, app.transferJson,
                persistBook = { throw IllegalStateException("injected") })
            try {
                failed.openNew()
                failed.edit { it.copy(name = "Recoverable") }
                assertFalse(failed.save())
                assertEquals(WorldBookEditorProblem.SAVE_FAILED, failed.state.value.problem)
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, null))
                assertTrue(app.worldBookRepository.getAll().isEmpty())
            } finally { failed.closeAndDrain() }
            val warning = DesktopWorldBookEditorController(app.worldBookRepository,
                app.editorDraftRepository, app.characterRepository, app.transferJson,
                deleteDraft = { _, _ -> Unit })
            try {
                warning.openNew()
                warning.edit { it.copy(name = "Committed") }
                assertTrue(warning.save())
                assertEquals(WorldBookEditorProblem.SAVE_COMMITTED_WARNING, warning.state.value.problem)
                assertNotNull(app.worldBookRepository.getAll().singleOrNull())
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, null))
            } finally { warning.closeAndDrain() }
        }
    }

    @Test fun `clean save is a no-op and reverted draft is physically removed`() = runBlocking {
        fixture { _, app, editor ->
            val source = WorldBook.create("Clean")
            app.worldBookRepository.save(source)
            val original = app.worldBookRepository.getById(source.id)!!
            editor.openExisting(source.id)
            assertTrue(editor.save())
            assertEquals(original, app.worldBookRepository.getById(source.id))
            editor.edit { it.copy(description = "Temporary") }
            editor.flushDraft()
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, source.id))
            editor.edit { it.copy(description = original.description) }
            assertFalse(editor.state.value.dirty)
            assertTrue(editor.save())
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, source.id))
            assertEquals(original, app.worldBookRepository.getById(source.id))
            val identical = app.editorDraftRepository.worldBookDraft(source.id, "old-session", original, original, null)
            app.editorDraftRepository.save(identical)
            editor.openExisting(source.id)
            editor.requestLeave(editor::closeClean)
            kotlinx.coroutines.withTimeout(5_000) {
                while (app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, source.id))
                    kotlinx.coroutines.delay(20)
            }
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, source.id))
        }
    }

    @Test fun `clean draft cleanup failure remains visible and retryable`() = runBlocking {
        fixture { _, app, _ ->
            val source = WorldBook.create("Clean")
            app.worldBookRepository.save(source)
            app.editorDraftRepository.save(app.editorDraftRepository.worldBookDraft(source.id,
                "old-session", source, source, null))
            val controller = DesktopWorldBookEditorController(app.worldBookRepository,
                app.editorDraftRepository, app.characterRepository, app.transferJson,
                deleteDraft = { _, _ -> Unit })
            try {
                controller.openExisting(source.id)
                assertFalse(controller.save())
                assertEquals(WorldBookEditorProblem.CLEAN_DRAFT_WARNING, controller.state.value.problem)
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, source.id))
            } finally { controller.closeAndDrain() }
            val recovered = app.worldBookEditorController
            recovered.openExisting(source.id)
            assertTrue(recovered.save())
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, source.id))
        }
    }

    @Test fun `source deletion can recover as new without overwriting another new draft`() = runBlocking {
        fixture { _, app, editor ->
            val source = WorldBook.create("Deleted")
            app.worldBookRepository.save(source)
            editor.openExisting(source.id)
            editor.edit { it.copy(description = "Draft") }
            app.worldBookRepository.delete(source.id)
            assertFalse(editor.save())
            val unrelated = app.editorDraftRepository.worldBookDraft(null, "other-session",
                WorldBook.create("Unrelated"), null, null)
            app.editorDraftRepository.save(unrelated)
            assertFalse(editor.saveAsNew())
            assertEquals(WorldBookEditorProblem.NEW_DRAFT_EXISTS, editor.state.value.problem)
            assertEquals("Unrelated", app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, null)
                ?.worldBookPayload?.name)
            app.editorDraftRepository.deleteForTarget(EditorDraftType.WORLD_BOOK, null)
            assertTrue(editor.saveAsNew())
            assertTrue(editor.save())
            assertNotEquals(source.id, editor.state.value.book?.id)
            assertEquals("Draft", editor.state.value.book?.description)
        }
    }

    @Test fun `post write failure recognizes committed entity and does not blind overwrite`() = runBlocking {
        fixture { _, app, _ ->
            val controller = DesktopWorldBookEditorController(app.worldBookRepository,
                app.editorDraftRepository, app.characterRepository, app.transferJson,
                persistBook = { book -> app.worldBookRepository.save(book); error("after write") })
            try {
                controller.openNew()
                controller.edit { it.copy(name = "Committed") }
                assertTrue(controller.save())
                assertEquals("Committed", app.worldBookRepository.getAll().single().name)
                assertFalse(controller.state.value.dirty)
                assertEquals(WorldBookEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
                controller.retryCleanup()
                assertNull(controller.state.value.problem)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `dismissed modal is removed from recovered draft`() = runBlocking {
        fixture { root, app, editor ->
            val source = WorldBook.create("Modal")
            app.worldBookRepository.save(source)
            editor.openExisting(source.id)
            editor.edit { it.copy(description = "Draft") }
            editor.openEntry(null)
            editor.updateEntryModal { it.copy(name = "Not committed") }
            editor.flushDraft()
            assertNotNull(app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, source.id)?.openModalState)
            editor.dismissEntry()
            editor.flushDraft()
            val savedUi = app.editorDraftRepository.getForTarget(EditorDraftType.WORLD_BOOK, source.id)?.openModalState
            assertNull(WorldBookEditorDraftUiStateCodec.decode(app.transferJson, savedUi)?.entryModalState)
            val restarted = container(root)
            try {
                restarted.worldBookEditorController.openExisting(source.id)
                assertNull(restarted.worldBookEditorController.state.value.modal)
                assertEquals("Draft", restarted.worldBookEditorController.state.value.book?.description)
            } finally { restarted.close() }
        }
    }

    @Test fun `manual character import is draft first and source clearing needs explicit confirmation`() = runBlocking {
        fixture { _, app, editor ->
            val alice = CharacterInfo.create("Alice").copy(profile = "Profile", appearance = "Eyes",
                background = "History", imagePrompt = "Image prompt")
            val bob = CharacterInfo.create("Bob").copy(profile = "Bob profile", speakingStyle = "Quiet")
            val card = CharacterCard.create("Duo", characters = listOf(alice, bob))
            app.characterRepository.save(card)
            val existing = WorldBookEntry.create(listOf("Alice"), "Old").copy(name = "Alice", priority = 8)
            val source = WorldBook.create("Lore").copy(entries = listOf(existing))
            app.worldBookRepository.save(source)
            editor.openExisting(source.id)
            val result = editor.importCharacters(card.id, setOf(alice.id, bob.id))!!
            assertEquals(1, result.updatedCount)
            assertEquals(1, result.createdCount)
            assertEquals(2, editor.state.value.book!!.entries.size)
            assertEquals(existing.id, editor.state.value.book!!.entries.first().id)
            assertEquals(8, editor.state.value.book!!.entries.first().priority)
            assertEquals("Old", app.worldBookRepository.getById(source.id)?.entries?.first()?.content)
            editor.keepSourceCharacters()
            assertEquals("Eyes", app.characterRepository.getById(card.id)?.characters?.first()?.appearance)
            editor.importCharacters(card.id, setOf(alice.id))
            assertTrue(editor.confirmClearSourceCharacters())
            val cleared = app.characterRepository.getById(card.id)!!.characters
            assertEquals("", cleared.first().appearance)
            assertEquals("", cleared.first().background)
            assertEquals("Profile", cleared.first().profile)
            assertEquals("Image prompt", cleared.first().imagePrompt)
            assertEquals("Quiet", cleared.last().speakingStyle)
            assertTrue(editor.save())
            assertEquals(2, app.worldBookRepository.getById(source.id)?.entries?.size)
        }
    }

    @Test fun `package and SillyTavern transfer remain the formal WorldBook service`() = runBlocking {
        fixture { _, app, editor ->
            editor.openNew()
            editor.edit { it.copy(name = "Transfer") }
            editor.openEntry(null)
            editor.updateEntryModal { it.copy(name = "One", keys = "key", content = "Body") }
            assertTrue(editor.saveEntry())
            assertTrue(editor.save())
            val id = editor.state.value.book!!.id
            val raw = app.worldBookTransfers.exportJson(id)
            assertTrue(raw.contains("schemaVersion"))
            val decoded = app.worldBookTransfers.decode(raw)
            assertEquals("One", decoded.book.entries.single().name)
            val imported = app.worldBookTransfers.importNew(decoded)
            assertNotEquals(id, imported.id)
            assertEquals("Body", imported.entries.single().content)
            val st = app.worldBookTransfers.exportSillyTavernJson(id)
            assertEquals("Body", app.worldBookTransfers.decode(st).book.entries.single().content)
        }
    }

    @Test fun `WorldBook labels and warnings are localized`() {
        val zh = DesktopUiStrings(DesktopUiLanguage.ZH_CN)
        val en = DesktopUiStrings(DesktopUiLanguage.EN)
        listOf(DesktopUiText.WORLD_MANAGEMENT, DesktopUiText.WORLD_ENTRY_CONTENT,
            DesktopUiText.WORLD_SOURCE_CHANGED, DesktopUiText.WORLD_SAVE_WARNING).forEach { key ->
            assertTrue(zh(key).isNotBlank())
            assertTrue(en(key).isNotBlank())
            assertNotEquals(zh(key), en(key))
        }
    }

    @Test fun `committed warning blocks route section and leave prompt paths until retry`() = runBlocking {
        fixture { _, app, _ ->
            var canDelete = false
            val controller = DesktopWorldBookEditorController(app.worldBookRepository,
                app.editorDraftRepository, app.characterRepository, app.transferJson,
                deleteDraft = { type, target ->
                    if (canDelete) app.editorDraftRepository.deleteForTarget(type, target)
                })
            try {
                controller.openNew()
                controller.edit { it.copy(name = "Committed") }
                var left = false
                controller.requestLeave { left = true }
                assertTrue(controller.state.value.leavePrompt)
                controller.saveAndLeave()
                assertEquals(WorldBookEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
                controller.requestLeave { left = true }
                controller.keepDraftAndLeave()
                controller.saveAndLeave()
                controller.closeClean()
                assertFalse(left)
                assertNotNull(controller.state.value.book)
                canDelete = true
                controller.retryCleanup()
                assertNull(controller.state.value.problem)
                controller.requestLeave { left = true }
                assertTrue(left)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `dismissed clean modal is removed on drain but active modal survives restart`() = runBlocking {
        fixture { root, app, controller ->
            val source = WorldBook.create("Modal drain")
            app.worldBookRepository.save(source)
            controller.openExisting(source.id)
            controller.openEntry(null)
            controller.updateEntryModal { it.copy(name = "Dismiss me") }
            controller.flushDraft()
            controller.dismissEntry()
            assertFalse(controller.state.value.dirty)
            controller.closeAndDrain()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, source.id))
            val reopened = container(root)
            try {
                val active = reopened.worldBookEditorController
                active.openExisting(source.id)
                assertNull(active.state.value.modal)
                active.openEntry(null)
                active.updateEntryModal { it.copy(name = "Keep me", content = "Body") }
                active.closeAndDrain()
                assertTrue(reopened.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, source.id))
                val restarted = container(root)
                try {
                    restarted.worldBookEditorController.openExisting(source.id)
                    assertEquals("Keep me", restarted.worldBookEditorController.state.value.modal?.name)
                } finally { restarted.close() }
            } finally { reopened.close() }
        }
    }

    @Test fun `raw valid and incomplete numeric inputs recover exactly and invalid save stays blocked`() = runBlocking {
        fixture { root, app, controller ->
            val source = WorldBook.create("Numbers")
            app.worldBookRepository.save(source)
            controller.openExisting(source.id)
            controller.editScanDepth("07")
            controller.editTokenBudget("120")
            controller.flushDraft()
            controller.closeAndDrain()
            val valid = container(root)
            try {
                valid.worldBookEditorController.openExisting(source.id)
                assertEquals("07", valid.worldBookEditorController.state.value.scanDepthInput)
                assertEquals("120", valid.worldBookEditorController.state.value.tokenBudgetInput)
                valid.worldBookEditorController.editScanDepth("-")
                valid.worldBookEditorController.editTokenBudget("120-")
                valid.worldBookEditorController.closeAndDrain()
            } finally { valid.close() }
            val invalid = container(root)
            try {
                val editor = invalid.worldBookEditorController
                editor.openExisting(source.id)
                assertEquals("-", editor.state.value.scanDepthInput)
                assertEquals("120-", editor.state.value.tokenBudgetInput)
                assertTrue(editor.state.value.dirty)
                assertFalse(editor.save())
                assertEquals(WorldBookEditorProblem.SCAN_DEPTH_INVALID, editor.state.value.problem)
                editor.editScanDepth("7")
                assertFalse(editor.save())
                assertEquals(WorldBookEditorProblem.TOKEN_BUDGET_INVALID, editor.state.value.problem)
                assertEquals(source, invalid.worldBookRepository.getById(source.id))
            } finally { invalid.close() }
        }
    }

    @Test fun `legacy raw modal draft remains readable`() = runBlocking {
        fixture { _, app, controller ->
            val source = WorldBook.create("Legacy")
            app.worldBookRepository.save(source)
            val raw = app.transferJson.encodeToString(WorldBookEntryModalState(name = "Old modal"))
            app.editorDraftRepository.save(app.editorDraftRepository.worldBookDraft(source.id,
                "legacy-session", source, source, raw))
            controller.openExisting(source.id)
            assertEquals("Old modal", controller.state.value.modal?.name)
            assertEquals(source.scanDepth.toString(), controller.state.value.scanDepthInput)
        }
    }

    @Test fun `warning drain retries cleanup once and preserves draft on repeated deletion failure`() = runBlocking {
        fixture { _, app, _ ->
            var canDelete = false
            val controller = DesktopWorldBookEditorController(app.worldBookRepository,
                app.editorDraftRepository, app.characterRepository, app.transferJson,
                deleteDraft = { type, target ->
                    if (canDelete) app.editorDraftRepository.deleteForTarget(type, target)
                })
            controller.openNew()
            controller.edit { it.copy(name = "Committed") }
            assertTrue(controller.save())
            controller.closeAndDrain()
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, null))
            assertEquals("Committed", app.worldBookRepository.getAll().single().name)
            assertEquals(WorldBookEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
        }
    }

    @Test fun `WorldBook warning drain clears the stale draft when deletion recovers`() = runBlocking {
        fixture { _, app, _ ->
            var canDelete = false
            val controller = DesktopWorldBookEditorController(app.worldBookRepository,
                app.editorDraftRepository, app.characterRepository, app.transferJson,
                deleteDraft = { type, target ->
                    if (canDelete) app.editorDraftRepository.deleteForTarget(type, target)
                })
            controller.openNew()
            controller.edit { it.copy(name = "Drain") }
            assertTrue(controller.save())
            assertEquals(WorldBookEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
            canDelete = true
            controller.closeAndDrain()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.WORLD_BOOK, null))
            assertNull(controller.state.value.problem)
        }
    }

    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopWorldBookEditorController) -> Unit) {
        val parent = Files.createTempDirectory("desktop-world-editor-")
        val root = parent.resolve("app-data")
        val app = container(root)
        try { block(root, app, app.worldBookEditorController) }
        finally { app.close(); parent.toFile().deleteRecursively() }
    }

    private fun container(root: Path): DesktopAppContainer = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
}
