package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import com.example.chatbar.domain.card.CharacterSectionSelection
import com.example.chatbar.domain.card.CharacterTextSection
import com.example.chatbar.domain.card.StructuredCharacterFreeformConverter
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import javax.imageio.ImageIO
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

class DesktopCharacterEditorControllerTest {
    @Test fun `create edit save restart preserves identity and owned fields`() = runBlocking {
        fixture { root, container, controller ->
            controller.openNew()
            val firstId = assertNotNull(controller.state.value.card).id
            controller.edit { it.copy(name = "林月", botName = "阿月", greeting = "你好", basicSetting = "城镇",
                systemPrompt = "角色资料", postHistoryInstructions = "回应", mesExample = "示例",
                creatorNotes = "注", defaultImagePrompt = "风景", defaultImageNegativePrompt = "模糊") }
            controller.addCharacter()
            val entryId = controller.state.value.card!!.characters.single().id
            controller.updateCharacter(entryId) { it.copy(name = "林月", profile = "勇敢", appearance = "长发",
                clothing = "蓝衣", abilities = "旅行", habits = "读书", background = "故乡",
                relationships = "朋友", speakingStyle = "温柔", imagePrompt = "portrait",
                fishAudioVoice = FishAudioVoiceBinding("voice-id", "Voice")) }
            controller.addGreeting(); controller.setGreeting(0, "再次见面")
            assertTrue(controller.save())
            val saved = assertNotNull(container.characterRepository.getById(firstId))
            assertEquals(firstId, saved.id)
            assertEquals(listOf("再次见面"), saved.alternateGreetings)
            assertEquals("portrait", saved.characters.single().imagePrompt)
            assertEquals("voice-id", saved.characters.single().fishAudioVoice?.referenceId)
            val createdAt = saved.createdAt
            controller.edit { it.copy(name = "林月 II") }
            assertTrue(controller.save())
            assertEquals(createdAt, container.characterRepository.getById(firstId)?.createdAt)
            val reopened = containerFor(root)
            try { assertEquals("林月 II", reopened.characterRepository.getById(firstId)?.name) }
            finally { reopened.close() }
        }
    }

    @Test fun `draft autosave recovery discard and successful save cleanup`() = runBlocking {
        fixture { root, container, controller ->
            controller.openNew()
            controller.edit { it.copy(name = "Draft", greeting = "Hello") }
            controller.flushDraft()
            assertNotNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
            val reopened = containerFor(root)
            try {
                val restored = reopened.characterEditorController
                restored.openNew()
                assertEquals("Draft", restored.state.value.card?.name)
                assertTrue(restored.state.value.draftPersisted)
                assertTrue(restored.save())
                assertNull(reopened.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                restored.openExisting(restored.state.value.card!!.id)
                restored.edit { it.copy(name = "Discarded") }
                restored.flushDraft()
                restored.discard()
                assertNull(restored.state.value.card)
                assertNull(reopened.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD,
                    reopened.characterRepository.getAll().single().id))
            } finally { reopened.close() }
        }
    }

    @Test fun `debounced edits autosave without explicit flush`() = runBlocking {
        fixture { _, container, controller ->
            controller.openNew()
            controller.edit { it.copy(name = "Autosaved", greeting = "Hello") }
            withTimeout(15_000) {
                while (container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null)
                        ?.characterPayload?.name != "Autosaved" || !controller.state.value.draftPersisted) delay(50)
            }
            assertTrue(controller.state.value.draftPersisted)
        }
    }

    @Test fun `external edit conflicts and deleted source recovers as new`() = runBlocking {
        fixture { _, container, controller ->
            val source = CharacterCard.create("Original", "Hello")
            container.characterRepository.save(source)
            controller.openExisting(source.id)
            controller.edit { it.copy(creatorNotes = "Local") }; controller.flushDraft()
            container.characterRepository.save(source.copy(name = "External"))
            assertFalse(controller.save())
            assertEquals(CharacterEditorProblem.SOURCE_CHANGED, controller.state.value.problem)
            assertEquals("External", container.characterRepository.getById(source.id)?.name)
            controller.saveAsNew()
            controller.edit { it.copy(name = "Recovered") }
            assertTrue(controller.save())
            assertNotEquals(source.id, controller.state.value.card?.id)
            assertEquals(2, container.characterRepository.getAll().size)
            val deleted = CharacterCard.create("Gone", "Hello")
            container.characterRepository.save(deleted)
            controller.openExisting(deleted.id)
            controller.edit { it.copy(creatorNotes = "Still here") }; controller.flushDraft()
            container.characterRepository.delete(deleted.id)
            assertFalse(controller.save())
            assertEquals(CharacterEditorProblem.SOURCE_DELETED, controller.state.value.problem)
            controller.saveAsNew()
            assertTrue(controller.save())
            assertEquals("Still here", controller.state.value.card?.creatorNotes)
        }
    }

    @Test fun `mode switch keeps both bodies and CharacterInfo IDs`() = runBlocking {
        fixture { _, container, controller ->
            controller.openNew()
            controller.edit { it.copy(name = "Mode", greeting = "Hi", freeformCharacterText = "Free") }
            controller.addCharacter()
            val id = controller.state.value.card!!.characters.single().id
            controller.updateCharacter(id) { it.copy(name = "Person", profile = "Structured") }
            controller.switchMode(CharacterEditMode.FREEFORM)
            assertEquals("Structured", controller.state.value.card!!.characters.single().profile)
            controller.switchMode(CharacterEditMode.STRUCTURED)
            assertEquals("Free", controller.state.value.card!!.freeformCharacterText)
            assertTrue(controller.save())
            val saved = container.characterRepository.getAll().single()
            assertEquals(id, saved.characters.single().id)
            assertEquals("Free", saved.freeformCharacterText)
        }
    }

    @Test fun `binding selectors retain stale IDs and do not change existing session`() = runBlocking {
        fixture { root, container, controller ->
            val world = WorldBook.create("Lore")
            val format = FormatCard.create("Style", "format")
            container.worldBookRepository.save(world)
            container.formatCardRepository.save(format)
            val source = CharacterCard.create("Bindings", "Hi")
            container.characterRepository.save(source)
            val sessionId = container.characterSessionService.createSessionForCharacter(source.id)
            val session = container.chatRepository.getSession(sessionId)!!
            controller.openExisting(source.id)
            controller.toggleWorldBook(world.id)
            controller.toggleWorldBook("missing-world")
            controller.setDefaultFormatCard(format.id)
            assertTrue(controller.save())
            assertEquals(listOf(world.id, "missing-world"), container.characterRepository.getById(source.id)?.worldBookIds)
            assertEquals(format.id, container.characterRepository.getById(source.id)?.defaultFormatCardId)
            assertEquals(session.formatCardId, container.chatRepository.getSession(session.id)?.formatCardId)
            val reopened = containerFor(root)
            try {
                reopened.characterEditorController.openExisting(source.id)
                assertEquals(listOf(world.id, "missing-world"), reopened.characterEditorController.state.value.card?.worldBookIds)
            } finally { reopened.close() }
        }
    }

    @Test fun `images occupy independent durable slots and discard preserves source`() = runBlocking {
        fixture { root, container, _ ->
            val image = root.parent.resolve("sample.png")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", image.toFile())
            val picker = FakePicker(image)
            val controller = editor(container, picker)
            controller.openNew()
            controller.edit { it.copy(name = "Images", greeting = "Hi", characters = listOf(CharacterInfo.create("Person"))) }
            val personId = controller.state.value.card!!.characters.single().id
            controller.chooseAvatar(); controller.chooseBackground(); controller.chooseAppearance(personId)
            assertTrue(controller.save())
            val saved = container.characterRepository.getAll().single()
            val refs = listOfNotNull(saved.avatar, saved.chatBackground, saved.characters.single().appearanceImage)
            assertEquals(3, refs.distinct().size)
            assertTrue(refs.all { it.startsWith("images/") && Files.exists(root.resolve(it)) })
            controller.clearAvatar(); controller.flushDraft(); controller.discard()
            assertTrue(refs.all { Files.exists(root.resolve(it)) })
            controller.openExisting(saved.id)
            controller.chooseAvatar()
            assertTrue(controller.save())
            assertFalse(Files.exists(root.resolve(saved.avatar!!)))
            assertEquals(saved.chatBackground, container.characterRepository.getById(saved.id)?.chatBackground)
        }
    }

    @Test fun `documents materialize and package round trip without network`() = runBlocking {
        fixture { root, container, _ ->
            val image = root.parent.resolve("avatar.png")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", image.toFile())
            val controller = editor(container, FakePicker(image))
            val format = FormatCard.create("Roundtrip format", "inline format")
            container.formatCardRepository.save(format)
            controller.openNew()
            controller.edit { it.copy(name = "Package", greeting = "Hi", freeformCharacterText = "Story",
                editMode = CharacterEditMode.FREEFORM) }
            controller.setDefaultFormatCard(format.id)
            controller.chooseAvatar()
            controller.addTextDocument("notes.txt", "Important notes")
            assertTrue(controller.save())
            val saved = container.characterRepository.getAll().single()
            val doc = saved.customDocuments.single()
            assertTrue(doc.filePath.startsWith("documents/"))
            assertEquals("Important notes", Files.readString(root.resolve(doc.filePath)))
            val raw = container.characterTransfers.exportJson(saved.id)
            val imported = container.characterTransfers.importNew(container.characterTransfers.decode(raw))
            assertEquals("Story", imported.freeformCharacterText)
            assertEquals(CharacterEditMode.FREEFORM, imported.editMode)
            assertNotNull(imported.defaultFormatCardId)
            assertTrue(Files.exists(root.resolve(imported.avatar!!)))
            assertEquals("Important notes", Files.readString(root.resolve(imported.customDocuments.single().filePath)))
            controller.openExisting(saved.id)
            controller.editDocument(doc.id, "revised.txt", "Revised")
            assertTrue(controller.save())
            assertEquals("Revised", Files.readString(root.resolve(container.characterRepository.getById(saved.id)!!.customDocuments.single().filePath)))
        }
    }

    @Test fun `repository save failure keeps draft and prior durable resource`() = runBlocking {
        fixture { root, container, _ ->
            val image = root.parent.resolve("sample.png")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", image.toFile())
            val controller = editor(container, FakePicker(image))
            controller.openNew(); controller.edit { it.copy(name = "Failure", greeting = "Hi") }
            controller.chooseAvatar(); assertTrue(controller.save())
            val original = container.characterRepository.getAll().single()
            val oldRef = original.avatar!!
            controller.chooseAvatar(); controller.flushDraft()
            val entityFile = root.resolve("entities/character_cards/${original.id}.json")
            val backup = Files.readAllBytes(entityFile)
            Files.delete(entityFile)
            Files.createDirectory(entityFile)
            try {
                assertFalse(controller.save())
                assertNotNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
                assertTrue(Files.exists(root.resolve(oldRef)))
            } finally {
                Files.delete(entityFile)
                Files.write(entityFile, backup)
            }
        }
    }

    @Test fun `later owned fields survive and localized labels exist`() = runBlocking {
        fixture { _, container, controller ->
            val original = CharacterCard.create("Metadata", "Hi").copy(
                sourcePresetKey = "preset", sourcePresetVersion = 4, momentsEnabled = false,
                extensions = "{\"custom\":true}", ragIndexStatus = "COMPLETE")
            container.characterRepository.save(original)
            controller.openExisting(original.id)
            controller.edit { it.copy(creatorNotes = "Edited") }
            assertTrue(controller.save())
            val saved = container.characterRepository.getById(original.id)!!
            assertEquals(original.sourcePresetKey, saved.sourcePresetKey)
            assertEquals(original.sourcePresetVersion, saved.sourcePresetVersion)
            assertEquals(original.momentsEnabled, saved.momentsEnabled)
            assertEquals(original.extensions, saved.extensions)
            assertEquals(original.ragIndexStatus, saved.ragIndexStatus)
            assertEquals("角色管理", DesktopUiStrings(DesktopUiLanguage.ZH_CN)(DesktopUiText.CHARACTER_MANAGEMENT))
            assertEquals("Character management", DesktopUiStrings(DesktopUiLanguage.EN)(DesktopUiText.CHARACTER_MANAGEMENT))
        }
    }

    @Test fun `unrelated edits preserve newer indexing metadata`() = runBlocking {
        fixture { _, container, controller ->
            val document = DocumentInfo.create("notes.txt", "documents/persisted.txt", "text/plain")
            val original = CharacterCard.create("Index", "Hi").copy(customDocuments = listOf(document))
            container.characterRepository.save(original)
            controller.openExisting(original.id)
            controller.edit { it.copy(creatorNotes = "Edited") }; controller.flushDraft()
            container.characterRepository.save(original.copy(
                ragIndexStatus = "COMPLETE", ragIndexDone = 1, ragIndexTotal = 1,
                customDocuments = listOf(document.copy(ragStatus = "INDEXED", ragChunkCount = 3))))
            assertTrue(controller.save())
            val saved = container.characterRepository.getById(original.id)!!
            assertEquals("COMPLETE", saved.ragIndexStatus)
            assertEquals("INDEXED", saved.customDocuments.single().ragStatus)
            assertEquals(3, saved.customDocuments.single().ragChunkCount)
        }
    }

    @Test fun `community card is read only and copy as new leaves original intact`() = runBlocking {
        fixture { _, container, controller ->
            val original = CharacterCard.create("Community", "Hi").copy(communityItemId = "remote-id")
            container.characterRepository.save(original)
            controller.openExisting(original.id)
            controller.edit { it.copy(name = "Not allowed") }
            assertEquals(CharacterEditorProblem.COMMUNITY_READ_ONLY, controller.state.value.problem)
            assertEquals("Community", controller.state.value.card?.name)
            controller.saveAsNew()
            assertTrue(controller.save())
            val copy = controller.state.value.card!!
            assertNotEquals(original.id, copy.id)
            assertNull(copy.communityItemId)
            assertEquals("Community", container.characterRepository.getById(original.id)?.name)
        }
    }

    @Test fun `rename uses shared session title rewrite and retains history`() = runBlocking {
        fixture { _, container, controller ->
            val original = CharacterCard.create("Before", "Greeting")
            container.characterRepository.save(original)
            val sessionId = container.characterSessionService.createSessionForCharacter(original.id)
            val messageCount = container.chatRepository.getMessages(sessionId).size
            controller.openExisting(original.id)
            controller.edit { it.copy(name = "After") }
            assertTrue(controller.save())
            assertEquals("After", container.chatRepository.getSession(sessionId)?.title)
            assertEquals(messageCount, container.chatRepository.getMessages(sessionId).size)
        }
    }

    @Test fun `clean save never overwrites an externally updated card`() = runBlocking {
        fixture { _, container, controller ->
            val original = CharacterCard.create("Clean", "Hi")
            container.characterRepository.save(original)
            controller.openExisting(original.id)
            val newer = original.copy(greeting = "External", updatedAt = original.updatedAt + 1)
            container.characterRepository.save(newer)
            assertTrue(controller.save())
            assertEquals(newer, container.characterRepository.getById(original.id))
            assertNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, original.id))
        }
    }

    @Test fun `failed draft deletion keeps referenced assets on save and discard`() = runBlocking {
        fixture { root, container, _ ->
            val controller = editor(container, deleteDraft = { _, _ -> Unit })
            try {
                controller.openNew()
                controller.edit { it.copy(name = "Keep assets", greeting = "Hi") }
                controller.addTextDocument("notes.txt", "draft content")
                controller.flushDraft()
                val draft = assertNotNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                val asset = root.resolve(draft.draftAssetPaths.single())
                assertTrue(controller.save())
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
                assertNotNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertTrue(Files.exists(asset))
                controller.discard()
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
                assertNotNull(controller.state.value.card)
                assertTrue(Files.exists(asset))
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `committed save with pending draft cleanup blocks edits until retry succeeds`() = runBlocking {
        fixture { _, container, _ ->
            var failDelete = true
            val controller = editor(container, deleteDraft = { type, id ->
                if (!failDelete) container.editorDraftRepository.deleteForTarget(type, id)
            })
            try {
                controller.openNew()
                controller.edit { it.copy(name = "Pending cleanup", greeting = "Hi") }
                var leftEditor = false
                controller.requestLeave { leftEditor = true }
                controller.saveAndLeave()
                assertFalse(leftEditor)
                val saved = controller.state.value.card!!
                assertNotNull(controller.state.value.draftBasis)
                assertTrue(desktopCharacterEditorPresentation(controller.state.value).readOnly)
                assertTrue(desktopCharacterEditorPresentation(controller.state.value).showCommittedCleanupRetry)
                assertFalse(desktopCharacterEditorPresentation(controller.state.value).showOrdinaryCleanupRetry)
                controller.edit { it.copy(creatorNotes = "must wait") }
                assertEquals(saved, controller.state.value.card)
                failDelete = false
                controller.retryCleanup()
                assertNull(controller.state.value.draftBasis)
                assertNull(controller.state.value.problem)
                controller.edit { it.copy(creatorNotes = "now editable") }
                assertTrue(controller.save())
                assertEquals("now editable", container.characterRepository.getById(saved.id)?.creatorNotes)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `post-delete cache error still permits asset cleanup and confirmed reconciliation`() = runBlocking {
        fixture { root, container, _ ->
            val controller = editor(container, deleteDraft = { type, id ->
                container.editorDraftRepository.deleteForTarget(type, id)
                error("cache refresh after delete")
            })
            try {
                controller.openNew()
                controller.edit { it.copy(name = "Deleted", greeting = "Hi") }
                controller.addTextDocument("notes.txt", "draft")
                controller.flushDraft()
                val asset = root.resolve(assertNotNull(container.editorDraftRepository
                    .getForTarget(EditorDraftType.CHARACTER_CARD, null)).draftAssetPaths.single())
                assertTrue(controller.save())
                assertNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertFalse(Files.exists(asset))
                assertNull(controller.state.value.problem)
                controller.retryCommittedCleanup()
                assertNull(controller.state.value.problem)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `save as new never replaces another new character draft`() = runBlocking {
        fixture { root, container, controller ->
            val source = CharacterCard.create("Community", "Hi").copy(communityItemId = "remote")
            container.characterRepository.save(source)
            val pending = editor(container)
            try {
                pending.openNew()
                pending.edit { it.copy(name = "Unsaved", greeting = "Hi") }
                pending.addTextDocument("pending.txt", "keep")
                pending.flushDraft()
                val original = assertNotNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                controller.openExisting(source.id)
                controller.saveAsNew()
                assertEquals(CharacterEditorProblem.NEW_DRAFT_EXISTS, controller.state.value.problem)
                assertEquals(source.id, controller.state.value.targetId)
                assertEquals(original, container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertTrue(Files.exists(root.resolve(original.draftAssetPaths.single())))
            } finally { pending.closeAndDrain() }
        }
    }

    @Test fun `discarding save-as-new attempt preserves original recovery draft and assets`() = runBlocking {
        fixture { root, container, controller ->
            val source = CharacterCard.create("Original", "Hi")
            container.characterRepository.save(source)
            controller.openExisting(source.id)
            controller.addTextDocument("recovery.txt", "recoverable")
            controller.flushDraft()
            val originalDraft = assertNotNull(container.editorDraftRepository
                .getForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            container.characterRepository.save(source.copy(name = "Changed"))
            assertFalse(controller.save())
            controller.saveAsNew()
            assertNotNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
            controller.discard()
            assertNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
            assertNotNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            assertTrue(Files.exists(root.resolve(originalDraft.draftAssetPaths.single())))
            val reopened = containerFor(root)
            try {
                reopened.characterEditorController.openExisting(source.id)
                assertEquals(CharacterEditorProblem.SOURCE_CHANGED, reopened.characterEditorController.state.value.problem)
                assertEquals("recovery.txt", reopened.characterEditorController.state.value.card?.customDocuments?.single()?.fileName)
            } finally { reopened.close() }
        }
    }

    @Test fun `legacy worldbook bindings display normalize and preserve embedded entries`() = runBlocking {
        fixture { _, container, controller ->
            val bound = WorldBook.create("Bound")
            val entry = WorldBookEntry.create(listOf("key"), "embedded content")
            val embedded = WorldBook.create("Embedded").copy(entries = listOf(entry))
            val source = CharacterCard.create("Legacy", "Hi").copy(
                boundWorldBookId = bound.id, characterBook = embedded)
            container.characterRepository.save(source)
            controller.openExisting(source.id)
            assertEquals(listOf(bound.id, embedded.id), controller.state.value.card?.worldBookIds)
            controller.edit { it.copy(creatorNotes = "Normalize") }
            assertTrue(controller.save())
            val saved = assertNotNull(container.characterRepository.getById(source.id))
            assertEquals(listOf(bound.id, embedded.id), saved.worldBookIds)
            assertNull(saved.boundWorldBookId)
            assertNull(saved.characterBook)
            assertEquals(listOf(entry), container.worldBookRepository.getById(embedded.id)?.entries)
            controller.toggleWorldBook(bound.id)
            controller.toggleWorldBook(embedded.id)
            assertTrue(controller.save())
            assertTrue(container.characterRepository.getById(source.id)!!.worldBookIds.isEmpty())
        }
    }

    @Test fun `existing embedded worldbook ID is not overwritten by legacy normalization`() = runBlocking {
        fixture { _, container, controller ->
            val existing = WorldBook.create("Independent").copy(entries = listOf(WorldBookEntry.create(content = "independent")))
            container.worldBookRepository.save(existing)
            val source = CharacterCard.create("Legacy", "Hi").copy(
                characterBook = existing.copy(entries = listOf(WorldBookEntry.create(content = "old embedded"))))
            container.characterRepository.save(source)
            controller.openExisting(source.id)
            controller.edit { it.copy(creatorNotes = "Normalize") }
            assertTrue(controller.save())
            assertEquals(existing.entries, container.worldBookRepository.getById(existing.id)?.entries)
            assertEquals(listOf(existing.id), container.characterRepository.getById(source.id)?.worldBookIds)
        }
    }

    @Test fun `txt md json document types and package round trip follow filename`() = runBlocking {
        fixture { root, container, controller ->
            controller.openNew()
            controller.edit { it.copy(name = "Documents", greeting = "Hi") }
            controller.addTextDocument("notes.txt", "txt")
            controller.addTextDocument("notes.md", "md")
            val jsonFile = root.parent.resolve("notes.json")
            Files.writeString(jsonFile, "{}")
            val picker = FakePicker(jsonFile)
            val pickerController = editor(container, picker)
            try {
                // The picker controller owns the same draft identity after recovery.
                controller.flushDraft()
                pickerController.openNew()
                pickerController.addDocumentFromPicker()
                assertEquals(listOf("txt", "md", "json"), picker.lastOpenType?.extensions)
                assertTrue(pickerController.save())
                val saved = container.characterRepository.getAll().single()
                assertEquals(listOf("txt", "md", "json"), saved.customDocuments.map { it.fileType })
                val raw = container.characterTransfers.exportJson(saved.id)
                val imported = container.characterTransfers.importNew(container.characterTransfers.decode(raw))
                assertEquals(listOf("txt", "md", "json"), imported.customDocuments.map { it.fileType })
                pickerController.openExisting(saved.id)
                val doc = pickerController.state.value.card!!.customDocuments.first()
                pickerController.renameDocument(doc.id, "notes.json")
                assertTrue(pickerController.save())
                assertEquals("json", container.characterRepository.getById(saved.id)!!.customDocuments.first().fileType)
                pickerController.editDocument(doc.id, "notes.md", "revised")
                assertTrue(pickerController.save())
                assertEquals("md", container.characterRepository.getById(saved.id)!!.customDocuments.first().fileType)
            } finally { pickerController.closeAndDrain() }
        }
    }

    @Test fun `unreadable document is explicit and not silently replaced`() = runBlocking {
        fixture { _, container, controller ->
            val missing = DocumentInfo.create("lost.txt", "documents/missing.txt", "txt")
            val source = CharacterCard.create("Missing document", "Hi").copy(customDocuments = listOf(missing))
            container.characterRepository.save(source)
            controller.openExisting(source.id)
            assertTrue(controller.documentText(missing.id)!!.isFailure)
            assertTrue(controller.save())
            assertEquals(missing, container.characterRepository.getById(source.id)?.customDocuments?.single())
            controller.editDocument(missing.id, "replacement.txt", "replacement")
            assertTrue(controller.save())
            assertEquals("replacement", controller.documentText(missing.id)?.getOrNull())
        }
    }

    @Test fun `community resource actions do not stage before read-only rejection`() = runBlocking {
        fixture { root, container, _ ->
            val image = root.parent.resolve("sample.png")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", image.toFile())
            val source = CharacterCard.create("Community", "Hi").copy(communityItemId = "remote")
            container.characterRepository.save(source)
            val controller = editor(container, FakePicker(image))
            try {
                controller.openExisting(source.id)
                controller.chooseAvatar()
                controller.addTextDocument("blocked.txt", "blocked")
                assertEquals(CharacterEditorProblem.COMMUNITY_READ_ONLY, controller.state.value.problem)
                assertFalse(Files.exists(root.resolve("draft_assets")))
                assertEquals(source, container.characterRepository.getById(source.id))
                controller.saveAsNew()
                controller.chooseAvatar()
                assertTrue(controller.state.value.card!!.avatar!!.startsWith("draft_assets/"))
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `confirmed post-commit failure reconciles durable card and clears draft`() = runBlocking {
        fixture { _, container, _ ->
            val controller = editor(container, persistCharacter = { card ->
                container.characterRepository.save(card)
                error("refresh failed after commit")
            })
            try {
                controller.openNew()
                controller.edit { it.copy(name = "Committed", greeting = "Hi") }
                assertTrue(controller.save())
                assertNull(controller.state.value.problem)
                assertFalse(controller.state.value.dirty)
                assertNull(container.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertEquals("Committed", container.characterRepository.getById(controller.state.value.card!!.id)?.name)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `character info normalization preserves ID and Fish binding`() = runBlocking {
        fixture { _, container, controller ->
            controller.openNew()
            controller.edit { it.copy(name = "Names", greeting = "Hi") }
            val entry = CharacterInfo.create(" Person ").copy(fishAudioVoice = FishAudioVoiceBinding("voice", "Voice"))
            controller.edit { it.copy(characters = listOf(entry)) }
            assertTrue(controller.save())
            val saved = container.characterRepository.getAll().single().characters.single()
            assertEquals(entry.id, saved.id)
            assertEquals("Person", saved.name)
            assertEquals(entry.fishAudioVoice, saved.fishAudioVoice)
        }
    }

    @Test fun `WebP PNG JPEG and GIF validate while malformed image is rejected`() = runBlocking {
        fixture { root, container, _ ->
            Files.createDirectories(root)
            val png = root.parent.resolve("sample.png")
            val bitmap = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            ImageIO.write(bitmap, "png", png.toFile())
            val jpeg = root.parent.resolve("sample.jpg")
            val gif = root.parent.resolve("sample.gif")
            ImageIO.write(bitmap, "jpg", jpeg.toFile())
            ImageIO.write(bitmap, "gif", gif.toFile())
            val webp = root.parent.resolve("sample.webp")
            val encoded = Image.makeFromEncoded(Files.readAllBytes(png)).encodeToData(EncodedImageFormat.WEBP)
            Files.write(webp, assertNotNull(encoded).bytes)
            val malformed = root.parent.resolve("bad.webp")
            Files.writeString(malformed, "not an image")
            val resources = DesktopCharacterDraftResources(root, container.characterResourceStore)
            val session = "test-session"
            assertTrue(resources.stage(session, png, image = true).startsWith("draft_assets/"))
            assertTrue(resources.stage(session, jpeg, image = true).startsWith("draft_assets/"))
            assertTrue(resources.stage(session, gif, image = true).startsWith("draft_assets/"))
            assertTrue(resources.stage(session, webp, image = true).startsWith("draft_assets/"))
            assertTrue(runCatching { resources.stage(session, malformed, image = true) }.isFailure)
        }
    }

    @Test fun `NAI negative default is displayed and only dirty save materializes it`() = runBlocking {
        fixture { _, container, controller ->
            val default = CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt()
            controller.openNew()
            assertEquals(default, controller.state.value.card?.defaultImageNegativePrompt)
            val blank = CharacterCard.create("Blank negative", "Hi")
            container.characterRepository.save(blank)
            controller.openExisting(blank.id)
            assertEquals(default, controller.state.value.card?.defaultImageNegativePrompt)
            assertTrue(controller.save())
            assertEquals("", container.characterRepository.getById(blank.id)?.defaultImageNegativePrompt)
            controller.edit { it.copy(creatorNotes = "real edit") }
            assertTrue(controller.save())
            assertEquals(default, container.characterRepository.getById(blank.id)?.defaultImageNegativePrompt)
            val custom = CharacterCard.create("Custom negative", "Hi").copy(defaultImageNegativePrompt = "custom")
            container.characterRepository.save(custom)
            controller.openExisting(custom.id)
            controller.edit { it.copy(creatorNotes = "real edit") }
            assertTrue(controller.save())
            assertEquals("custom", container.characterRepository.getById(custom.id)?.defaultImageNegativePrompt)
        }
    }

    @Test fun `committed warning blocks route and section leaves until confirmed cleanup`() = runBlocking {
        fixture { _, app, _ ->
            var canDelete = false
            val controller = editor(app, deleteDraft = { type, target ->
                if (canDelete) app.editorDraftRepository.deleteForTarget(type, target)
            })
            try {
                controller.openNew()
                controller.edit { it.copy(name = "Committed", greeting = "Hi") }
                assertTrue(controller.save())
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
                var left = false
                controller.requestLeave { left = true }
                controller.requestLeave { left = true }
                controller.closeClean()
                controller.saveAndLeave()
                controller.discard()
                assertFalse(left)
                assertNotNull(controller.state.value.card)
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, null))
                canDelete = true
                controller.retryCommittedCleanup()
                assertNull(controller.state.value.problem)
                controller.requestLeave { left = true }
                assertTrue(left)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `character warning drain retries confirmed deletion before draft assets`() = runBlocking {
        fixture { _, app, _ ->
            var canDelete = false
            val controller = editor(app, deleteDraft = { type, target ->
                if (canDelete) app.editorDraftRepository.deleteForTarget(type, target)
            })
            controller.openNew()
            controller.edit { it.copy(name = "Drain", greeting = "Hi") }
            assertTrue(controller.save())
            assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
            canDelete = true
            controller.closeAndDrain()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, null))
            assertNull(controller.state.value.problem)
        }
    }

    @Test fun `reverted and identical clean drafts are removed on shutdown without entity rewrite`() = runBlocking {
        fixture { root, app, controller ->
            val source = CharacterCard.create("Original", "Hi")
            app.characterRepository.save(source)
            val durable = app.characterRepository.getById(source.id)!!
            controller.openExisting(source.id)
            controller.edit { it.copy(name = "Draft B") }
            controller.flushDraft()
            controller.edit { it.copy(name = "Original") }
            assertFalse(controller.state.value.dirty)
            controller.closeAndDrain()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            assertEquals(durable, app.characterRepository.getById(source.id))
            app.editorDraftRepository.save(app.editorDraftRepository.characterDraft(source.id,
                "identical-session", durable, durable, emptyList(), emptyList(), emptyList()))
            val restarted = containerFor(root)
            try {
                restarted.characterEditorController.openExisting(source.id)
                assertFalse(restarted.characterEditorController.state.value.dirty)
                restarted.characterEditorController.closeAndDrain()
                assertFalse(restarted.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            } finally { restarted.close() }
        }
    }

    @Test fun `cross card import follows selected sections and keeps source and target identity`() = runBlocking {
        fixture { _, app, controller ->
            val sourcePerson = CharacterInfo.create("  Alice  ").copy(profile = "From source",
                appearance = "New eyes", imagePrompt = "New prompt", fishAudioVoice = FishAudioVoiceBinding("voice", "Voice"))
            val newPerson = CharacterInfo.create("Bob").copy(abilities = "Skilled")
            val source = CharacterCard.create("Source", "Hi", listOf(sourcePerson, newPerson))
            val targetPerson = CharacterInfo.create("Alice").copy(profile = "Old profile",
                appearance = "Old eyes", imagePrompt = "Old prompt")
            val target = CharacterCard.create("Target", "Hi", listOf(targetPerson))
            app.characterRepository.save(source)
            app.characterRepository.save(target)
            controller.load()
            controller.openExisting(target.id)
            assertTrue(controller.availableImportCards.any { it.id == source.id })
            assertFalse(controller.availableImportCards.any { it.id == target.id })
            val result = controller.importCharacters(source.id, listOf(
                CharacterSectionSelection(sourcePerson.id, setOf(CharacterTextSection.PROFILE,
                    CharacterTextSection.IMAGE_PROMPT)),
                CharacterSectionSelection(newPerson.id, setOf(CharacterTextSection.ABILITIES))))!!
            assertEquals(1, result.updatedCount)
            assertEquals(1, result.createdCount)
            val updated = controller.state.value.card!!.characters
            assertEquals(targetPerson.id, updated.first().id)
            assertEquals("From source", updated.first().profile)
            assertEquals("Old eyes", updated.first().appearance)
            assertEquals("New prompt", updated.first().imagePrompt)
            assertNull(updated.first().fishAudioVoice)
            assertEquals("Skilled", updated.last().abilities)
            assertNull(updated.last().fishAudioVoice)
            assertEquals(listOf(targetPerson), app.characterRepository.getById(target.id)?.characters)
            assertEquals(listOf(sourcePerson, newPerson), app.characterRepository.getById(source.id)?.characters)
        }
    }

    @Test fun `explicit conversion overwrites freeform only on action and leaves structured entries intact`() = runBlocking {
        fixture { _, _, controller ->
            controller.openNew()
            assertFalse(controller.canConvertStructuredToFreeform)
            val person = CharacterInfo.create("Alice").copy(profile = "Brave", imagePrompt = "portrait", appearanceImage = "images/person.png")
            controller.edit { it.copy(name = "Card", greeting = "Hi", characters = listOf(person),
                freeformCharacterText = "Original freeform") }
            controller.switchMode(CharacterEditMode.FREEFORM)
            assertEquals("Original freeform", controller.state.value.card?.freeformCharacterText)
            assertEquals(person.appearanceImage, controller.state.value.card?.characters?.single()?.appearanceImage)
            controller.switchMode(CharacterEditMode.STRUCTURED)
            assertEquals("Original freeform", controller.state.value.card?.freeformCharacterText)
            assertEquals(person.appearanceImage, controller.state.value.card?.characters?.single()?.appearanceImage)
            assertTrue(controller.canConvertStructuredToFreeform)
            // A cancelled confirmation does not invoke the action.
            assertEquals("Original freeform", controller.state.value.card?.freeformCharacterText)
            assertTrue(controller.convertStructuredToFreeform())
            assertEquals(StructuredCharacterFreeformConverter.createTransition(listOf(person))?.freeformCharacterText,
                controller.state.value.card?.freeformCharacterText)
            assertEquals(CharacterEditMode.FREEFORM, controller.state.value.card?.editMode)
            assertEquals(listOf(person), controller.state.value.card?.characters)
        }
    }

    @Test fun `new Character import and conversion labels have Chinese and English variants`() {
        val zh = DesktopUiStrings(DesktopUiLanguage.ZH_CN)
        val en = DesktopUiStrings(DesktopUiLanguage.EN)
        listOf(DesktopUiText.CHARACTER_IMPORT_DATA, DesktopUiText.CHARACTER_IMPORT_SOURCE,
            DesktopUiText.CHARACTER_IMPORT_PERSON, DesktopUiText.CHARACTER_IMPORT_SECTIONS,
            DesktopUiText.CHARACTER_IMPORT_ACTION, DesktopUiText.CHARACTER_IMPORT_RESULT,
            DesktopUiText.CHARACTER_CONVERT_FREEFORM, DesktopUiText.CHARACTER_CONVERT_WARNING,
            DesktopUiText.CHARACTER_CONVERT_CONFIRM).forEach { key ->
            assertTrue(zh(key).isNotBlank())
            assertTrue(en(key).isNotBlank())
            assertNotEquals(zh(key), en(key))
        }
    }

    @Test fun `post-delete asset failure commits dirty Discard without claiming durable work`() = runBlocking {
        fixture { root, app, _ ->
            val editor = editor(app, discardDraftAssets = { error("asset locked") })
            editor.openNew()
            editor.edit { it.copy(name = "Discard me", greeting = "Hi") }
            editor.addTextDocument("notes.txt", "draft content")
            editor.flushDraft()
            val draft = app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, null)!!
            val asset = root.resolve(draft.draftAssetPaths.single())
            editor.discard()
            assertNull(editor.state.value.card)
            assertFalse(editor.state.value.dirty)
            assertEquals(CharacterEditorProblem.RESOURCE_FAILED, editor.state.value.problem)
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, null))
            assertTrue(Files.exists(asset))
            editor.closeAndDrain()
            val reopened = containerFor(root)
            try {
                reopened.characterEditorController.openNew()
                assertEquals("", reopened.characterEditorController.state.value.card?.name)
            } finally { reopened.close() }
        }
    }

    @Test fun `committed rename retries session title rewrite before leave is unblocked`() = runBlocking {
        fixture { _, app, _ ->
            val source = CharacterCard.create("Old", "Hi")
            app.characterRepository.save(source)
            val session = app.chatRepository.createSession(ChatSession.create(source.id, "Old chat"))
            var attempts = 0
            val editor = editor(app, rewriteTitles = { id, oldName, newName ->
                attempts++
                if (attempts == 1) error("title rewrite failed")
                app.chatRepository.rewriteSessionTitlesForCharacterCard(id, oldName, newName)
            })
            try {
                editor.openExisting(source.id)
                editor.edit { it.copy(name = "New") }
                assertTrue(editor.save())
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, editor.state.value.problem)
                assertEquals("Old chat", app.chatRepository.getSession(session.id)?.title)
                var left = false
                editor.requestLeave { left = true }
                assertFalse(left)
                editor.retryCommittedCleanup()
                assertNull(editor.state.value.problem)
                assertEquals("New chat", app.chatRepository.getSession(session.id)?.title)
                assertEquals(2, attempts)
                editor.requestLeave { left = true }
                assertTrue(left)
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `Character cache failure reconciles before committed warning clears`() = runBlocking {
        fixture { _, app, _ ->
            val source = CharacterCard.create("Source", "Hi")
            app.characterRepository.save(source)
            var attempts = 0
            val editor = editor(app, persistCharacter = { card ->
                app.jsonFileStorage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
                error("cache failed after commit")
            }, refreshRepository = {
                attempts++
                if (attempts == 1) error("still unavailable")
                app.characterRepository.refreshFromStorage()
            })
            try {
                editor.openExisting(source.id)
                editor.edit { it.copy(creatorNotes = "Durable") }
                assertTrue(editor.save())
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, editor.state.value.problem)
                assertEquals("", app.characterRepository.getAll().single().creatorNotes)
                editor.retryCommittedCleanup()
                assertNull(editor.state.value.problem)
                assertEquals("Durable", app.characterRepository.getAll().single().creatorNotes)
                assertEquals(2, attempts)
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `obsolete resource failure is visible but does not block a committed Character`() = runBlocking {
        fixture { _, app, _ ->
            val source = CharacterCard.create("Source", "Hi")
            app.characterRepository.save(source)
            val editor = editor(app, discardObsoleteResources = { _, _ -> error("old file locked") })
            try {
                editor.openExisting(source.id)
                editor.edit { it.copy(creatorNotes = "Saved") }
                assertTrue(editor.save())
                assertEquals(CharacterEditorProblem.RESOURCE_FAILED, editor.state.value.problem)
                var left = false
                editor.requestLeave { left = true }
                assertTrue(left)
                assertEquals("Saved", app.characterRepository.getById(source.id)?.creatorNotes)
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `committed new Character draft is reconciled on restart`() = runBlocking {
        fixture { root, app, _ ->
            val editor = editor(app, deleteDraft = { _, _ -> Unit })
            editor.openNew()
            editor.edit { it.copy(name = "Committed", greeting = "Hi") }
            assertTrue(editor.save())
            val id = editor.state.value.card!!.id
            editor.closeAndDrain()
            val blocked = editor(app, deleteDraft = { _, _ -> Unit })
            blocked.openNew()
            assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, blocked.state.value.problem)
            assertFalse(blocked.state.value.dirty)
            assertEquals(id, blocked.state.value.targetId)
            blocked.closeAndDrain()
            val reopened = containerFor(root)
            try {
                reopened.characterEditorController.openNew()
                assertEquals(id, reopened.characterEditorController.state.value.targetId)
                assertFalse(reopened.characterEditorController.state.value.dirty)
                assertNull(reopened.characterEditorController.state.value.problem)
                assertFalse(reopened.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertEquals(id, reopened.characterRepository.getAll().single().id)
            } finally { reopened.close() }
        }
    }

    @Test fun `pre-commit Character marker failure prevents entity commit`() = runBlocking {
        fixture { root, app, _ ->
            val source = CharacterCard.create("Old", "Hi")
            app.characterRepository.save(source)
            val editor = editor(app, persistDraftMarker = { error("marker storage unavailable") })
            try {
                editor.openExisting(source.id)
                editor.edit { it.copy(name = "New") }
                editor.addTextDocument("notes.txt", "draft content")
                assertFalse(editor.save())
                assertEquals(CharacterEditorProblem.SAVE_FAILED, editor.state.value.problem)
                assertEquals(source, app.characterRepository.getById(source.id))
                assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
                val draftPath = assertNotNull(app.editorDraftRepository
                    .getForTarget(EditorDraftType.CHARACTER_CARD, source.id))
                    .characterPayload!!.customDocuments.single().filePath
                assertTrue(Files.exists(root.resolve(draftPath)))
                Files.list(root.resolve("documents")).use { assertFalse(it.findAny().isPresent) }
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `existing committed Character with materialized document reconciles after restart`() = runBlocking {
        fixture { root, app, _ ->
            val source = CharacterCard.create("Card", "Hi")
            app.characterRepository.save(source)
            val editor = editor(app, deleteDraft = { _, _ -> Unit })
            editor.openExisting(source.id)
            editor.addTextDocument("notes.txt", "saved text")
            assertTrue(editor.save())
            val durable = assertNotNull(app.characterRepository.getById(source.id))
            val staleDraft = assertNotNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            assertNotEquals(staleDraft.characterPayload?.customDocuments?.single()?.filePath,
                durable.customDocuments.single().filePath)
            editor.closeAndDrain()
            val restarted = containerFor(root)
            try {
                restarted.characterEditorController.openExisting(source.id)
                assertNull(restarted.characterEditorController.state.value.problem)
                assertFalse(restarted.characterEditorController.state.value.dirty)
                assertEquals(durable, restarted.characterRepository.getById(source.id))
                assertFalse(restarted.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
                assertEquals(1, restarted.characterRepository.getAll().size)
            } finally { restarted.close() }
        }
    }

    @Test fun `Character intent marker without entity commit remains an ordinary draft on restart`() = runBlocking {
        fixture { root, app, _ ->
            val source = CharacterCard.create("Old", "Hi")
            app.characterRepository.save(source)
            val editor = editor(app, persistCharacter = { error("entity write failed") })
            editor.openExisting(source.id)
            editor.edit { it.copy(name = "Not committed") }
            assertFalse(editor.save())
            assertTrue(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, source.id)
                ?.openModalState?.contains("postCommit") == true)
            editor.closeAndDrain()
            val restarted = containerFor(root)
            try {
                restarted.characterEditorController.openExisting(source.id)
                assertTrue(restarted.characterEditorController.state.value.dirty)
                assertEquals("Not committed", restarted.characterEditorController.state.value.card?.name)
                assertTrue(restarted.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
                assertEquals(source, restarted.characterRepository.getById(source.id))
            } finally { restarted.close() }
        }
    }

    @Test fun `Character marker callback returning without durable write cannot commit entity`() = runBlocking {
        fixture { _, app, _ ->
            val source = CharacterCard.create("Old", "Hi")
            app.characterRepository.save(source)
            val editor = editor(app, persistDraftMarker = { it })
            try {
                editor.openExisting(source.id)
                editor.edit { it.copy(name = "New") }
                assertFalse(editor.save())
                assertEquals(CharacterEditorProblem.SAVE_FAILED, editor.state.value.problem)
                assertEquals(source, app.characterRepository.getById(source.id))
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `legacy committed-new Character draft retains prior restart cleanup`() = runBlocking {
        fixture { _, app, _ ->
            val durable = CharacterCard.create("Legacy", "Hi")
            app.characterRepository.save(durable)
            app.editorDraftRepository.save(app.editorDraftRepository.characterDraft(null, "legacy-session",
                durable, null, emptyList(), emptyList(), emptyList()))
            val editor = editor(app)
            try {
                editor.openNew()
                assertNull(editor.state.value.problem)
                assertEquals(durable.id, editor.state.value.targetId)
                assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, null))
                assertEquals(1, app.characterRepository.getAll().size)
            } finally { editor.closeAndDrain() }
        }
    }

    @Test fun `Character rename survives failed shutdown retry and resumes from durable marker`() = runBlocking {
        fixture { root, app, _ ->
            val source = CharacterCard.create("Old", "Hi")
            app.characterRepository.save(source)
            val session = app.chatRepository.createSession(ChatSession.create(source.id, "Old chat"))
            var failedAttempts = 0
            val first = editor(app, rewriteTitles = { _, _, _ ->
                failedAttempts++
                error("rename unavailable")
            })
            first.openExisting(source.id)
            first.edit { it.copy(name = "New") }
            assertTrue(first.save())
            val durable = assertNotNull(app.characterRepository.getById(source.id))
            assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, first.state.value.problem)
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            first.closeAndDrain()
            assertEquals(2, failedAttempts)
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            val restarted = containerFor(root)
            try {
                var resumedAttempts = 0
                val second = editor(restarted, rewriteTitles = { id, old, new ->
                    resumedAttempts++
                    if (resumedAttempts == 1) error("still unavailable")
                    restarted.chatRepository.rewriteSessionTitlesForCharacterCard(id, old, new)
                })
                try {
                    second.openExisting(source.id)
                    assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, second.state.value.problem)
                    assertEquals("Old chat", restarted.chatRepository.getSession(session.id)?.title)
                    assertTrue(restarted.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
                    second.retryCommittedCleanup()
                    assertEquals(2, resumedAttempts)
                    assertNull(second.state.value.problem)
                    assertEquals("New chat", restarted.chatRepository.getSession(session.id)?.title)
                    assertFalse(restarted.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
                    assertEquals(durable, restarted.characterRepository.getById(source.id))
                } finally { second.closeAndDrain() }
            } finally { restarted.close() }
        }
    }

    @Test fun `mismatched Character post-commit intent remains ordinary conflict draft`() = runBlocking {
        fixture { root, app, _ ->
            val source = CharacterCard.create("Old", "Hi")
            app.characterRepository.save(source)
            val editor = editor(app, deleteDraft = { _, _ -> Unit })
            editor.openExisting(source.id)
            editor.edit { it.copy(name = "New") }
            assertTrue(editor.save())
            editor.closeAndDrain()
            val committed = assertNotNull(app.characterRepository.getById(source.id))
            val changed = committed.copy(creatorNotes = "external change", updatedAt = committed.updatedAt + 1)
            app.characterRepository.save(changed)
            val restarted = containerFor(root)
            try {
                restarted.characterEditorController.openExisting(source.id)
                assertEquals(CharacterEditorProblem.SOURCE_CHANGED, restarted.characterEditorController.state.value.problem)
                assertTrue(restarted.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
                assertEquals(changed, restarted.characterRepository.getById(source.id))
            } finally { restarted.close() }
        }
    }

    @Test fun `background-only Character metadata preserves committed marker after restart`() = runBlocking {
        fixture { root, app, _ ->
            val document = DocumentInfo.create("notes.txt", "documents/notes.txt", "txt")
            val source = CharacterCard.create("Card", "Hi").copy(customDocuments = listOf(document))
            app.characterRepository.save(source)
            val editor = editor(app, deleteDraft = { _, _ -> Unit })
            editor.openExisting(source.id)
            editor.edit { it.copy(creatorNotes = "Committed") }
            assertTrue(editor.save())
            assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, editor.state.value.problem)
            editor.closeAndDrain()
            val committed = assertNotNull(app.characterRepository.getById(source.id))
            app.characterRepository.save(committed.copy(
                ragIndexStatus = "COMPLETE", ragIndexDone = 1, ragIndexTotal = 1,
                ragIndexMessage = "indexed", ragIndexedAt = committed.updatedAt + 1,
                customDocuments = committed.customDocuments.map { it.copy(
                    contentHash = "content", indexedHash = "indexed", ragStatus = "COMPLETE",
                    ragChunkCount = 1, ragIndexedAt = committed.updatedAt + 1) },
                updatedAt = committed.updatedAt + 2))
            val restarted = containerFor(root)
            try {
                restarted.characterEditorController.openExisting(source.id)
                assertNull(restarted.characterEditorController.state.value.problem)
                assertFalse(restarted.characterEditorController.state.value.dirty)
                assertFalse(restarted.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD,
                    source.id))
                assertEquals("Committed", restarted.characterRepository.getById(source.id)?.creatorNotes)
            } finally { restarted.close() }
        }
    }

    @Test fun `reverted draft document and its assets are removed on shutdown`() = runBlocking {
        fixture { root, app, controller ->
            val source = CharacterCard.create("Source", "Hi")
            app.characterRepository.save(source)
            controller.openExisting(source.id)
            val original = controller.state.value.card!!
            controller.addTextDocument("notes.txt", "temporary")
            controller.flushDraft()
            val draft = app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, source.id)!!
            val asset = root.resolve(draft.draftAssetPaths.single())
            controller.edit { original }
            assertFalse(controller.state.value.dirty)
            controller.closeAndDrain()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            assertFalse(Files.exists(asset))
        }
    }

    @Test fun `Community read-only Character rejects import and explicit conversion`() = runBlocking {
        fixture { _, app, controller ->
            val person = CharacterInfo.create("Alice").copy(profile = "Profile")
            val community = CharacterCard.create("Community", "Hi", listOf(person))
                .copy(communityItemId = "remote")
            val donor = CharacterCard.create("Donor", "Hi", listOf(person.copy(id = "donor-person", profile = "New")))
            app.characterRepository.save(community)
            app.characterRepository.save(donor)
            controller.load()
            controller.openExisting(community.id)
            val before = controller.state.value.card
            assertNull(controller.importCharacters(donor.id, listOf(CharacterSectionSelection("donor-person",
                setOf(CharacterTextSection.PROFILE)))))
            assertFalse(controller.convertStructuredToFreeform())
            assertEquals(before, controller.state.value.card)
            assertEquals(community, app.characterRepository.getById(community.id))
            assertEquals(donor, app.characterRepository.getById(donor.id))
        }
    }

    @Test fun `Character drain persists dirty work before debounce completes`() = runBlocking {
        fixture { root, app, controller ->
            controller.openNew()
            controller.edit { it.copy(name = "Unflushed", greeting = "Hi") }
            assertFalse(controller.state.value.draftPersisted)
            controller.closeAndDrain()
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, null))
            val reopened = containerFor(root)
            try {
                reopened.characterEditorController.openNew()
                assertEquals("Unflushed", reopened.characterEditorController.state.value.card?.name)
            } finally { reopened.close() }
        }
    }

    @Test fun `recovered mode reverted to base stays editable and autosaves the next switch`() = runBlocking {
        fixture { _, app, controller ->
            val source = CharacterCard.create("Card", "Hi").copy(editMode = CharacterEditMode.STRUCTURED)
            app.characterRepository.save(source)
            app.editorDraftRepository.save(app.editorDraftRepository.characterDraft(source.id, "recovered",
                source.copy(editMode = CharacterEditMode.FREEFORM), source, emptyList(), emptyList(), emptyList()))
            controller.openExisting(source.id)
            controller.switchMode(CharacterEditMode.STRUCTURED)
            assertFalse(controller.state.value.dirty)
            assertNotNull(controller.state.value.draftBasis)
            assertNull(controller.state.value.problem)
            assertOrdinaryEditable(controller.state.value)
            controller.switchMode(CharacterEditMode.FREEFORM)
            assertTrue(controller.state.value.dirty)
            withTimeout(5000) { while (!controller.state.value.draftPersisted) delay(20) }
            assertEquals(CharacterEditMode.FREEFORM, app.editorDraftRepository
                .getForTarget(EditorDraftType.CHARACTER_CARD, source.id)?.characterPayload?.editMode)
            assertEquals(source, app.characterRepository.getById(source.id))
        }
    }

    @Test fun `text revert remains editable and immediately persists another edit`() = runBlocking {
        fixture { _, app, controller ->
            val source = CharacterCard.create("Card", "Hi").copy(creatorNotes = "A")
            app.characterRepository.save(source)
            controller.openExisting(source.id)
            controller.edit { it.copy(creatorNotes = "B") }
            controller.flushDraft()
            controller.edit { it.copy(creatorNotes = "A") }
            assertFalse(controller.state.value.dirty)
            assertNotNull(controller.state.value.draftBasis)
            assertOrdinaryEditable(controller.state.value)
            controller.edit { it.copy(creatorNotes = "C") }
            assertTrue(controller.state.value.dirty)
            controller.flushDraft()
            assertEquals("C", app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD,
                source.id)?.characterPayload?.creatorNotes)
        }
    }

    @Test fun `document revert remains editable and keeps assets safe until confirmed cleanup`() = runBlocking {
        fixture { root, app, controller ->
            val source = CharacterCard.create("Card", "Hi")
            app.characterRepository.save(source)
            controller.openExisting(source.id)
            controller.addTextDocument("notes.txt", "Draft document")
            controller.flushDraft()
            val draft = assertNotNull(app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            val asset = root.resolve(draft.draftAssetPaths.single())
            controller.clearDocuments()
            assertFalse(controller.state.value.dirty)
            assertOrdinaryEditable(controller.state.value)
            assertTrue(Files.exists(asset))
            controller.edit { it.copy(creatorNotes = "Next edit") }
            assertTrue(controller.state.value.dirty)
            controller.flushDraft()
            assertEquals("Next edit", app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD,
                source.id)?.characterPayload?.creatorNotes)
            controller.discard()
            assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
            assertFalse(Files.exists(asset))
            assertEquals(source, app.characterRepository.getById(source.id))
        }
    }

    @Test fun `ordinary cleanup failure stays editable and retries without committed operations`() = runBlocking {
        for (close in listOf(false, true)) fixture { _, app, _ ->
            val source = CharacterCard.create("Card", "Hi").copy(creatorNotes = "A")
            app.characterRepository.save(source)
            var failDelete = true
            var writes = 0
            val controller = editor(app,
                persistCharacter = { writes++; error("clean editor must not write") },
                refreshRepository = { error("ordinary cleanup must not reconcile committed Character") },
                rewriteTitles = { _, _, _ -> error("ordinary cleanup must not rename sessions") },
                deleteDraft = { type, id ->
                    if (failDelete) error("draft deletion unavailable")
                    app.editorDraftRepository.deleteForTarget(type, id)
                })
            try {
                controller.openExisting(source.id)
                controller.edit { it.copy(creatorNotes = "B") }; controller.flushDraft()
                controller.edit { it.copy(creatorNotes = "A") }
                var left = false
                if (close) {
                    controller.requestLeave { left = true }
                    withTimeout(5000) { while (controller.state.value.problem == null) delay(10) }
                } else assertFalse(controller.save())
                val failed = controller.state.value
                assertEquals(CharacterEditorProblem.CLEAN_DRAFT_WARNING, failed.problem)
                assertFalse(failed.dirty)
                assertNotNull(failed.draftBasis)
                assertFalse(failed.draftPersisted) // The old B draft does not persist the current A payload.
                assertFalse(left)
                assertEquals(0, writes)
                assertEquals(source, app.characterRepository.getById(source.id))
                val presentation = desktopCharacterEditorPresentation(failed)
                assertFalse(presentation.readOnly)
                assertTrue(presentation.showOrdinaryCleanupRetry)
                assertFalse(presentation.showCommittedCleanupRetry)
                controller.retryCommittedCleanup()
                assertEquals(failed, controller.state.value) // No fabricated committedCard state.
                if (close) {
                    controller.edit { it.copy(creatorNotes = "New edit while deletion is unavailable") }
                    assertTrue(controller.state.value.dirty)
                    assertNull(controller.state.value.problem)
                    controller.flushDraft()
                    controller.edit { it.copy(creatorNotes = "A") }
                }
                failDelete = false
                controller.retryCleanup()
                assertNull(controller.state.value.draftBasis)
                assertFalse(controller.state.value.draftPersisted)
                assertNull(controller.state.value.problem)
                assertFalse(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id))
                assertOrdinaryEditable(controller.state.value)
                controller.edit { it.copy(creatorNotes = "C") }
                assertTrue(controller.state.value.dirty)
                controller.flushDraft()
                assertEquals("C", app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD,
                    source.id)?.characterPayload?.creatorNotes)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `obsolete draft asset failure is nonblocking after confirmed deletion`() = runBlocking {
        fixture { root, app, _ ->
            val source = CharacterCard.create("Card", "Hi")
            app.characterRepository.save(source)
            var assetCleanupAttempted = false
            val controller = editor(app, discardDraftAssets = {
                assertFalse(runBlocking { app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, source.id) })
                assetCleanupAttempted = true
                error("draft asset cleanup unavailable")
            })
            try {
                controller.openExisting(source.id)
                controller.addTextDocument("notes.txt", "temporary")
                controller.flushDraft()
                val asset = root.resolve(controller.state.value.card!!.customDocuments.single().filePath)
                controller.clearDocuments()
                assertTrue(controller.save())
                assertTrue(assetCleanupAttempted)
                assertNull(controller.state.value.draftBasis)
                assertFalse(controller.state.value.draftPersisted)
                assertEquals(CharacterEditorProblem.RESOURCE_FAILED, controller.state.value.problem)
                assertOrdinaryEditable(controller.state.value)
                assertTrue(Files.exists(asset))
                assertEquals(source, app.characterRepository.getById(source.id))
                controller.edit { it.copy(creatorNotes = "Next edit") }
                assertTrue(controller.state.value.dirty)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `Character presentation preserves Community protection and localized ordinary warning`() {
        val source = CharacterCard.create("Community", "Hi").copy(communityItemId = "remote")
        val state = DesktopCharacterEditorState(card = source, base = source, targetId = source.id)
        assertTrue(desktopCharacterEditorPresentation(state).communityReadOnly)
        assertTrue(desktopCharacterEditorPresentation(state).readOnly)
        assertFalse(desktopCharacterEditorPresentation(state).showCommittedCleanupRetry)
        assertFalse(desktopCharacterEditorPresentation(state).showOrdinaryCleanupRetry)
        val zh = DesktopUiStrings(DesktopUiLanguage.ZH_CN)
        val en = DesktopUiStrings(DesktopUiLanguage.EN)
        assertTrue(zh(DesktopUiText.CHARACTER_CLEAN_DRAFT_WARNING).isNotBlank())
        assertTrue(en(DesktopUiText.CHARACTER_CLEAN_DRAFT_WARNING).isNotBlank())
        assertNotEquals(zh(DesktopUiText.CHARACTER_CLEAN_DRAFT_WARNING), en(DesktopUiText.CHARACTER_CLEAN_DRAFT_WARNING))
    }

    private fun assertOrdinaryEditable(state: DesktopCharacterEditorState) {
        val presentation = desktopCharacterEditorPresentation(state)
        assertFalse(presentation.readOnly)
        assertFalse(presentation.showCommittedCleanupRetry)
        assertFalse(presentation.showOrdinaryCleanupRetry)
    }

    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopCharacterEditorController) -> Unit) {
        val parent = Files.createTempDirectory("desktop-character-editor-")
        val root = parent.resolve("app-data")
        val container = containerFor(root)
        try { block(root, container, container.characterEditorController) }
        finally { container.close(); parent.toFile().deleteRecursively() }
    }

    private fun containerFor(root: Path) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() },
    )

    private fun editor(
        container: DesktopAppContainer,
        picker: DesktopFilePicker = FakePicker(Path.of("unused")),
        persistCharacter: suspend (CharacterCard) -> Unit = container.characterRepository::save,
        deleteDraft: suspend (EditorDraftType, String?) -> Unit = container.editorDraftRepository::deleteForTarget,
        refreshRepository: suspend () -> Unit = container.characterRepository::refreshFromStorage,
        rewriteTitles: suspend (String, String, String) -> Int =
            container.chatRepository::rewriteSessionTitlesForCharacterCard,
        discardObsoleteResources: (CharacterCard, List<CharacterCard>) -> Unit =
            DesktopCharacterDraftResources(container.appDataRoot, container.characterResourceStore)::discardObsolete,
        discardDraftAssets: (String) -> Unit =
            DesktopCharacterDraftResources(container.appDataRoot, container.characterResourceStore)::discardSession,
        persistDraftMarker: suspend (EditorDraft) -> EditorDraft = container.editorDraftRepository::save,
    ) = DesktopCharacterEditorController(
        container.characterRepository, container.editorDraftRepository, container.worldBookRepository,
        container.formatCardRepository, container.chatRepository,
        DesktopCharacterDraftResources(container.appDataRoot, container.characterResourceStore),
        container.jsonFileStorage.json, picker,
        persistCharacter = persistCharacter, deleteDraft = deleteDraft,
        refreshRepository = refreshRepository, rewriteTitles = rewriteTitles,
        discardObsoleteResources = discardObsoleteResources, discardDraftAssets = discardDraftAssets,
        persistDraftMarker = persistDraftMarker,
    )

    private class FakePicker(private val source: Path) : DesktopFilePicker {
        var lastOpenType: DesktopFileType? = null
        override fun pickOpenFile(type: DesktopFileType): Path {
            lastOpenType = type
            return source
        }
        override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
    }
}
