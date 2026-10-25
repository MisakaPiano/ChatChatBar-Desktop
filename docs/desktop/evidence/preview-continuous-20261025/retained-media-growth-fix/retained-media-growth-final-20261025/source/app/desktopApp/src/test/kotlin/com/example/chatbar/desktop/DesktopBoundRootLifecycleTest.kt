package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.root.AppDataRootInfrastructure
import com.example.chatbar.desktop.backup.MergeRootSyntheticProtector
import com.example.chatbar.desktop.security.*
import java.awt.image.BufferedImage
import java.nio.file.*
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

/** Real directory identities and invented values; native protection is explicit opt-in, always task-owned. */
class DesktopBoundRootLifecycleTest {
    private data class Fixture(val source: Path, val credentials: Path, val target: Path, val bootstrap: Path,
        val resolution: DesktopDataRootResolution.Resolved, val sourceNamespace: Path, val store: WindowsSecretStore,
        val container: DesktopAppContainer, val fake: SecretProtector)

    @Test fun `bound root relocation preserves protected consumers and rebases owned media`() = runBlocking<Unit> {
        fixture { assertSuccessfulMove() }
    }

    @Test fun `native DPAPI protected relocation uses only invented values and owned roots`() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows") && System.getenv("CCB_PREVIEW_NATIVE_DPAPI") == "1")
        fixture(WindowsDpapiSecretProtector()) { assertSuccessfulMove() }
    }

    private suspend fun Fixture.assertSuccessfulMove() {
            val before = fingerprint(source)
            val result = container.dataRootMigrationService.migrate(target)
            if (result is DesktopDataRootMigrationResult.Failure && result.cause != null) {
                throw IllegalStateException("SYNTHETIC_BOUND_RELOCATION_${result.kind}", result.cause)
            }
            assertFalse(result is DesktopDataRootMigrationResult.Failure, "Protected-root relocation must have a controlled successful route: $result")
            val completed = assertIs<DesktopDataRootMigrationResult.ProtectedSuccess>(result)
            assertEquals(DesktopMaintenanceResumeResult.RestartRequired, completed.runtimeDisposition)
            assertTrue(completed.preMigrationSnapshot.validation.valid)
            assertTrue(Files.isDirectory(completed.preMigrationSnapshot.directory))
            assertTrue(com.example.chatbar.data.snapshot.AppDataSnapshotService(target).listSnapshots().isEmpty())
            assertTrue(container.dataOperationCoordinator.isRestartRequired)
            assertEquals(target, (DesktopBootstrapSettingsStore(bootstrap).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertEquals(before, fingerprint(source))
            fun media(proof: Map<String, String>) = proof.filterKeys {
                it.startsWith("images/") || it.startsWith("audio/")
            }.values.sorted()
            assertEquals(media(before), media(fingerprint(target)), "A directory move must retain media without creating duplicate copies")
            assertEquals("FAKE_BOUND_MODEL_VALUE", store.load(DesktopCredentialKey.ModelApiKey("model")))
            assertEquals("FAKE_BOUND_NAI_VALUE", store.load(DesktopCredentialKey.NovelAiToken))
            val destinationNamespace = DesktopFreshRootAuthority(target, credentials, fake).verifyAndResolve()
            assertNotEquals(sourceNamespace, destinationNamespace)
            val moved = WindowsSecretStore(destinationNamespace, fake)
            assertEquals("FAKE_BOUND_MODEL_VALUE", moved.load(DesktopCredentialKey.ModelApiKey("model")))
            assertEquals("FAKE_BOUND_NAI_VALUE", moved.load(DesktopCredentialKey.NovelAiToken))
            DesktopFullBackupGraphValidation.validate(target)
            val voices = com.example.chatbar.data.repository.VoiceMessageRepository(JsonFileStorage(target), strictReads = true).listForSession("s")
            assertContentEquals(fishPreviewMp3(), Files.readAllBytes(DesktopVoiceAudioStore(target).verify(voices.single().audioPath)))
            assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(DesktopDataRootOwnership.acquire(resolution.copy(appDataRoot = target)))
    }

    private class ControlledProtector : SecretProtector {
        var failNaiProtection = false
        var onModelRead: (() -> Unit)? = null
        override fun protect(plaintext: ByteArray): ByteArray {
            if (failNaiProtection && plaintext.toString(Charsets.UTF_8).contains("FAKE_BOUND_NAI_VALUE")) {
                error("SYNTHETIC_KEY_PROTECTION_FAILURE")
            }
            return MergeRootSyntheticProtector.protect(plaintext)
        }
        override fun unprotect(protected: ByteArray): ByteArray = MergeRootSyntheticProtector.unprotect(protected).also {
            if (it.toString(Charsets.UTF_8).contains("FAKE_BOUND_MODEL_VALUE")) onModelRead?.invoke()
        }
    }

    @Test fun `external source edit after capture refuses publication and keeps newer source and keys`() = runBlocking<Unit> {
        val protector = ControlledProtector()
        fixture(protector) {
            var changed = false
            val message = source.resolve("entities/chat_messages/s_m.json")
            protector.onModelRead = {
                if (!changed) {
                    changed = true
                    Files.writeString(message, Files.readString(message).replace("Synthetic original source text", "Synthetic external newer text"))
                }
            }
            val result = assertIs<DesktopDataRootMigrationResult.Failure>(container.dataRootMigrationService.migrate(target))
            assertTrue(changed)
            assertFalse(result.restartRequired)
            assertEquals(DesktopMaintenanceResumeResult.Resumed, result.runtimeDisposition)
            assertTrue(Files.readString(message).contains("Synthetic external newer text"))
            assertEquals(source, (DesktopBootstrapSettingsStore(bootstrap).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertFalse(Files.exists(target.resolve(DesktopFreshRootAuthority.FENCE)))
            assertEquals("FAKE_BOUND_MODEL_VALUE", store.load(DesktopCredentialKey.ModelApiKey("model")))
            assertEquals("FAKE_BOUND_NAI_VALUE", store.load(DesktopCredentialKey.NovelAiToken))
            container.taskRuntime.withMessageMutation("s") { }
        }
    }

    @Test fun `protected target failure leaves inert root original keys and working admission`() = runBlocking<Unit> {
        val protector = ControlledProtector()
        fixture(protector) {
            val before = fingerprint(source)
            protector.failNaiProtection = true
            val result = assertIs<DesktopDataRootMigrationResult.Failure>(container.dataRootMigrationService.migrate(target))
            assertFalse(result.restartRequired)
            assertEquals(DesktopMaintenanceResumeResult.Resumed, result.runtimeDisposition)
            assertEquals(before, fingerprint(source))
            assertEquals(source, (DesktopBootstrapSettingsStore(bootstrap).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertEquals("FAKE_BOUND_MODEL_VALUE", store.load(DesktopCredentialKey.ModelApiKey("model")))
            assertEquals("FAKE_BOUND_NAI_VALUE", store.load(DesktopCredentialKey.NovelAiToken))
            assertTrue(Files.exists(target.resolve(DesktopFreshRootAuthority.FENCE).resolve("intent.json")))
            assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution.copy(appDataRoot = target))).ownership.use {
                val refused = assertFails { DesktopFreshRootAuthority(target, credentials, protector).verifyAndResolve() }
                assertEquals("FRESH_IMPORT_INCOMPLETE", refused.message)
            }
            container.taskRuntime.withMessageMutation("s") { }
        }
    }

    @Test fun `cancellation after captured key read keeps old selection keys and admission`() = runBlocking<Unit> {
        val protector = ControlledProtector()
        fixture(protector) {
            val before = fingerprint(source)
            lateinit var operation: Deferred<DesktopDataRootMigrationResult>
            var cancelled = false
            protector.onModelRead = { if (!cancelled) { cancelled = true; operation.cancel() } }
            operation = async(Dispatchers.IO, start = CoroutineStart.LAZY) { container.dataRootMigrationService.migrate(target) }
            operation.start()
            assertFailsWith<CancellationException> { operation.await() }
            assertTrue(cancelled)
            assertFalse(container.dataOperationCoordinator.isRestartRequired)
            assertEquals(before, fingerprint(source))
            assertEquals(source, (DesktopBootstrapSettingsStore(bootstrap).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertEquals("FAKE_BOUND_MODEL_VALUE", store.load(DesktopCredentialKey.ModelApiKey("model")))
            assertEquals("FAKE_BOUND_NAI_VALUE", store.load(DesktopCredentialKey.NovelAiToken))
            container.taskRuntime.withMessageMutation("s") { }
        }
    }

    @Test fun `accepted task final write is drained before relocation and further mutation stays stopped`() = runBlocking<Unit> {
        fixture {
            val entered = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            container.taskRuntime.launchNovelAi("synthetic-relocation-final-writer") {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) {
                    container.chatRepository.addMessage(ChatMessage("late", "s", MessageRole.USER, "Synthetic drained final write", createdAt = 2, updatedAt = 2))
                    completed.complete(Unit)
                } }
            }
            withTimeout(5000) { entered.await() }
            assertIs<DesktopDataRootMigrationResult.ProtectedSuccess>(container.dataRootMigrationService.migrate(target))
            assertTrue(completed.isCompleted)
            assertFailsWith<DesktopDataOperationRejectedException> { container.chatRepository.getMessagesReadOnly("s") }
            val retained = com.example.chatbar.data.repository.ChatRepository(JsonFileStorage(source)).getMessagesReadOnly("s")
            assertEquals("Synthetic drained final write", retained.single { it.id == "late" }.content)
            val copied = com.example.chatbar.data.repository.ChatRepository(JsonFileStorage(target)).getMessagesReadOnly("s")
            assertEquals("Synthetic drained final write", copied.single { it.id == "late" }.content)
            var mutationRan = false
            assertFails { container.taskRuntime.withMessageMutation("s") { mutationRan = true } }
            assertFalse(mutationRan)
        }
    }

    @Test fun `a preexisting global restart seal rejects relocation before creating destination`() = runBlocking<Unit> {
        fixture {
            container.dataOperationCoordinator.withExclusiveMaintenance { requireRestart() }
            val result = assertIs<DesktopDataRootMigrationResult.Failure>(container.dataRootMigrationService.migrate(target))
            assertEquals(DesktopDataRootMigrationFailureKind.RESTART_REQUIRED, result.kind)
            assertTrue(result.restartRequired)
            assertNull(result.sourceStillAuthoritative)
            assertFalse(Files.exists(target))
        }
    }

    @Test fun `legacy unbound root with owned media moves recordings off the original directory`() = runBlocking<Unit> {
        fixture(bound = false) {
            val before = fingerprint(source)
            val result = container.dataRootMigrationService.migrate(target)
            assertFalse(result is DesktopDataRootMigrationResult.Failure)
            val voices = com.example.chatbar.data.repository.VoiceMessageRepository(JsonFileStorage(target), strictReads = true).listForSession("s")
            // Merely copying audio bytes is insufficient when the persisted owned reference is absolute.
            val movedAudio = DesktopVoiceAudioStore(target).verify(voices.single().audioPath)
            assertTrue(movedAudio.startsWith(target))
            assertContentEquals(fishPreviewMp3(), Files.readAllBytes(movedAudio))
            assertIs<DesktopDataRootMigrationResult.ProtectedSuccess>(result)
            val moved = WindowsSecretStore(DesktopFreshRootAuthority(target, credentials, fake).verifyAndResolve(), fake)
            assertEquals("FAKE_BOUND_MODEL_VALUE", moved.load(DesktopCredentialKey.ModelApiKey("model")))
            assertEquals("FAKE_BOUND_NAI_VALUE", moved.load(DesktopCredentialKey.NovelAiToken))
            assertEquals(before, fingerprint(source))
            assertEquals("FAKE_BOUND_MODEL_VALUE", store.load(DesktopCredentialKey.ModelApiKey("model")))
        }
    }

    private suspend fun fixture(fake: SecretProtector = MergeRootSyntheticProtector, bound: Boolean = true, block: suspend Fixture.() -> Unit) {
        val parent = Files.createDirectories(Path.of("build/preview-synthetic/bound-lifecycle").toAbsolutePath())
        val base = Files.createTempDirectory(parent, "case-")
        val source = Files.createDirectory(base.resolve("source"))
        val credentials = Files.createDirectory(base.resolve("credentials"))
        val target = base.resolve("destination")
        val bootstrap = base.resolve("bootstrap.json")
        val resolution = DesktopDataRootResolution.Resolved(source, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, bootstrap)
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        val authority = DesktopFreshRootAuthority(source, credentials, fake)
        var container: DesktopAppContainer? = null
        try {
            val pending = if (bound) authority.begin() else null
            val legacy = if (bound) null else Files.createDirectory(credentials.resolve("legacy"))
            val initialStore = pending?.store ?: WindowsSecretStore(requireNotNull(legacy), fake)
            initialStore.save(DesktopCredentialKey.ModelApiKey("model"), "FAKE_BOUND_MODEL_VALUE")
            initialStore.save(DesktopCredentialKey.NovelAiToken, "FAKE_BOUND_NAI_VALUE")
            val storage = JsonFileStorage(source)
            storage.saveEntity("model_configs", "model", ModelConfig("model", "Synthetic", "https://synthetic.invalid/v1", "", "synthetic", createdAt = 1), ModelConfig.serializer())
            storage.saveEntity("character_cards", "c", CharacterCard("c", "Synthetic", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            storage.saveEntity("chat_sessions", "s", ChatSession("s", "c", "Synthetic", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1), ChatSession.serializer())
            val image = source.resolve("images/synthetic.png"); Files.createDirectories(image.parent)
            val pixels = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
            try { check(javax.imageio.ImageIO.write(pixels, "png", image.toFile())) } finally { pixels.flush() }
            val message = ChatMessage("m", "s", MessageRole.ASSISTANT, "Synthetic original source text", images = listOf(image.toString()),
                currentAlternativeVersionId = "v", sourceTurnId = "t", sourceTurnOrder = 1, createdAt = 1, updatedAt = 1)
            storage.saveEntity("chat_messages", "s_m", message, ChatMessage.serializer())
            val audio = DesktopVoiceAudioStore(source).persist("s", "voice", fishPreviewMp3().inputStream())
            storage.saveEntity("generated_voice_messages", "voice", GeneratedVoiceMessage("voice", "s", "m", "v",
                anchorId = null, sourceOrder = 1, sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "Synthetic",
                sourceText = message.content, taggedText = message.content, characterId = "c", characterName = "Synthetic",
                voice = FishAudioVoiceBinding("fake-reference", "Synthetic"), fishModelId = "synthetic", audioPath = audio.path,
                durationMs = audio.durationMs, byteLength = audio.byteLength, createdAt = 1, updatedAt = 1), GeneratedVoiceMessage.serializer())
            val files = fingerprint(source)
            val sourceNamespace = if (pending != null) {
                authority.complete(pending, files)
                authority.verifyAndResolve()
            } else requireNotNull(legacy)
            val store = WindowsSecretStore(sourceNamespace, fake)
            val settings = DesktopBootstrapSettingsStore(bootstrap)
            settings.save((settings.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(source))
            container = DesktopAppContainer(resolution, secretStoreFactory = { root ->
                if (root == source && !bound) store else WindowsSecretStore(DesktopFreshRootAuthority(root, credentials, fake).verifyAndResolve(), fake)
            }, bundledAssets = { error("NO_PRESET_READ") }, freshImportAuthority = { DesktopFreshRootAuthority(it, credentials, fake) })
            container.automaticBackupRuntime.initialize()
            DesktopFullBackupGraphValidation.validate(source)
            Fixture(source, credentials, target, bootstrap, resolution, sourceNamespace, store, requireNotNull(container), fake).block()
        } finally {
            container?.close(); owner.close()
            check(base.parent == parent && base.startsWith(parent))
            Files.walk(base).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    private suspend fun fingerprint(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
            !AppDataRootInfrastructure.isReservedPath(root.relativize(it)) }.toList().associate {
            root.relativize(it).toString().replace('\\', '/') to DesktopArchiveFiles.hash(it, 256L shl 20)
        }
    }
}
