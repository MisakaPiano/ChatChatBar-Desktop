package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.domain.card.CharacterCardAiOperation
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.prompt.CharacterCardAiPromptAuthority
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopCharacterImageTest {
    private val person = CharacterInfo("person", "合成人物", imagePrompt = "synthetic-person",
        fishAudioVoice = FishAudioVoiceBinding("voice", "合成声线"), desktopEnabled = false)
    private val card = CharacterCard("card", "合成卡", greeting = "合成开场", characters = listOf(person),
        defaultImagePrompt = "synthetic-style", defaultImageNegativePrompt = "synthetic-negative", createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("model", "合成模型", "http://127.0.0.1:1/v1", "fixture-key", "original", createdAt = 1)
    private val bytes get() = DesktopImageEditing.png(java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB))
    private val plan get() = NovelAiPromptPlan("synthetic-cover", emptyList(), negativePrompt = "synthetic-negative")

    @Test fun `avatar bypasses design model and applies only selected person image through normal save`() = runBlocking {
        fixture { _, app, editor, secrets ->
            app.settingsRepository.updateAppSettings { it.copy(defaultImageModelId = "missing-design-model") }
            var generated = 0
            val ai = controller(app, editor, secrets, designer = { _, _, _, _, _ -> error("No avatar design") },
                generator = { actual, size, _, _, _ ->
                    val parent = assertNotNull(currentCoroutineContext()[DesktopUsageParent]?.value)
                    assertEquals(DesktopUsageSource.CHARACTER_CARD, parent.source)
                    assertEquals(card.id, parent.cardId); assertEquals(person.id, parent.personId)
                    assertEquals(editor.state.value.draftSessionId, parent.editorSessionId); assertNull(parent.sessionId)
                    assertEquals(app.taskRuntime.ownedWork.value.single().taskId, parent.taskId)
                    generated++; assertEquals(512, size.width); assertEquals(512, size.height)
                    assertEquals("synthetic-style, synthetic-person, ${CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT}", actual.baseCaption)
                    assertEquals("synthetic-negative, ${CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_NEGATIVE_PROMPT}", actual.negativePrompt)
                    bytes
                })
            ai.openAvatar(person.id); assertEquals(0, generated)
            ai.generate(); idle(ai); assertNotNull(ai.state.value.candidate); assertEquals(card, editor.state.value.card)
            val draft = assertNotNull(editor.state.value.draftSessionId)
            assertTrue(ai.revealTaskDraft(draft)); assertFalse(ai.revealTaskDraft("other-draft"))
            assertEquals(DesktopOwnedWorkKind.CHARACTER_IMAGE, app.taskRuntime.ownedWork.value.single().kind)
            ai.apply(); idle(ai); assertFalse(ai.state.value.open)
            assertFalse(ai.revealTaskDraft(draft))
            val applied = editor.state.value.card!!
            assertEquals(person.copy(appearanceImage = applied.characters.single().appearanceImage), applied.characters.single())
            assertTrue(applied.characters.single().appearanceImage!!.startsWith("draft_assets/"))
            assertNull(applied.avatar); assertNull(applied.chatBackground)
            assertEquals(card, app.characterRepository.getById(card.id))
            assertTrue(editor.save())
            val saved = app.characterRepository.getById(card.id)!!
            assertFalse(saved.characters.single().appearanceImage!!.startsWith("draft_assets/"))
            assertContentEquals(bytes, editor.imageBytes(saved.characters.single().appearanceImage))
            assertEquals(1, generated)
        }
    }

    @Test fun `cover retry reuses finished design and cropped avatar preserves full background on save`() = runBlocking {
        fixture { _, app, editor, secrets ->
            var designs = 0; var generations = 0
            val ai = controller(app, editor, secrets, designer = { actual, _, hint, requirement, _ ->
                designs++; assertEquals(card, actual); assertEquals("合成内容要求", hint); assertEquals("合成保存要求", requirement); plan
            }, generator = { actual, _, _, preview, _ ->
                assertEquals(plan, actual)
                generations++
                preview(bytes, 0.5f)
                if (generations == 1) error("synthetic generation failure") else bytes
            })
            ai.openCover(); ready(ai); ai.setHint("合成内容要求")
            ai.generate(); idle(ai); assertNull(ai.state.value.candidate); assertNotNull(ai.state.value.error)
            ai.generate(retry = true); idle(ai); assertNotNull(ai.state.value.candidate)
            assertEquals(1, designs); assertEquals(2, generations)
            val crop = DesktopImageEditing.png(java.awt.image.BufferedImage(12, 12, java.awt.image.BufferedImage.TYPE_INT_RGB))
            ai.setCrop(crop); ai.apply(); idle(ai)
            val applied = editor.state.value.card!!
            assertNotEquals(applied.avatar, applied.chatBackground)
            assertContentEquals(crop, editor.imageBytes(applied.avatar)); assertContentEquals(bytes, editor.imageBytes(applied.chatBackground))
            assertEquals(listOf(person), applied.characters); assertEquals(card, app.characterRepository.getById(card.id))
            assertTrue(editor.save())
            val saved = app.characterRepository.getById(card.id)!!
            assertContentEquals(crop, editor.imageBytes(saved.avatar)); assertContentEquals(bytes, editor.imageBytes(saved.chatBackground))
        }
    }

    @Test fun `candidate cover stays inside AI candidate until explicit card draft apply`() = runBlocking {
        fixture { _, app, editor, secrets ->
            val imageAi = controller(app, editor, secrets)
            val textAi = DesktopCharacterCardAiController(editor, app.settingsRepository, app.effectiveModelResolver,
                app.taskRuntime, app.dataOperationCoordinator, stream = { _, _, _, _, _ ->
                    flowOf(StreamEvent.Delta("""{"characters":[{"targetIndex":0,"appearance":"新外貌"}]}"""), StreamEvent.Done)
                }).also { editor.cardAi = it }
            textAi.open(CharacterCardAiOperation.FILL)
            withTimeout(5_000) { textAi.state.first { !it.loading } }
            textAi.setRequest("合成要求"); textAi.generate()
            withTimeout(5_000) { textAi.state.first { !it.busy } }
            textAi.openCandidateCover(); ready(imageAi); imageAi.generate(); idle(imageAi)
            imageAi.apply(); idle(imageAi)
            assertEquals(card, editor.state.value.card)
            assertNotNull(textAi.state.value.candidate!!.avatar)
            assertNotNull(textAi.state.value.candidate!!.chatBackground)
            assertTrue(textAi.apply()); assertNotNull(editor.state.value.card!!.avatar)
            assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `failed draft resource save retains completed image and applying again never generates`() = runBlocking {
        fixture { root, app, editor, secrets ->
            var generations = 0
            val ai = controller(app, editor, secrets, generator = { _, _, _, _, _ -> generations++; bytes })
            ai.openAvatar(person.id); ai.generate(); idle(ai)
            val blocker = root.resolve("draft_assets"); Files.writeString(blocker, "synthetic blocker")
            ai.apply(); idle(ai)
            assertNotNull(ai.state.value.error); assertNotNull(ai.state.value.candidate); assertEquals(card, editor.state.value.card)
            assertEquals("synthetic blocker", Files.readString(blocker)); Files.delete(blocker)
            ai.apply(); idle(ai)
            assertFalse(ai.state.value.open); assertEquals(1, generations)
            assertNotNull(editor.state.value.card!!.characters.single().appearanceImage)
        }
    }

    @Test fun `frozen account remains selected during generation and maintenance cancels partial candidate`() = runBlocking {
        fixture { _, app, editor, secrets ->
            val entered = CompletableDeferred<Unit>(); val inspect = CompletableDeferred<Unit>(); val drained = CompletableDeferred<Unit>()
            val accountObserved = CompletableDeferred<String?>()
            val ai = controller(app, editor, secrets, generator = { _, _, _, preview, _ ->
                try {
                    preview(bytes, 0.5f); entered.complete(Unit); inspect.await()
                    accountObserved.complete(secrets.credentialForCall(DesktopCredentialKey.NovelAiToken))
                    drained.complete(Unit); awaitCancellation()
                } finally { if (!drained.isCompleted) drained.complete(Unit) }
            })
            ai.openAvatar(person.id); ai.generate(); withTimeout(5_000) { entered.await() }
            secrets.save(DesktopCredentialKey.NovelAiToken, "synthetic-replacement-key"); inspect.complete(Unit)
            assertEquals("synthetic-original-key", withTimeout(5_000) { accountObserved.await() })
            withTimeout(5_000) { drained.await() }
            app.taskRuntime.withMaintenance { }
            idle(ai); assertNull(ai.state.value.candidate); assertEquals(card, editor.state.value.card)
            assertEquals("synthetic-replacement-key", secrets.load(DesktopCredentialKey.NovelAiToken))
        }
    }

    @Test fun `freeform avatar requires explicit full positive prompt and never borrows structured style`() = runBlocking {
        fixture { _, app, editor, secrets ->
            editor.edit { it.copy(editMode = CharacterEditMode.FREEFORM, freeformCharacterText = "合成自由正文") }
            var requests = 0
            val ai = controller(app, editor, secrets, generator = { actual, _, _, _, _ ->
                requests++; assertEquals("manual-avatar, ${CharacterCardAiPromptAuthority.CHARACTER_AVATAR_NAI_POSITIVE_PROMPT}", actual.baseCaption); bytes
            })
            ai.openAvatar(person.id); ai.generate(); idle(ai)
            assertEquals(0, requests); assertNull(ai.state.value.candidate)
            ai.setManualPrompt("manual-avatar"); ai.generate(); idle(ai)
            assertEquals(1, requests); assertNotNull(ai.state.value.candidate)
            assertEquals("合成自由正文", editor.state.value.card!!.freeformCharacterText)
        }
    }

    @Test fun `rejected AI candidate image assignment rolls back only newly staged resources`() = runBlocking {
        fixture { root, app, editor, secrets ->
            val ai = controller(app, editor, secrets)
            ai.openCover(card.copy(basicSetting = "合成候选设定")) { false }
            ready(ai); ai.generate(); idle(ai); ai.apply(); idle(ai)
            assertNotNull(ai.state.value.error); assertNotNull(ai.state.value.candidate)
            assertEquals(card, editor.state.value.card)
            val dir = root.resolve("draft_assets").resolve(editor.state.value.draftSessionId!!)
            Files.list(dir).use { assertEquals(0L, it.count()) }
        }
    }

    private fun controller(app: DesktopAppContainer, editor: DesktopCharacterEditorController, secrets: DesktopSecretStore,
        designer: suspend (CharacterCard, ModelConfig, String, String, (String) -> Unit) -> NovelAiPromptPlan = { _, _, _, _, _ -> plan },
        generator: suspend (NovelAiPromptPlan, NovelAiImageSize, NovelAiGenerationSettings, (ByteArray, Float) -> Unit, (String) -> Unit) -> ByteArray =
            { _, _, _, _, _ -> bytes }) =
        DesktopCharacterImageController(editor, app.settingsRepository, app.effectiveModelResolver, app.taskRuntime, app.dataOperationCoordinator,
            captureCredential = { DesktopCredentialCall(secrets, DesktopCredentialKey.NovelAiToken, "synthetic-account", secrets.load(DesktopCredentialKey.NovelAiToken)) },
            loadRequirement = { "合成保存要求" }, design = designer, generateImage = generator).also { editor.cardImages = it }
    private suspend fun ready(ai: DesktopCharacterImageController) = withTimeout(5_000) { ai.state.first { !it.loading } }
    private suspend fun idle(ai: DesktopCharacterImageController) = withTimeout(5_000) { ai.state.first { !it.busy } }
    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopCharacterEditorController, DesktopSecretStore) -> Unit) {
        val parent = Files.createTempDirectory("character-image-fixture-").toAbsolutePath().normalize()
        val secrets = InMemoryDesktopSecretStore().also { it.save(DesktopCredentialKey.NovelAiToken, "synthetic-original-key") }
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            app.characterRepository.save(card); app.modelRepository.saveModel(model)
            app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id, defaultImageModelId = model.id) }
            val editor = app.characterEditorController; editor.openExisting(card.id)
            block(root, app, editor, secrets)
        } finally {
            app.close()
            check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            parent.toFile().deleteRecursively()
        }
    }
}
