package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.*
import java.nio.file.*
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Explicit companion for a task-owned native package test. Never distributed or auto-executed. */
internal object DesktopWindowsPackageFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        require(args.size == 2 && args[0] in setOf("seed", "verify", "busy"))
        val base = Path.of(args[1]).toAbsolutePath().normalize()
        val approved = Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/preview-continuous-private-20261025")
        require(base.startsWith(approved) && base.fileName.toString().startsWith("windows-runtime-"))
        DesktopArchiveFiles.safe(base, true)
        val owner = Json.parseToJsonElement(Files.readString(base.resolve("TASK_OWNER.json"))).jsonObject
        require(owner["kind"]?.jsonPrimitive?.content == "CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME")
        val local = base.resolve("local-appdata")
        require(Path.of(System.getenv("LOCALAPPDATA")).toAbsolutePath().normalize() == local)
        DesktopArchiveFiles.safe(local, true)
        val root = base.resolve("data")
        val bootstrap = local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME)
        if (args[0] == "seed") {
            require(!Files.exists(root) && !Files.exists(bootstrap))
            Files.createDirectory(root)
            val settings = DesktopBootstrapSettingsStore(bootstrap)
            val document = (settings.load() as DesktopBootstrapLoadResult.Missing).document
            settings.save(document, DesktopDataRootSelection.custom(root))
        }
        val resolution = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, bootstrap)
        if (args[0] == "busy") {
            check(DesktopDataRootOwnership.acquire(resolution) is DesktopDataRootOwnershipResult.AlreadyInUse)
            println("WINDOWS_SYNTHETIC_SELECTED_ROOT_BUSY_PASS")
            return@runBlocking
        }
        val acquired = DesktopDataRootOwnership.acquire(resolution) as DesktopDataRootOwnershipResult.Acquired
        acquired.ownership.use {
            val container = DesktopAppContainer(resolution)
            try {
                container.initializePersistentState()
                val secrets = WindowsSecretStore.create(root)
                if (args[0] == "seed") {
                    container.characterRepository.save(CharacterCard("package-card", "Windows synthetic card", createdAt = 1, updatedAt = 1))
                    container.chatRepository.createSession(ChatSession("package-session", "package-card", "Windows synthetic session", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1))
                    container.chatRepository.addMessage(ChatMessage("package-message", "package-session", MessageRole.USER, "Synthetic retained native-package message", createdAt = 1, updatedAt = 1))
                    container.chatRepository.updateSessionDraft("package-session", "Synthetic retained composer draft")
                    container.modelRepository.saveModel(ModelConfig("package-model", "Synthetic offline model", "https://synthetic.invalid/v1", MODEL, "synthetic", createdAt = 1))
                    secrets.save(DesktopCredentialKey.NovelAiToken, NAI)
                    secrets.save(DesktopCredentialKey.FishAudioApiKey, FISH)
                    val image = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    Files.createDirectories(root.resolve("images"))
                    try { check(javax.imageio.ImageIO.write(image, "png", root.resolve("images/package-fixture.png").toFile())) }
                    finally { image.flush() }
                    val proof = buildJsonObject {
                        put("imageSha256", hash(root.resolve("images/package-fixture.png")))
                        put("bootstrapSha256", hash(bootstrap))
                        put("credentialFiles", buildJsonObject {
                            val directory = WindowsSecretRootResolver.resolve(root)
                            Files.list(directory).use { paths -> paths.forEach { file -> put(file.fileName.toString(), hash(file)) } }
                        })
                    }
                    Files.writeString(base.resolve("PRIVATE_BASELINE.json"), proof.toString(), StandardOpenOption.CREATE_NEW)
                }
                check(container.characterRepository.getById("package-card")?.name == "Windows synthetic card")
                check(container.chatRepository.getSession("package-session")?.characterCardId == "package-card")
                check(container.chatRepository.getMessages("package-session").single().content == "Synthetic retained native-package message")
                check(container.chatRepository.getSessionDraft("package-session") == "Synthetic retained composer draft")
                check(container.modelRepository.getAllModels().single { it.id == "package-model" }.apiKey == MODEL)
                check(secrets.load(DesktopCredentialKey.NovelAiToken) == NAI)
                check(secrets.load(DesktopCredentialKey.FishAudioApiKey) == FISH)
                val baseline = Json.parseToJsonElement(Files.readString(base.resolve("PRIVATE_BASELINE.json"))).jsonObject
                check(hash(root.resolve("images/package-fixture.png")) == baseline.getValue("imageSha256").jsonPrimitive.content)
                check(hash(bootstrap) == baseline.getValue("bootstrapSha256").jsonPrimitive.content)
                val secretRoot = WindowsSecretRootResolver.resolve(root)
                val protectedProof = baseline.getValue("credentialFiles").jsonObject
                check(Files.list(secretRoot).use { it.map { file -> file.fileName.toString() }.toList().toSet() } == protectedProof.keys)
                protectedProof.forEach { (name, proof) -> check(hash(secretRoot.resolve(name)) == proof.jsonPrimitive.content) }
                Files.walk(root.resolve("entities")).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    check(!Files.readString(file).contains("FAKE_DESKTOP_PACKAGE_"))
                } }
                println("WINDOWS_SYNTHETIC_DATA_MEDIA_BOOTSTRAP_THREE_PROTECTED_KEYS_PASS")
            } finally { container.close() }
        }
    }

    private fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private const val NAI = "FAKE_DESKTOP_PACKAGE_NAI_NOT_REAL"
    private const val FISH = "FAKE_DESKTOP_PACKAGE_FISH_NOT_REAL"
    private const val MODEL = "FAKE_DESKTOP_PACKAGE_MODEL_NOT_REAL"
}
