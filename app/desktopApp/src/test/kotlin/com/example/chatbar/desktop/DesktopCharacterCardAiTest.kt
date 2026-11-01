package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.search.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopCharacterCardAiTest {
    private val person = CharacterInfo("person", "合成人物", profile = "原简介",
        appearanceImage = "synthetic-avatar.png", fishAudioVoice = FishAudioVoiceBinding("synthetic-voice", "合成声线"), desktopEnabled = false)
    private val card = CharacterCard("synthetic-card", "合成卡", greeting = "合成开场", characters = listOf(person),
        defaultImageNegativePrompt = "synthetic-negative", createdAt = 1, updatedAt = 1)
    private val model = ModelConfig("synthetic-model", "合成模型", "http://127.0.0.1:1/v1", "", "original-model", createdAt = 1)
    private val fill = """{"characters":[{"targetIndex":0,"appearance":"新外观"}]}"""

    @Test fun `fill exact fresh model diff apply ordinary draft and save preserve owned person configuration`() = runBlocking {
        fixture { _, app, editor ->
            val ai = controller(app, editor) { _, _, _, actual, _ ->
                assertEquals("fresh-model", actual.modelName); flowOf(StreamEvent.Delta(fill), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("补全外观"); ai.selectModel(model.id)
            app.modelRepository.saveModel(model.copy(modelName = "fresh-model"))
            ai.generate(); idle(ai)
            val merged = assertNotNull(ai.state.value.candidate)
            assertEquals(person.copy(appearance = "新外观"), merged.characters.single())
            assertTrue(desktopCharacterCardAiDiff(card, merged).any { it.before == "" && it.after == "新外观" })
            assertEquals(card, app.characterRepository.getById(card.id))
            assertTrue(ai.apply()); assertEquals(merged, editor.state.value.card)
            editor.flushDraft()
            assertTrue(app.editorDraftRepository.existsForTarget(EditorDraftType.CHARACTER_CARD, card.id))
            assertEquals(card, app.characterRepository.getById(card.id))
            assertTrue(editor.save())
            assertEquals(person.copy(appearance = "新外观"), app.characterRepository.getById(card.id)!!.characters.single())
        }
    }

    @Test fun `missing selected model does not fall back and stale manual edits cannot receive a candidate`() = runBlocking {
        fixture { _, app, editor ->
            var calls = 0
            val ai = controller(app, editor) { _, _, _, _, _ -> calls++; flowOf(StreamEvent.Delta(fill), StreamEvent.Done) }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("补全外观"); ai.selectModel("missing-model")
            ai.generate(); idle(ai); assertEquals(0, calls); assertNull(ai.state.value.candidate)
            ai.selectModel(model.id); ai.generate(); idle(ai); assertEquals(1, calls)
            editor.edit { it.copy(basicSetting = "手动更改") }
            assertFalse(ai.apply()); assertEquals("手动更改", editor.state.value.card!!.basicSetting)
            assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `completed malformed generation reports repair truncation and retries only repair without changing draft`() = runBlocking {
        fixture { _, app, editor ->
            val stages = mutableListOf<CharacterCardAiStage>(); var firstRepair = true
            val ai = controller(app, editor) { _, stage, _, _, _ ->
                stages += stage
                if (stage == CharacterCardAiStage.GENERATE) flowOf(StreamEvent.Delta("not-json"), StreamEvent.Done)
                else if (firstRepair) { firstRepair = false; flowOf(StreamEvent.Error("synthetic failure", cause = ModelResponseTruncatedException())) }
                else flowOf(StreamEvent.Delta(fill), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("补全外观")
            ai.generate(); idle(ai); assertNull(ai.state.value.candidate)
            assertTrue(ai.state.value.error.orEmpty().contains("JSON 格式修复"))
            assertTrue(ai.state.value.error.orEmpty().contains("输出上限"))
            assertEquals(card, editor.state.value.card)
            ai.generate(retry = true); idle(ai)
            assertEquals(listOf(CharacterCardAiStage.GENERATE, CharacterCardAiStage.REPAIR, CharacterCardAiStage.REPAIR), stages)
            assertNotNull(ai.state.value.candidate)
            assertEquals(card, editor.state.value.card)
        }
    }

    @Test fun `maintenance cancels active request drains it and preserves partial preview without applying`() = runBlocking {
        fixture { _, app, editor ->
            val entered = CompletableDeferred<Unit>(); val drained = CompletableDeferred<Unit>()
            val ai = controller(app, editor) { _, _, _, _, _ -> flow {
                try { emit(StreamEvent.Delta("未完成正文")); entered.complete(Unit); awaitCancellation() }
                finally { drained.complete(Unit) }
            } }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("合成要求"); ai.generate()
            withTimeout(5_000) { entered.await() }
            ai.close(); assertTrue(ai.state.value.open)
            app.taskRuntime.withMaintenance { assertTrue(drained.isCompleted) }
            idle(ai); assertNull(ai.state.value.candidate); assertEquals("未完成正文", ai.state.value.raw)
            assertEquals(card, editor.state.value.card); assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `freeform supports rewrite only and retains roster while applying explicit body change`() = runBlocking {
        fixture { _, app, editor ->
            editor.edit { it.copy(editMode = CharacterEditMode.FREEFORM, freeformCharacterText = "原自由正文") }
            val ai = controller(app, editor) { operation, _, _, _, _ ->
                assertEquals(CharacterCardAiOperation.REWRITE, operation)
                flowOf(StreamEvent.Delta("""{"freeformCharacterText":"新自由正文","deleteCharacterIds":["person"]}"""), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); assertFalse(ai.state.value.open)
            ai.open(CharacterCardAiOperation.REWRITE); ready(ai); ai.setRequest("改正文"); ai.generate(); idle(ai)
            assertEquals(listOf(person), ai.state.value.candidate!!.characters)
            assertTrue(ai.apply()); assertEquals("新自由正文", editor.state.value.card!!.freeformCharacterText)
            assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `selected manual source failure blocks generation and does not fall back to card only`() = runBlocking {
        fixture { _, app, editor ->
            var calls = 0; var preparations = 0
            val ai = controller(app, editor, prepareResearch = { _, _, _, options, _, _, _, _, _, _ ->
                preparations++; assertEquals(CharacterResearchSourceMode.MANUAL_URLS, options.mode)
                assertEquals(listOf("https://example.test/source"), options.urls)
                error("synthetic unavailable source")
            }) { _, _, _, _, _ -> calls++; flowOf(StreamEvent.Delta(fill), StreamEvent.Done) }
            ai.open(CharacterCardAiOperation.FILL); ready(ai)
            ai.selectSourceMode(CharacterResearchSourceMode.MANUAL_URLS); ai.setManualUrls("https://example.test/source")
            ai.setRequest("合成要求"); ai.generate(); idle(ai)
            assertEquals(1, preparations); assertEquals(0, calls); assertNull(ai.state.value.candidate)
            assertTrue(ai.state.value.error.orEmpty().contains("外部资料准备失败"))
            assertEquals(card, editor.state.value.card); assertEquals(card, app.characterRepository.getById(card.id))
        }
    }

    @Test fun `temporary reference reaches prepared research and final request without adding durable documents and final retry reuses brief`() = runBlocking {
        fixture { root, app, editor ->
            val path = root.parent.resolve("synthetic-reference.txt")
            val text = "合成临时参考正文"; Files.writeString(path, text)
            var sourceReads = 0; var generationCalls = 0
            val prepared = ResearchBrief(facts = listOf("合成整理事实"))
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType) = path
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No export")
            }
            val ai = controller(app, editor, prepareResearch = { _, _, selected, options, docs, resume, checkpoint, _, _, _ ->
                assertEquals(model.modelName, selected.modelName); assertEquals(CharacterResearchSourceMode.NONE, options.mode)
                assertEquals(listOf(CharacterReferenceDocument(path.fileName.toString(), text)), docs)
                if (resume?.brief == null) sourceReads++ else assertEquals(prepared, resume.brief)
                checkpoint(ResearchDebugSnapshot(plan = CharacterResearchPlan(), brief = prepared)); prepared
            }, filePicker = picker) { _, stage, messages, _, _ ->
                assertEquals(CharacterCardAiStage.GENERATE, stage); generationCalls++
                val payload = Json.parseToJsonElement(messages.last().content.jsonPrimitive.content).jsonObject
                assertEquals("合成整理事实", payload["externalResearch"]!!.jsonObject["facts"]!!.jsonArray.single().jsonPrimitive.content)
                flowOf(StreamEvent.Delta(fill), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.selectSourceMode(CharacterResearchSourceMode.NONE)
            ai.setRequest("补全外观"); ai.chooseReference()
            withTimeout(5_000) { ai.state.first { !it.referenceLoading } }
            assertEquals(text, ai.state.value.reference?.content)
            ai.generate(); idle(ai); assertNotNull(ai.state.value.candidate)
            ai.regenerateFinal(); idle(ai); assertNotNull(ai.state.value.candidate)
            assertEquals(1, sourceReads); assertEquals(2, generationCalls)
            assertTrue(editor.state.value.card!!.customDocuments.isEmpty())
            assertTrue(app.characterRepository.getById(card.id)!!.customDocuments.isEmpty())
            assertEquals(text, Files.readString(path))
            ai.clearReference(); assertNull(ai.state.value.candidate); assertNull(ai.state.value.research)
        }
    }

    @Test fun `model change retains partial research plan and allows resuming required source preparation`() = runBlocking {
        fixture { _, app, editor ->
            val replacement = model.copy(id = "replacement-model", modelName = "replacement")
            app.modelRepository.saveModel(replacement)
            val plan = CharacterResearchPlan(true, listOf(CharacterResearchQuery("https://example.test/source")))
            var attempts = 0; var planned = 0
            val ai = controller(app, editor, prepareResearch = { _, _, selected, _, _, resume, checkpoint, _, _, _ ->
                attempts++
                if (resume?.plan == null) {
                    planned++; checkpoint(ResearchDebugSnapshot(plan = plan)); error("synthetic source failure")
                }
                assertEquals(plan, resume.plan); assertEquals("replacement", selected.modelName)
                val brief = ResearchBrief(facts = listOf("合成整理事实"))
                checkpoint(ResearchDebugSnapshot(plan = plan, brief = brief)); brief
            }) { _, _, _, selected, _ ->
                assertEquals("replacement", selected.modelName); flowOf(StreamEvent.Delta(fill), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("补全外观")
            ai.selectSourceMode(CharacterResearchSourceMode.MANUAL_URLS); ai.setManualUrls("https://example.test/source")
            ai.generate(); idle(ai); assertNull(ai.state.value.candidate); assertEquals(plan, ai.state.value.research?.plan)
            ai.selectModel(replacement.id); assertNull(ai.state.value.error); assertEquals(plan, ai.state.value.research?.plan)
            ai.generate(retry = true); idle(ai)
            assertEquals(2, attempts); assertEquals(1, planned); assertNotNull(ai.state.value.candidate)
            assertEquals(card, editor.state.value.card)
        }
    }

    @Test fun `fresh research clears prior visible brief before failure while retry preserves current checkpoint`() = runBlocking {
        fixture { _, app, editor ->
            val brief = ResearchBrief(facts = listOf("上一轮合成事实"))
            var attempts = 0
            val ai = controller(app, editor, prepareResearch = { _, _, _, _, _, resume, checkpoint, output, _, _ ->
                attempts++
                assertNull(resume)
                if (attempts == 1) {
                    output("brief", "合成资料", "上一轮来源")
                    checkpoint(ResearchDebugSnapshot(plan = CharacterResearchPlan(), brief = brief))
                    brief
                } else error("synthetic failure before first checkpoint")
            }) { _, _, _, _, _ -> flowOf(StreamEvent.Delta(fill), StreamEvent.Done) }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.setRequest("合成要求")
            ai.selectSourceMode(CharacterResearchSourceMode.MANUAL_URLS); ai.setManualUrls("https://example.test/source")
            ai.generate(); idle(ai)
            assertEquals(brief, ai.state.value.research?.brief)
            assertTrue(ai.state.value.sourceOutput.isNotEmpty())
            ai.generate(); idle(ai)
            assertNull(ai.state.value.research); assertTrue(ai.state.value.sourceOutput.isEmpty())
            assertNull(ai.state.value.candidate); assertNotNull(ai.state.value.error)
            ai.generate(retry = true); idle(ai)
            assertEquals(3, attempts)
            assertNull(ai.state.value.research); assertTrue(ai.state.value.sourceOutput.isEmpty())
            assertEquals(card, editor.state.value.card)
        }
    }

    @Test fun `reference image batches keep order roll back failed batch and reach only fill multimodal request`() = runBlocking {
        fixture { root, app, editor ->
            val paths = listOf(root.parent.resolve("red.png"), root.parent.resolve("blue.png"))
            paths.forEachIndexed { index, path ->
                val image = java.awt.image.BufferedImage(12, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val color = if (index == 0) java.awt.Color.RED.rgb else java.awt.Color.BLUE.rgb
                repeat(image.width) { x -> repeat(image.height) { y -> image.setRGB(x, y, color) } }
                Files.write(path, DesktopImageEditing.png(image))
            }
            val sourceHashes = paths.map { Files.readAllBytes(it).toList() }
            var selection = paths
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path? = error("Multi selection expected")
                override fun pickOpenFiles(type: DesktopFileType) = selection
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No save")
            }
            app.modelRepository.saveModel(model.copy(isMultimodal = true))
            var calls = 0
            lateinit var expected: List<String>
            val ai = controller(app, editor, filePicker = picker) { _, _, messages, _, _ ->
                calls++
                val content = messages.last().content.jsonArray
                assertEquals(expected.map { "data:image/jpeg;base64,$it" }, content.drop(1).map {
                    it.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                })
                assertTrue(Json.parseToJsonElement(content.first().jsonObject["text"]!!.jsonPrimitive.content)
                    .jsonObject.containsKey("sourceImageInstructions"))
                flowOf(StreamEvent.Delta(fill), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.chooseImages(); imagesReady(ai)
            assertEquals(listOf("red.png", "blue.png"), ai.state.value.images.map { it.name })
            val originals = ai.state.value.images
            ai.moveImage(originals[1].id, -1)
            val invalid = root.parent.resolve("invalid.png"); Files.writeString(invalid, "invalid synthetic image")
            selection = listOf(paths[0], invalid); ai.chooseImages(); imagesReady(ai)
            assertEquals(originals.reversed(), ai.state.value.images)
            assertNotNull(ai.state.value.error)
            expected = ai.state.value.images.map { it.base64 }
            ai.generate(); idle(ai); assertEquals(1, calls); assertNotNull(ai.state.value.candidate)
            assertEquals(card, editor.state.value.card)
            assertEquals(sourceHashes, paths.map { Files.readAllBytes(it).toList() })
            ai.close(); assertTrue(ai.state.value.images.isEmpty()); assertFalse(ai.state.value.open)
        }
    }

    @Test fun `linked vision describes images sequentially and successful preparation survives final retry`() = runBlocking {
        fixture { root, app, editor ->
            val path = root.parent.resolve("reference.png")
            Files.write(path, DesktopImageEditing.png(java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB)))
            val vision = model.copy(id = "vision", modelName = "exact-vision", isMultimodal = true, apiKey = "synthetic-key")
            app.modelRepository.saveModel(vision)
            app.modelRepository.saveModel(model.copy(visionModelId = vision.id))
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType) = path
                override fun pickOpenFiles(type: DesktopFileType) = listOf(path, path)
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = error("No save")
            }
            var descriptions = 0; var requests = 0
            val ai = controller(app, editor, filePicker = picker, describeImage = { _, selected, delta, _ ->
                assertEquals("exact-vision", selected.modelName); descriptions++
                "合成图片说明 $descriptions".also(delta)
            }) { _, _, messages, _, _ ->
                requests++
                val text = messages.last().content.jsonPrimitive.content
                if (requests <= 2) {
                    assertTrue(text.contains("合成图片说明 1")); assertTrue(text.contains("合成图片说明 2"))
                } else {
                    assertTrue(text.contains("合成图片说明 3")); assertFalse(text.contains("合成图片说明 1"))
                }
                flowOf(StreamEvent.Delta(fill), StreamEvent.Done)
            }
            ai.open(CharacterCardAiOperation.FILL); ready(ai); ai.chooseImages(); imagesReady(ai)
            ai.generate(); idle(ai); assertNotNull(ai.state.value.candidate)
            ai.regenerateFinal(); idle(ai)
            assertEquals(2, descriptions); assertEquals(2, requests)
            ai.removeImage(ai.state.value.images.first().id); assertNull(ai.state.value.candidate)
            ai.generate(); idle(ai); assertEquals(3, descriptions)
            assertEquals(card, editor.state.value.card)
        }
    }

    private suspend fun imagesReady(ai: DesktopCharacterCardAiController) = withTimeout(5_000) { ai.state.first { !it.imagesLoading } }

    private fun controller(app: DesktopAppContainer, editor: DesktopCharacterEditorController,
        prepareResearch: DesktopCharacterResearchPreparation? = null,
        filePicker: DesktopFilePicker = UnconfiguredDesktopFilePicker,
        describeImage: (suspend (String, ModelConfig, (String) -> Unit, ProviderTransportDiagnostics) -> String)? = null,
        stream: (CharacterCardAiOperation, CharacterCardAiStage, List<ChatApiMessage>, ModelConfig, ProviderTransportDiagnostics) -> Flow<StreamEvent>) =
        DesktopCharacterCardAiController(editor, app.settingsRepository, app.effectiveModelResolver,
            app.taskRuntime, app.dataOperationCoordinator, stream,
            streamResearch = { _, _, _, _ -> error("No unplanned research model call") },
            prepareResearch = prepareResearch, filePicker = filePicker, describeImage = describeImage).also { editor.cardAi = it }
    private suspend fun ready(ai: DesktopCharacterCardAiController) = withTimeout(5_000) { ai.state.first { !it.loading } }
    private suspend fun idle(ai: DesktopCharacterCardAiController) = withTimeout(5_000) { ai.state.first { !it.busy } }
    private suspend fun fixture(block: suspend (Path, DesktopAppContainer, DesktopCharacterEditorController) -> Unit) {
        val parent = Files.createTempDirectory("character-card-ai-fixture-").toAbsolutePath().normalize()
        val root = parent.resolve("app-data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            app.characterRepository.save(card)
            app.modelRepository.saveModel(model)
            app.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true, defaultModelId = model.id) }
            val editor = app.characterEditorController; editor.openExisting(card.id)
            block(root, app, editor)
        } finally {
            if (app.dataOperationCoordinator.state != DesktopDataOperationCoordinatorState.CLOSED) app.close()
            check(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            parent.toFile().deleteRecursively()
        }
    }
}
