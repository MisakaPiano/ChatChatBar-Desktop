package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.*
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeOmittedImageRetentionTest {
    private fun <T> bytes(serializer: KSerializer<T>, value: T) =
        backupJson.encodeToJsonElement(serializer, value).toString().toByteArray(Charsets.UTF_8)
    private fun own(root: Path, base: Path) = (DesktopDataRootOwnership.acquire(DesktopDataRootResolution.Resolved(root,
        DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("bootstrap.json"))) as DesktopDataRootOwnershipResult.Acquired).ownership

    @Test fun `destination planning retains unselected Desktop Moment versions and Studio history images only for matching owners`() {
        fun <T> record(serializer: KSerializer<T>, value: T) = backupJson.encodeToJsonElement(serializer, value).jsonObject
        val prefix = DesktopBackupPolicy.PREFIX
        val post = MomentPost("p", "c", "s", senderName = "Synthetic", text = "Computer post", imagePath = prefix + "images/old.png",
            scheduledAt = 1, generatedAt = 1, desktopImageVersions = listOf(
                MomentImageVersion("v1", prefix + "images/old.png", createdAt = 1),
                MomentImageVersion("v2", prefix + "images/alternative.png", createdAt = 2)))
        val history = NovelAiGenerationHistoryEntry("h", listOf(NovelAiGenerationHistoryImage(prefix + "images/history.png", 17)),
            NovelAiStudioDraft(basePrompt = "Computer recipe").toRecipe(), 1)
        val pc = mapOf("entities/moment_posts/p.json" to record(MomentPost.serializer(), post),
            "entities/novelai_generation_history/h.json" to record(NovelAiGenerationHistoryEntry.serializer(), history))
        val media = mapOf("images/old.png" to "a".repeat(64), "images/alternative.png" to "b".repeat(64), "images/history.png" to "c".repeat(64))
        for (includeImages in listOf(false, true)) for (sameOwner in listOf(false, true)) {
            val phonePost = record(MomentPost.serializer(), post.copy(characterCardId = if (sameOwner) "c" else "other-card",
                text = "Phone post", imagePath = null, desktopImageVersions = emptyList()))
                .let { JsonObject(it + (DesktopMomentBackupExtension.FIELD to JsonArray(emptyList()))) }
            val phoneHistory = record(NovelAiGenerationHistoryEntry.serializer(), history.copy(images = emptyList(),
                recipe = NovelAiStudioDraft(basePrompt = "Phone recipe").toRecipe()))
            val phone = mapOf("entities/moment_posts/p.json" to phonePost, "entities/novelai_generation_history/h.json" to phoneHistory)
            val scope = InspectionScope(emptySet(), 0, 0, 0, 0, 0, false, true, includeImages, true, includeImages)
            val preview = DesktopMergePreview.create(pc, phone, media, emptyMap(), scope)
            DesktopMergeDestinationPlan.create(preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.TAKE_PHONE }),
                media, emptyMap()).use { plan ->
                val retainedPost = plan.entities.getValue("entities/moment_posts/p.json")
                assertEquals(JsonPrimitive("Phone post"), retainedPost["text"])
                if (!includeImages && sameOwner) {
                    assertNotEquals(JsonNull, retainedPost["imagePath"])
                    assertEquals(2, retainedPost.getValue(DesktopMomentBackupExtension.FIELD).jsonArray.size)
                } else {
                    assertEquals(JsonNull, retainedPost["imagePath"])
                    assertTrue(retainedPost.getValue(DesktopMomentBackupExtension.FIELD).jsonArray.isEmpty())
                }
                val retainedHistory = plan.entities.getValue("entities/novelai_generation_history/h.json")
                assertEquals(phoneHistory["recipe"], retainedHistory["recipe"])
                assertEquals(if (includeImages) 0 else 1, retainedHistory.getValue("images").jsonArray.size)
                assertTrue(plan.resources.values.all { it.side == DesktopMergeDestinationPlan.Side.COMPUTER })
            }
        }
    }

    @Test fun `actual selected merge retains omitted matching images without crossing fork or message identity`() = runBlocking<Unit> {
        data class Case(val includeImages: Boolean, val choice: DesktopMergePreview.Choice, val messageId: String = "m", val includeArchives: Boolean = true)
        for (case in listOf(Case(false, DesktopMergePreview.Choice.TAKE_PHONE),
            Case(true, DesktopMergePreview.Choice.TAKE_PHONE), Case(false, DesktopMergePreview.Choice.BOTH),
            Case(false, DesktopMergePreview.Choice.TAKE_PHONE, "new-message"),
            Case(true, DesktopMergePreview.Choice.TAKE_PHONE, includeArchives = false))) {
            val base = Files.createTempDirectory(Files.createDirectories(Path.of("build/preview-synthetic/omitted-media").toAbsolutePath()), "case-")
            try {
                val pcRoot = Files.createDirectory(base.resolve("pc"))
                val image = pcRoot.resolve("images/old.png"); Files.createDirectories(image.parent)
                val raster = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                try { raster.setRGB(0, 0, 0xff336699.toInt()); javax.imageio.ImageIO.write(raster, "png", image.toFile()) } finally { raster.flush() }
                val imageBytes = Files.readAllBytes(image)
                val card = bytes(CharacterCard.serializer(), CharacterCard("c", "Synthetic", createdAt = 1, updatedAt = 1))
                val session = bytes(ChatSession.serializer(), ChatSession("s", "c", "Synthetic", createdAt = 1, updatedAt = 1))
                val metadata = GeneratedImageMetadata(image.toString(), "synthetic original caption", negativePrompt = "synthetic exclusions",
                    sizePreset = "synthetic", width = 2, height = 2)
                val message = ChatMessage("m", "s", MessageRole.USER, "Computer body", images = listOf(image.toString()),
                    generatedImageMetadata = listOf(metadata), createdAt = 1, updatedAt = 1)
                val packagePath = pcRoot.resolve("save_slot_packages/synthetic.cbsave"); Files.createDirectories(packagePath.parent)
                SyntheticSaveSlotCorpus.archive(packagePath)
                val packageBytes = Files.readAllBytes(packagePath)
                val slot = SyntheticSaveSlotCorpus.slot().copy(packageRef = SaveSlotPackageRef("synthetic.cbsave", 1, byteLength = packageBytes.size.toLong()))
                val original = mapOf("entities/character_cards/c.json" to card, "entities/chat_sessions/s.json" to session,
                    "entities/chat_messages/s_m.json" to bytes(ChatMessage.serializer(), message),
                    "entities/save_slots/slot.json" to bytes(SaveSlot.serializer(), slot))
                for ((path, raw) in original) { val target = pcRoot.resolve(path); Files.createDirectories(target.parent); Files.write(target, raw) }
                val incoming = mapOf("entities/character_cards/c.json" to card, "entities/chat_sessions/s.json" to session,
                    "entities/chat_messages/s_${case.messageId}.json" to bytes(ChatMessage.serializer(),
                        ChatMessage(case.messageId, "s", MessageRole.USER, "Phone body", createdAt = 1, updatedAt = 2)))
                val scope = OfficialWireScope(emptySet(), 1, 1, if (case.includeImages) 1 else 0, 1, if (case.includeArchives) 1 else 0,
                    false, false, false, false, sessionDefaultChoice = OfficialWireSessionChoice(images = case.includeImages, archives = case.includeArchives))
                val source = Files.write(base.resolve("synthetic.cbbackup"), CensusWireFixtures.bytes(prefs = "{}", files = incoming,
                    code = 88, version = 2, scope = scope))
                val sourceBytes = Files.readAllBytes(source)
                own(pcRoot, base).use {
                    DesktopFullBackupMapping(pcRoot).export(Files.createDirectory(base.resolve("pc-work")), 88,
                        StrictBackupZipReader.Limits().expanded, forMerge = true).use { pc ->
                        DesktopFullBackupStaging.prepare(source, null, Files.createDirectory(base.resolve("phone-work"))).use { phone ->
                            val pcMedia = pc.proof.filterKeys { !it.startsWith("entities/") }
                            val phoneMedia = phone.source.inventory.filterKeys { !it.startsWith("entities/") }.mapValues { it.value.sha256 }
                            val preview = DesktopMergePreview.create(DesktopMergeRootPreparation.computerEntities(pc), phone.entities,
                                pcMedia, phoneMedia, phone.source.manifest.scope)
                            val selected = preview.select(preview.conflicts.associate { it.key to case.choice })
                            val root = Files.createDirectory(base.resolve("merged"))
                            val authority = DesktopFreshRootAuthority(root, Files.createDirectory(base.resolve("credentials")), MergeRootSyntheticProtector)
                            own(root, base).use {
                                val pending = authority.begin()
                                DesktopMergeRootPreparation.prepare(root, authority, pending, pc, phone, selected,
                                    Files.createDirectory(base.resolve("prepare-work"))).use { result ->
                                    authority.complete(pending, result.files); authority.verifyAndResolve()
                                    val incomingSession = result.plan.identity.value("chat_sessions", "s")
                                    val messages = ChatRepository(JsonFileStorage(root)).getMessagesReadOnly(incomingSession)
                                    val restored = messages.single { it.id == result.plan.identity.value("chat_messages", case.messageId) }
                                    assertEquals("Phone body", restored.content)
                                    val retain = !case.includeImages && case.choice == DesktopMergePreview.Choice.TAKE_PHONE && case.messageId == "m"
                                    assertEquals(if (retain) 1 else 0, restored.images.size, case.toString())
                                    if (retain) {
                                        assertContentEquals(imageBytes, Files.readAllBytes(Path.of(restored.images.single())))
                                        assertEquals(restored.images.single(), restored.generatedImageMetadata.single().imagePath)
                                        assertEquals(metadata.baseCaption, restored.generatedImageMetadata.single().baseCaption)
                                        assertEquals(restored, ChatRepository(JsonFileStorage(root)).getMessagesReadOnly(incomingSession).single { it.id == restored.id })
                                    }
                                    if (!case.includeImages && case.choice == DesktopMergePreview.Choice.TAKE_PHONE && case.messageId != "m") {
                                        assertEquals(2, messages.size)
                                        val kept = messages.single { it.id == "m" }
                                        assertEquals("Computer body", kept.content)
                                        assertContentEquals(imageBytes, Files.readAllBytes(Path.of(kept.images.single())))
                                    }
                                    if (case.choice == DesktopMergePreview.Choice.BOTH) {
                                        val kept = ChatRepository(JsonFileStorage(root)).getMessagesReadOnly("s").single()
                                        assertEquals("Computer body", kept.content); assertEquals(1, kept.images.size)
                                        assertContentEquals(imageBytes, Files.readAllBytes(Path.of(kept.images.single())))
                                    }
                                    val slots = mutableListOf<SaveSlot>()
                                    JsonFileStorage(root).visitEntitiesStrict("save_slots", SaveSlot.serializer()) { _, value -> slots += value }
                                    val keepArchive = !case.includeArchives || case.choice == DesktopMergePreview.Choice.BOTH
                                    assertEquals(if (keepArchive) 1 else 0, slots.size, case.toString())
                                    if (keepArchive) {
                                        assertEquals(slot, slots.single())
                                        val restoredPackage = root.resolve("save_slot_packages").resolve(slots.single().packageRef!!.fileName)
                                        assertContentEquals(packageBytes, Files.readAllBytes(restoredPackage))
                                        assertEquals(1, DesktopSaveSlotInspector(true).inspect(restoredPackage).counts?.messages)
                                    }
                                }
                            }
                        }
                    }
                }
                assertContentEquals(sourceBytes, Files.readAllBytes(source)); assertContentEquals(imageBytes, Files.readAllBytes(image))
                assertContentEquals(packageBytes, Files.readAllBytes(packagePath))
                original.forEach { (path, raw) -> assertContentEquals(raw, Files.readAllBytes(pcRoot.resolve(path))) }
            } finally { check(base.toFile().deleteRecursively()) }
        }
    }
}
