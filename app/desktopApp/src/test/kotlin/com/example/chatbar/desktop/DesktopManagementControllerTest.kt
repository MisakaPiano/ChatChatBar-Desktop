package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.card.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.test.*

class DesktopManagementControllerTest {
    @Test fun `management construction is zero write and empty refresh creates no business files`() = runBlocking {
        val parent = Files.createTempDirectory("management-zero-write-")
        val root = parent.resolve("absent")
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")))
        try {
            val management = container.createManagementController(container.createTypedTransferController(Picker()))
            assertFalse(Files.exists(root))
            management.refresh()
            assertNull(management.state.value.error)
            // Shared storage reads may create empty entity directories, never default entities here.
            Files.walk(root).use { paths -> assertFalse(paths.anyMatch { Files.isRegularFile(it) }) }
        } finally { container.close(); parent.toFile().deleteRecursively() }
    }

    @Test fun `draft presentation separates existing badges new recovery and missing targets by type`() = runBlocking {
        fixture { f ->
            val existing = f.draft(EditorDraftType.FORMAT_CARD, existing = true)
            val new = f.draft(EditorDraftType.FORMAT_CARD)
            f.draft(EditorDraftType.WORLD_BOOK)
            val all = f.c.editorDraftRepository.getAll()
            val rows = desktopManagementDraftRows(all, DesktopTransferKind.FORMAT, setOf(existing.targetId!!))
            assertEquals(mapOf(existing.targetId!! to existing), rows.badges)
            assertEquals(listOf(new), rows.recoverable)
            assertEquals(setOf(existing.id, new.id), desktopManagementDraftRows(all, DesktopTransferKind.FORMAT, emptySet()).recoverable.map { it.id }.toSet())
        }
    }

    @Test fun `WorldBook guard does not add boundWorldBook or embedded book semantics`() = runBlocking {
        fixture { f ->
            val book = WorldBook.create("Only embedded")
            f.c.worldBookRepository.save(book)
            val card = CharacterCard.create("Character").copy(characterBook = book, boundWorldBookId = book.id)
            f.c.characterRepository.save(card)
            f.management.requestDelete(DesktopTransferKind.WORLD_BOOK, book.id, book.name)
            f.management.confirmDeletion()
            assertNull(f.c.worldBookRepository.getById(book.id))
            assertEquals(card, f.c.characterRepository.getById(card.id))
        }
    }

    @Test fun `duplicate community Character is independent editable local copy with owned resources`() = runBlocking {
        fixture { f ->
            val imported = f.c.characterTransfers.importNew(CharacterCardPackage(
                card = PackagedCharacterCard(name = "Community", avatarResourceId = "avatar",
                    chatBackgroundResourceId = "background", characters = listOf(
                        PackagedCharacter(name = "Person", appearanceImageResourceId = "appearance"))),
                images = mapOf("avatar" to PackagedImage("avatar.png", PNG),
                    "background" to PackagedImage("background.png", PNG),
                    "appearance" to PackagedImage("appearance.png", PNG)),
                documents = listOf(PackagedDocument("notes", "txt", "independent document")),
            ))
            val source = imported.copy(communityItemId = "community-source")
            f.c.characterRepository.save(source)
            f.management.duplicate(DesktopTransferKind.CHARACTER, source.id)
            val copy = f.c.characterRepository.getAll().single { it.id != source.id }
            assertFalse(copy.isCommunityDownload)
            assertNotEquals(source.name, copy.name)
            assertNotEquals(source.avatar, copy.avatar)
            assertContentEquals(Files.readAllBytes(f.root.resolve(source.avatar!!)), Files.readAllBytes(f.root.resolve(copy.avatar!!)))
            assertNotEquals(source.chatBackground, copy.chatBackground)
            assertContentEquals(Files.readAllBytes(f.root.resolve(source.chatBackground!!)), Files.readAllBytes(f.root.resolve(copy.chatBackground!!)))
            assertNotEquals(source.characters.single().appearanceImage, copy.characters.single().appearanceImage)
            assertContentEquals(Files.readAllBytes(f.root.resolve(source.characters.single().appearanceImage!!)),
                Files.readAllBytes(f.root.resolve(copy.characters.single().appearanceImage!!)))
            assertNotEquals(source.customDocuments.single().filePath, copy.customDocuments.single().filePath)
            assertEquals("independent document", Files.readString(f.root.resolve(copy.customDocuments.single().filePath)))
            assertEquals(source, f.c.characterRepository.getById(source.id))
            f.c.characterTransfers.deleteCard(copy.id)
            assertTrue(Files.exists(f.root.resolve(source.avatar)))
            assertTrue(Files.exists(f.root.resolve(source.chatBackground)))
            assertTrue(Files.exists(f.root.resolve(source.characters.single().appearanceImage!!)))
            assertTrue(Files.exists(f.root.resolve(source.customDocuments.single().filePath)))
            assertFalse(copy.isCommunityDownload)
        }
    }

    @Test fun `Character delete needs confirmation and leaves session history archived`() = runBlocking {
        fixture { f ->
            val card = CharacterCard.create("Delete")
            f.c.characterRepository.save(card)
            val session = ChatSession(id = "session", characterCardId = card.id, title = "History", createdAt = 1, updatedAt = 1)
            f.c.chatRepository.createSession(session)
            f.c.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, "Retained"))
            f.management.requestDelete(DesktopTransferKind.CHARACTER, card.id, card.name)
            assertNotNull(f.c.characterRepository.getById(card.id))
            f.management.confirmDeletion()
            assertNull(f.c.characterRepository.getById(card.id))
            assertEquals(session.id, f.c.chatRepository.getSession(session.id)!!.id)
            assertEquals("Retained", f.c.chatRepository.getMessages(session.id).single().content)
            f.c.primaryChatController.refresh()
            assertTrue(f.c.primaryChatController.state.value.sessions.single().characterMissing)
            assertTrue(f.c.characterEditorController.state.value.characters.isEmpty())
        }
    }

    @Test fun `post commit Character delete failure reconciles without resurrection or replay`() = runBlocking {
        fixture { f ->
            var cleanupCalls = 0
            val core = f.c.createCharacterTransferCore(CharacterDocumentRagCleanup { cleanupCalls++; error("cleanup fault") })
            val management = f.controller(core = core)
            val card = CharacterCard.create("Delete")
            f.c.characterRepository.save(card)
            management.requestDelete(DesktopTransferKind.CHARACTER, card.id, card.name)
            management.confirmDeletion()
            assertNull(f.c.characterRepository.getById(card.id))
            assertTrue(f.c.characterEditorController.state.value.characters.isEmpty())
            assertEquals(DesktopUiText.MANAGE_DELETE_COMMITTED, management.state.value.warning)
            assertNull(management.state.value.pendingDeletion)
            management.refresh()
            assertEquals(DesktopUiText.MANAGE_DELETE_COMMITTED, management.state.value.warning)
            management.confirmDeletion()
            assertEquals(1, cleanupCalls)
        }
    }

    @Test fun `committed copy warning is distinct from delete and truthful about refresh`() = runBlocking {
        fixture { f ->
            val source = CharacterCard.create("Copy me")
            f.c.characterRepository.save(source)
            val fault: (String) -> Unit = { id -> throw CharacterTransferPostCommitException(
                CharacterTransferPostCommitOperation.DUPLICATE, id, "committed", IllegalStateException("cache")) }
            val management = f.controller(afterDuplicate = fault)
            management.duplicate(DesktopTransferKind.CHARACTER, source.id)
            assertEquals(2, f.c.characterEditorController.state.value.characters.size)
            assertEquals(DesktopUiText.MANAGE_DUPLICATE_COMMITTED, management.state.value.warning)
            assertNull(management.state.value.error)

            val failingRefresh = f.controller(afterDuplicate = fault, afterReconcile = { error("refresh failed") })
            failingRefresh.duplicate(DesktopTransferKind.CHARACTER, source.id)
            assertEquals(3, f.c.characterRepository.getAll().size)
            assertEquals(DesktopUiText.MANAGE_DUPLICATE_REFRESH_FAILED, failingRefresh.state.value.warning)
            assertNull(failingRefresh.state.value.error)
        }
    }

    @Test fun `committed delete with refresh failure remains a committed warning`() = runBlocking {
        fixture { f ->
            val card = CharacterCard.create("Delete")
            f.c.characterRepository.save(card)
            val core = f.c.createCharacterTransferCore(CharacterDocumentRagCleanup { error("cleanup failed") })
            val management = f.controller(core = core, afterReconcile = { error("refresh failed") })
            management.requestDelete(DesktopTransferKind.CHARACTER, card.id, card.name)
            management.confirmDeletion()
            assertNull(f.c.characterRepository.getById(card.id))
            assertEquals(DesktopUiText.MANAGE_DELETE_REFRESH_FAILED, management.state.value.warning)
            assertNull(management.state.value.error)
            assertNull(management.state.value.pendingDeletion)
        }
    }

    @Test fun `management refresh failure after typed commit keeps nonretryable committed notice`() = runBlocking {
        fixture { f ->
            val transfer = f.c.createTypedTransferController(f.picker, afterCharacterCommit = { operation, id ->
                throw CharacterTransferPostCommitException(operation, id, "committed", IllegalStateException("post-commit"))
            })
            val management = f.controller(transferController = transfer, afterReconcile = { error("management refresh") })
            f.picker.open = Files.writeString(f.root.resolve("incoming.json"),
                f.c.transferJson.encodeToString(CharacterCardPackage.serializer(),
                    CharacterCardPackage(card = PackagedCharacterCard(name = "Committed"))))
            management.import(DesktopTransferKind.CHARACTER)
            assertEquals(1, f.c.characterRepository.getAll().size)
            assertNull(transfer.state.value.pendingConflict)
            assertEquals(DesktopUiText.TRANSFER_IMPORT_REFRESH_FAILED, transfer.state.value.committedNotice?.uiText())
            assertNull(management.state.value.error)
            assertNull(transfer.state.value.error)
        }
    }

    @Test fun `Manage navigation waits until admitted mutation finishes and reconciles`() = runBlocking {
        fixture { f ->
            val card = CharacterCard.create("Delete")
            f.c.characterRepository.save(card)
            val cleanupEntered = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val core = f.c.createCharacterTransferCore(CharacterDocumentRagCleanup {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
            })
            val management = f.controller(core = core)
            val navigation = DesktopPrimaryNavigationController()
            val idle = DesktopDataRootSwitchState.Idle(f.root, DesktopDataRootProvenance.CLI_OVERRIDE, true)
            navigation.navigate(DesktopPrimaryRoute.MANAGE, idle)
            management.requestDelete(DesktopTransferKind.CHARACTER, card.id, card.name)
            val deleting = async { management.confirmDeletion() }
            cleanupEntered.await()
            assertFalse(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.CHAT, idle,
                management.state.value.busy, f.transfer.state.value.busy))
            releaseCleanup.complete(Unit)
            deleting.await()
            assertFalse(management.state.value.busy)
            assertTrue(f.c.characterEditorController.state.value.characters.isEmpty())
            assertTrue(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.CHAT, idle,
                management.state.value.busy, f.transfer.state.value.busy))
        }
    }

    @Test fun `navigation waits for committed delete and cancellation still reconciles`() = runBlocking {
        fixture { f ->
            val card = CharacterCard.create("Cancel after commit")
            f.c.characterRepository.save(card)
            val cleanupEntered = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val core = f.c.createCharacterTransferCore(CharacterDocumentRagCleanup {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
            })
            val management = f.controller(core = core)
            val navigation = DesktopPrimaryNavigationController()
            val idle = DesktopDataRootSwitchState.Idle(f.root, DesktopDataRootProvenance.CLI_OVERRIDE, true)
            assertTrue(navigation.navigate(DesktopPrimaryRoute.MANAGE, idle))
            management.requestDelete(DesktopTransferKind.CHARACTER, card.id, card.name)
            val deleting = async { management.confirmDeletion() }
            cleanupEntered.await()
            assertTrue(management.state.value.busy)
            assertFalse(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.CHAT, idle,
                management.state.value.busy, f.transfer.state.value.busy))
            assertEquals(DesktopPrimaryRoute.MANAGE, navigation.selectedRoute.value)
            deleting.cancel()
            releaseCleanup.complete(Unit)
            assertFailsWith<CancellationException> { deleting.await() }
            assertFalse(management.state.value.busy)
            assertNull(f.c.characterRepository.getById(card.id))
            assertTrue(f.c.characterEditorController.state.value.characters.isEmpty())
            assertNull(management.state.value.error)
            assertTrue(navigation.navigateFromManageWhenIdle(DesktopPrimaryRoute.CHAT, idle,
                management.state.value.busy, f.transfer.state.value.busy))
        }
    }

    @Test fun `Format duplicate and delete preserve global default and Character bindings`() = runBlocking {
        fixture { f ->
            val card = FormatCard.create("Format", "Inline").copy(isDefault = true)
            f.c.formatCardRepository.save(card)
            f.c.settingsRepository.saveAppSettings(AppSettings(defaultFormatCardId = card.id))
            val character = CharacterCard.create("Character").copy(defaultFormatCardId = card.id)
            f.c.characterRepository.save(character)
            f.management.duplicate(DesktopTransferKind.FORMAT, card.id)
            val copy = f.c.formatCardRepository.getAll().single { it.id != card.id }
            assertEquals(card.content, copy.content)
            assertFalse(copy.isDefault)
            f.management.requestDelete(DesktopTransferKind.FORMAT, card.id, card.name)
            f.management.confirmDeletion()
            assertNull(f.c.formatCardRepository.getById(card.id))
            assertEquals(card.id, f.c.settingsRepository.getAppSettings().defaultFormatCardId)
            assertEquals(card.id, f.c.characterRepository.getById(character.id)!!.defaultFormatCardId)
            assertEquals(listOf(copy.id), f.c.formatCardEditorController.state.value.cards.map { it.id })
        }
    }

    @Test fun `WorldBook duplicate renews entry identities and deletes when unreferenced`() = runBlocking {
        fixture { f ->
            val book = WorldBook.create("Book").copy(entries = listOf(WorldBookEntry(id = "entry", content = "inline")))
            f.c.worldBookRepository.save(book)
            f.management.duplicate(DesktopTransferKind.WORLD_BOOK, book.id)
            val copy = f.c.worldBookRepository.getAll().single { it.id != book.id }
            assertNotEquals(book.entries.single().id, copy.entries.single().id)
            assertEquals("inline", copy.entries.single().content)
            f.management.requestDelete(DesktopTransferKind.WORLD_BOOK, book.id, book.name)
            f.management.confirmDeletion()
            assertNull(f.c.worldBookRepository.getById(book.id))
            assertNotNull(f.c.worldBookRepository.getById(copy.id))
        }
    }

    @Test fun `WorldBook Character guard retains bindings and bounds names to three`() = runBlocking {
        fixture { f ->
            val book = WorldBook.create("Referenced")
            f.c.worldBookRepository.save(book)
            repeat(5) { f.c.characterRepository.save(CharacterCard.create("Character $it").copy(worldBookIds = listOf(book.id))) }
            f.management.requestDelete(DesktopTransferKind.WORLD_BOOK, book.id, book.name)
            f.management.confirmDeletion()
            assertNotNull(f.c.worldBookRepository.getById(book.id))
            assertEquals(3, f.management.state.value.characterReferences.size)
            assertEquals(DesktopUiText.MANAGE_WORLD_REFERENCED, f.management.state.value.warning)
            assertTrue(f.c.characterRepository.getAll().all { book.id in it.worldBookIds })
        }
    }

    @Test fun `WorldBook Session guard uses extraWorldBookIds and title`() = runBlocking {
        fixture { f ->
            val book = WorldBook.create("Referenced")
            f.c.worldBookRepository.save(book)
            f.c.chatRepository.createSession(ChatSession(id = "session", characterCardId = "archived", title = "Session name", extraWorldBookIds = listOf(book.id), createdAt = 1, updatedAt = 1))
            f.management.requestDelete(DesktopTransferKind.WORLD_BOOK, book.id, book.name)
            f.management.confirmDeletion()
            assertNotNull(f.c.worldBookRepository.getById(book.id))
            assertEquals(listOf("Session name"), f.management.state.value.sessionReferences)
            assertEquals(listOf(book.id), f.c.chatRepository.getSession("session")!!.extraWorldBookIds)
        }
    }

    @Test fun `new and existing draft listings recover original editor session for all three types`() = runBlocking {
        for (type in EditorDraftType.entries) for (existing in listOf(false, true)) fixture { f ->
            val draft = f.draft(type, existing)
            f.management.refresh()
            assertEquals(draft, f.management.drafts.first().single())
            f.management.openDraft(draft)
            val session = when (type) {
                EditorDraftType.CHARACTER_CARD -> f.c.characterEditorController.state.value.draftSessionId
                EditorDraftType.FORMAT_CARD -> f.c.formatCardEditorController.state.value.draftSessionId
                EditorDraftType.WORLD_BOOK -> f.c.worldBookEditorController.state.value.draftSessionId
            }
            assertEquals(draft.draftSessionId, session)
            assertEquals(1, f.c.editorDraftRepository.getAll().size)
        }
    }

    @Test fun `confirmed draft discard removes all types through durable authority`() = runBlocking {
        for (type in EditorDraftType.entries) fixture { f ->
            val draft = f.draft(type)
            var deletes = 0
            val management = f.controller(deleteDraft = { kind, id -> deletes++; f.c.editorDraftRepository.deleteForTarget(kind, id) })
            management.requestDiscard(draft)
            assertNotNull(f.c.editorDraftRepository.getById(draft.id))
            management.confirmDeletion()
            assertEquals(1, deletes)
            assertNull(f.c.editorDraftRepository.getById(draft.id))
            assertTrue(management.drafts.first().isEmpty())
        }
    }

    @Test fun `Character draft resources removed after commit without touching durable resources`() = runBlocking {
        fixture { f ->
            val draft = f.draft(EditorDraftType.CHARACTER_CARD)
            val assets = DesktopCharacterDraftResources(f.root, DesktopCharacterResourceStore(f.root))
            val staged = assets.stageText(draft.draftSessionId, "draft.txt", "draft")
            val owned = DesktopCharacterResourceStore(f.root).materializeDocument(PackagedDocument("owned.txt", "txt", "durable"), 1, "owned")
            var committed = false
            val management = f.controller(discard = { session ->
                assertTrue(committed)
                assets.discardSession(session)
            }, deleteDraft = { type, target ->
                f.c.editorDraftRepository.deleteForTarget(type, target)
                committed = !f.c.editorDraftRepository.existsForTarget(type, target)
            })
            management.requestDiscard(draft)
            management.confirmDeletion()
            assertFalse(Files.exists(f.root.resolve(staged)))
            assertEquals("durable", Files.readString(f.root.resolve(owned)))
        }
    }

    @Test fun `asset cleanup failure leaves draft deleted with nonfatal warning`() = runBlocking {
        fixture { f ->
            val draft = f.draft(EditorDraftType.CHARACTER_CARD)
            val management = f.controller(discard = { error("asset cleanup fault") })
            management.requestDiscard(draft)
            management.confirmDeletion()
            assertNull(f.c.editorDraftRepository.getById(draft.id))
            assertEquals(DesktopUiText.MANAGE_DRAFT_CLEANUP_WARNING, management.state.value.warning)
            assertTrue(management.drafts.first().isEmpty())
        }
    }

    @Test fun `pre commit draft delete failure retains assets and recovery`() = runBlocking {
        fixture { f ->
            val draft = f.draft(EditorDraftType.CHARACTER_CARD)
            var assets = 0
            val management = f.controller(discard = { assets++ }, deleteDraft = { _, _ -> error("delete denied") })
            management.requestDiscard(draft)
            management.confirmDeletion()
            assertNotNull(f.c.editorDraftRepository.getById(draft.id))
            assertEquals(0, assets)
            assertNotNull(management.state.value.error)
        }
    }

    @Test fun `post commit draft delete exception still cleans only draft session`() = runBlocking {
        fixture { f ->
            val draft = f.draft(EditorDraftType.CHARACTER_CARD)
            var assets = 0
            val management = f.controller(discard = { assertEquals(draft.draftSessionId, it); assets++ }, deleteDraft = { kind, id ->
                f.c.editorDraftRepository.deleteForTarget(kind, id); error("cache fault after delete")
            })
            management.requestDiscard(draft)
            management.confirmDeletion()
            assertEquals(1, assets)
            assertNull(f.c.editorDraftRepository.getById(draft.id))
            assertEquals(DesktopUiText.MANAGE_DRAFT_CLEANUP_WARNING, management.state.value.warning)
        }
    }

    @Test fun `stale discard confirmation cannot delete a replaced draft`() = runBlocking {
        fixture { f ->
            val draft = f.draft(EditorDraftType.FORMAT_CARD)
            f.management.requestDiscard(draft)
            val replacement = f.c.editorDraftRepository.save(draft.copy(draftSessionId = "replacement"))
            f.management.confirmDeletion()
            assertEquals(replacement, f.c.editorDraftRepository.getById(draft.id))
            assertNotNull(f.management.state.value.error)
        }
    }

    @Test fun `Character management typed import conflict prohibits community overwrite and resolves locally`() = runBlocking {
        fixture { f ->
            val card = CharacterCard.create("Conflict").copy(communityItemId = "remote")
            f.c.characterRepository.save(card)
            val file = Files.writeString(f.root.resolve("incoming.json"), f.c.transferJson.encodeToString(CharacterCardPackage.serializer(),
                CharacterCardPackage(card = PackagedCharacterCard(name = "Conflict", greeting = "new"))))
            f.picker.open = file
            f.management.import(DesktopTransferKind.CHARACTER)
            assertFalse((f.transfer.state.value.pendingConflict as DesktopPendingTransferConflict.Character).overwriteAllowed)
            f.management.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals(card, f.c.characterRepository.getById(card.id))
            assertNotNull(f.transfer.state.value.pendingConflict)
            f.management.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertNull(f.transfer.state.value.pendingConflict)
            assertEquals(2, f.c.characterEditorController.state.value.characters.size)
        }
    }

    @Test fun `Format and WorldBook typed import conflicts keep cancel overwrite and import new semantics`() = runBlocking {
        for (kind in listOf(DesktopTransferKind.FORMAT, DesktopTransferKind.WORLD_BOOK)) fixture { f ->
            val id = if (kind == DesktopTransferKind.FORMAT) FormatCard.create("Conflict", "old").also { f.c.formatCardRepository.save(it) }.id
                else WorldBook.create("Conflict").also { f.c.worldBookRepository.save(it) }.id
            val json = if (kind == DesktopTransferKind.FORMAT) f.c.formatTransfers.exportJson(id) else f.c.worldBookTransfers.exportJson(id)
            f.picker.open = Files.writeString(f.root.resolve("import.json"), json)
            f.management.import(kind)
            assertNotNull(f.transfer.state.value.pendingConflict)
            f.management.resolveConflict(DesktopTransferConflictAction.CANCEL)
            assertNull(f.transfer.state.value.pendingConflict)
            f.management.import(kind)
            f.management.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertNull(f.transfer.state.value.pendingConflict)
            f.management.import(kind)
            f.management.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            assertEquals(2, if (kind == DesktopTransferKind.FORMAT) f.c.formatCardEditorController.state.value.cards.size else f.c.worldBookEditorController.state.value.books.size)
        }
    }

    @Test fun `per item export routes use existing Character JSON PNG Format and native ST authorities`() = runBlocking {
        fixture { f ->
            val card = CharacterCard.create("Export")
            val format = FormatCard.create("Format", "Inline")
            val book = WorldBook.create("World")
            f.c.characterRepository.save(card); f.c.formatCardRepository.save(format); f.c.worldBookRepository.save(book)
            for ((kind, id) in listOf(DesktopTransferKind.CHARACTER to card.id, DesktopTransferKind.FORMAT to format.id, DesktopTransferKind.WORLD_BOOK to book.id)) {
                f.picker.save = f.root.resolve("$kind.json")
                f.management.export(kind, id)
                val expected = when (kind) {
                    DesktopTransferKind.CHARACTER -> f.c.characterTransfers.exportJson(id)
                    DesktopTransferKind.FORMAT -> f.c.formatTransfers.exportJson(id)
                    DesktopTransferKind.WORLD_BOOK -> f.c.worldBookTransfers.exportJson(id)
                }
                val actual = Files.readString(f.picker.save)
                when (kind) {
                    DesktopTransferKind.CHARACTER -> assertEquals(f.c.characterTransfers.decode(expected).copy(exportedAt = 0), f.c.characterTransfers.decode(actual).copy(exportedAt = 0))
                    DesktopTransferKind.FORMAT -> assertEquals(f.c.formatTransfers.decode(expected).copy(exportedAt = 0), f.c.formatTransfers.decode(actual).copy(exportedAt = 0))
                    DesktopTransferKind.WORLD_BOOK -> assertEquals(f.c.worldBookTransfers.decode(expected).copy(exportedAt = 0), f.c.worldBookTransfers.decode(actual).copy(exportedAt = 0))
                }
            }
            f.picker.save = f.root.resolve("character.png")
            f.management.export(DesktopTransferKind.CHARACTER, card.id, alternate = true)
            assertNotNull(f.c.characterTransfers.decodePng(Files.readAllBytes(f.picker.save)))
            f.picker.save = f.root.resolve("world-st.json")
            f.management.export(DesktopTransferKind.WORLD_BOOK, book.id, alternate = true)
            assertEquals(f.c.worldBookTransfers.exportSillyTavernJson(book.id), Files.readString(f.picker.save))
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val parent = Files.createTempDirectory("desktop-management-")
        val root = Files.createDirectory(parent.resolve("root"))
        val c = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() })
        try { block(Fixture(root, c)) } finally { c.close(); parent.toFile().deleteRecursively() }
    }

    private class Picker : DesktopFilePicker {
        var open: Path? = null
        var save: Path? = null
        override fun pickOpenFile(type: DesktopFileType) = open
        override fun pickSaveFile(type: DesktopFileType, suggestedName: String) = save
    }
    private class Fixture(val root: Path, val c: DesktopAppContainer) {
        val picker = Picker()
        val transfer = c.createTypedTransferController(picker)
        val management = c.createManagementController(transfer)
        fun controller(core: CharacterCardTransferCore = c.characterTransfers,
            transferController: DesktopTypedTransferController = transfer,
            discard: (String) -> Unit = DesktopCharacterDraftResources(root, DesktopCharacterResourceStore(root))::discardSession,
            deleteDraft: suspend (EditorDraftType, String?) -> Unit = c.editorDraftRepository::deleteForTarget,
            afterReconcile: suspend () -> Unit = {},
            afterDuplicate: (String) -> Unit = {},
        ) = DesktopManagementController(c.characterRepository, c.formatCardRepository, c.worldBookRepository, c.chatRepository,
            c.editorDraftRepository, core, c.formatTransfers, c.worldBookTransfers, transferController, c.characterEditorController,
            c.formatCardEditorController, c.worldBookEditorController, discard, deleteDraft, afterReconcile, afterDuplicate)

        suspend fun draft(type: EditorDraftType, existing: Boolean = false): EditorDraft {
            val repo = c.editorDraftRepository
            val session = "draft-session"
            return repo.save(when (type) {
                EditorDraftType.CHARACTER_CARD -> {
                    val base = CharacterCard.create("Character")
                    if (existing) c.characterRepository.save(base)
                    repo.characterDraft(base.id.takeIf { existing }, session, base.copy(name = "Draft Character"), base.takeIf { existing }, emptyList(), emptyList(), emptyList())
                }
                EditorDraftType.FORMAT_CARD -> {
                    val base = FormatCard.create("Format", "inline")
                    if (existing) c.formatCardRepository.save(base)
                    repo.formatDraft(base.id.takeIf { existing }, session, base.copy(name = "Draft Format"), base.takeIf { existing })
                }
                EditorDraftType.WORLD_BOOK -> {
                    val base = WorldBook.create("World")
                    if (existing) c.worldBookRepository.save(base)
                    repo.worldBookDraft(base.id.takeIf { existing }, session, base.copy(name = "Draft World"), base.takeIf { existing })
                }
            })
        }
    }
    companion object { private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=" }
}
