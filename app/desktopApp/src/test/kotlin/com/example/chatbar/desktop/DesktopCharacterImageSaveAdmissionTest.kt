package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
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
