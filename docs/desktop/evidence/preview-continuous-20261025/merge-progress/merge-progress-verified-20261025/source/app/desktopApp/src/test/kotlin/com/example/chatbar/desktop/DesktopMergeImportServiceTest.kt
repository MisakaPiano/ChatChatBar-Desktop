package com.example.chatbar.desktop

import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.WindowsSafeModelStorageKeyPolicy
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopMergeImportServiceTest {
    private val unavailable = DesktopApplicationHomeResult.Unavailable(DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT)
    private val fake = MergeRootSyntheticProtector
    private class Fixture(val base: Path, val root: Path, val source: DesktopDataRootResolution.Resolved,
        val gate: DesktopDataOperationCoordinator, val service: DesktopMergeImportService,
        val old: InMemoryDesktopSecretStore, val archive: Path, val original: Map<String, ByteArray>, val credentials: Path) {
        suspend fun inspect() = service.inspect(archive, null)
        fun choices(p: DesktopMergeImportService.Preview, choice: DesktopMergePreview.Choice = DesktopMergePreview.Choice.BOTH) =
            p.data.conflicts.associate { it.key to if (choice in it.choices) choice else DesktopMergePreview.Choice.TAKE_PHONE }
        fun keyChoices(p: DesktopMergeImportService.Preview) = p.credentialConflicts.associate { it.kind to DesktopMergeCredentials.Choice.KEEP_COMPUTER }
    }
    private suspend fun fixture(replace: ((Path, Path) -> Unit)? = null,
        quiesce: suspend (suspend () -> Unit) -> Unit = { it() }, block: suspend Fixture.() -> Unit) {
        val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/merge-service").toAbsolutePath()), "case-")
        val root = Files.createDirectory(base.resolve("pc")); val credentials = Files.createDirectory(base.resolve("credentials"))
        val bootstrap = base.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME)
        val source = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, bootstrap)
        val store = DesktopBootstrapSettingsStore(bootstrap)
        store.save((store.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(root))
        val pc = records("Computer", "https://pc.invalid/v1")
        pc.forEach { (relative, bytes) -> val path = root.resolve(relative); Files.createDirectories(path.parent); Files.write(path, bytes) }
        val gate = DesktopDataOperationCoordinator()
        val settings = DesktopSettingsStore(root, gate)
        settings.save((settings.load() as DesktopSettingsLoadResult.Missing).document, DesktopSettings())
        val archive = Files.write(base.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(
            prefs = """{"novelAiToken":"FAKE_PHONE_GLOBAL"}""", files = records("Phone", "https://phone.invalid/v1") +
                ("entities/model_configs/m.json" to CensusWireFixtures.model(extra = ",\"templateType\":\"OPENAI\"").replace("https://synthetic.invalid/v1", "https://phone.invalid/v1").toByteArray())))
        val old = InMemoryDesktopSecretStore().apply { save(DesktopCredentialKey.ModelApiKey("m"), "FAKE_PC_MODEL"); save(DesktopCredentialKey.NovelAiToken, "FAKE_PC_GLOBAL") }
        val service = DesktopMergeImportService(source, gate, old, {}, quiesce,
            DesktopMigrationDestinationPreparer({ unavailable }, DesktopDataRootOwnership::acquire),
            DesktopBootstrapAuthorityTransaction(storeFactory = { path -> if (replace == null) DesktopBootstrapSettingsStore(path)
                else DesktopBootstrapSettingsStore(path, { "synthetic" }, replace) },
                applicationHomeResolver = { unavailable }, portableRootResolver = DesktopPortableRootResolver()),
            freshAuthority = { DesktopFreshRootAuthority(it, credentials, fake) })
        val bytes = Files.readAllBytes(archive)
        val owner = (DesktopDataRootOwnership.acquire(source) as DesktopDataRootOwnershipResult.Acquired).ownership
        try { Fixture(base, root, source, gate, service, old, archive, pc, credentials).block() }
        finally {
            gate.closeAndDrain(); service.close(); owner.close()
            assertContentEquals(bytes, Files.readAllBytes(archive))
            assertEquals("FAKE_PC_MODEL", old.load(DesktopCredentialKey.ModelApiKey("m")))
            assertEquals("FAKE_PC_GLOBAL", old.load(DesktopCredentialKey.NovelAiToken))
        }
    }
    private fun records(label: String, endpoint: String): Map<String, ByteArray> = mapOf(
        "entities/character_cards/c.json" to """{"id":"c","name":"$label","createdAt":1,"updatedAt":1}""".toByteArray(),
        "entities/chat_sessions/s.json" to """{"id":"s","characterCardId":"c","title":"$label","displayTitleOverride":"$label","createdAt":1,"updatedAt":1}""".toByteArray(),
        "entities/chat_messages/s_m.json" to """{"id":"m","sessionId":"s","role":"USER","content":"$label content","createdAt":1,"updatedAt":1}""".toByteArray(),
        "entities/model_configs/m.json" to """{"id":"m","displayName":"$label","baseUrl":"$endpoint","apiKey":"","modelName":"synthetic","createdAt":1}""".toByteArray())

    @Test fun `actual container controller drains final writer refuses stale preview then publishes fresh selection`() = runBlocking<Unit> {
        fixture {
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType) = archive
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
            }
            val container = DesktopAppContainer(source, secretStoreFactory = { old }, bundledAssets = { error("NO_PRESET_ASSET_READ") },
                filePicker = picker, directoryPicker = DesktopDirectoryPicker { base },
                freshImportAuthority = { DesktopFreshRootAuthority(it, credentials, fake) })
            try {
                container.automaticBackupRuntime.initialize()
                val entered = CompletableDeferred<Unit>(); val saved = CompletableDeferred<Unit>()
                container.taskRuntime.launchNovelAi("synthetic-final-writer") {
                    try { entered.complete(Unit); CompletableDeferred<Unit>().await() }
                    finally { withContext(NonCancellable) {
                        container.dataOperationCoordinator.withNormalOperation {
                            val file = root.resolve("entities/chat_messages/s_m.json")
                            Files.writeString(file, Files.readString(file).replace("Computer content", "Newer computer content"))
                            saved.complete(Unit)
                        }
                    } }
                }
                withTimeout(5000) { entered.await() }
                val controller = container.mergeImportController
                suspend fun ready(): DesktopMergeImportState = withTimeout(10000) {
                    controller.state.first { !it.busy && (it.ready || it.error != null) }.also { assertNull(it.error); assertTrue(it.ready) }
                }
                suspend fun submit(state: DesktopMergeImportState) {
                    controller.commit(state.conflicts.associate { it.key to if (DesktopMergePreview.Choice.BOTH in it.choices) DesktopMergePreview.Choice.BOTH else DesktopMergePreview.Choice.TAKE_PHONE },
                        state.credentials.associate { it.kind to DesktopMergeCredentials.Choice.KEEP_COMPUTER }, false, true)
                }
                controller.inspect(null)
                val firstPreview = ready()
                val details = requireNotNull(firstPreview.modelDetails["model_configs:m"])
                assertTrue(details.computer.single().startsWith("OPENAI · "))
                assertTrue(details.computer.single().contains("https://pc.invalid/v1"))
                assertTrue(details.phone.single().contains("https://phone.invalid/v1"))
                assertFalse((details.computer + details.phone).any { "FAKE_PC_MODEL" in it || CensusWireFixtures.SECRET in it })
                submit(firstPreview)
                val refused = withTimeout(10000) { controller.state.first { !it.busy && it.error != null } }
                assertTrue(saved.isCompleted); assertFalse(refused.restartRequired)
                assertTrue(Files.readString(root.resolve("entities/chat_messages/s_m.json")).contains("Newer computer content"))
                controller.inspect(null); submit(ready())
                val completed = withTimeout(15000) { controller.state.first { !it.busy && (it.restartRequired || it.error != null) } }
                assertNull(completed.error); assertTrue(completed.restartRequired)
                val target = requireNotNull(completed.destination)
                assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(source.copy(appDataRoot = target)))
                assertFailsWith<DesktopTaskAdmissionException> { container.taskRuntime.launchNovelAi("late") { error("MUST_NOT_RUN") } }
                container.close()
                assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(source.copy(appDataRoot = target))).ownership.close()
            } finally { container.close() }
        }
    }

    @Test fun `cancelled file picker clears password without preparing source or touching keys`() = runBlocking<Unit> {
        fixture {
            val picker = object : DesktopFilePicker {
                override fun pickOpenFile(type: DesktopFileType): Path? = null
                override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? = null
            }
            val controller = DesktopMergeImportController(service, picker, DesktopDirectoryPicker { null })
            val password = "FAKE_CANCELLED_PASSWORD".toCharArray()
            try {
                controller.inspect(password)
                withTimeout(5000) { controller.state.first { !it.busy && it.message.isNotEmpty() } }
                assertTrue(password.all { it == '\u0000' }); assertFalse(controller.state.value.ready)
                assertFalse(Files.exists(root.resolve("backups/.merge-import-work")))
            } finally { controller.closeAndDrain() }
        }
    }

    @Test fun `actual service publishes both data branches and selected protected keys with old root retained`() = runBlocking<Unit> {
        fixture {
            val events = mutableListOf<DesktopMergeImportService.Progress>()
            val originalBootstrap = Files.readAllBytes(source.bootstrapPath)
            fun record(event: DesktopMergeImportService.Progress) {
                assertFalse(service.restartRequired)
                assertContentEquals(originalBootstrap, Files.readAllBytes(source.bootstrapPath))
                events += event
            }
            val p = service.inspectWithProgress(archive, null, ::record); val target = base.resolve("merged")
            assertEquals(DesktopMergeImportService.Publication.COMMITTED,
                service.commitWithProgress(p, target, choices(p), keyChoices(p), true, progress = ::record))
            assertEquals(DesktopMergeImportService.Stage.values().toList(), events.map { it.stage }.distinct())
            val verified = events.filter { it.stage == DesktopMergeImportService.Stage.PREPARING_ROOT && it.verifiedFiles > 0 }.map { it.verifiedFiles }
            assertTrue(verified.isNotEmpty()); assertEquals(1, verified.first())
            assertTrue(verified.zipWithNext().all { (a, b) -> b > a })
            assertTrue(service.restartRequired)
            assertEquals(target, (DesktopBootstrapSettingsStore(source.bootstrapPath).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(source.copy(appDataRoot = target)))
            val namespace = DesktopFreshRootAuthority(target, credentials, fake).verifyAndResolve()
            val newStore = WindowsSecretStore(namespace, fake)
            val models = ModelRepository(JsonFileStorage(target), modelStorageKeyPolicy = WindowsSafeModelStorageKeyPolicy).getAllModels()
            assertEquals(2, models.size)
            assertEquals("FAKE_PC_MODEL", newStore.load(DesktopCredentialKey.ModelApiKey(models.single { it.baseUrl == "https://pc.invalid/v1" }.id)))
            assertEquals(CensusWireFixtures.SECRET, newStore.load(DesktopCredentialKey.ModelApiKey(models.single { it.baseUrl == "https://phone.invalid/v1" }.id)))
            assertEquals("FAKE_PC_GLOBAL", newStore.load(DesktopCredentialKey.NovelAiToken))
            assertContentEquals(Files.readAllBytes(root.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME)), Files.readAllBytes(target.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME)))
            original.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve(path))) }
        }
    }

    @Test fun `damaged source reports authentication only and never reports preparation`() = runBlocking<Unit> {
        fixture {
            val damaged = Files.write(base.resolve("damaged.cbbackup"), byteArrayOf(1, 2, 3))
            val events = mutableListOf<DesktopMergeImportService.Progress>()
            assertFails { service.inspectWithProgress(damaged, null, events::add) }
            assertEquals(listOf(DesktopMergeImportService.Stage.AUTHENTICATING_SOURCE), events.map { it.stage })
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(damaged))
            assertFalse(service.restartRequired)
        }
    }

    @Test fun `cancel during verified file progress preserves old authority and leaves inert result`() = runBlocking<Unit> {
        fixture {
            val p = inspect(); val target = base.resolve("cancelled")
            val events = mutableListOf<DesktopMergeImportService.Progress>()
            assertFailsWith<CancellationException> {
                service.commitWithProgress(p, target, choices(p), keyChoices(p), true, progress = {
                    events += it
                    if (it.verifiedFiles > 0) throw CancellationException("SYNTHETIC_PROGRESS_CANCEL")
                })
            }
            assertTrue(events.any { it.verifiedFiles == 1 })
            assertFalse(events.any { it.stage == DesktopMergeImportService.Stage.PROTECTING_CREDENTIALS })
            assertFalse(service.restartRequired)
            assertEquals(root, (DesktopBootstrapSettingsStore(source.bootstrapPath).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertFails { DesktopFreshRootAuthority(target, credentials, fake).verifyAndResolve() }
            original.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve(path))) }
        }
    }

    @Test fun `progress presentation uses fixed stages and verified file count without completion claims`() {
        for (stage in DesktopMergeImportService.Stage.values()) {
            val progress = DesktopMergeImportService.Progress(stage, 128)
            assertTrue(progress.message().isNotBlank())
            assertFalse(progress.message().contains("已完成"))
            assertFalse(progress.message().contains("FAKE_"))
        }
        assertTrue(DesktopMergeImportService.Progress(DesktopMergeImportService.Stage.PREPARING_ROOT, 128).message().contains("128"))
        assertTrue(DesktopMergeImportService.Progress(DesktopMergeImportService.Stage.PREPARING_ROOT).message().contains("尚未提交"))
    }

    @Test fun `stale preview preserves newer computer data and cannot publish`() = runBlocking<Unit> {
        fixture {
            val p = inspect(); val file = root.resolve("entities/chat_messages/s_m.json")
            val updated = String(Files.readAllBytes(file)).replace("Computer content", "Newer computer content").toByteArray()
            Files.write(file, updated)
            val error = assertFailsWith<IllegalStateException> { service.commit(p, base.resolve("merged"), choices(p), keyChoices(p), true) }
            assertEquals("MERGE_COMPUTER_CHANGED_REINSPECT", error.message)
            assertContentEquals(updated, Files.readAllBytes(file)); assertFalse(service.restartRequired)
            assertEquals(root, (DesktopBootstrapSettingsStore(source.bootstrapPath).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
        }
    }

    @Test fun `bootstrap publication failure leaves original root selected and new data retained`() = runBlocking<Unit> {
        fixture(replace = { _, _ -> error("SYNTHETIC_PREPUBLICATION_FAILURE") }) {
            val p = inspect(); val target = base.resolve("merged")
            assertFailsWith<IllegalStateException> { service.commit(p, target, choices(p), keyChoices(p), true) }
            assertFalse(service.restartRequired)
            assertTrue(Files.isRegularFile(target.resolve("entities/character_cards/c.json")))
            assertEquals(root, (DesktopBootstrapSettingsStore(source.bootstrapPath).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(source.copy(appDataRoot = target))).ownership.close()
        }
    }

    @Test fun `ambiguous bootstrap publication seals old writers and retains destination ownership`() = runBlocking<Unit> {
        fixture(replace = { _, target -> Files.writeString(target, "SYNTHETIC_CORRUPT_POINTER"); error("SYNTHETIC_POSTPUBLICATION_FAILURE") }) {
            val p = inspect(); val target = base.resolve("merged")
            assertEquals(DesktopMergeImportService.Publication.INDETERMINATE, service.commit(p, target, choices(p), keyChoices(p), true))
            assertTrue(service.restartRequired)
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(source.copy(appDataRoot = target)))
            assertFailsWith<DesktopDataOperationRejectedException> { gate.withNormalOperation { error("MUST_NOT_RUN") } }
        }
    }

    @Test fun `cancel before final freeze keeps bootstrap original and releases new root lock`() = runBlocking<Unit> {
        fixture(quiesce = { throw CancellationException("SYNTHETIC_CANCEL") }) {
            val p = inspect(); val target = base.resolve("merged")
            assertFailsWith<CancellationException> { service.commit(p, target, choices(p), keyChoices(p), true) }
            assertFalse(service.restartRequired)
            assertEquals(root, (DesktopBootstrapSettingsStore(source.bootstrapPath).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(source.copy(appDataRoot = target))).ownership.close()
        }
    }
}
