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
    @Test fun `orphan Save as New survives restart and deletion of the original draft with independent attachments`() = runBlocking {
        orphanCopyAfterRestart(legacySharedReference = false)
    }

    @Test fun `legacy cross draft attachment references survive cold management deletion and formal save`() = runBlocking {
        orphanCopyAfterRestart(legacySharedReference = true)
    }

    private suspend fun orphanCopyAfterRestart(legacySharedReference: Boolean) {
        val parent = Files.createTempDirectory("orphan-copy-restart-")
        val root = Files.createDirectories(parent.resolve("data"))
        fun reopen() = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        var app = reopen()
        try {
            val original = source()
            app.characterRepository.save(original)
            val editor = app.characterEditorController
            editor.openExisting(original.id)
            assertTrue(editor.addTextDocument("retained.txt", "SYNTHETIC_ATTACHMENT_BYTES"))
            editor.flushDraft()
            val old = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
            val oldReference = old.characterPayload!!.customDocuments.single().filePath
            app.close()
            app = reopen()
            app.characterRepository.delete(original.id)
            app.characterEditorController.openExisting(original.id)
            app.characterEditorController.saveAsNew()
            var survivor = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, null))
            if (legacySharedReference) {
                survivor = survivor.copy(characterPayload = survivor.characterPayload!!.copy(
                    customDocuments = old.characterPayload!!.customDocuments), draftAssetPaths = listOf(oldReference))
            }
            // Stop at the durable new draft, before the guarded UI Save/Discard decision.
            app.close()
            if (legacySharedReference) EditorDraftRepository(JsonFileStorage(root)).save(survivor)
            app = reopen()
            val management = app.createManagementController(app.createTypedTransferController())
            management.requestDiscard(old)
            management.confirmDeletion()
            assertNull(management.state.value.error)
            assertNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
            val recovered = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, null))
            val reference = recovered.characterPayload!!.customDocuments.single().filePath
            assertTrue(Files.isRegularFile(root.resolve(reference)), "Deleting the original draft removed the surviving draft attachment")
            assertEquals("SYNTHETIC_ATTACHMENT_BYTES", Files.readString(root.resolve(reference)))
            if (!legacySharedReference) assertNotEquals(oldReference, reference)
            app.characterEditorController.openNew()
            assertTrue(app.characterEditorController.save())
            val saved = assertNotNull(CharacterRepository(JsonFileStorage(root)).getById(recovered.characterPayload!!.id))
            assertEquals("SYNTHETIC_ATTACHMENT_BYTES", app.characterResourceStore.readText(saved.customDocuments.single().filePath))
        } finally {
            app.close()
            parent.toFile().deleteRecursively()
        }
    }

    @Test fun `Windows relative and absolute case aliases retain attachments until the last draft is deleted`() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        for (absolute in listOf(false, true)) for (uppercase in listOf(false, true)) fixture { app, _ ->
            Files.createDirectories(app.appDataRoot)
            val resources = DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore)
            val session = "abcdef00-1234-1234-1234-abcdef123456"
            val staged = resources.stageText(session, "retained.txt", "SYNTHETIC_CASE_BYTES")
            val alias = if (uppercase) staged.replace(session, session.uppercase()) else staged
            val reference = if (absolute) app.appDataRoot.resolve(alias).toString() else alias
            assertEquals("SYNTHETIC_CASE_BYTES", resources.readDocument(reference))
            val card = source()
            val document = DocumentInfo("case-doc", "retained.txt", reference, "txt", 1)
            val first = app.editorDraftRepository.save(app.editorDraftRepository.characterDraft(card.id, session,
                card.copy(customDocuments = listOf(document)), card, listOf(reference), emptyList(), emptyList()))
            val last = app.editorDraftRepository.save(app.editorDraftRepository.characterDraft(null, UUID.randomUUID().toString(),
                card.copy(id = UUID.randomUUID().toString(), customDocuments = listOf(document)), null,
                listOf(reference), emptyList(), emptyList()))
            val management = app.createManagementController(app.createTypedTransferController())
            management.requestDiscard(first); management.confirmDeletion()
            assertNull(management.state.value.error)
            assertEquals("SYNTHETIC_CASE_BYTES", resources.readDocument(reference))
            management.requestDiscard(last); management.confirmDeletion()
            assertNull(management.state.value.error)
            assertFalse(Files.exists(app.appDataRoot.resolve("draft_assets/$session")), "The last draft must retire its inherited directory")
        }
    }

    @Test fun `orphan copy can retry a transient resource failure with the original source and draft retained`() = runBlocking {
        fixture { app, editor ->
            val original = source()
            app.characterRepository.save(original)
            editor.openExisting(original.id)
            assertTrue(editor.addTextDocument("transient.txt", "SYNTHETIC_COPY_RETRY"))
            editor.flushDraft()
            val draft = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
            val reference = draft.characterPayload!!.customDocuments.single().filePath
            app.characterRepository.delete(original.id)
            val another = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json)
            try {
                another.openExisting(original.id)
                val sourcePath = app.appDataRoot.resolve(reference)
                Files.delete(sourcePath)
                another.saveAsNew()
                assertEquals(CharacterEditorProblem.SOURCE_DELETED, another.state.value.problem)
                assertEquals(original.id, another.state.value.targetId)
                val other = source("Another card")
                app.characterRepository.save(other)

                assertEquals(draft, coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
                Files.writeString(sourcePath, "SYNTHETIC_COPY_RETRY")
                another.saveAsNew()
                assertNull(another.state.value.problem)
                val copied = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertEquals("SYNTHETIC_COPY_RETRY", Files.readString(app.appDataRoot.resolve(copied.characterPayload!!.customDocuments.single().filePath)))
            } finally { another.closeAndDrain() }
        }
    }

    @Test fun `failed inherited attachment directory cleanup retries the actual owner after its last draft is removed`() = runBlocking {
        fixture { app, _ ->
            val resources = DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore)
            val inherited = UUID.randomUUID().toString()
            val card = source()
            app.characterRepository.save(card) // Establish the real owned root before staging.
            val reference = resources.stageText(inherited, "legacy.txt", "INHERITED_RETRY_BYTES")
            val document = DocumentInfo("legacy", "legacy.txt", reference, "txt", 21)
            val draft = app.editorDraftRepository.save(app.editorDraftRepository.characterDraft(null,
                UUID.randomUUID().toString(), card.copy(customDocuments = listOf(document)), null,
                listOf(reference), emptyList(), emptyList()))
            var failed = false
            val editor = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository, resources, app.jsonFileStorage.json,
                discardDraftAssets = { session ->
                    if (session == inherited && !failed) { failed = true; throw IOException("Synthetic inherited directory busy") }
                    resources.discardSession(session)
                })
            try {
                // Management uses this editor's existing retry queue, including when no card is open.
                val management = DesktopManagementController(app.characterRepository, app.formatCardRepository,
                    app.worldBookRepository, app.chatRepository, app.editorDraftRepository,
                    app.characterTransfers, app.formatTransfers, app.worldBookTransfers,
                    app.createTypedTransferController(), editor, app.formatCardEditorController,
                    app.worldBookEditorController, discardAssets = { session ->
                        if (session == inherited && !failed) { failed = true; throw IOException("Synthetic inherited directory busy") }
                        resources.discardSession(session)
                    })
                management.requestDiscard(draft); management.confirmDeletion()
                assertTrue(failed)
                assertTrue(editor.state.value.resourceCleanupPending)
                assertTrue(Files.exists(app.appDataRoot.resolve(reference)))
                assertNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, null))
                editor.retryCleanup()
                assertFalse(editor.state.value.resourceCleanupPending)
                assertFalse(Files.exists(app.appDataRoot.resolve("draft_assets/$inherited")),
                    "Retry must retire the failed inherited owner, not only the deleted draft's own empty directory")
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `opening another ordinary card clears only the prior copy failure feedback`() = runBlocking {
        fixture { app, editor ->
            val card = source(); val other = source("Another ordinary card")
            app.characterRepository.save(card); app.characterRepository.save(other)
            editor.openExisting(card.id)
            editor.addTextDocument("missing.txt", "COPY_FAILURE")
            editor.flushDraft()
            val path = app.appDataRoot.resolve(editor.state.value.card!!.customDocuments.single().filePath)
            app.characterRepository.save(card.copy(creatorNotes = "Concurrent source edit"))
            val recovered = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json)
            try {
                recovered.openExisting(card.id); Files.delete(path); recovered.saveAsNew()
                assertNotNull(recovered.state.value.detail)
                recovered.discard(); recovered.openExisting(other.id)
                assertEquals(other.id, recovered.state.value.targetId)
                assertNull(recovered.state.value.detail, "Copy failure belongs only to the failed draft")
            } finally { recovered.closeAndDrain() }
        }
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
                            while (!writingOldSnapshot.isCompleted && savingThread.get()?.let { thread ->
                                    thread.state == Thread.State.BLOCKED &&
                                        thread.stackTrace.any { it.methodName == "reserveSaveSnapshot" }
                                } != true) delay(10)
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

    @Test fun `close stays on the editor until cleanup confirms draft deletion`() = runBlocking {
        fixture { app, _ ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var rejectDelete = true
            val editor = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json,
                deleteDraft = { type, id ->
                    entered.complete(Unit)
                    release.await()
                    if (rejectDelete) error("synthetic draft deletion failure")
                    app.editorDraftRepository.deleteForTarget(type, id)
                })
            val original = source()
            app.characterRepository.save(original)
            try {
                editor.openExisting(original.id)
                editor.edit { it.copy(creatorNotes = "Obsolete draft") }
                editor.flushDraft()
                editor.edit { it.copy(creatorNotes = original.creatorNotes) }
                editor.closeClean()
                withTimeout(5_000) { entered.await() }
                editor.closeClean()
                assertNotNull(editor.state.value.card, "A second close bypassed the pending confirmation")
                release.complete(Unit)
                withTimeout(5_000) {
                    while (editor.state.value.problem != CharacterEditorProblem.CLEAN_DRAFT_WARNING) delay(10)
                }
                assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
                assertNotNull(editor.state.value.card)
                rejectDelete = false
                withTimeout(5_000) {
                    while (editor.state.value.cleanDraftCleanupInProgress) delay(10)
                }
                editor.retryCleanup()
                editor.closeClean()
                withTimeout(5_000) { while (editor.state.value.card != null) delay(10) }
                assertNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
            } finally { release.complete(Unit); editor.closeAndDrain() }
        }
    }

    @Test fun `old draft publication waits for the resource edit and never marks a newer image written`() = runBlocking {
        fixture { app, editor -> coroutineScope {
            val original = source()
            app.characterRepository.save(original)
            editor.openExisting(original.id)
            editor.edit { it.copy(creatorNotes = "Snapshot before image") }
            val basis = assertNotNull(editor.state.value.card)
            val session = assertNotNull(editor.state.value.draftSessionId)
            val bytes = DesktopImageEditing.png(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB))
            val applying = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val image = async(Dispatchers.IO) {
                editor.stageGeneratedCardImage(session, basis, basis, null, bytes) { next ->
                    editor.edit {
                        applying.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                        next
                    }
                    editor.state.value.card == next
                }
            }
            val writer = AtomicReference<Thread>()
            val dispatcher = Executors.newSingleThreadExecutor { work ->
                Thread(work, "character-autosave-test").also { writer.set(it) }
            }.asCoroutineDispatcher()
            try {
                withTimeout(5_000) { applying.await() }
                val flushing = async(dispatcher) { editor.flushDraft() }
                try {
                    withTimeout(5_000) {
                        while (!flushing.isCompleted && writer.get()?.let { thread ->
                                thread.state == Thread.State.BLOCKED &&
                                    thread.stackTrace.any { it.className.contains("DesktopCharacterEditorController") }
                            } != true) delay(10)
                    }
                    assertFalse(flushing.isCompleted, "Draft publication raced the in-progress resource edit")
                    val oldDraft = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
                    assertNull(oldDraft.characterPayload?.coverImage)
                    release.countDown()
                    assertTrue(image.await())
                    flushing.await()
                    if (editor.state.value.draftPersisted) {
                        val latest = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
                        assertNotNull(latest.characterPayload?.coverImage, "The prior snapshot cannot mark a newer image persisted")
                    }
                    editor.flushDraft()
                    val current = assertNotNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
                    assertContentEquals(bytes, editor.imageBytes(assertNotNull(current.characterPayload?.coverImage)))
                } finally { release.countDown(); flushing.cancelAndJoin() }
            } finally { release.countDown(); image.cancelAndJoin(); dispatcher.close() }
        } }
    }

    @Test fun `discard reserves the staged session before suspension and rejects a pending generated image`() = runBlocking {
        fixture { app, _ -> coroutineScope {
            val deleting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val editor = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json,
                deleteDraft = { type, id ->
                    deleting.complete(Unit)
                    release.await()
                    app.editorDraftRepository.deleteForTarget(type, id)
                })
            val original = source()
            app.characterRepository.save(original)
            try {
                editor.openExisting(original.id)
                editor.edit { it.copy(creatorNotes = "Draft being discarded") }
                editor.flushDraft()
                val session = assertNotNull(editor.state.value.draftSessionId)
                val basis = assertNotNull(editor.state.value.card)
                val bytes = DesktopImageEditing.png(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB))
                val discarding = async { editor.discard() }
                try {
                    withTimeout(5_000) { deleting.await() }
                    assertFalse(withContext(Dispatchers.IO) {
                        editor.stageGeneratedCardImage(session, basis, basis, null, bytes)
                    })
                    assertNull(editor.state.value.card?.coverImage)
                    release.complete(Unit)
                    discarding.await()
                    assertNull(editor.state.value.card)
                    assertNull(coldDrafts(app).getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
                    assertFalse(Files.exists(app.appDataRoot.resolve("draft_assets").resolve(session)))
                } finally { release.complete(Unit); discarding.cancelAndJoin() }
            } finally { release.complete(Unit); editor.closeAndDrain() }
        } }
    }

    @Test fun `document form reports a stale session rejection and can resubmit the same input`() = runBlocking {
        fixture { app, _ -> coroutineScope {
            val captured = CompletableDeferred<Unit>()
            val releaseDocument = CountDownLatch(1)
            val pause = AtomicBoolean(true)
            val editor = DesktopCharacterEditorController(app.characterRepository, app.editorDraftRepository,
                app.worldBookRepository, app.formatCardRepository, app.chatRepository,
                DesktopCharacterDraftResources(app.appDataRoot, app.characterResourceStore), app.jsonFileStorage.json,
                manualResourceAccess = { operation ->
                    if (pause.compareAndSet(true, false)) {
                        captured.complete(Unit)
                        check(releaseDocument.await(10, TimeUnit.SECONDS))
                    }
                    operation()
                })
            val original = source()
            app.characterRepository.save(original)
            try {
                editor.openExisting(original.id)
                editor.edit { it.copy(creatorNotes = "Obsolete draft") }
                editor.flushDraft()
                editor.edit { it.copy(creatorNotes = original.creatorNotes) }
                val title = "new form.txt"
                val body = "User input retained for retry"
                val adding = async(Dispatchers.IO) { editor.addTextDocument(title, body) }
                try {
                    withTimeout(5_000) { captured.await() }
                    assertTrue(editor.save(), "Clean save rotates the draft session")
                    releaseDocument.countDown()
                    assertFalse(adding.await(), "The UI must retain the form contents when the add is rejected")
                    assertTrue(editor.state.value.card!!.customDocuments.isEmpty())
                    assertTrue(editor.addTextDocument(title, body))
                    assertTrue(editor.save())
                    val durable = assertNotNull(CharacterRepository(JsonFileStorage(app.appDataRoot)).getById(original.id))
                    val document = durable.customDocuments.single()
                    assertEquals(title, document.fileName)
                    assertEquals(body, app.characterResourceStore.readText(document.filePath))
                } finally { releaseDocument.countDown(); adding.cancelAndJoin() }
            } finally { editor.closeAndDrain() }
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
