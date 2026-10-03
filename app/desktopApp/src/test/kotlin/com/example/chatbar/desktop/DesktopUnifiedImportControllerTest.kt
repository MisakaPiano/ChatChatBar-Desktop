package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.DesktopModelCredentialPersistencePolicy
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.ModelTemplatePackage
import com.example.chatbar.domain.card.ModelTemplateTransferService
import com.example.chatbar.domain.card.PackagedCharacterCard
import com.example.chatbar.domain.card.PngTextChunks
import com.example.chatbar.domain.card.SharedImportKind
import com.example.chatbar.domain.card.WorldBookPackage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*

class DesktopUnifiedImportControllerTest {
    @Test fun `top level picker accepts any extension and still classifies by bytes`() = runBlocking {
        fixture { f ->
            f.picker.opens.add(f.write("character.unknown", f.json.encodeToString(CharacterCardPackage.serializer(),
                CharacterCardPackage(card = PackagedCharacterCard(name = "Picked"))).toByteArray()))
            f.unified.chooseFile()
            assertTrue(assertNotNull(f.picker.lastOpenType).extensions.isEmpty())
            assertEquals(1, f.app.characterRepository.getAll().size)
        }
    }

    @Test fun `content first routes Character Format WorldBook and Model with exact focus`() = runBlocking {
        fixture { f ->
            val character = f.write("character.txt", f.json.encodeToString(CharacterCardPackage.serializer(),
                CharacterCardPackage(card = PackagedCharacterCard(name = "C"))).toByteArray())
            f.unified.importFile(character)
            assertEquals(1, f.app.characterRepository.getAll().size)
            assertEquals(SharedImportKind.CHARACTER, f.unified.state.value.focus?.kind)
            assertEquals(f.app.characterRepository.getAll().single().id, f.unified.state.value.focus?.targetId)

            f.unified.importFile(character)
            assertNotNull(f.typed.state.value.pendingConflict)
            assertEquals(1, f.app.characterRepository.getAll().size)
            f.typed.refresh() // Opening the Transfer tab must not erase the existing decision.
            assertNotNull(f.typed.state.value.pendingConflict)
            val blockedFormat = f.write("blocked-format.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Blocked", content = "body")).toByteArray())
            f.unified.importFile(blockedFormat)
            assertTrue(f.app.formatCardRepository.getAll().isEmpty())
            f.typed.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            f.unified.acceptTypedResult(f.typed.state.value)
            assertEquals(2, f.app.characterRepository.getAll().size)
            assertEquals(f.typed.state.value.lastResult?.targetId, f.unified.state.value.focus?.targetId)

            f.unified.importFile(f.write("format.png", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "F", content = "body")).toByteArray()))
            assertEquals(SharedImportKind.FORMAT, f.unified.state.value.focus?.kind)
            assertEquals(f.app.formatCardRepository.getAll().single().id, f.unified.state.value.focus?.targetId)

            f.unified.importFile(f.write("world.txt", f.json.encodeToString(WorldBookPackage.serializer(),
                WorldBookPackage(book = WorldBook.create("W"))).toByteArray()))
            assertEquals(SharedImportKind.WORLD_BOOK, f.unified.state.value.focus?.kind)
            assertEquals(f.app.worldBookRepository.getAll().single().id, f.unified.state.value.focus?.targetId)

            f.unified.importFile(f.write("model.bin", f.modelBytes()))
            val models = f.app.modelRepository.getAllModels()
            assertEquals(1, models.size)
            assertEquals("", models.single().apiKey)
            assertNull(models.single().visionModelId)
            assertEquals(SharedImportKind.MODEL_TEMPLATE, f.unified.state.value.focus?.kind)
            assertEquals(models.single().id, f.unified.state.value.focus?.targetId)
            assertNull(f.app.settingsRepository.readExistingAppSettings())
            assertFalse(Files.exists(f.root.resolve("entities/app_settings.json")))
            assertFalse(Files.walk(f.root).use { paths -> paths.anyMatch { Files.isRegularFile(it) &&
                Files.readString(it).contains("FAKE-SECRET") } })
        }
    }

    @Test fun `unknown text keeps strict manual choices after wrong target and nontext has none`() = runBlocking {
        fixture { f ->
            val ambiguous = """{"schemaVersion":9,"card":{"name":"Manual"},"name":"F","content":"body"}"""
            f.unified.importFile(f.write("ambiguous.json", ambiguous.toByteArray()))
            assertNotNull(f.unified.state.value.unknown)
            f.unified.tryManualTarget(SharedImportKind.MODEL_TEMPLATE)
            assertNotNull(f.unified.state.value.unknown)
            assertNotNull(f.unified.state.value.error)
            f.unified.tryManualTarget(SharedImportKind.CHARACTER)
            assertNull(f.unified.state.value.unknown)
            assertEquals(1, f.app.characterRepository.getAll().size)
            f.unified.importFile(f.write("foreign.json", "{}".toByteArray()))
            assertNotNull(f.unified.state.value.unknown)
            f.unified.importFile(f.write("binary.dat", byteArrayOf(0, 1, 2, 3)))
            assertTrue(f.unified.state.value.unsupported)
            assertNull(f.unified.state.value.unknown)
        }
    }

    @Test fun `SillyTavern JSON PNG World Info BOM and invalid candidates use shared classifier`() = runBlocking {
        fixture { f ->
            val v1 = """{"name":"V1","description":"desc","first_mes":"hello"}"""
            val v2 = """{"spec":"chara_card_v2","data":{"name":"V2","description":"desc","first_mes":"hello"}}"""
            f.unified.importFile(f.write("v1.bin", v1.toByteArray()))
            f.unified.importFile(f.write("v2.png", v2.toByteArray()))
            val stPng = PngTextChunks.insertTextChunk(imageBytes("png"), "Chara",
                Base64.getEncoder().encodeToString(v2.replace("V2", "PngV2").toByteArray()))
            f.unified.importFile(f.write("st-card.txt", stPng))
            assertEquals(3, f.app.characterRepository.getAll().size)

            val worldInfo = """{"name":"ST World","entries":{"0":{"key":["city"],"content":"here"}}}"""
            f.unified.importFile(f.write("world-info.bin", worldInfo.toByteArray()))
            assertEquals(1, f.app.worldBookRepository.getAll().size)
            val bomFormat = "\uFEFF" + f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "BOM format", content = "body"))
            f.unified.importFile(f.write("bom.dat", bomFormat.toByteArray()))
            assertEquals(1, f.app.formatCardRepository.getAll().size)

            f.unified.importFile(f.write("invalid.json", """{"name":"","content":""}""".toByteArray()))
            assertNotNull(f.unified.state.value.unknown)
            f.unified.importFile(f.write("foreign.json", """{"foreign":true}""".toByteArray()))
            assertNotNull(f.unified.state.value.unknown)
            f.unified.importFile(f.write("ambiguous.json", """{"card":{},"book":{},"name":"F","content":"body"}""".toByteArray()))
            assertNotNull(f.unified.state.value.unknown)
            assertEquals(3, f.app.characterRepository.getAll().size)
        }
    }

    @Test fun `conflict overwrite focuses original ID and cancel never focuses nonexistent target`() = runBlocking {
        fixture { f ->
            val original = f.app.characterTransfers.importNew(CharacterCardPackage(
                card = PackagedCharacterCard(name = "Same", greeting = "old")))
            val incoming = f.write("same.json", f.json.encodeToString(CharacterCardPackage.serializer(),
                CharacterCardPackage(card = PackagedCharacterCard(name = "Same", greeting = "new"))).toByteArray())
            f.unified.importFile(incoming)
            assertNotNull(f.typed.state.value.pendingConflict)
            f.typed.resolveConflict(DesktopTransferConflictAction.CANCEL)
            f.unified.acceptTypedResult(f.typed.state.value)
            assertNull(f.unified.state.value.focus)
            assertEquals(1, f.app.characterRepository.getAll().size)

            f.unified.importFile(incoming)
            val characterOperation = assertNotNull(f.typed.state.value.activeUnifiedOperationId)
            f.typed.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals(characterOperation, f.typed.state.value.lastResult?.unifiedOperationId)
            f.unified.acceptTypedResult(f.typed.state.value)
            assertEquals(original.id, f.unified.state.value.focus?.targetId)
            assertEquals("new", f.app.characterRepository.getById(original.id)?.greeting)
            assertEquals(1, f.app.characterRepository.getAll().size)
        }
    }

    @Test fun `Format and WorldBook conflicts focus their exact overwritten IDs`() = runBlocking {
        fixture { f ->
            val format = f.app.formatTransfers.importNew(FormatCardPackage(name = "Same F", content = "old"))
            val world = f.app.worldBookTransfers.importNew(WorldBookPackage(book = WorldBook.create("Same W")))
            val incomingFormat = f.write("same-format.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Same F", content = "new")).toByteArray())
            f.unified.importFile(incomingFormat)
            assertNotNull(f.typed.state.value.pendingConflict)
            val formatOperation = assertNotNull(f.typed.state.value.activeUnifiedOperationId)
            f.typed.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals(formatOperation, f.typed.state.value.lastResult?.unifiedOperationId)
            f.unified.acceptTypedResult(f.typed.state.value)
            assertEquals(DesktopImportFocus(SharedImportKind.FORMAT, format.id), f.unified.state.value.focus)
            assertEquals("new", f.app.formatCardRepository.getById(format.id)?.content)

            val incomingWorld = f.write("same-world.json", f.json.encodeToString(WorldBookPackage.serializer(),
                WorldBookPackage(book = WorldBook.create("Same W").copy(description = "new"))).toByteArray())
            f.unified.importFile(incomingWorld)
            assertNotNull(f.typed.state.value.pendingConflict)
            val worldOperation = assertNotNull(f.typed.state.value.activeUnifiedOperationId)
            f.typed.resolveConflict(DesktopTransferConflictAction.OVERWRITE)
            assertEquals(worldOperation, f.typed.state.value.lastResult?.unifiedOperationId)
            f.unified.acceptTypedResult(f.typed.state.value)
            assertEquals(DesktopImportFocus(SharedImportKind.WORLD_BOOK, world.id), f.unified.state.value.focus)
            assertEquals("new", f.app.worldBookRepository.getById(world.id)?.description)
        }
    }

    @Test fun `PNG metadata wins and ordinary PNG JPEG GIF are deferred with zero entity or owned writes`() = runBlocking {
        fixture { f ->
            val png = imageBytes("png")
            val ccb = f.json.encodeToString(CharacterCardPackage.serializer(),
                CharacterCardPackage(card = PackagedCharacterCard(name = "PNG card")))
            val cardPng = PngTextChunks.insertTextChunk(png, PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
                Base64.getEncoder().encodeToString(ccb.toByteArray()))
            f.unified.importFile(f.write("not-an-image.txt", cardPng))
            assertEquals(1, f.app.characterRepository.getAll().size)
            val before = Files.walk(f.root).use { it.filter(Files::isRegularFile).count() }
            val novelAiStyle = PngTextChunks.insertTextChunk(png, "Comment", "NovelAI metadata")
            for ((name, bytes) in listOf("image.bin" to png, "photo.txt" to imageBytes("jpeg"),
                "animation.dat" to imageBytes("gif"), "novelai.txt" to novelAiStyle)) {
                f.unified.importFile(f.write(name, bytes))
                assertTrue(f.unified.state.value.imageDeferred)
                assertNull(f.unified.state.value.unknown)
                assertEquals(1, f.app.characterRepository.getAll().size)
                assertTrue(f.app.formatCardRepository.getAll().isEmpty())
                assertTrue(f.app.worldBookRepository.getAll().isEmpty())
                assertTrue(f.app.modelRepository.getAllModels().isEmpty())
            }
            val after = Files.walk(f.root).use { it.filter(Files::isRegularFile).count() }
            assertEquals(before + 4, after) // Only the four input fixtures were written.
            assertFalse(Files.exists(f.root.resolve("images")))
            assertFalse(Files.exists(f.root.resolve("documents")))
        }
    }

    @Test fun `strict model action always imports new and export omits credential`() = runBlocking {
        fixture { f ->
            val path = f.write("template.txt", f.modelBytes())
            f.unified.importModelFile(path)
            f.unified.importModelFile(path)
            assertEquals(2, f.app.modelRepository.getAllModels().size)
            val original = f.app.modelRepository.getAllModels().first()
            val output = f.root.resolve("template-export.json")
            f.picker.saves.add(output)
            f.unified.exportModel(original.id, original.displayName)
            assertTrue(Files.readString(output).contains("Imported Template"))
            assertFalse(Files.readString(output).contains("apiKey"))
            assertNull(f.unified.state.value.error)
        }
    }

    @Test fun `production model policy persists a blank credential and preserves default model`() = runBlocking {
        fixture { f ->
            f.app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "existing-default"))
            val before = f.app.settingsRepository.readExistingAppSettings()
            f.unified.importModelFile(f.write("model.json", f.modelBytes()))
            val imported = f.app.modelRepository.getAllModels().single()
            assertEquals("", imported.apiKey)
            val raw = assertIs<JsonFileStorage.EntityReadResult.Valid<ModelConfig>>(
                f.app.modelRepository.readDurableModel(imported.id)).value
            assertEquals("", raw.apiKey)
            assertEquals(imported, raw)
            assertNull(f.secrets.values[DesktopCredentialKey.ModelApiKey(imported.id)])
            assertEquals(before, f.app.settingsRepository.readExistingAppSettings())
            assertEquals("existing-default", f.app.settingsRepository.readExistingAppSettings()?.defaultModelId)
        }
    }

    @Test fun `secret hydration failure remains precommit and permits explicit retry`() = runBlocking {
        fixture { f ->
            val path = f.write("model.json", f.modelBytes())
            f.secrets.failNextLoad()
            f.unified.importModelFile(path)
            assertNull(f.unified.state.value.unresolvedModel)
            assertNull(f.unified.state.value.focus)
            assertIs<JsonFileStorage.EntityReadResult.Missing>(
                f.app.modelRepository.readDurableModel("missing"))
            f.secrets.loadFailure = null
            f.unified.importModelFile(path)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
        }
    }

    @Test fun `real entity save rejection is precommit and keeps model import retryable`() = runBlocking {
        fixture { f ->
            val gate = object : AppDataOperationGate {
                var failNext = false
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                    if (failNext) { failNext = false; throw IOException("entity save denied") }
                    return operation()
                }
            }
            val storage = JsonFileStorage(f.root, gate)
            val models = ModelRepository(storage, WindowsSafeModelStorageKeyPolicy,
                DesktopModelCredentialPersistencePolicy(f.secrets))
            models.initialize()
            val controller = DesktopUnifiedImportController(f.typed, models,
                ModelTemplateTransferService(models, f.json), f.picker, json = f.json)
            val path = f.write("model.json", f.modelBytes())
            gate.failNext = true
            controller.importModelFile(path)
            assertNull(controller.state.value.unresolvedModel)
            assertNull(controller.state.value.focus)
            assertTrue(models.getAllModels().isEmpty())
            controller.importModelFile(path)
            assertEquals(1, models.getAllModels().size)
            assertNotNull(controller.state.value.focus)
        }
    }

    @Test fun `model prepared failure is precommit and permits explicit retry`() = runBlocking {
        fixture(afterPrepared = { error("before save") }) { f ->
            val path = f.write("template.json", f.modelBytes())
            f.unified.importModelFile(path)
            assertNull(f.unified.state.value.unresolvedModel)
            assertTrue(f.app.modelRepository.getAllModels().isEmpty())
            assertNotNull(f.unified.state.value.error)
        }
    }

    @Test fun `model committed callback cancellation propagates same object after reconcile`() = runBlocking {
        val original = CancellationException("after model commit")
        fixture(afterCommitted = { throw original }) { f ->
            val caught = runCatching { f.unified.importModelFile(f.write("template.json", f.modelBytes())) }.exceptionOrNull()
            assertSame(original, caught)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
            assertNotNull(f.unified.state.value.focus)
            assertNull(f.unified.state.value.unresolvedModel)
            assertFalse(f.unified.state.value.busy)
        }
    }

    @Test fun `model refresh cancellation after durable commit finalizes focus and propagates cancellation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(refresh = { entered.complete(Unit); release.await() }) { f ->
            val path = f.write("model.json", f.modelBytes())
            val cancelled = CancellationException("cancel model refresh")
            var caught: Throwable? = null
            val job = launch {
                try { f.unified.importModelFile(path) } catch (error: Throwable) { caught = error }
            }
            entered.await()
            assertEquals(1, f.app.modelRepository.getAllModels().size)
            job.cancel(cancelled)
            release.complete(Unit)
            job.join()
            assertEquals(cancelled.message, assertIs<CancellationException>(caught).message)
            assertNotNull(f.unified.state.value.focus)
            assertFalse(f.unified.state.value.status.orEmpty().contains("刷新失败"))
            assertFalse(f.unified.state.value.busy)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
        }
    }

    @Test fun `indeterminate model blocks reimport until exact strict verification proves precommit`() = runBlocking {
        var strict: JsonFileStorage.EntityReadResult<ModelConfig> =
            JsonFileStorage.EntityReadResult.ReadError(IOException("read denied"))
        var prepared = 0
        fixture(afterPrepared = { prepared++; error("before save") }, read = { strict }) { f ->
            val path = f.write("template.json", f.modelBytes())
            f.unified.importModelFile(path)
            val id = assertNotNull(f.unified.state.value.unresolvedModel).targetId
            f.typed.refresh()
            f.app.createManagementController(f.typed).refresh()
            assertEquals(id, f.unified.state.value.unresolvedModel?.targetId)
            f.unified.importModelFile(path)
            assertEquals(1, prepared)
            f.unified.recheckModelImport()
            assertEquals(id, f.unified.state.value.unresolvedModel?.targetId)
            assertTrue(f.app.modelRepository.getAllModels().isEmpty())
            strict = JsonFileStorage.EntityReadResult.Missing
            f.unified.recheckModelImport()
            assertNull(f.unified.state.value.unresolvedModel)
            assertNull(f.unified.state.value.focus)
            assertTrue(f.app.modelRepository.getAllModels().isEmpty())
        }
    }

    @Test fun `strict model recheck confirms committed exact target without replay or extra writes`() = runBlocking {
        var strict: JsonFileStorage.EntityReadResult<ModelConfig> =
            JsonFileStorage.EntityReadResult.ReadError(IOException("temporarily unavailable"))
        fixture(afterPrepared = { error("return before save") }, read = { strict }) { f ->
            val path = f.write("template.json", f.modelBytes())
            f.unified.importModelFile(path)
            val expected = assertNotNull(f.unified.state.value.unresolvedModel).expected
            f.app.modelRepository.saveModel(expected) // Deterministic late durable commit evidence.
            val before = f.app.modelRepository.getAllModels().single()
            strict = JsonFileStorage.EntityReadResult.Valid(expected)
            f.unified.recheckModelImport()
            assertNull(f.unified.state.value.unresolvedModel)
            assertEquals(expected.id, f.unified.state.value.focus?.targetId)
            assertEquals(before, f.app.modelRepository.getAllModels().single())
            assertEquals(1, f.app.modelRepository.getAllModels().size)
        }
    }

    @Test fun `production physical strict read confirms delayed model commit without replay`() = runBlocking {
        fixture { f ->
            var strictUnavailable = true
            val models = f.app.modelRepository
            val controller = DesktopUnifiedImportController(f.typed, models,
                ModelTemplateTransferService(models, f.json), f.picker, json = f.json,
                afterModelPrepared = { error("return before save") },
                readModelDurable = { id -> if (strictUnavailable)
                    JsonFileStorage.EntityReadResult.ReadError(IOException("temporarily denied"))
                    else models.readDurableModel(id) })
            val path = f.write("model.json", f.modelBytes())
            controller.importModelFile(path)
            val expected = assertNotNull(controller.state.value.unresolvedModel).expected
            models.saveModel(expected)
            val physical = f.root.resolve("entities/model_configs")
                .resolve("${WindowsSafeModelStorageKeyPolicy.storageKey(expected.id)}.json")
            val beforeBytes = Files.readAllBytes(physical)
            val beforeModified = Files.getLastModifiedTime(physical)
            strictUnavailable = false
            controller.recheckModelImport()
            assertEquals(expected.id, controller.state.value.focus?.targetId)
            assertNull(controller.state.value.unresolvedModel)
            assertEquals(expected, assertIs<JsonFileStorage.EntityReadResult.Valid<ModelConfig>>(
                models.readDurableModel(expected.id)).value)
            assertContentEquals(beforeBytes, Files.readAllBytes(physical))
            assertEquals(beforeModified, Files.getLastModifiedTime(physical))
            assertEquals(1, models.getAllModels().size)
        }
    }

    @Test fun `strict model recheck retains corrupt readerror and mismatched target`() = runBlocking {
        var strict: JsonFileStorage.EntityReadResult<ModelConfig> =
            JsonFileStorage.EntityReadResult.ReadError(IOException("temporarily unavailable"))
        fixture(afterPrepared = { error("return before save") }, read = { strict }) { f ->
            val path = f.write("template.json", f.modelBytes())
            f.unified.importModelFile(path)
            val expected = assertNotNull(f.unified.state.value.unresolvedModel).expected
            val cases = listOf<JsonFileStorage.EntityReadResult<ModelConfig>>(
                JsonFileStorage.EntityReadResult.Corrupt(IllegalArgumentException("bad JSON")),
                JsonFileStorage.EntityReadResult.ReadError(IOException("locked")),
                JsonFileStorage.EntityReadResult.Valid(expected.copy(modelName = "other")),
            )
            cases.forEach { result ->
                strict = result
                f.unified.recheckModelImport()
                assertEquals(expected.id, f.unified.state.value.unresolvedModel?.targetId)
                f.unified.importModelFile(path)
                assertTrue(f.app.modelRepository.getAllModels().isEmpty())
            }
        }
    }

    @Test fun `cancelled strict model recheck retains evidence and releases busy`() = runBlocking {
        val original = CancellationException("verify cancelled")
        var cancelRead = false
        fixture(afterPrepared = { error("return before save") }, read = {
            if (cancelRead) throw original
            JsonFileStorage.EntityReadResult.ReadError(IOException("temporarily unavailable"))
        }) { f ->
            f.unified.importModelFile(f.write("template.json", f.modelBytes()))
            val id = assertNotNull(f.unified.state.value.unresolvedModel).targetId
            cancelRead = true
            val caught = runCatching { f.unified.recheckModelImport() }.exceptionOrNull()
            assertSame(original, caught)
            assertEquals(id, f.unified.state.value.unresolvedModel?.targetId)
            assertFalse(f.unified.state.value.busy)
        }
    }

    @Test fun `real Job cancellation after durable model save before dispatcher return does not invite replay`() = runBlocking {
        fixture { f ->
            val path = f.write("template.json", f.modelBytes())
            val dispatcher = QueuedCallerDispatcher()
            val cancellation = CancellationException("cancel queued model return")
            var caught: Throwable? = null
            val job = CoroutineScope(currentCoroutineContext()).launch(dispatcher) {
                try { f.unified.importModelFile(path) } catch (error: Throwable) { caught = error }
            }
            dispatcher.next().run()
            var returnStep = dispatcher.next()
            while (f.app.modelRepository.getAllModels().isEmpty()) {
                returnStep.run() // Byte read completed; advance to the shared transfer.
                returnStep = dispatcher.next()
            }
            assertEquals(1, f.app.modelRepository.getAllModels().size)
            assertNull(f.unified.state.value.focus)
            job.cancel(cancellation)
            returnStep.run()
            while (!job.isCompleted) dispatcher.next().run()
            assertEquals(cancellation.message, assertIs<CancellationException>(caught).message)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
            assertNotNull(f.unified.state.value.focus)
            assertFalse(f.unified.state.value.busy)
        }
    }

    @Test fun `typed unresolved guard blocks unified ingress before any new resource write`() = runBlocking {
        fixture(typedFactory = { app, picker -> app.createTypedTransferController(picker,
            afterTypedPrepared = { _, _ -> error("uncertain") },
            readFormatDurable = { JsonFileStorage.EntityReadResult.ReadError(IOException("read denied")) }) }) { f ->
            val format = f.write("format.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "F", content = "body")).toByteArray())
            f.typed.importFormat(format)
            assertNotNull(f.typed.state.value.unresolvedTransfer)
            val character = f.write("character.json", f.json.encodeToString(CharacterCardPackage.serializer(),
                CharacterCardPackage(card = PackagedCharacterCard(name = "C"))).toByteArray())
            f.unified.importFile(character)
            assertTrue(f.app.characterRepository.getAll().isEmpty())
            assertTrue(f.app.formatCardRepository.getAll().isEmpty())
        }
    }

    @Test fun `unified Format unresolved recheck delivers exact committed target without replay`() = runBlocking {
        var strictUnavailable = true
        fixture(typedFactory = { app, picker -> app.createTypedTransferController(picker,
            afterTypedPrepared = { _, _ -> error("return before save") },
            readFormatDurable = { id -> if (strictUnavailable)
                JsonFileStorage.EntityReadResult.ReadError(IOException("temporarily denied"))
                else app.formatCardRepository.readDurable(id) }) }) { f ->
            val path = f.write("format.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Delayed F", content = "body")).toByteArray())
            f.unified.importFile(path)
            val evidence = assertNotNull(f.typed.state.value.unresolvedTransfer)
            val expected = evidence.expected as com.example.chatbar.data.local.entity.FormatCard
            f.app.formatCardRepository.save(expected)
            strictUnavailable = false
            f.typed.recheckUnresolvedTransfer()
            f.unified.acceptTypedResult(f.typed.state.value)
            assertEquals(DesktopImportFocus(SharedImportKind.FORMAT, expected.id), f.unified.state.value.focus)
            assertEquals(1, f.app.formatCardRepository.getAll().size)
        }
    }

    @Test fun `same kind stale result cannot focus a newer unified conflict`() = runBlocking {
        fixture { f ->
            val first = f.write("first.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Same", content = "first")).toByteArray())
            f.unified.importFile(first)
            val stale = f.typed.state.value
            val oldId = assertNotNull(stale.lastResult).targetId
            val second = f.write("second.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Same", content = "second")).toByteArray())
            f.unified.importFile(second)
            assertNotNull(f.typed.state.value.pendingConflict)
            f.unified.acceptTypedResult(stale)
            assertNull(f.unified.state.value.focus)
            f.typed.resolveConflict(DesktopTransferConflictAction.IMPORT_AS_NEW)
            f.unified.acceptTypedResult(f.typed.state.value)
            assertNotNull(f.unified.state.value.focus)
            assertTrue(f.unified.state.value.focus?.targetId != oldId)
        }
    }

    @Test fun `unified WorldBook unresolved recheck delivers exact committed target without replay`() = runBlocking {
        var strictUnavailable = true
        fixture(typedFactory = { app, picker -> app.createTypedTransferController(picker,
            afterTypedPrepared = { _, _ -> error("return before save") },
            readWorldDurable = { id -> if (strictUnavailable)
                JsonFileStorage.EntityReadResult.ReadError(IOException("temporarily denied"))
                else app.worldBookRepository.readDurable(id) }) }) { f ->
            val path = f.write("world.json", f.json.encodeToString(WorldBookPackage.serializer(),
                WorldBookPackage(book = WorldBook.create("Delayed W"))).toByteArray())
            f.unified.importFile(path)
            val expected = assertNotNull(f.typed.state.value.unresolvedTransfer).expected as WorldBook
            f.app.jsonFileStorage.saveEntity("world_books", expected.id, expected, WorldBook.serializer())
            strictUnavailable = false
            f.typed.recheckUnresolvedTransfer()
            f.unified.acceptTypedResult(f.typed.state.value)
            assertEquals(DesktopImportFocus(SharedImportKind.WORLD_BOOK, expected.id), f.unified.state.value.focus)
            assertEquals(1, f.app.worldBookRepository.getAll().size)
        }
    }

    @Test fun `typed precommit recheck clears unified ownership and needs a new explicit import`() = runBlocking {
        var strictUnavailable = true
        var failPrepared = true
        fixture(typedFactory = { app, picker -> app.createTypedTransferController(picker,
            afterTypedPrepared = { _, _ -> if (failPrepared) error("before save") },
            readFormatDurable = { id -> if (strictUnavailable)
                JsonFileStorage.EntityReadResult.ReadError(IOException("temporarily denied"))
                else app.formatCardRepository.readDurable(id) }) }) { f ->
            val path = f.write("format.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Retry F", content = "body")).toByteArray())
            f.unified.importFile(path)
            assertNotNull(f.typed.state.value.unresolvedTransfer)
            strictUnavailable = false
            f.typed.recheckUnresolvedTransfer()
            f.unified.acceptTypedResult(f.typed.state.value)
            assertNull(f.unified.state.value.focus)
            assertTrue(f.app.formatCardRepository.getAll().isEmpty())
            failPrepared = false
            f.unified.importFile(path)
            assertEquals(1, f.app.formatCardRepository.getAll().size)
            assertNotNull(f.unified.state.value.focus)
        }
    }

    @Test fun `direct typed result cannot satisfy a unified conflict token`() = runBlocking {
        fixture { f ->
            f.app.formatTransfers.importNew(FormatCardPackage(name = "Same", content = "old"))
            val path = f.write("unified.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Same", content = "new")).toByteArray())
            f.unified.importFile(path)
            assertNotNull(f.typed.state.value.pendingConflict)
            val direct = f.write("direct.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Direct", content = "own")).toByteArray())
            f.typed.importFormat(direct)
            f.unified.acceptTypedResult(f.typed.state.value)
            assertNull(f.unified.state.value.focus)
        }
    }

    @Test fun `cancelled focus delivery retains exact target until retry succeeds`() = runBlocking {
        fixture { f ->
            f.unified.importFile(f.write("format.json", f.json.encodeToString(FormatCardPackage.serializer(),
                FormatCardPackage(name = "Focus", content = "body")).toByteArray()))
            val focus = assertNotNull(f.unified.state.value.focus)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var opens = 0
            val job = launch {
                deliverImportFocus(f.unified, focus) {
                    entered.complete(Unit)
                    release.await()
                    opens++
                    true
                }
            }
            entered.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertEquals(focus, f.unified.state.value.focus)
            deliverImportFocus(f.unified, focus) { opens++; true }
            assertEquals(1, opens)
            assertNull(f.unified.state.value.focus)
        }
    }

    @Test fun `model focus delivery cancellation retains target and missing target requires dismissal`() = runBlocking {
        fixture { f ->
            f.unified.importModelFile(f.write("model.json", f.modelBytes()))
            val focus = assertNotNull(f.unified.state.value.focus)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = launch { deliverImportFocus(f.unified, focus) {
                entered.complete(Unit); release.await(); true
            } }
            entered.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertEquals(focus, f.unified.state.value.focus)
            deliverImportFocus(f.unified, focus) { false }
            assertNotNull(f.unified.state.value.focusDeliveryError)
            assertEquals(focus, f.unified.state.value.focus)
            f.unified.retryFocusDelivery()
            deliverImportFocus(f.unified, focus) { true }
            assertNull(f.unified.state.value.focus)
        }
    }

    @Test fun `unknown manual typed conflict cancel ends ownership without focus`() = runBlocking {
        fixture { f ->
            f.app.formatTransfers.importNew(FormatCardPackage(name = "Manual", content = "old"))
            val ambiguous = """{"card":{"name":"Manual"},"name":"Manual","content":"new"}"""
            f.unified.importFile(f.write("ambiguous.json", ambiguous.toByteArray()))
            assertNotNull(f.unified.state.value.unknown)
            f.unified.tryManualTarget(SharedImportKind.FORMAT)
            assertNotNull(f.typed.state.value.pendingConflict)
            f.typed.resolveConflict(DesktopTransferConflictAction.CANCEL)
            f.unified.acceptTypedResult(f.typed.state.value)
            assertNull(f.unified.state.value.focus)
            assertNull(f.unified.state.value.unknown)
        }
    }

    private suspend fun fixture(afterPrepared: (ModelConfig) -> Unit = {}, afterCommitted: () -> Unit = {},
        read: (suspend (String) -> JsonFileStorage.EntityReadResult<ModelConfig>)? = null,
        refresh: (suspend () -> Unit)? = null,
        typedFactory: (DesktopAppContainer, DesktopFilePicker) -> DesktopTypedTransferController =
            { app, picker -> app.createTypedTransferController(picker) },
        block: suspend (Fixture) -> Unit) {
        val parent = Files.createTempDirectory("desktop-unified-import-")
        val root = Files.createDirectory(parent.resolve("profile"))
        val secrets = InMemoryDesktopSecretStore()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, parent.resolve("bootstrap.json")),
            secretStoreFactory = { secrets })
        val picker = FakePicker()
        val typed = typedFactory(app, picker)
        val models = app.modelRepository
        val unified = DesktopUnifiedImportController(typed, models, ModelTemplateTransferService(models, app.transferJson),
            picker, json = app.transferJson, readModelDurable = read ?: models::readDurableModel,
            afterModelPrepared = afterPrepared, afterModelCommitted = afterCommitted,
            refreshModels = refresh ?: models::refreshModelsFromStorage)
        try { block(Fixture(root, app, picker, typed, unified, app.transferJson, secrets)) }
        finally { app.close(); parent.toFile().deleteRecursively() }
    }

    private data class Fixture(val root: Path, val app: DesktopAppContainer, val picker: FakePicker,
        val typed: DesktopTypedTransferController, val unified: DesktopUnifiedImportController,
        val json: kotlinx.serialization.json.Json, val secrets: InMemoryDesktopSecretStore) {
        fun write(name: String, bytes: ByteArray): Path = Files.write(root.resolve(name), bytes)
        fun modelBytes() = json.encodeToString(ModelTemplatePackage.serializer(), ModelTemplatePackage(
            displayName = "Template", baseUrl = "https://example.test/v1", modelName = "model",
            isMultimodal = false, templateType = ModelTemplate.OPENAI, customParams = emptyMap())).toByteArray()
    }

    private class FakePicker : DesktopFilePicker {
        val opens = ArrayDeque<Path>()
        val saves = ArrayDeque<Path>()
        var lastOpenType: DesktopFileType? = null
        override fun pickOpenFile(type: DesktopFileType): Path? { lastOpenType = type; return opens.pollFirst() }
        override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = saves.pollFirst()
    }

    private class QueuedCallerDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.put(block) }
        fun next(): Runnable = assertNotNull(queue.poll(15, TimeUnit.SECONDS), "caller continuation was not queued")
    }

    private fun imageBytes(format: String): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        assertTrue(ImageIO.write(image, format, output))
        output.toByteArray()
    }
}
