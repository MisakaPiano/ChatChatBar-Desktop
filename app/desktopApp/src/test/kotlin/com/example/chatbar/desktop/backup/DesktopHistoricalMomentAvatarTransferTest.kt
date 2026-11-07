package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.*
import com.example.chatbar.desktop.security.*
import java.awt.image.BufferedImage
import java.nio.file.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopHistoricalMomentAvatarTransferTest {
    private fun own(root: Path, base: Path) = assertIs<DesktopDataRootOwnershipResult.Acquired>(
        DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
            DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json")))).ownership

    @Test fun `ordinary export reports missing avatar and still refuses other missing or existing external resources`() = runBlocking<Unit> {
        val base = Files.createTempDirectory("preview-synthetic-avatar-warning-")
        val gate = DesktopDataOperationCoordinator(); val focus = DesktopAudioFocus()
        try {
            val root = Files.createDirectory(base.resolve("source"))
            own(root, base).use {
                val storage = JsonFileStorage(root, gate)
                val card = CharacterCard("card", "Synthetic card", createdAt = 1, updatedAt = 1)
                val session = ChatSession("session", card.id, "Synthetic session", createdAt = 1, updatedAt = 1)
                val post = MomentPost("post", card.id, session.id, senderName = "Synthetic", senderAvatar = "images/absent.png",
                    text = "Synthetic retained post", scheduledAt = 1, generatedAt = 1)
                storage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
                storage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
                storage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
                val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {})
                val exported = base.resolve("ordinary.cbbackup")
                val picker = object : DesktopFilePicker {
                    override fun pickOpenFile(type: DesktopFileType): Path = exported
                    override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path = exported
                }
                val controller = DesktopFullBackupController(service, picker)
                try {
                    controller.export(null)
                    val state = withTimeout(20000) { controller.state.first { !it.busy && it.exportResult != null } }
                    assertNull(state.error)
                    val result = assertNotNull(state.exportResult)
                    assertTrue(result.success)
                    assertEquals(exported.toAbsolutePath().normalize(), result.path)
                    assertTrue(result.message.contains("1 条朋友圈记录的历史头像文件缺失"))
                    assertEquals(1, service.verifyFile(exported, null).missingHistoricalAvatars)
                    controller.verifyFile(null)
                    val checked = withTimeout(20000) { controller.state.first { !it.busy && it.summary != null } }
                    assertTrue(checked.message.contains("1 条朋友圈记录的历史头像文件缺失"))
                } finally { controller.closeAndDrain() }
                val image = DesktopImageEditing.png(BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB))
                val missingVersion = DesktopMomentImageVersions.append(post, DesktopMomentImageResult("images/version.png",
                    GeneratedImageMetadata("images/version.png", "Synthetic", sizePreset = "SQUARE", width = 3, height = 2), "Synthetic", "Synthetic"), 2)
                val directory = Files.createDirectories(root.resolve("images/directory"))
                val external = base.resolve("unrelated.png"); Files.write(external, image)
                val failures = listOf(
                    post.copy(imagePath = "images/body.png"),
                    post.copy(generatedImageMetadata = GeneratedImageMetadata("images/metadata.png", "Synthetic", sizePreset = "SQUARE", width = 3, height = 2)),
                    missingVersion,
                    post.copy(senderAvatar = root.relativize(directory).joinToString("/")),
                    post.copy(senderAvatar = external.toAbsolutePath().toString()))
                failures.forEachIndexed { index, broken ->
                    storage.saveEntity("moment_posts", post.id, broken, MomentPost.serializer())
                    val output = base.resolve("refused-$index.cbbackup")
                    assertFails { service.exportModern(output, null) }
                    assertFalse(Files.exists(output))
                }
                storage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
                storage.saveEntity("character_cards", card.id, card.copy(avatar = "images/card.png"), CharacterCard.serializer())
                assertFails { service.exportModern(base.resolve("missing-card.cbbackup"), null) }
                assertFalse(Files.exists(base.resolve("missing-card.cbbackup")))
                assertContentEquals(image, Files.readAllBytes(external))
            }
        } finally { focus.closeAndDrain(); gate.closeAndDrain(); check(base.toFile().deleteRecursively()) }
    }

    @Test fun `actual Service and fenced merge retain missing historical avatars without borrowing present media`() = runBlocking<Unit> {
        val base = Files.createTempDirectory("preview-synthetic-historical-avatar-")
        val gate = DesktopDataOperationCoordinator()
        val focus = DesktopAudioFocus()
        try {
            val root = Files.createDirectory(base.resolve("source"))
            own(root, base).use {
                val storage = JsonFileStorage(root, gate)
                val card = CharacterCard("card", "Synthetic card", createdAt = 1, updatedAt = 1)
                val session = ChatSession("session", card.id, "Synthetic session", createdAt = 1, updatedAt = 1)
                storage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
                storage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
                Files.writeString(root.resolve("entities/app_settings.json"),
                    backupJson.encodeToString(AppSettings.serializer(), AppSettings()))
                Files.createDirectory(root.resolve("images"))
                val image = DesktopImageEditing.png(BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB))
                Files.write(root.resolve("images/body.png"), image)
                val externalMissing = base.resolve("retired-source/avatar.png").toAbsolutePath().toString()
                for ((id, avatar) in listOf("relative" to "images/missing.png", "external" to externalMissing)) {
                    val post = MomentPost(id, card.id, session.id, senderName = "Synthetic $id", senderAvatar = avatar,
                        text = "Retained historical post", scheduledAt = 1, generatedAt = 1,
                        imagePath = "images/body.png")
                    storage.saveEntity("moment_posts", id, post, MomentPost.serializer())
                }
                fun sourceBytes() = Files.walk(root.resolve("entities")).use { paths -> paths.filter(Files::isRegularFile)
                    .toList().associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() } }
                val original = sourceBytes()
                val reads = mutableListOf<DesktopCredentialKey>()
                val store = object : DesktopSecretStore {
                    override fun load(key: DesktopCredentialKey): String? {
                        reads += key
                        return if (key == DesktopCredentialKey.NovelAiToken) "SYNTHETIC_AVATAR_TOKEN" else null
                    }
                    override fun save(key: DesktopCredentialKey, secret: String) = error("No credential mutation")
                    override fun delete(key: DesktopCredentialKey) = error("No credential mutation")
                }
                val service = DesktopFullBackupService(root, gate, DesktopAudioMaintenance({ focus }) {}, secretStore = { store })
                for (encrypted in listOf(false, true)) for (credentials in listOf(false, true)) {
                    reads.clear()
                    val suffix = "$encrypted-$credentials"
                    val selection = if (encrypted) AppBackupSelection(globalOptions = setOf("novelAiCredentials"),
                        characterIds = setOf(card.id), sessions = mapOf(session.id to BackupSessionChoice()), moments = true,
                        momentsImages = true) else null
                    val output = base.resolve("export-$suffix.cbbackup")
                    val password = if (encrypted) "SYNTHETIC_AVATAR_PASSWORD".toCharArray() else null
                    service.exportModern(output, password, credentials, selection)
                    assertTrue(password == null || password.all { it == '\u0000' })
                    assertEquals(credentials, reads.isNotEmpty())
                    val archiveBytes = Files.readAllBytes(output)
                    DesktopFullBackupStaging.prepare(output, if (encrypted) "SYNTHETIC_AVATAR_PASSWORD".toCharArray() else null,
                        Files.createDirectory(base.resolve("inspect-$suffix"))).use { staged ->
                        assertEquals(1, staged.files.keys.count { it.startsWith("images/") })
                        val pcRoot = Files.createDirectory(base.resolve("pc-$suffix"))
                        Files.createDirectory(pcRoot.resolve("images"))
                        // A same-spelling PC file must not fill the incoming missing avatar.
                        Files.write(pcRoot.resolve("images/missing.png"), image)
                        DesktopFullBackupMapping(pcRoot).export(Files.createDirectory(base.resolve("pc-work-$suffix")),
                            CURRENT_DESKTOP_BACKUP_CODE, StrictBackupZipReader.Limits().expanded, forMerge = true).use { pc ->
                            val pcEntities = DesktopMergeRootPreparation.computerEntities(pc)
                            val pcMedia = pc.files.filterKeys { !it.startsWith("entities/") }
                                .mapValues { DesktopArchiveFiles.hash(it.value, DesktopFullBackupSource.MAX_FILE) }
                            val incomingMedia = staged.source.inventory.filterKeys { !it.startsWith("entities/") }.mapValues { it.value.sha256 }
                            val selected = DesktopMergePreview.create(pcEntities, staged.entities, pcMedia, incomingMedia,
                                staged.source.manifest.scope).select(emptyMap())
                            val target = Files.createDirectory(base.resolve("target-$suffix"))
                            own(target, base).use {
                                val authority = DesktopFreshRootAuthority(target, Files.createDirectory(base.resolve("keys-$suffix")), MergeRootSyntheticProtector)
                                val pending = authority.begin()
                                DesktopMergeRootPreparation.prepare(target, authority, pending, pc, staged, selected,
                                    Files.createDirectory(base.resolve("merge-work-$suffix"))).use { result ->
                                    authority.complete(pending, result.files)
                                }
                                authority.verifyAndResolve()
                                DesktopFullBackupGraphValidation.validate(target)
                                val restoredGate = DesktopDataOperationCoordinator()
                                try {
                                    val reopened = DesktopMomentStore(JsonFileStorage(target, restoredGate), restoredGate).posts()
                                    assertEquals(2, reopened.size)
                                    assertEquals(2, reopened.map { it.senderAvatar }.distinct().size)
                                    for (post in reopened) {
                                        assertFalse(post.senderAvatar.isNullOrBlank())
                                        assertFalse(Files.exists(target.resolve(post.senderAvatar!!)))
                                        assertContentEquals(image, Files.readAllBytes(target.resolve(post.imagePath!!)))
                                    }
                                    val next = base.resolve("roundtrip-$suffix.cbbackup")
                                    DesktopFullBackupService(target, restoredGate, DesktopAudioMaintenance({ focus }) {})
                                        .exportModern(next, null)
                                    DesktopFullBackupStaging.prepare(next, null, Files.createDirectory(base.resolve("recheck-$suffix"))).use {
                                        assertEquals(2, it.entities.keys.count { path -> path.startsWith("entities/moment_posts/") })
                                    }
                                } finally { restoredGate.closeAndDrain() }
                            }
                        }
                    }
                    assertEquals(original, sourceBytes())
                    assertContentEquals(archiveBytes, Files.readAllBytes(output))
                    assertContentEquals(image, Files.readAllBytes(root.resolve("images/body.png")))
                    assertFalse(Files.exists(Path.of(externalMissing)))
                }
                assertFailsWith<DesktopBackupCompatibilityException> { service.export(base.resolve("legacy.cbbackup"), null) }
                assertFalse(Files.exists(base.resolve("legacy.cbbackup")))
            }
        } finally {
            focus.closeAndDrain(); gate.closeAndDrain()
            check(base.toFile().deleteRecursively())
        }
    }
}
