package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFormatCardEditorControllerTest {
    @Test fun `create edit save and restart preserve identity content and provenance`() = runBlocking {
        fixture { root, app, editor ->
            editor.openNew()
            editor.edit { it.copy(name = "  Card  ", content = "Prompt format") }
            assertTrue(editor.save())
            val first = app.formatCardRepository.getAll().single()
            assertEquals("Card", first.name)
            editor.openExisting(first.id)
            editor.edit { it.copy(content = "Changed") }
            assertTrue(editor.save())
            val reopened = container(root)
            try {
                val saved = reopened.formatCardRepository.getById(first.id)!!
                assertEquals(first.id, saved.id)
                assertEquals(first.createdAt, saved.createdAt)
                assertEquals("Changed", saved.content)
            } finally { reopened.close() }
            val preset = FormatCard.create("Preset", "Original").copy(sourcePresetKey = "source", sourcePresetVersion = 2)
            app.formatCardRepository.save(preset)
            editor.openExisting(preset.id)
            editor.edit { it.copy(content = "Manual edit") }
            assertTrue(editor.save())
            assertEquals("source", app.formatCardRepository.getById(preset.id)?.sourcePresetKey)
            assertEquals(2, app.formatCardRepository.getById(preset.id)?.sourcePresetVersion)
            val packageData = app.formatTransfers.decode(app.formatTransfers.exportJson(preset.id))
            val imported = app.formatTransfers.importNew(packageData)
            assertEquals("source", imported.sourcePresetKey)
            assertEquals(2, imported.sourcePresetVersion)
        }
    }

    @Test fun `name content and case-insensitive collision block formal save`() = runBlocking {
        fixture { _, app, editor ->
            app.formatCardRepository.save(FormatCard.create("Existing", "Body"))
            editor.openNew()
            editor.edit { it.copy(content = "Body") }
            assertFalse(editor.save())
            assertEquals(FormatEditorProblem.NAME_REQUIRED, editor.state.value.problem)
            editor.edit { it.copy(name = "Other", content = "  ") }
            assertFalse(editor.save())
            assertEquals(FormatEditorProblem.CONTENT_REQUIRED, editor.state.value.problem)
            editor.edit { it.copy(name = " existing ", content = "Body") }
            assertFalse(editor.save())
            assertEquals(FormatEditorProblem.DUPLICATE_NAME, editor.state.value.problem)
            assertEquals(1, app.formatCardRepository.getAll().size)
        }
    }

    @Test fun `draft autosaves recovers after restart and confirms cleanup on save and discard`() = runBlocking {
        fixture { root, app, editor ->
            editor.openNew()
            editor.edit { it.copy(name = "Draft", content = "Incomplete") }
            withTimeout(15_000) {
                while (!app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null)) delay(50)
            }
            val reopened = container(root)
            try {
                val recovered = reopened.formatCardEditorController
                recovered.openNew()
                assertEquals("Draft", recovered.state.value.card?.name)
                assertTrue(recovered.save())
                assertFalse(reopened.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
                val id = recovered.state.value.card!!.id
                recovered.openExisting(id)
                recovered.edit { it.copy(content = "Unsaved") }
                recovered.flushDraft()
                recovered.discard()
                assertFalse(reopened.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, id))
                assertEquals("Incomplete", reopened.formatCardRepository.getById(id)?.content)
            } finally { reopened.close() }
        }
    }

    @Test fun `leave guard can keep a durable draft and navigate away`() = runBlocking {
        fixture { _, app, editor ->
            editor.openNew()
            editor.edit { it.copy(name = "Later", content = "Unfinished") }
            var navigated = false
            editor.requestLeave { navigated = true }
            assertTrue(editor.state.value.leavePrompt)
            editor.keepDraftAndLeave()
            assertTrue(navigated)
            assertNull(editor.state.value.card)
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
            editor.openNew()
            assertEquals("Later", editor.state.value.card?.name)
        }
    }

    @Test fun `ordered tools validate raw drafts and survive save and package round trip`() = runBlocking {
        fixture { root, app, editor ->
            editor.openNew()
            editor.edit { it.copy(name = "Tools", content = "Format") }
            editor.addTool(FormatCardUserToolType.RANDOM_NUMBER)
            editor.addTool(FormatCardUserToolType.STRONG_PROMPT_SUFFIX)
            editor.addTool(FormatCardUserToolType.RANDOM_NUMBER)
            editor.updateTool(0) { it.copy(minimum = "bad") }
            editor.updateTool(1) { it.copy(text = "") }
            editor.updateTool(2) { it.copy(maximum = "0") }
            editor.flushDraft()
            assertEquals("bad", app.editorDraftRepository.getForTarget(EditorDraftType.FORMAT_CARD, null)
                ?.formatPayload?.userTools?.first()?.minimum)
            assertFalse(editor.save())
            assertEquals(FormatEditorProblem.INVALID_TOOL, editor.state.value.problem)
            editor.updateTool(0) { it.copy(minimum = "1", maximum = "bad") }
            assertFalse(editor.save())
            editor.updateTool(0) { it.copy(maximum = "0") }
            assertFalse(editor.save())
            editor.updateTool(0) { it.copy(maximum = "10") }
            editor.updateTool(1) { it.copy(text = "Strong") }
            editor.updateTool(2) { it.copy(maximum = "20") }
            editor.moveTool(2, -1)
            editor.moveTool(2, -1)
            editor.moveTool(0, 1)
            editor.removeTool(2)
            assertTrue(editor.save())
            val saved = app.formatCardRepository.getAll().single()
            assertEquals(listOf(FormatCardUserToolType.STRONG_PROMPT_SUFFIX, FormatCardUserToolType.RANDOM_NUMBER),
                saved.userTools.map { it.type })
            val exported = app.formatTransfers.exportJson(saved.id)
            assertTrue(exported.contains("\"schemaVersion\": 2"))
            val decoded = app.formatTransfers.decode(exported)
            val imported = app.formatTransfers.importNew(decoded)
            assertEquals(saved.userTools, imported.userTools)
            val reopened = container(root)
            try { assertEquals(saved.userTools, reopened.formatCardRepository.getById(saved.id)?.userTools) }
            finally { reopened.close() }
        }
    }

    @Test fun `single default is repository authority and unrelated save keeps it`() = runBlocking {
        fixture { root, app, editor ->
            val a = FormatCard.create("A", "A")
            val b = FormatCard.create("B", "B")
            app.formatCardRepository.save(a)
            app.formatCardRepository.save(b)
            editor.openExisting(a.id)
            editor.edit { it.copy(isDefault = true) }
            assertTrue(editor.save())
            editor.openExisting(b.id)
            editor.edit { it.copy(content = "B2") }
            assertTrue(editor.save())
            assertEquals(a.id, app.formatCardRepository.getDefault()?.id)
            editor.openExisting(b.id)
            editor.edit { it.copy(isDefault = true) }
            assertTrue(editor.save())
            assertEquals(b.id, app.formatCardRepository.getDefault()?.id)
            assertFalse(app.formatCardRepository.getById(a.id)!!.isDefault)
            val reopened = container(root)
            try { assertEquals(b.id, reopened.formatCardRepository.getDefault()?.id) }
            finally { reopened.close() }
            // Android's editor flag path does not write AppSettings.defaultFormatCardId.
            assertFalse(Files.exists(root.resolve("entities/app_settings.json")))
        }
    }

    @Test fun `external change blocks dirty save while clean save is a no-op`() = runBlocking {
        fixture { _, app, editor ->
            val source = FormatCard.create("Source", "Body")
            app.formatCardRepository.save(source)
            editor.openExisting(source.id)
            app.formatCardRepository.save(source.copy(content = "External"))
            assertTrue(editor.save())
            assertEquals("External", app.formatCardRepository.getById(source.id)?.content)
            editor.edit { it.copy(content = "Local") }
            editor.flushDraft()
            assertFalse(editor.save())
            assertEquals(FormatEditorProblem.SOURCE_CHANGED, editor.state.value.problem)
            assertEquals("External", app.formatCardRepository.getById(source.id)?.content)
            assertTrue(editor.saveAsNew())
            assertTrue(editor.save())
            assertNotEquals(source.id, editor.state.value.card?.id)
            assertEquals("External", app.formatCardRepository.getById(source.id)?.content)
        }
    }

    @Test fun `reverting an existing edit cleans its draft before clean save without entity write`() = runBlocking {
        fixture { _, app, _ ->
            val source = FormatCard.create("A", "A")
            app.formatCardRepository.save(source)
            val editor = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                persistCard = { error("clean Save must not persist a FormatCard") })
            try {
                editor.openExisting(source.id)
                editor.edit { it.copy(content = "B") }
                editor.flushDraft()
                editor.edit { it.copy(content = "A") }
                assertFalse(editor.state.value.dirty)
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, source.id))
                assertTrue(editor.save())
                assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, source.id))
                assertNull(editor.state.value.draftBasis)
                assertFalse(editor.state.value.draftPersisted)
                assertEquals(source, app.formatCardRepository.getById(source.id))
                editor.closeClean()
                editor.openExisting(source.id)
                assertEquals("A", editor.state.value.card?.content)
                assertFalse(editor.state.value.dirty)
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `reverting an existing edit cleans its draft before clean close`() = runBlocking {
        fixture { _, app, editor ->
            val source = FormatCard.create("A", "A")
            app.formatCardRepository.save(source)
            editor.openExisting(source.id)
            editor.edit { it.copy(content = "B") }
            editor.flushDraft()
            editor.edit { it.copy(content = "A") }
            editor.closeClean()
            withTimeout(15_000) { while (editor.state.value.card != null) delay(10) }
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, source.id))
            editor.openExisting(source.id)
            assertEquals("A", editor.state.value.card?.content)
            assertFalse(editor.state.value.dirty)
        }
    }

    @Test fun `failed obsolete draft deletion blocks clean close and clean save without touching source`() = runBlocking {
        fixture { _, app, _ ->
            val source = FormatCard.create("A", "A")
            app.formatCardRepository.save(source)
            val editor = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                persistCard = { error("clean path must not write entity") }, deleteDraft = { _, _ -> Unit })
            try {
                editor.openExisting(source.id)
                editor.edit { it.copy(content = "B") }
                editor.flushDraft()
                editor.edit { it.copy(content = "A") }
                editor.closeClean()
                withTimeout(15_000) {
                    while (editor.state.value.problem != FormatEditorProblem.CLEAN_DRAFT_WARNING) delay(10)
                }
                assertNotNull(editor.state.value.card)
                assertFalse(editor.save())
                assertEquals(FormatEditorProblem.CLEAN_DRAFT_WARNING, editor.state.value.problem)
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, source.id))
                assertEquals(source, app.formatCardRepository.getById(source.id))
                editor.retryCleanup()
                assertEquals(FormatEditorProblem.CLEAN_DRAFT_WARNING, editor.state.value.problem)
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `identical recovered draft is semantically clean and confirmed away before close`() = runBlocking {
        fixture { _, app, editor ->
            val source = FormatCard.create("A", "A")
            app.formatCardRepository.save(source)
            app.editorDraftRepository.save(app.editorDraftRepository.formatDraft(source.id, "same-payload",
                source, source))
            editor.openExisting(source.id)
            assertFalse(editor.state.value.dirty)
            assertNotNull(editor.state.value.draftBasis)
            assertTrue(editor.save())
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, source.id))
            editor.closeClean()
            editor.openExisting(source.id)
            assertEquals(source, editor.state.value.card)
            assertNull(editor.state.value.draftBasis)
        }
    }

    @Test fun `clean save with no persisted draft is immediate no-op`() = runBlocking {
        fixture { _, app, _ ->
            val source = FormatCard.create("A", "A")
            app.formatCardRepository.save(source)
            val editor = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                persistCard = { error("clean path must not write entity") },
                deleteDraft = { _, _ -> error("clean path must not delete absent draft") })
            try {
                editor.openExisting(source.id)
                assertTrue(editor.save())
                assertEquals(source, app.formatCardRepository.getById(source.id))
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `deleted source recovers as new without clobbering another new draft`() = runBlocking {
        fixture { _, app, editor ->
            val source = FormatCard.create("Deleted", "Body")
            app.formatCardRepository.save(source)
            editor.openExisting(source.id)
            editor.edit { it.copy(content = "Recovered") }
            editor.flushDraft()
            app.formatCardRepository.delete(source.id)
            val unrelated = app.editorDraftRepository.formatDraft(null, "other-session",
                FormatCard.create("Other", "Other"), null)
            app.editorDraftRepository.save(unrelated)
            assertFalse(editor.save())
            assertFalse(editor.saveAsNew())
            assertEquals(FormatEditorProblem.NEW_DRAFT_EXISTS, editor.state.value.problem)
            assertEquals("Other", app.editorDraftRepository.getForTarget(EditorDraftType.FORMAT_CARD, null)?.formatPayload?.name)
            app.editorDraftRepository.deleteForTarget(EditorDraftType.FORMAT_CARD, null)
            assertTrue(editor.saveAsNew())
            assertTrue(editor.save())
            assertEquals("Recovered", app.formatCardRepository.getById(editor.state.value.card!!.id)?.content)
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, source.id))
        }
    }

    @Test fun `failed draft cleanup leaves committed warning and retry is possible`() = runBlocking {
        fixture { _, app, _ ->
            val editor = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                deleteDraft = { _, _ -> Unit })
            try {
                editor.openNew()
                editor.edit { it.copy(name = "Saved", content = "Body") }
                assertTrue(editor.save())
                assertEquals(FormatEditorProblem.SAVE_COMMITTED_WARNING, editor.state.value.problem)
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `existing card save never deletes an unrelated new card draft`() = runBlocking {
        fixture { _, app, editor ->
            val source = FormatCard.create("Existing", "Body")
            app.formatCardRepository.save(source)
            val unrelated = app.editorDraftRepository.formatDraft(null, "other-session",
                FormatCard.create("New draft", "Work"), null)
            app.editorDraftRepository.save(unrelated)
            editor.openExisting(source.id)
            editor.edit { it.copy(content = "Edited") }
            assertTrue(editor.save())
            assertEquals("New draft", app.editorDraftRepository.getForTarget(EditorDraftType.FORMAT_CARD, null)
                ?.formatPayload?.name)
        }
    }

    @Test fun `restart recovery detects external semantic change and explicit overwrite`() = runBlocking {
        fixture { root, app, editor ->
            val source = FormatCard.create("Original", "Body")
            app.formatCardRepository.save(source)
            editor.openExisting(source.id)
            editor.edit { it.copy(content = "Local") }
            editor.flushDraft()
            app.formatCardRepository.save(source.copy(content = "External"))
            val reopened = container(root)
            try {
                val recovered = reopened.formatCardEditorController
                recovered.openExisting(source.id)
                assertEquals("Local", recovered.state.value.card?.content)
                assertEquals(FormatEditorProblem.SOURCE_CHANGED, recovered.state.value.problem)
                assertFalse(recovered.save())
                assertTrue(recovered.save(forceOverwrite = true))
                assertEquals("Local", reopened.formatCardRepository.getById(source.id)?.content)
            } finally { reopened.close() }
        }
    }

    @Test fun `entity save failure retains the durable draft for recovery`() = runBlocking {
        fixture { _, app, _ ->
            val editor = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                persistCard = { throw IllegalStateException("injected entity save failure") })
            try {
                editor.openNew()
                editor.edit { it.copy(name = "Recoverable", content = "Body") }
                assertFalse(editor.save())
                assertEquals(FormatEditorProblem.SAVE_FAILED, editor.state.value.problem)
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
                assertTrue(app.formatCardRepository.getAll().isEmpty())
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `save as new clears preset provenance and does not change source`() = runBlocking {
        fixture { _, app, editor ->
            val source = FormatCard.create("Bundled", "Body").copy(
                sourcePresetKey = "bundled", sourcePresetVersion = 4)
            app.formatCardRepository.save(source)
            editor.openExisting(source.id)
            editor.edit { it.copy(content = "Local edit") }
            assertTrue(editor.saveAsNew())
            assertTrue(editor.save())
            val copy = app.formatCardRepository.getById(editor.state.value.card!!.id)!!
            assertNull(copy.sourcePresetKey)
            assertNull(copy.sourcePresetVersion)
            assertEquals("Body", app.formatCardRepository.getById(source.id)?.content)
        }
    }

    @Test fun `localized FormatCard labels and warnings exist in both languages`() {
        val zh = DesktopUiStrings(DesktopUiLanguage.ZH_CN)
        val en = DesktopUiStrings(DesktopUiLanguage.EN)
        listOf(DesktopUiText.FORMAT_MANAGEMENT, DesktopUiText.FORMAT_CONTENT,
            DesktopUiText.FORMAT_NEW_DRAFT_EXISTS, DesktopUiText.FORMAT_INVALID_TOOL,
            DesktopUiText.FORMAT_SOURCE_CHANGED).forEach { key ->
            assertTrue(zh(key).isNotBlank())
            assertTrue(en(key).isNotBlank())
            assertNotEquals(zh(key), en(key))
        }
    }

    @Test fun `committed warning blocks every leave path until cleanup retry succeeds`() = runBlocking {
        fixture { _, app, _ ->
            var canDelete = false
            val controller = DesktopFormatCardEditorController(app.formatCardRepository,
                app.editorDraftRepository, deleteDraft = { type, target ->
                    if (canDelete) app.editorDraftRepository.deleteForTarget(type, target)
                })
            try {
                controller.openNew()
                controller.edit { it.copy(name = "Committed", content = "Body") }
                assertTrue(controller.save())
                assertEquals(FormatEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
                var left = false
                controller.requestLeave { left = true }
                controller.keepDraftAndLeave()
                controller.saveAndLeave()
                controller.closeClean()
                assertFalse(left)
                assertNotNull(controller.state.value.card)
                canDelete = true
                controller.retryCleanup()
                assertNull(controller.state.value.problem)
                controller.requestLeave { left = true }
                assertTrue(left)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `FormatCard warning drain retries pending cleanup`() = runBlocking {
        fixture { _, app, _ ->
            var canDelete = false
            val controller = DesktopFormatCardEditorController(app.formatCardRepository,
                app.editorDraftRepository, deleteDraft = { type, target ->
                    if (canDelete) app.editorDraftRepository.deleteForTarget(type, target)
                })
            controller.openNew()
            controller.edit { it.copy(name = "Drain", content = "Body") }
            assertTrue(controller.save())
            assertEquals(FormatEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
            canDelete = true
            controller.closeAndDrain()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
            assertNull(controller.state.value.problem)
        }
    }

    @Test fun `shutdown removes reverted and identical stale draft without rewriting FormatCard`() = runBlocking {
        fixture { root, app, controller ->
            val source = FormatCard.create("Source", "A")
            app.formatCardRepository.save(source)
            val durable = app.formatCardRepository.getById(source.id)!!
            controller.openExisting(source.id)
            controller.edit { it.copy(content = "B") }
            controller.flushDraft()
            controller.edit { it.copy(content = "A") }
            assertFalse(controller.state.value.dirty)
            controller.closeAndDrain()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, source.id))
            assertEquals(durable, app.formatCardRepository.getById(source.id))
            app.editorDraftRepository.save(app.editorDraftRepository.formatDraft(source.id,
                "identical-session", durable, durable))
            val reopened = container(root)
            try {
                reopened.formatCardEditorController.openExisting(source.id)
                assertFalse(reopened.formatCardEditorController.state.value.dirty)
                reopened.formatCardEditorController.closeAndDrain()
                assertFalse(reopened.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, source.id))
            } finally { reopened.close() }
        }
    }

    @Test fun `post-delete failure commits dirty Discard rather than leaving a false durable draft`() = runBlocking {
        fixture { root, app, _ ->
            val editor = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                deleteDraft = { type, target ->
                    app.editorDraftRepository.deleteForTarget(type, target)
                    error("cache failed after physical delete")
                })
            editor.openNew()
            editor.edit { it.copy(name = "Discard me", content = "Body") }
            editor.flushDraft()
            editor.discard()
            assertNull(editor.state.value.card)
            assertFalse(editor.state.value.dirty)
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
            editor.closeAndDrain()
            val reopened = container(root)
            try {
                reopened.formatCardEditorController.openNew()
                assertEquals("", reopened.formatCardEditorController.state.value.card?.name)
            } finally { reopened.close() }
        }
    }

    @Test fun `committed default cache is refreshed before warning clears`() = runBlocking {
        fixture { _, app, _ ->
            val source = FormatCard.create("Default", "Body").copy(isDefault = true)
            app.formatCardRepository.save(source)
            var refreshAttempts = 0
            val editor = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                persistCard = { card ->
                    app.jsonFileStorage.saveEntity("format_cards", card.id, card, FormatCard.serializer())
                    error("cache refresh failed after commit")
                }, refreshRepository = {
                    refreshAttempts++
                    if (refreshAttempts == 1) error("still unavailable")
                    app.formatCardRepository.refreshFromStorage()
                })
            try {
                editor.openExisting(source.id)
                editor.edit { it.copy(isDefault = false) }
                assertTrue(editor.save())
                assertEquals(FormatEditorProblem.SAVE_COMMITTED_WARNING, editor.state.value.problem)
                assertEquals(source.id, app.formatCardRepository.getDefault()?.id)
                editor.retryCleanup()
                assertNull(editor.state.value.problem)
                assertNull(app.formatCardRepository.getDefault())
                assertEquals(false, app.formatCardRepository.getById(source.id)?.isDefault)
                assertEquals(2, refreshAttempts)
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `committed new FormatCard draft is not reopened as unsaved creation`() = runBlocking {
        fixture { root, app, _ ->
            val editor = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                deleteDraft = { _, _ -> Unit })
            editor.openNew()
            editor.edit { it.copy(name = "Committed", content = "Body") }
            assertTrue(editor.save())
            val id = editor.state.value.card!!.id
            editor.closeAndDrain()
            val blocked = DesktopFormatCardEditorController(app.formatCardRepository, app.editorDraftRepository,
                deleteDraft = { _, _ -> Unit })
            blocked.openNew()
            assertEquals(FormatEditorProblem.SAVE_COMMITTED_WARNING, blocked.state.value.problem)
            assertFalse(blocked.state.value.dirty)
            assertEquals(id, blocked.state.value.targetId)
            blocked.closeAndDrain()
            val reopened = container(root)
            try {
                reopened.formatCardEditorController.openNew()
                assertEquals(id, reopened.formatCardEditorController.state.value.targetId)
                assertFalse(reopened.formatCardEditorController.state.value.dirty)
                assertNull(reopened.formatCardEditorController.state.value.problem)
                assertFalse(reopened.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
                assertEquals(id, reopened.formatCardRepository.getAll().single().id)
            } finally { reopened.close() }
        }
    }

    @Test fun `FormatCard drain persists dirty work before debounce completes`() = runBlocking {
        fixture { root, app, controller ->
            controller.openNew()
            controller.edit { it.copy(name = "Unflushed", content = "Body") }
            assertFalse(controller.state.value.draftPersisted)
            controller.closeAndDrain()
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.FORMAT_CARD, null))
            val reopened = container(root)
            try {
                reopened.formatCardEditorController.openNew()
                assertEquals("Unflushed", reopened.formatCardEditorController.state.value.card?.name)
            } finally { reopened.close() }
        }
    }

    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopFormatCardEditorController) -> Unit) {
        val parent = Files.createTempDirectory("desktop-format-editor-")
        val root = parent.resolve("app-data")
        val app = container(root)
        try { block(root, app, app.formatCardEditorController) }
        finally { app.close(); parent.toFile().deleteRecursively() }
    }

    private fun container(root: Path): DesktopAppContainer = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
}
