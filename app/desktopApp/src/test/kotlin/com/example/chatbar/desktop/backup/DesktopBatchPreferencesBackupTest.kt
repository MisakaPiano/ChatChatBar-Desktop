package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.DesktopDataOperationCoordinator
import com.example.chatbar.desktop.DesktopFullBackupGraphValidation
import com.example.chatbar.domain.chat.SaveSlotSessionPolicy
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopBatchPreferencesBackupTest {
    @Test fun `session plan rebases independently and survives selected authenticated backup without runtime consent`() = runBlocking {
        val base = Files.createTempDirectory("batch-preferences-backup-")
        val root = Files.createDirectory(base.resolve("source"))
        val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate)
            val repository = ChatRepository(storage)
            val card = CharacterCard("card", "Synthetic card", greeting = "Hello", createdAt = 1, updatedAt = 1)
            storage.saveEntity("character_cards", card.id, card, CharacterCard.serializer())
            val baseline = ChatSession("session-a", card.id, "A", createdAt = 1, updatedAt = 1)
            repository.createSession(baseline)
            repository.createSession(baseline.copy(id = "session-b", title = "B"))
            val plan = ImageBatchPreferences(true, 4, 3, ImageBatchPacing(fixedSeconds = 7), true)
            repository.saveSessionSettingsDraft(baseline, baseline.copy(imageBatchPreferences = plan))
            repository.saveSessionSettingsDraft(baseline, baseline.copy(playerName = "Concurrent player edit"))
            val saved = assertNotNull(ChatRepository(JsonFileStorage(root)).getSession(baseline.id))
            assertEquals(plan, saved.imageBatchPreferences)
            assertEquals("Concurrent player edit", saved.playerName)
            assertFalse(saved.automaticImageGenerationEnabled)
            assertEquals(ImageBatchPreferences(), repository.getSession("session-b")!!.imageBatchPreferences)
            assertEquals(listOf(4, 4, 4), saved.imageBatchPreferences.configuration().freeze(ImageBatchOrigin.CHAT_MANUAL).batches)
            val slot = SaveSlotSessionPolicy.create(saved, "Synthetic archive", null, null, SaveSlotImagePolicy.NONE, false)
            val slotRoundTrip = backupJson.decodeFromString(SaveSlot.serializer(), backupJson.encodeToString(SaveSlot.serializer(), slot))
            assertEquals(plan, SaveSlotSessionPolicy.restore(baseline, slotRoundTrip, null).imageBatchPreferences)
            assertTrue(DesktopBackupPolicy.hasDesktopParticipation(backupJson.encodeToJsonElement(SaveSlot.serializer(), slotRoundTrip).jsonObject))
            val oldShape = backupJson.encodeToJsonElement(ChatSession.serializer(), baseline).jsonObject
            assertFalse("imageBatchPreferences" in oldShape)
            assertEquals(ImageBatchPreferences(), backupJson.decodeFromJsonElement(ChatSession.serializer(), oldShape).imageBatchPreferences)
            assertFalse(DesktopBackupPolicy.hasDesktopParticipation(oldShape))
            assertFalse(DesktopBackupPolicy.hasDesktopParticipation(JsonObject(oldShape +
                ("imageBatchPreferences" to backupJson.encodeToJsonElement(ImageBatchPreferences.serializer(), ImageBatchPreferences())))))
            assertTrue(DesktopBackupPolicy.hasDesktopParticipation(backupJson.encodeToJsonElement(NovelAiStudioDraft.serializer(),
                NovelAiStudioDraft(batchCount = 3)).jsonObject))
            val source = root.resolve("entities/chat_sessions/session-a.json")
            val before = Files.readAllBytes(source)
            val selection = AppBackupSelection(characterIds = setOf(card.id), sessions = mapOf(baseline.id to BackupSessionChoice(false, false, false)))
            DesktopFullBackupMapping(root).export(Files.createDirectory(base.resolve("export")), CURRENT_DESKTOP_BACKUP_CODE,
                StrictBackupZipReader.Limits().expanded, selection = selection).use { snapshot ->
                val archive = base.resolve("synthetic.cbbackup")
                DesktopFullBackupWriter.export(snapshot.files, archive, null, selection = selection)
                DesktopFullBackupStaging.prepare(archive, null, Files.createDirectory(base.resolve("inspect"))).use { staged ->
                    assertTrue(staged.hasDesktopParticipation, "UI compatibility warning must remain visible for a batch-plan-only extension")
                    assertFalse("entities/chat_sessions/session-b.json" in staged.entities)
                    val encoded = staged.entities.getValue("entities/chat_sessions/session-a.json")
                    assertFalse("paymentTicket" in encoded.toString())
                    DesktopFullBackupMapping(root).restoreMerged(staged.files, Files.createDirectory(base.resolve("restore")), BackupPreferences()).use { mapped ->
                        val live = Files.createDirectory(base.resolve("live"))
                        for ((relative, item) in mapped.files) {
                            val target = live.resolve(relative)
                            Files.createDirectories(target.parent); Files.copy(item, target)
                        }
                        DesktopFullBackupGraphValidation.validate(live)
                        val cold = ChatRepository(JsonFileStorage(live))
                        val restored = cold.getAllSessions().single()
                        assertEquals(plan, restored.imageBatchPreferences)
                        assertFalse(restored.automaticImageGenerationEnabled)
                        assertEquals("Concurrent player edit", restored.playerName)
                    }
                }
            }
            assertContentEquals(before, Files.readAllBytes(source))
        } finally { gate.closeAndDrain(); check(base.toFile().deleteRecursively()) }
    }
}
