package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.EditorDraftRepository
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
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

    @Test fun `generated image edit and formal save cannot publish competing snapshots`() = runBlocking {
        fixture { app, _ -> coroutineScope {
            val savingThread = AtomicReference<Thread>()
            val writingOldSnapshot = CompletableDeferred<Unit>()
            val saveDispatcher = Executors.newSingleThreadExecutor { work ->
                Thread(work, "character-save-test").also { savingThread.set(it) }
            }.asCoroutineDispatcher()
            val editEntered = CompletableDeferred<Unit>()
            val releaseEdit = CountDownLatch(1)
            val editor = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json,
                persistCharacter = {
                    writingOldSnapshot.complete(Unit)
                    app.characterRepository.save(it)
                })
            val original = source()
            app.characterRepository.save(original)
            try {
                editor.openExisting(original.id)
                editor.edit { it.copy(creatorNotes = "An edit ready to save") }
                editor.flushDraft()
                val basis = assertNotNull(editor.state.value.card)
                val session = assertNotNull(editor.state.value.draftSessionId)
                val bytes = DesktopImageEditing.png(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB))
                val applying = async(Dispatchers.IO) {
                    editor.stageGeneratedCardImage(session, basis, basis, null, bytes) { next ->
                        editor.edit {
                            editEntered.complete(Unit)
                            check(releaseEdit.await(10, TimeUnit.SECONDS))
                            next
                        }
                        editor.state.value.card == next
                    }
                }
                try {
                    withTimeout(5_000) { editEntered.await() }
                    val saving = async(saveDispatcher) { editor.save() }
                    try {
                        // The previous implementation enters persistence here. The fixed save waits
                        // at the same lock used by the already admitted image assignment.
                        withTimeout(5_000) {
                            while (!writingOldSnapshot.isCompleted && savingThread.get()?.stackTrace.orEmpty()
                                    .none { it.methodName == "reserveSaveSnapshot" }) delay(10)
                        }
                        assertFalse(writingOldSnapshot.isCompleted, "Save reserved the old card during image assignment")
                        releaseEdit.countDown()
                        assertTrue(applying.await())
                        assertFalse(saving.await(), "The stale preflight must yield to the newer accepted edit")
                        editor.flushDraft()
                        val draft = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
                        val staged = assertNotNull(draft.characterPayload?.coverImage)
                        assertContentEquals(bytes, editor.imageBytes(staged))
                        assertTrue(editor.save())
                        val durable = assertNotNull(CharacterRepository(JsonFileStorage(app.appDataRoot)).getById(original.id))
                        assertEquals("An edit ready to save", durable.creatorNotes)
                        assertContentEquals(bytes, editor.imageBytes(assertNotNull(durable.coverImage)))
                    } finally { releaseEdit.countDown(); saving.cancelAndJoin() }
                } finally { releaseEdit.countDown(); applying.cancelAndJoin() }
            } finally { saveDispatcher.close(); editor.closeAndDrain() }
        } }
    }

    @Test fun `suspended person and document focus preserve the freshly rotated draft owner and its accepted attachment`() = runBlocking {
        for (personFocus in listOf(true, false)) fixture { app, _ -> coroutineScope {
            val sourceEntered = CompletableDeferred<Unit>(); val sourceRelease = CompletableDeferred<Unit>()
            val cleanupEntered = CompletableDeferred<Unit>(); val cleanupRelease = CompletableDeferred<Unit>()
            val armedSource = AtomicBoolean(); val armedCleanup = AtomicBoolean()
            val gate = object : AppDataOperationGate {
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                    if (armedSource.compareAndSet(true, false)) { sourceEntered.complete(Unit); sourceRelease.await() }
                    return operation()
                }
            }
            val cards = CharacterRepository(JsonFileStorage(app.appDataRoot, gate))
            val editor = DesktopCharacterEditorController(cards, app.editorDraftRepository, app.worldBookRepository,
                app.formatCardRepository, app.chatRepository, DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore),
                app.jsonFileStorage.json, deleteDraft = { type, id ->
                    if (armedCleanup.compareAndSet(true, false)) { cleanupEntered.complete(Unit); cleanupRelease.await() }
                    app.editorDraftRepository.deleteForTarget(type, id)
                })
            val person = CharacterInfo.create("Synthetic person")
            Files.createDirectories(app.appDataRoot) // This fixture materializes before any repository has initialized the root.
            val originalRef = app.characterResourceStore.materializeDocument(
                com.example.chatbar.domain.card.PackagedDocument("source.txt", "txt", "Original document"), 1, "focus-source")
            val doc = DocumentInfo.create("source.txt", originalRef, "txt")
            val a = source().copy(characters = listOf(person), customDocuments = listOf(doc))
            app.characterRepository.save(a)
            try {
                editor.openExisting(a.id)
                editor.edit { it.copy(creatorNotes = "Old persisted edit") }; editor.flushDraft()
                editor.edit { it.copy(creatorNotes = a.creatorNotes) }; editor.flushDraft()
                val oldSession = editor.state.value.draftSessionId
                armedSource.set(true)
                val focusing = async {
                    if (personFocus) editor.openPerson(a.id, person.id)
                    else editor.openDocument(a.id, doc.id) { error("Current document unexpectedly unavailable") }
                }
                try {
                    withTimeout(5_000) { sourceEntered.await() }
                    armedCleanup.set(true)
                    val cleaning = async { editor.save() }
                    try {
                        withTimeout(5_000) { cleanupEntered.await() }
                        val freshSession = editor.state.value.draftSessionId
                        assertNotEquals(oldSession, freshSession)
                        sourceRelease.complete(Unit); focusing.await()
                        editor.addTextDocument("focus-new.txt", "Attachment accepted after focus")
                        val added = editor.state.value.card!!.customDocuments.single { it.id != doc.id }
                        cleanupRelease.complete(Unit); assertFalse(cleaning.await()); editor.flushDraft()
                        assertTrue(Files.exists(app.appDataRoot.resolve(added.filePath)), "A resumed focus callback reinstalled the retired attachment directory")
                        assertEquals(freshSession, editor.state.value.draftSessionId)
                        assertEquals("Attachment accepted after focus", Files.readString(app.appDataRoot.resolve(added.filePath)))
                        val restored = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, a.id))
                        assertEquals(freshSession, restored.draftSessionId)
                        assertEquals(added, restored.characterPayload!!.customDocuments.single { it.id != doc.id })
                        assertTrue(editor.save())
                        val durable = assertNotNull(CharacterRepository(JsonFileStorage(app.appDataRoot)).getById(a.id))
                        assertEquals("Attachment accepted after focus", app.characterResourceStore.readText(durable.customDocuments.single { it.id != doc.id }.filePath))
                    } finally { cleanupRelease.complete(Unit); cleaning.cancelAndJoin() }
                } finally { sourceRelease.complete(Unit); focusing.cancelAndJoin() }
            } finally { sourceRelease.complete(Unit); cleanupRelease.complete(Unit); editor.closeAndDrain() }
        } }
    }

    @Test fun `ordinary edit cannot reinstall a retired resource session across obsolete draft admission`() = runBlocking {
        fixture { app, _ -> coroutineScope {
            val cleanupAdmitted = CompletableDeferred<Unit>()
            val admissionCount = java.util.concurrent.atomic.AtomicInteger()
            val armed = AtomicBoolean()
            val gate = object : AppDataOperationGate {
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                    if (armed.get() && admissionCount.incrementAndGet() == 2) cleanupAdmitted.complete(Unit)
                    return operation()
                }
            }
            val editor = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json, dataGate = gate)
            val a = source(); app.characterRepository.save(a); editor.openExisting(a.id)
            editor.edit { it.copy(creatorNotes = "Old persisted edit") }; editor.flushDraft()
            editor.edit { it.copy(creatorNotes = a.creatorNotes) }
            editor.flushDraft()
            val oldSession = editor.state.value.draftSessionId
            val captured = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val editing = async(Dispatchers.Default) {
                editor.edit {
                    captured.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                    it.copy(creatorNotes = "Accepted during cleanup admission")
                }
            }
            try {
                withTimeout(5_000) { captured.await() }
                armed.set(true)
                val saving = async(Dispatchers.Default) { editor.save() }
                try {
                    withTimeout(5_000) { cleanupAdmitted.await() }
                    release.countDown(); editing.await()
                    assertFalse(saving.await())
                    armed.set(false)
                    assertEquals(oldSession, editor.state.value.draftSessionId)
                    editor.addTextDocument("after-edit.txt", "Retained after old-state writer")
                    editor.flushDraft()
                    val doc = editor.state.value.card!!.customDocuments.single()
                    assertEquals("Retained after old-state writer", Files.readString(app.appDataRoot.resolve(doc.filePath)))
                    val draft = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, a.id))
                    assertEquals(doc, draft.characterPayload!!.customDocuments.single())
                    assertEquals("Accepted during cleanup admission", draft.characterPayload!!.creatorNotes)
                } finally { release.countDown(); saving.cancelAndJoin() }
            } finally { release.countDown(); editing.cancelAndJoin(); editor.closeAndDrain() }
        } }
    }

    @Test fun `clean draft deletion cannot erase attachments accepted while its old directory is being retired`() = runBlocking {
        fixture { app, _ -> coroutineScope {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val armed = AtomicBoolean()
            val editor = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json,
                deleteDraft = { type, id ->
                    if (armed.compareAndSet(true, false)) { entered.complete(Unit); release.await() }
                    app.editorDraftRepository.deleteForTarget(type, id)
                })
            val a = source()
            app.characterRepository.save(a)
            try {
                editor.openExisting(a.id)
                editor.edit { it.copy(creatorNotes = "Temporary draft edit") }
                editor.addTextDocument("obsolete.txt", "Old staged bytes")
                editor.flushDraft()
                val oldPath = editor.state.value.card!!.customDocuments.single().filePath
                editor.edit { it.copy(creatorNotes = a.creatorNotes, customDocuments = a.customDocuments) }
                assertFalse(editor.state.value.dirty)
                val oldSession = editor.state.value.draftSessionId
                armed.set(true)
                val saving = async { editor.save() }
                try {
                    withTimeout(5_000) { entered.await() }
                    editor.addTextDocument("new.txt", "New accepted bytes")
                    val newDoc = editor.state.value.card!!.customDocuments.single()
                    assertTrue(editor.state.value.dirty)
                    assertEquals("New accepted bytes", Files.readString(app.appDataRoot.resolve(newDoc.filePath)))
                    release.complete(Unit)
                    assertFalse(saving.await(), "A newer accepted edit remains pending for its own Save")
                    editor.flushDraft()
                    assertFalse(Files.exists(app.appDataRoot.resolve(oldPath)))
                    assertTrue(Files.exists(app.appDataRoot.resolve(newDoc.filePath)), "Accepted new attachment was erased by old-session cleanup")
                    assertNotEquals(oldSession, editor.state.value.draftSessionId)
                    val draft = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, a.id))
                    assertEquals(newDoc, draft.characterPayload!!.customDocuments.single())
                    assertEquals("New accepted bytes", Files.readString(app.appDataRoot.resolve(newDoc.filePath)))
                } finally { release.complete(Unit); saving.cancelAndJoin() }
            } finally { release.complete(Unit); editor.closeAndDrain() }
            val reopened = app.characterEditorController
            reopened.openExisting(a.id)
            val restored = reopened.state.value.card!!.customDocuments.single()
            assertEquals("New accepted bytes", Files.readString(app.appDataRoot.resolve(restored.filePath)))
            assertTrue(reopened.save())
            val durable = assertNotNull(CharacterRepository(JsonFileStorage(app.appDataRoot)).getById(a.id)).customDocuments.single()
            assertFalse(durable.filePath.startsWith("draft_assets/"))
            assertEquals("New accepted bytes", app.characterResourceStore.readText(durable.filePath))
        } }
    }

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

    @Test fun `late save preflight failure cannot mark a newer form or another card as failed`() = runBlocking {
        for (switchOwner in listOf(false, true)) fixture { app, _ -> coroutineScope {
            val armed = AtomicBoolean(false)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gate = object : AppDataOperationGate {
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                    if (armed.compareAndSet(true, false)) {
                        entered.complete(Unit)
                        release.await()
                        throw IOException("Synthetic preflight read failure")
                    }
                    return operation()
                }
            }
            val cards = CharacterRepository(JsonFileStorage(app.appDataRoot, gate))
            val editor = DesktopCharacterEditorController(cards, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json)
            val a = source(); val b = source("Synthetic B")
            app.characterRepository.save(a); app.characterRepository.save(b)
            try {
                cards.initialize()
                editor.openExisting(a.id)
                editor.edit { it.copy(creatorNotes = "Old save attempt") }
                editor.flushDraft()
                armed.set(true)
                val saving = async { editor.save() }
                try {
                    withTimeout(5_000) { entered.await() }
                    if (switchOwner) { editor.discard(); editor.openExisting(b.id) }
                    editor.edit { it.copy(creatorNotes = "Newer accepted form") }
                    val expected = editor.state.value.card
                    assertNull(editor.state.value.problem)
                    release.complete(Unit)
                    assertFalse(saving.await())
                    assertEquals(expected, editor.state.value.card)
                    assertNull(editor.state.value.problem, "Old preflight errors belong to their captured form only")
                    assertEquals(a, app.characterRepository.getById(a.id))
                    assertEquals(b, app.characterRepository.getById(b.id))
                } finally { release.complete(Unit); saving.cancelAndJoin() }
            } finally { release.complete(Unit); editor.closeAndDrain() }
        } }
    }
}
