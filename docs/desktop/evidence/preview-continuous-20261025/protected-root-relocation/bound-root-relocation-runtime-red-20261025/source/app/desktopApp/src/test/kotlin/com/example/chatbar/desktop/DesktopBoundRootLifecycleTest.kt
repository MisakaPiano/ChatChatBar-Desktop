package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.root.AppDataRootInfrastructure
import com.example.chatbar.desktop.backup.MergeRootSyntheticProtector
import com.example.chatbar.desktop.security.*
import java.awt.image.BufferedImage
import java.nio.file.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/** Real directory identities, fake protection and invented values; never an ordinary credential root. */
class DesktopBoundRootLifecycleTest {
    @Test fun `bound root relocation preserves protected consumers and rebases owned media`() = runBlocking<Unit> {
        val parent = Files.createDirectories(Path.of("build/preview-synthetic/bound-lifecycle").toAbsolutePath())
        val base = Files.createTempDirectory(parent, "case-")
        val source = Files.createDirectory(base.resolve("source"))
        val credentials = Files.createDirectory(base.resolve("credentials"))
        val target = base.resolve("destination")
        val bootstrap = base.resolve("bootstrap.json")
        val resolution = DesktopDataRootResolution.Resolved(source, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, bootstrap)
        val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolution)).ownership
        val fake = MergeRootSyntheticProtector
        val authority = DesktopFreshRootAuthority(source, credentials, fake)
        var container: DesktopAppContainer? = null
        try {
            val pending = authority.begin()
            pending.store.save(DesktopCredentialKey.ModelApiKey("model"), "FAKE_BOUND_MODEL_VALUE")
            pending.store.save(DesktopCredentialKey.NovelAiToken, "FAKE_BOUND_NAI_VALUE")
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
            authority.complete(pending, files)
            val sourceNamespace = authority.verifyAndResolve()
            val store = WindowsSecretStore(sourceNamespace, fake)
            val settings = DesktopBootstrapSettingsStore(bootstrap)
            settings.save((settings.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(source))
            container = DesktopAppContainer(resolution, secretStoreFactory = { root ->
                WindowsSecretStore(DesktopFreshRootAuthority(root, credentials, fake).verifyAndResolve(), fake)
            }, bundledAssets = { error("NO_PRESET_READ") }, freshImportAuthority = { DesktopFreshRootAuthority(it, credentials, fake) })
            container.initializePersistentState()
            container.automaticBackupRuntime.initialize()
            DesktopFullBackupGraphValidation.validate(source)
            val before = fingerprint(source)
            val result = container.dataRootMigrationService.migrate(target)
            assertFalse(result is DesktopDataRootMigrationResult.Failure, "Protected-root relocation must have a controlled successful route: $result")
            assertTrue(container.dataOperationCoordinator.isRestartRequired)
            assertEquals(target, (DesktopBootstrapSettingsStore(bootstrap).load() as DesktopBootstrapLoadResult.Loaded).document.selection.customRoot)
            assertEquals(before, fingerprint(source))
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
