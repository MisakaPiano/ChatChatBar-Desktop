package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
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
                        ?.characterPayload?.name != "Autosaved") delay(50)
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
                assertEquals(CharacterEditorProblem.DRAFT_FAILED, controller.state.value.problem)
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
                controller.edit { it.copy(creatorNotes = "must wait") }
                assertEquals(saved, controller.state.value.card)
                failDelete = false
                controller.retryCommittedCleanup()
                assertNull(controller.state.value.draftBasis)
                assertNull(controller.state.value.problem)
                controller.edit { it.copy(creatorNotes = "now editable") }
                assertTrue(controller.save())
                assertEquals("now editable", container.characterRepository.getById(saved.id)?.creatorNotes)
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `post-delete cache error still permits asset cleanup with warning`() = runBlocking {
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
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
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
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, controller.state.value.problem)
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
    ) = DesktopCharacterEditorController(
        container.characterRepository, container.editorDraftRepository, container.worldBookRepository,
        container.formatCardRepository, container.chatRepository,
        DesktopCharacterDraftResources(container.appDataRoot, container.characterResourceStore), picker,
        persistCharacter = persistCharacter, deleteDraft = deleteDraft,
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
