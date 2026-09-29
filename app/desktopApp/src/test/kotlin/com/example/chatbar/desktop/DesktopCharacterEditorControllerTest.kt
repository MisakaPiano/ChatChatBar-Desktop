package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
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

    private fun editor(container: DesktopAppContainer, picker: DesktopFilePicker) = DesktopCharacterEditorController(
        container.characterRepository, container.editorDraftRepository, container.worldBookRepository,
        container.formatCardRepository, container.chatRepository,
        DesktopCharacterDraftResources(container.appDataRoot, container.characterResourceStore), picker,
    )

    private class FakePicker(private val source: Path) : DesktopFilePicker {
        override fun pickOpenFile(type: DesktopFileType): Path = source
        override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
    }
}
