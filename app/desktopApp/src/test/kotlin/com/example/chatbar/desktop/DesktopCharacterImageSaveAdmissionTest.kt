package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterImageSaveAdmissionTest {
    @Test fun `real container saves null and V5 High overrides with either transparency value and reopens without resource warning`() = runBlocking {
        for (transparent in listOf<Boolean?>(null, false, true)) {
            fixture { root, app, closeApp ->
                val source = card(root)
                app.characterRepository.save(source)
                val editor = app.characterEditorController
                editor.openExisting(source.id)
                val parameters = transparent?.let {
                    NovelAiCharacterImageSettings(steps = 28, guidance = 8f, cfgRescale = 0f,
                        effort = NovelAiEffort.HIGH, transparentBackground = it)
                }
                editor.edit { it.copy(defaultImageGenerationSettings = parameters, creatorNotes = "Explicit save") }
                assertTrue(editor.save())
                assertNull(editor.state.value.problem, editor.state.value.detail)
                assertFalse(editor.state.value.dirty)
                assertNull(editor.state.value.draftBasis)
                assertEquals(parameters, app.characterRepository.getById(source.id)!!.defaultImageGenerationSettings)
                assertTrue(Files.exists(root.resolve(source.avatar!!)))
                closeApp()
                val reopened = container(root)
                try {
                    reopened.characterEditorController.openExisting(source.id)
                    val state = reopened.characterEditorController.state.value
                    assertNull(state.problem, state.detail)
                    assertFalse(state.dirty)
                    assertEquals(parameters, state.card!!.defaultImageGenerationSettings)
                    assertEquals(NovelAiImageModel.V5_FULL, state.card!!.defaultNovelAiImageModel)
                    assertEquals("Explicit save", state.card!!.creatorNotes)
                } finally { reopened.close() }
            }
        }
    }

    @Test fun `postcommit cleaner removes only unreferenced old avatar and preserves another message reference`() = runBlocking {
        for (referenced in listOf(false, true)) fixture { root, app, _ ->
            val source = card(root)
            val nextImage = image(root, "next.png")
            app.characterRepository.save(source)
            if (referenced) app.jsonFileStorage.saveEntity("chat_messages", "message",
                ChatMessage("message", "session", MessageRole.ASSISTANT, "Retained", images = listOf(source.avatar!!),
                    createdAt = 1, updatedAt = 1), ChatMessage.serializer())
            val editor = app.characterEditorController
            editor.openExisting(source.id)
            editor.edit { it.copy(avatar = nextImage, defaultImageGenerationSettings = NovelAiCharacterImageSettings()) }
            assertTrue(editor.save())
            assertNull(editor.state.value.problem, editor.state.value.detail)
            assertEquals(nextImage, app.characterRepository.getById(source.id)!!.avatar)
            assertEquals(referenced, Files.exists(root.resolve(source.avatar!!)))
            assertTrue(Files.exists(root.resolve(nextImage)))
        }
    }

    @Test fun `unreadable reference authority still reports postcommit resource failure and retains both images`() = runBlocking {
        fixture { root, app, _ ->
            val source = card(root)
            val nextImage = image(root, "next.png")
            app.characterRepository.save(source)
            val damaged = root.resolve("entities/unknown_authority/broken.json")
            Files.createDirectories(damaged.parent)
            Files.writeString(damaged, "not valid JSON")
            val editor = app.characterEditorController
            editor.openExisting(source.id)
            editor.edit { it.copy(avatar = nextImage, defaultImageGenerationSettings = NovelAiCharacterImageSettings()) }
            assertTrue(editor.save()) // Entity commit succeeded; the independent cleanup did not.
            assertEquals(CharacterEditorProblem.RESOURCE_FAILED, editor.state.value.problem)
            assertFalse(editor.state.value.detail.isNullOrBlank())
            assertEquals(nextImage, app.characterRepository.getById(source.id)!!.avatar)
            assertTrue(Files.exists(root.resolve(source.avatar!!)))
            assertTrue(Files.exists(root.resolve(nextImage)))
            assertEquals("not valid JSON", Files.readString(damaged))
        }
    }

    @Test fun `waiting postcommit cleanup retains original marker and serializes duplicate retry before another edit`() = runBlocking {
        fixture { root, app, _ ->
            val source = card(root)
            app.characterRepository.save(source)
            val editor = app.characterEditorController
            editor.openExisting(source.id)
            val nextImage = image(root, "waiting-replacement.png")
            editor.edit { it.copy(creatorNotes = "Ordinary field save", avatar = nextImage) }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker = launch(Dispatchers.Default) {
                app.dataOperationCoordinator.withNormalOperation { entered.complete(Unit); release.await() }
            }
            entered.await()
            val saved = async(Dispatchers.Default) { editor.save() }
            try {
                withTimeout(5_000) {
                    while (app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING) delay(10)
                }
                assertFalse(saved.isCompleted)
                assertEquals(CharacterEditorProblem.SAVE_COMMITTED_WARNING, editor.state.value.problem)
                assertNotNull(editor.state.value.draftBasis)
                editor.edit { it.copy(creatorNotes = "Must not replace pending save") }
                assertEquals("Ordinary field save", editor.state.value.card!!.creatorNotes)
                val retry = async(Dispatchers.Default) { editor.retryCleanup() }
                release.complete(Unit); blocker.join()
                assertTrue(withTimeout(5_000) { saved.await() })
                withTimeout(5_000) { retry.await() }
                assertNull(editor.state.value.problem, editor.state.value.detail)
                assertNull(editor.state.value.draftBasis)
                assertEquals("Ordinary field save", app.characterRepository.getById(source.id)!!.creatorNotes)
                editor.edit { it.copy(creatorNotes = "Next draft") }
                editor.flushDraft()
                assertTrue(editor.state.value.dirty)
                assertEquals("Next draft", app.editorDraftRepository.getForTarget(EditorDraftType.CHARACTER_CARD, source.id)!!.characterPayload!!.creatorNotes)
                assertFalse(Files.exists(root.resolve(source.avatar!!)))
                assertTrue(Files.exists(root.resolve(nextImage)))
            } finally { release.complete(Unit); blocker.join() }
        }
    }

    private fun card(root: Path) = CharacterCard("card", "Synthetic card", greeting = "Synthetic greeting",
        avatar = image(root, "original.png"), defaultNovelAiImageModel = NovelAiImageModel.V5_FULL,
        createdAt = 1, updatedAt = 1)

    private fun image(root: Path, name: String): String {
        Files.createDirectories(root.resolve("images"))
        val reference = "images/$name"
        Files.write(root.resolve(reference), DesktopImageEditing.png(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)))
        return reference
    }

    private fun container(root: Path) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.parent.resolve("bootstrap.json")),
        secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })

    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, suspend () -> Unit) -> Unit) {
        val parent = Files.createTempDirectory("card-image-save-admission-")
        val root = parent.resolve("data")
        val app = container(root)
        var closed = false
        val closeApp: suspend () -> Unit = { if (!closed) { closed = true; app.close() } }
        try { block(root, app, closeApp) }
        finally { closeApp(); check(parent.toFile().deleteRecursively()) }
    }
}
