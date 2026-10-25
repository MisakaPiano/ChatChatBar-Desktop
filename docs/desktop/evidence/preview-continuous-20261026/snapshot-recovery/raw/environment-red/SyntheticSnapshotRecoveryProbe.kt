package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.snapshot.AppDataSnapshotService
import com.example.chatbar.data.snapshot.SnapshotPurpose
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Test-only companion: explicit newly created synthetic roots, no application/credential bootstrap. */
object SyntheticSnapshotRecoveryProbe {
    private fun sha(path: Path) = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun files(directory: Path): Map<String, String> = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            .associate { directory.relativize(it).toString().replace('\\', '/') to sha(it) }
    }

    private fun live(root: Path) = files(root).filterKeys {
        !it.startsWith("backups/") && it != DesktopDataRootOwnership.LOCK_FILE_NAME
    }

    private fun proof(path: Path, value: Map<String, String>) = Files.writeString(
        path, buildJsonObject { value.toSortedMap().forEach { (k, v) -> put(k, v) } }.toString(), CREATE_NEW,
    )

    private fun readProof(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject
        .mapValues { it.value.jsonPrimitive.content }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2 && args[0] in setOf("seed", "restore", "reopen", "reject-corrupt"))
        val base = Path.of(args[1]).toAbsolutePath().normalize()
        require(Files.readString(base.resolve("SYNTHETIC_ONLY")) == "snapshot-recovery-v1")
        require(!Files.isSymbolicLink(base))
        val root = base.resolve("root")
        val resolution = DesktopDataRootResolution.Resolved(
            root, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("unused-bootstrap.json"),
        )
        val acquired = DesktopDataRootOwnership.acquire(resolution) as DesktopDataRootOwnershipResult.Acquired
        acquired.ownership.use {
            val coordinator = DesktopDataOperationCoordinator()
            try {
                val raw = AppDataSnapshotService(root)
                val snapshots = DesktopCoordinatedSnapshotService(raw, coordinator)
                val storage = JsonFileStorage(root, coordinator)
                val chats = ChatRepository(storage)
                val cards = CharacterRepository(storage)
                chats.initialize()
                cards.initialize()
                val image = root.resolve("images/synthetic.png")
                when (args[0]) {
                    "seed" -> {
                        cards.save(CharacterCard("synthetic-card", "Synthetic snapshot card", createdAt = 1, updatedAt = 1))
                        chats.createSession(ChatSession("synthetic-session", "synthetic-card", "Synthetic snapshot", longTermMemoryEnabled = false, createdAt = 1, updatedAt = 1))
                        Files.createDirectories(image.parent)
                        val bitmap = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
                        try { check(javax.imageio.ImageIO.write(bitmap, "png", image.toFile())) }
                        finally { bitmap.flush() }
                        chats.addMessage(ChatMessage("synthetic-old-message", "synthetic-session", MessageRole.USER,
                            "Synthetic old message", images = listOf(image.toString()), createdAt = 1, updatedAt = 1))
                        chats.updateSessionDraft("synthetic-session", "Synthetic old draft")
                        proof(base.resolve("OLD.json"), live(root))
                        val selected = snapshots.createSnapshot(SnapshotPurpose.MANUAL)
                        check(selected.validation.valid)
                        Files.writeString(base.resolve("SELECTED"), selected.name, CREATE_NEW)
                        proof(base.resolve("SELECTED_FILES.json"), files(selected.directory))
                        chats.addMessage(ChatMessage("synthetic-new-message", "synthetic-session", MessageRole.USER,
                            "Synthetic newer message", createdAt = 2, updatedAt = 2))
                        chats.updateSessionDraft("synthetic-session", "Synthetic newer draft")
                        Files.write(image, byteArrayOf(9, 8, 7))
                        proof(base.resolve("NEW.json"), live(root))
                        check(!coordinator.isRestartRequired)
                        println("SYNTHETIC_SEED_PASS")
                    }
                    "restore" -> {
                        check(live(root) == readProof(base.resolve("NEW.json")))
                        val selected = root.resolve("backups").resolve(Files.readString(base.resolve("SELECTED")))
                        val result = snapshots.restoreSnapshot(selected)
                        check(coordinator.isRestartRequired)
                        val rejected = runCatching { coordinator.withNormalOperation { error("MIXED_BUSINESS_ADMITTED") } }.exceptionOrNull()
                        check(rejected is DesktopDataOperationRejectedException)
                        check(live(root) == readProof(base.resolve("OLD.json")))
                        check(files(selected) == readProof(base.resolve("SELECTED_FILES.json")))
                        check(raw.validateSnapshot(result.preRestoreSnapshot.directory).valid)
                        check(files(result.preRestoreSnapshot.directory.resolve("payload")) == readProof(base.resolve("NEW.json")))
                        Files.writeString(base.resolve("SAFETY"), result.preRestoreSnapshot.name, CREATE_NEW)
                        println("SYNTHETIC_RESTORE_AND_RESTART_FENCE_PASS")
                    }
                    "reopen" -> {
                        check(!coordinator.isRestartRequired)
                        check(live(root) == readProof(base.resolve("OLD.json")))
                        check(cards.getById("synthetic-card")?.name == "Synthetic snapshot card")
                        check(chats.getAllSessions().single().id == "synthetic-session")
                        val message = chats.getMessagesReadOnly("synthetic-session").single()
                        check(message.id == "synthetic-old-message" && message.content == "Synthetic old message")
                        check(message.images == listOf(image.toString()))
                        check(chats.getSessionDraft("synthetic-session") == "Synthetic old draft")
                        check(javax.imageio.ImageIO.read(image.toFile())?.let { it.width == 3 && it.height == 2 } == true)
                        val selected = root.resolve("backups").resolve(Files.readString(base.resolve("SELECTED")))
                        check(files(selected) == readProof(base.resolve("SELECTED_FILES.json")))
                        val safety = root.resolve("backups").resolve(Files.readString(base.resolve("SAFETY")))
                        check(raw.validateSnapshot(safety).valid)
                        check(files(safety.resolve("payload")) == readProof(base.resolve("NEW.json")))
                        println("SYNTHETIC_INDEPENDENT_REOPEN_PASS")
                    }
                    "reject-corrupt" -> {
                        val before = live(root)
                        val selected = root.resolve("backups").resolve(Files.readString(base.resolve("SELECTED")))
                        check(!raw.validateSnapshot(selected).valid)
                        check(runCatching { snapshots.restoreSnapshot(selected) }.isFailure)
                        check(live(root) == before && before == readProof(base.resolve("NEW.json")))
                        check(!coordinator.isRestartRequired)
                        check(raw.listSnapshots().size == 1)
                        println("SYNTHETIC_CORRUPTION_REFUSED_UNCHANGED_PASS")
                    }
                }
            } finally {
                coordinator.closeAndDrain()
            }
        }
        check(!DesktopDataRootOwnership.isHeld(root))
        check(!Files.exists(base.resolve("unused-bootstrap.json")))
    }
}
