package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlin.test.*

class DesktopStudioEmptyScopeBackupTest {
    private val unavailable = DesktopApplicationHomeResult.Unavailable(DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT)
    private fun <T> record(type: String, serializer: KSerializer<T>, value: T, id: String? = null) =
        ("entities/$type" + (id?.let { "/$it" } ?: "") + ".json") to backupJson.encodeToString(serializer, value).toByteArray()

    @Test fun `actual selected empty studio scope replaces only settings family through each modern new root mode`() = runBlocking<Unit> {
        for (selected in listOf(true, false)) for (mode in listOf(DesktopBackupRestoreMode.UPDATE_EXISTING,
            DesktopBackupRestoreMode.ADD_COPIES, DesktopBackupRestoreMode.REPLACE_SELECTED)) {
            val base = Files.createTempDirectory("studio-empty-scope-")
            val pc = Files.createDirectory(base.resolve("pc")); val phone = Files.createDirectory(base.resolve("phone"))
            val keys = Files.createDirectory(base.resolve("keys"))
            val source = DesktopDataRootResolution.Resolved(pc, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, base.resolve("bootstrap.json"))
            val bootstrap = DesktopBootstrapSettingsStore(source.bootstrapPath)
            bootstrap.save((bootstrap.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(pc))
            val phoneResolved = DesktopDataRootResolution.Resolved(phone, DesktopDataRootProvenance.CLI_OVERRIDE, base.resolve("phone-bootstrap.json"))
            val pcOwner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(source)).ownership
            val phoneOwner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(phoneResolved)).ownership
            val gate = DesktopDataOperationCoordinator(); val phoneGate = DesktopDataOperationCoordinator(); val focus = DesktopAudioFocus()
            val draft = NovelAiStudioDraft(basePrompt = "Synthetic local draft", negativePrompt = "Synthetic negative")
            val settings = mapOf(
                record("novelai_studio_draft", NovelAiStudioDraft.serializer(), draft),
                record("novelai_studio_history_undo", NovelAiStudioUndoDraft.serializer(), NovelAiStudioUndoDraft(draft)),
                record("novelai_studio_guidance_checkpoint", NovelAiGuidanceEditorCheckpoint.serializer(), NovelAiGuidanceEditorCheckpoint()),
                record("desktop_studio_edit_history", DesktopStudioHistoryJournal.serializer(),
                    DesktopStudioHistoryJournal(DesktopStudioHistoryTimeline(listOf(draft)))),
                record("novelai_design_conversations", NovelAiDesignConversation.serializer(), NovelAiDesignConversation(id = "design", title = "Synthetic design"), "design"),
                record("novelai_design_current", NovelAiDesignCurrentState.serializer(), NovelAiDesignCurrentState("design")))
            val history = mapOf(
                record("novelai_generation_history", NovelAiGenerationHistoryEntry.serializer(),
                    NovelAiGenerationHistoryEntry(id = "history", recipe = NovelAiGenerationRecipe(basePrompt = "Synthetic historical", negativePrompt = "Synthetic negative")), "history"),
                record("novelai_history_fold_preferences", NovelAiHistoryFoldPreference.serializer(), NovelAiHistoryFoldPreference()))
            val original = settings + history
            for ((relative, bytes) in original) {
                val path = pc.resolve(relative); Files.createDirectories(path.parent); Files.write(path, bytes)
            }
            val incomingCard = CharacterCard("new-card", "Incoming card", createdAt = 1, updatedAt = 1)
            JsonFileStorage(phone).saveEntity("character_cards", incomingCard.id, incomingCard, CharacterCard.serializer())
            val merge = DesktopMergeImportService(source, gate, InMemoryDesktopSecretStore(), {}, { it() },
                DesktopMigrationDestinationPreparer({ unavailable }, DesktopDataRootOwnership::acquire),
                DesktopBootstrapAuthorityTransaction(storeFactory = { DesktopBootstrapSettingsStore(it) },
                    applicationHomeResolver = { unavailable }, portableRootResolver = DesktopPortableRootResolver()),
                freshAuthority = { DesktopFreshRootAuthority(it, keys, MergeRootSyntheticProtector) })
            try {
                val exporter = DesktopFullBackupService(phone, phoneGate, DesktopAudioMaintenance({ focus }) {})
                val selection = AppBackupSelection(characterIds = setOf(incomingCard.id), studioSettings = selected)
                val archive = base.resolve("phone.cbbackup")
                exporter.exportModern(archive, null, selection = selection)
                val archiveBytes = Files.readAllBytes(archive)
                DesktopFullBackupStaging.prepare(archive, null, Files.createDirectory(base.resolve("inspection"))).use { staged ->
                    assertEquals(selected, staged.source.manifest.scope!!.studioSettings)
                    assertTrue(staged.entities.keys.none { DesktopBackupPolicy.entityType(it).startsWith("novelai_") })
                }
                val preview = merge.inspect(archive, null)
                val projected = merge.previewMode(preview, mode)
                // The draft, undo, checkpoint and Desktop edit journal form one atomic Preview unit.
                assertEquals(if (selected) 3 else 0, projected.removed)
                val destination = base.resolve("prepared")
                assertEquals(DesktopMergeImportService.Publication.COMMITTED,
                    merge.commitModeWithProgress(preview, destination, emptyMap(), emptyMap(), true, false, mode) {})
                DesktopFreshRootAuthority(destination, keys, MergeRootSyntheticProtector).verifyAndResolve()
                val reopened = JsonFileStorage(destination)
                val importedCards = mutableListOf<CharacterCard>()
                reopened.visitEntitiesStrict("character_cards", CharacterCard.serializer()) { _, card -> importedCards += card }
                assertEquals(incomingCard.name, importedCards.single().name)
                if (mode != DesktopBackupRestoreMode.ADD_COPIES) assertEquals(incomingCard.id, importedCards.single().id)
                else assertNotEquals(incomingCard.id, importedCards.single().id)
                for ((relative, bytes) in settings) {
                    assertEquals(!selected, Files.exists(destination.resolve(relative)), relative)
                    if (!selected) assertContentEquals(bytes, Files.readAllBytes(destination.resolve(relative)))
                }
                for ((relative, bytes) in history) assertContentEquals(bytes, Files.readAllBytes(destination.resolve(relative)))
                assertEquals(!selected, reopened.loadSingleton("novelai_studio_draft", NovelAiStudioDraft.serializer()) != null)
                for ((relative, bytes) in original) assertContentEquals(bytes, Files.readAllBytes(pc.resolve(relative)))
                assertContentEquals(archiveBytes, Files.readAllBytes(archive))
            } finally {
                merge.close(); focus.closeAndDrain(); gate.closeAndDrain(); phoneGate.closeAndDrain()
                pcOwner.close(); phoneOwner.close(); check(base.toFile().deleteRecursively())
            }
        }
    }

    @Test fun `desktop edit history alone declares settings scope and cannot pass an explicitly unselected scope`() = runBlocking<Unit> {
        val base = Files.createTempDirectory("studio-history-scope-")
        try {
            val entry = record("desktop_studio_edit_history", DesktopStudioHistoryJournal.serializer(), DesktopStudioHistoryJournal())
            val input = Files.write(base.resolve("journal.json"), entry.second)
            val files = mapOf(entry.first to input)
            val output = base.resolve("journal.cbbackup")
            DesktopFullBackupWriter.export(files, output, null)
            DesktopFullBackupStaging.prepare(output, null, Files.createDirectory(base.resolve("read"))).use {
                assertTrue(it.source.manifest.scope!!.studioSettings)
            }
            assertFailsWith<DesktopFullBackupWriter.Refusal> {
                DesktopFullBackupWriter.export(files, base.resolve("unselected.cbbackup"), null, selection = AppBackupSelection())
            }
        } finally { check(base.toFile().deleteRecursively()) }
    }
}
