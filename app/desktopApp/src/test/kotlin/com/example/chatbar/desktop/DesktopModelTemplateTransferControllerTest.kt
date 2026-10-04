package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.DesktopModelCredentialPersistencePolicy
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.ModelTemplatePackage
import com.example.chatbar.domain.card.ModelTemplateTransferService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*

class DesktopModelTemplateTransferControllerTest {
    @Test fun `Models picker uses explicit JSON filter and imports one exact target`() = runBlocking {
        fixture { f ->
            f.picker.opens.add(f.write("template.json", f.modelBytes()))
            f.controller.chooseModelTemplate()
            assertEquals(DesktopTypedTransferController.JSON_FILES, f.picker.lastOpenType)
            val imported = f.app.modelRepository.getAllModels().single()
            assertEquals(imported.id, f.controller.state.value.pendingTargetId)
            assertTrue(imported.displayName.endsWith(" (Imported Template)"))
        }
    }

    @Test fun `invalid ModelTemplate JSON cannot create an entity`() = runBlocking {
        fixture { f ->
            f.controller.importModelFile(f.write("foreign.json", """{"name":"not a template"}""".toByteArray()))
            assertNotNull(f.controller.state.value.error)
            assertTrue(f.app.modelRepository.getAllModels().isEmpty())
            val invalidSchema = f.app.transferJson.decodeFromString(ModelTemplatePackage.serializer(),
                f.modelBytes().toString(Charsets.UTF_8)).copy(schemaVersion = 2)
            f.controller.importModelFile(f.write("schema.json",
                f.app.transferJson.encodeToString(ModelTemplatePackage.serializer(), invalidSchema).toByteArray()))
            assertNotNull(f.controller.state.value.error)
            assertTrue(f.app.modelRepository.getAllModels().isEmpty())
        }
    }

    @Test fun `production credential policy and Windows physical key preserve import fields and default`() = runBlocking {
        fixture { f ->
            f.app.settingsRepository.saveAppSettings(AppSettings(defaultModelId = "existing-default"))
            val beforeSettings = f.app.settingsRepository.readExistingAppSettings()
            val path = f.write("template.json", f.modelBytes())
            f.controller.importModelFile(path)
            f.controller.importModelFile(path)
            val models = f.app.modelRepository.getAllModels()
            assertEquals(2, models.size)
            assertNotEquals(models[0].id, models[1].id)
            models.forEach { model ->
                assertEquals("", model.apiKey)
                assertNull(model.visionModelId)
                assertEquals("high", model.reasoningEffort)
                assertEquals(true, model.enableThinking)
                assertEquals(2048, model.maxOutputTokens)
                assertEquals(FormatPromptPosition.END, model.formatPromptPosition)
                assertEquals(ParamValue.NumberValue(0.5), model.customParams["temperature"])
                assertEquals("", assertIs<JsonFileStorage.EntityReadResult.Valid<ModelConfig>>(
                    f.app.modelRepository.readDurableModel(model.id)).value.apiKey)
                assertNull(f.secrets.values[DesktopCredentialKey.ModelApiKey(model.id)])
                val physical = f.root.resolve("entities/model_configs")
                    .resolve("${WindowsSafeModelStorageKeyPolicy.storageKey(model.id)}.json")
                assertTrue(Files.exists(physical))
                assertFalse(Files.readString(physical).contains("FAKE-SECRET"))
            }
            assertEquals(beforeSettings, f.app.settingsRepository.readExistingAppSettings())
            assertEquals("existing-default", f.app.settingsRepository.readExistingAppSettings()?.defaultModelId)
        }
    }

    @Test fun `export of a credential-bearing user model omits API key`() = runBlocking {
        fixture { f ->
            f.app.modelRepository.saveModel(ModelConfig(id = "custom", displayName = "Custom",
                baseUrl = "https://example.test/v1", apiKey = "FAKE-SECRET", modelName = "model",
                createdAt = 1L))
            val destination = f.root.resolve("export.json")
            f.picker.saves.add(destination)
            f.controller.exportModel("custom", "Custom")
            val raw = Files.readString(destination)
            assertFalse(raw.contains("FAKE-SECRET"))
            assertFalse(raw.contains("apiKey"))
            assertEquals("Custom", f.app.transferJson.decodeFromString(ModelTemplatePackage.serializer(), raw).displayName)
        }
    }

    @Test fun `prepared failure remains precommit and an explicit new action may retry`() = runBlocking {
        var fail = true
        fixture(afterPrepared = { if (fail) error("before save") }) { f ->
            val path = f.write("template.json", f.modelBytes())
            f.controller.importModelFile(path)
            assertNull(f.controller.state.value.unresolved)
            assertNull(f.controller.state.value.pendingTargetId)
            assertTrue(f.app.modelRepository.getAllModels().isEmpty())
            fail = false
            f.controller.importModelFile(path)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
        }
    }

    @Test fun `real entity save rejection remains precommit and retryable`() = runBlocking {
        fixture { f ->
            val gate = object : AppDataOperationGate {
                var failNext = false
                override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
                    if (failNext) { failNext = false; throw IOException("entity save denied") }
                    return operation()
                }
            }
            val models = ModelRepository(JsonFileStorage(f.root, gate), WindowsSafeModelStorageKeyPolicy,
                DesktopModelCredentialPersistencePolicy(f.secrets))
            models.initialize()
            val controller = DesktopModelTemplateTransferController(models,
                ModelTemplateTransferService(models, f.app.transferJson), f.picker)
            val path = f.write("template.json", f.modelBytes())
            gate.failNext = true
            controller.importModelFile(path)
            assertNull(controller.state.value.unresolved)
            assertTrue(models.getAllModels().isEmpty())
            controller.importModelFile(path)
            assertEquals(1, models.getAllModels().size)
        }
    }

    @Test fun `SecretStore load failure is explicit precommit evidence`() = runBlocking {
        fixture { f ->
            val path = f.write("template.json", f.modelBytes())
            f.secrets.failNextLoad()
            f.controller.importModelFile(path)
            assertNull(f.controller.state.value.unresolved)
            assertNotNull(f.controller.state.value.error)
            f.secrets.loadFailure = null
            f.controller.importModelFile(path)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
        }
    }

    @Test fun `postcommit callback cancellation propagates the original object`() = runBlocking {
        val original = CancellationException("after durable model save")
        fixture(afterCommitted = { throw original }) { f ->
            val caught = runCatching { f.controller.importModelFile(f.write("template.json", f.modelBytes())) }
                .exceptionOrNull()
            assertSame(original, caught)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
            assertNotNull(f.controller.state.value.pendingTargetId)
            assertFalse(f.controller.state.value.busy)
        }
    }

    @Test fun `postcommit refresh cancellation keeps durable model and exact pending target`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(refresh = { entered.complete(Unit); release.await() }) { f ->
            val cancelled = CancellationException("cancel model refresh")
            var caught: Throwable? = null
            val job = launch { try { f.controller.importModelFile(f.write("template.json", f.modelBytes())) }
                catch (error: Throwable) { caught = error } }
            entered.await()
            assertEquals(1, f.app.modelRepository.getAllModels().size)
            job.cancel(cancelled)
            release.complete(Unit)
            job.join()
            assertEquals(cancelled.message, assertIs<CancellationException>(caught).message)
            assertNotNull(f.controller.state.value.pendingTargetId)
            assertFalse(f.controller.state.value.status.orEmpty().contains("刷新失败"))
            assertFalse(f.controller.state.value.busy)
        }
    }

    @Test fun `real dispatcher return cancellation cannot invite a second import`() = runBlocking {
        fixture { f ->
            val path = f.write("template.json", f.modelBytes())
            val dispatcher = QueuedCallerDispatcher()
            val cancellation = CancellationException("cancel queued model return")
            var caught: Throwable? = null
            val job = CoroutineScope(currentCoroutineContext()).launch(dispatcher) {
                try { f.controller.importModelFile(path) } catch (error: Throwable) { caught = error }
            }
            dispatcher.next().run()
            var returnStep = dispatcher.next()
            while (f.app.modelRepository.getAllModels().isEmpty()) {
                returnStep.run()
                returnStep = dispatcher.next()
            }
            job.cancel(cancellation)
            returnStep.run()
            while (!job.isCompleted) dispatcher.next().run()
            assertEquals(cancellation.message, assertIs<CancellationException>(caught).message)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
            assertNotNull(f.controller.state.value.pendingTargetId)
            assertFalse(f.controller.state.value.busy)
        }
    }

    @Test fun `indeterminate blocks duplicate until strict Missing proves precommit`() = runBlocking {
        var strict: JsonFileStorage.EntityReadResult<ModelConfig> =
            JsonFileStorage.EntityReadResult.ReadError(IOException("locked"))
        var failPrepared = true
        var prepared = 0
        fixture(afterPrepared = { prepared++; if (failPrepared) error("before save") }, read = { strict }) { f ->
            val path = f.write("template.json", f.modelBytes())
            f.controller.importModelFile(path)
            val target = assertNotNull(f.controller.state.value.unresolved).targetId
            f.controller.importModelFile(path)
            assertEquals(1, prepared)
            f.controller.recheckUnresolvedImport()
            assertEquals(target, f.controller.state.value.unresolved?.targetId)
            strict = JsonFileStorage.EntityReadResult.Missing
            f.controller.recheckUnresolvedImport()
            assertNull(f.controller.state.value.unresolved)
            assertNull(f.controller.state.value.pendingTargetId)
            assertTrue(f.app.modelRepository.getAllModels().isEmpty())
            failPrepared = false
            f.controller.importModelFile(path)
            assertEquals(1, f.app.modelRepository.getAllModels().size)
        }
    }

    @Test fun `production strict physical read confirms delayed commit with zero rewrite`() = runBlocking {
        fixture { f ->
            var denied = true
            val models = f.app.modelRepository
            val controller = DesktopModelTemplateTransferController(models,
                ModelTemplateTransferService(models, f.app.transferJson), f.picker,
                afterModelPrepared = { error("return before save") },
                readModelDurable = { id -> if (denied)
                    JsonFileStorage.EntityReadResult.ReadError(IOException("temporarily denied"))
                    else models.readDurableModel(id) })
            controller.importModelFile(f.write("template.json", f.modelBytes()))
            val expected = assertNotNull(controller.state.value.unresolved).expected
            models.saveModel(expected)
            val physical = f.root.resolve("entities/model_configs")
                .resolve("${WindowsSafeModelStorageKeyPolicy.storageKey(expected.id)}.json")
            val before = Files.readAllBytes(physical)
            val modified = Files.getLastModifiedTime(physical)
            denied = false
            controller.recheckUnresolvedImport()
            assertEquals(expected.id, controller.state.value.pendingTargetId)
            assertNull(controller.state.value.unresolved)
            assertEquals(expected, assertIs<JsonFileStorage.EntityReadResult.Valid<ModelConfig>>(
                models.readDurableModel(expected.id)).value)
            assertContentEquals(before, Files.readAllBytes(physical))
            assertEquals(modified, Files.getLastModifiedTime(physical))
            assertEquals(1, models.getAllModels().size)
        }
    }

    @Test fun `corrupt read error and mismatched target remain unresolved`() = runBlocking {
        var strict: JsonFileStorage.EntityReadResult<ModelConfig> =
            JsonFileStorage.EntityReadResult.ReadError(IOException("locked"))
        fixture(afterPrepared = { error("before save") }, read = { strict }) { f ->
            f.controller.importModelFile(f.write("template.json", f.modelBytes()))
            val expected = assertNotNull(f.controller.state.value.unresolved).expected
            for (result in listOf<JsonFileStorage.EntityReadResult<ModelConfig>>(
                JsonFileStorage.EntityReadResult.Corrupt(IllegalArgumentException("bad JSON")),
                JsonFileStorage.EntityReadResult.ReadError(IOException("locked")),
                JsonFileStorage.EntityReadResult.Valid(expected.copy(modelName = "other")))) {
                strict = result
                f.controller.recheckUnresolvedImport()
                assertEquals(expected.id, f.controller.state.value.unresolved?.targetId)
                assertNull(f.controller.state.value.pendingTargetId)
            }
        }
    }

    @Test fun `cancelling strict recheck retains evidence and releases busy`() = runBlocking {
        val original = CancellationException("verification cancelled")
        var cancelRead = false
        fixture(afterPrepared = { error("before save") }, read = {
            if (cancelRead) throw original
            JsonFileStorage.EntityReadResult.ReadError(IOException("locked"))
        }) { f ->
            f.controller.importModelFile(f.write("template.json", f.modelBytes()))
            val target = assertNotNull(f.controller.state.value.unresolved).targetId
            cancelRead = true
            assertSame(original, runCatching { f.controller.recheckUnresolvedImport() }.exceptionOrNull())
            assertEquals(target, f.controller.state.value.unresolved?.targetId)
            assertFalse(f.controller.state.value.busy)
        }
    }

    @Test fun `model delivery cancellation retains target and retry opens exact model once`() = runBlocking {
        fixture { f ->
            f.controller.importModelFile(f.write("template.json", f.modelBytes()))
            val target = assertNotNull(f.controller.state.value.pendingTargetId)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var opens = 0
            val job = launch { f.controller.deliverPendingTarget { id ->
                assertEquals(target, id)
                entered.complete(Unit)
                release.await()
                opens++
                true
            } }
            entered.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertEquals(target, f.controller.state.value.pendingTargetId)
            f.controller.deliverPendingTarget { false }
            assertNotNull(f.controller.state.value.deliveryError)
            f.controller.retryDelivery()
            f.controller.deliverPendingTarget { id -> assertEquals(target, id); opens++; true }
            assertEquals(1, opens)
            assertNull(f.controller.state.value.pendingTargetId)
        }
    }

    private suspend fun fixture(
        afterPrepared: (ModelConfig) -> Unit = {},
        afterCommitted: () -> Unit = {},
        read: (suspend (String) -> JsonFileStorage.EntityReadResult<ModelConfig>)? = null,
        refresh: (suspend () -> Unit)? = null,
        block: suspend (Fixture) -> Unit,
    ) {
        val parent = Files.createTempDirectory("desktop-model-template-")
        val root = Files.createDirectory(parent.resolve("profile"))
        val secrets = InMemoryDesktopSecretStore()
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, parent.resolve("bootstrap.json")),
            secretStoreFactory = { secrets })
        val picker = FakePicker()
        val models = app.modelRepository
        val controller = DesktopModelTemplateTransferController(models,
            ModelTemplateTransferService(models, app.transferJson), picker,
            readModelDurable = read ?: models::readDurableModel,
            afterModelPrepared = afterPrepared, afterModelCommitted = afterCommitted,
            refreshModels = refresh ?: models::refreshModelsFromStorage)
        try { block(Fixture(root, app, picker, controller, secrets)) }
        finally { app.close(); parent.toFile().deleteRecursively() }
    }

    private data class Fixture(val root: Path, val app: DesktopAppContainer, val picker: FakePicker,
        val controller: DesktopModelTemplateTransferController, val secrets: InMemoryDesktopSecretStore) {
        fun write(name: String, bytes: ByteArray): Path = Files.write(root.resolve(name), bytes)
        fun modelBytes(): ByteArray = app.transferJson.encodeToString(ModelTemplatePackage.serializer(),
            ModelTemplatePackage(displayName = "Template", baseUrl = "https://example.test/v1",
                modelName = "model", isMultimodal = false, templateType = ModelTemplate.OPENAI,
                customParams = mapOf("temperature" to ParamValue.NumberValue(0.5)),
                reasoningEffort = "high", enableThinking = true, maxOutputTokens = 2048,
                formatPromptPosition = FormatPromptPosition.END)).toByteArray()
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
}
