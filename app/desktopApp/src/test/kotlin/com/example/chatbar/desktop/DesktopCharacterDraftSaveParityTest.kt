package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopCharacterDraftSaveParityTest {
    private suspend fun fixture(block: suspend (DesktopAppContainer, DesktopCharacterEditorController) -> Unit) {
        val parent = Files.createTempDirectory("character-draft-parity-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        try { block(app, app.characterEditorController) }
        finally { app.close(); parent.toFile().deleteRecursively() }
    }
    private fun source(name: String = "Synthetic A") = CharacterCard.create(name, greeting = "Synthetic greeting").copy(
        extensions = """{"unknown_vendor":{"retained":[1,2,3]}}""", creatorNotes = "Original notes")
    private fun coldDrafts(app: DesktopAppContainer) = EditorDraftRepository(JsonFileStorage(app.appDataRoot))

    @Test fun `formal save cancels pending autosave and reentering same or another card cannot recreate its draft`() = runBlocking {
        fixture { app, editor ->
            val a = source(); val b = source("Synthetic B")
            app.characterRepository.save(a); app.characterRepository.save(b)
            editor.openExisting(a.id)
            editor.edit { it.copy(creatorNotes = "Saved notes") }
            // No explicit draft flush: formal Save owns cancelling/draining the queued debounce.
            assertTrue(editor.save())
            assertFalse(editor.state.value.dirty)
            delay(950)
            assertFalse(coldDrafts(app).existsForTarget(EditorDraftType.CHARACTER_CARD, a.id))
            editor.closeClean(); editor.openExisting(a.id)
            assertFalse(editor.state.value.dirty)
            assertFalse(editor.state.value.draftPersisted)
            assertEquals("Saved notes", editor.state.value.card!!.creatorNotes)
            editor.closeClean(); editor.openExisting(b.id); editor.closeClean(); editor.openExisting(a.id)
            delay(850)
            assertFalse(coldDrafts(app).existsForTarget(EditorDraftType.CHARACTER_CARD, a.id))
            assertFalse(editor.state.value.dirty)
            val durable = CharacterRepository(JsonFileStorage(app.appDataRoot)).getById(a.id)!!
            assertEquals(a.extensions, durable.extensions)
            assertEquals("Saved notes", durable.creatorNotes)
        }
    }

    @Test fun `derived only recovered card draft is clean while real document edits and full payload remain`() = runBlocking {
        fixture { app, editor ->
            val doc = DocumentInfo("doc", "notes.txt", "synthetic-not-opened.txt", "txt", 1,
                contentHash = "current-content", indexedHash = "current-index", ragStatus = "INDEXED", ragChunkCount = 7,
                ragIndexedAt = 4, ragError = null)
            val a = source().copy(customDocuments = listOf(doc), ragIndexStatus = "INDEXED", ragIndexDone = 1,
                ragIndexTotal = 1, ragIndexedAt = 4)
            app.characterRepository.save(a)
            val payload = a.copy(updatedAt = a.updatedAt + 10, ragIndexStatus = "INDEXING", ragIndexDone = 0,
                ragIndexMessage = "Synthetic progress", customDocuments = listOf(doc.copy(contentHash = "old-content",
                    indexedHash = null, ragStatus = "PENDING", ragChunkCount = 0, ragIndexedAt = null, ragError = "Synthetic old status")))
            val draft = app.editorDraftRepository.characterDraft(a.id, UUID.randomUUID().toString(), payload, a,
                emptyList(), emptyList(), emptyList())
            app.editorDraftRepository.save(draft)
            editor.openExisting(a.id)
            assertFalse(editor.state.value.dirty, "Derived-only document/index progress must not become a user edit")
            assertEquals(payload.extensions, editor.state.value.card!!.extensions)
            assertEquals(payload.customDocuments, editor.state.value.card!!.customDocuments,
                "Comparison must not strip metadata from the retained payload")
            editor.closeClean()
            withTimeout(5_000) { while (editor.state.value.card != null) delay(20) }
            assertFalse(coldDrafts(app).existsForTarget(EditorDraftType.CHARACTER_CARD, a.id))
            editor.openExisting(a.id)
            editor.edit { it.copy(customDocuments = it.customDocuments.map { d -> d.copy(fileName = "renamed.txt") }) }
            assertTrue(editor.state.value.dirty, "A real document-name edit must still count")
            editor.flushDraft()
            val savedDraft = coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, a.id)!!.characterPayload!!
            assertEquals("renamed.txt", savedDraft.customDocuments.single().fileName)
            assertEquals(doc.contentHash, savedDraft.customDocuments.single().contentHash)
            assertEquals(doc.indexedHash, savedDraft.customDocuments.single().indexedHash)
            assertEquals(a.extensions, savedDraft.extensions)
            assertEquals(a, CharacterRepository(JsonFileStorage(app.appDataRoot)).getById(a.id))
        }
    }

    @Test fun `an edit accepted while save preflight waits must survive instead of being overwritten or cleaned`() = runBlocking {
        fixture { app, editor ->
            val a = source()
            app.characterRepository.save(a); editor.openExisting(a.id)
            editor.edit { it.copy(creatorNotes = "Earlier submitted edit") }
            editor.flushDraft()
            val locked = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val blocker = async(Dispatchers.IO) {
                // The real entity mutex stalls getById after Save has captured its original form.
                app.jsonFileStorage.mutateEntitiesAtomically("character_cards", setOf(a.id), CharacterCard.serializer()) {
                    locked.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                    emptyMap()
                }
            }
            locked.await()
            val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.save() }
            try {
                assertFalse(saving.isCompleted)
                assertFalse(editor.state.value.busy)
                editor.edit { it.copy(creatorNotes = "Latest accepted edit") }
                assertEquals("Latest accepted edit", editor.state.value.card!!.creatorNotes)
                withTimeout(5_000) { while (!editor.state.value.draftPersisted) delay(20) }
            } finally { release.countDown() }
            blocker.await()
            saving.await()
            assertEquals("Latest accepted edit", editor.state.value.card!!.creatorNotes,
                "Save preflight must not replace a newer accepted form with its old capture")
            assertTrue(editor.state.value.dirty)
            editor.flushDraft()
            assertEquals("Latest accepted edit", coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, a.id)?.characterPayload?.creatorNotes)
            assertEquals(a.extensions, editor.state.value.card!!.extensions)
        }
    }
}
