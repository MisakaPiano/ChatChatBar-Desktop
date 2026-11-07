package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMergeTransientStateTest {
    private fun deletion(id: String) = "entities/pending_deletions/$id.json" to buildJsonObject {
        put("id", id); put("type", "SESSION"); put("ownerId", "unrelated-session")
    }
    private fun fold(depth: Int, enabled: Boolean) = "entities/novelai_history_fold_preferences/$depth.json" to
        backupJson.encodeToJsonElement(NovelAiHistoryFoldPreference.serializer(),
            NovelAiHistoryFoldPreference(depth, enabled, NovelAiHistoryFoldType.DAY)).jsonObject
    private fun scope(history: Boolean) = InspectionScope(emptySet(), 0, 0, 0, 0, 0, false, false, history, false)
    private fun preview(pc: Map<String, JsonObject>, incoming: Map<String, JsonObject>, history: Boolean = true) =
        DesktopMergePreview.create(pc, incoming, emptyMap(), emptyMap(), scope(history))
    private fun selection(preview: DesktopMergePreview, mode: DesktopBackupRestoreMode) =
        if (mode == DesktopBackupRestoreMode.MERGE_CHOICES)
            preview.select(preview.conflicts.associate { it.key to DesktopMergePreview.Choice.TAKE_PHONE })
        else preview.modePlan(mode).selection

    @Test fun `incoming deletion commands are omitted while computer records stay independent in every mode`() {
        val pc = mapOf(deletion("pc")); val incoming = mapOf(deletion("phone"))
        val before = incoming.toString()
        for (mode in DesktopBackupRestoreMode.entries) {
            val p = preview(pc, incoming)
            assertTrue(p.conflicts.isEmpty())
            DesktopMergeDestinationPlan.create(selection(p, mode), emptyMap(), emptyMap()).use { plan ->
                assertFalse("entities/pending_deletions/phone.json" in plan.entities)
                assertEquals(pc.getValue("entities/pending_deletions/pc.json"), plan.entities["entities/pending_deletions/pc.json"])
            }
        }
        assertEquals(before, incoming.toString())
    }

    @Test fun `modern update and copies keep existing fold depths while selected replacement and explicit manual choice retain their meanings`() {
        val pc = mapOf(fold(0, true), fold(2, true))
        val incoming = mapOf(fold(0, false), fold(1, false))
        for (mode in DesktopBackupRestoreMode.entries) {
            DesktopMergeDestinationPlan.create(selection(preview(pc, incoming), mode), emptyMap(), emptyMap()).use { plan ->
                assertEquals(if (mode in setOf(DesktopBackupRestoreMode.UPDATE_EXISTING, DesktopBackupRestoreMode.ADD_COPIES))
                    pc.getValue("entities/novelai_history_fold_preferences/0.json")
                    else incoming.getValue("entities/novelai_history_fold_preferences/0.json"),
                    plan.entities["entities/novelai_history_fold_preferences/0.json"])
                assertEquals(incoming.getValue("entities/novelai_history_fold_preferences/1.json"),
                    plan.entities["entities/novelai_history_fold_preferences/1.json"])
                assertEquals(mode != DesktopBackupRestoreMode.REPLACE_SELECTED,
                    "entities/novelai_history_fold_preferences/2.json" in plan.entities)
            }
        }
        for (selected in listOf(true, false)) {
            val p = preview(pc, emptyMap(), selected).modePlan(DesktopBackupRestoreMode.REPLACE_SELECTED)
            DesktopMergeDestinationPlan.create(p.selection, emptyMap(), emptyMap()).use { plan ->
                assertEquals(if (selected) emptyMap() else pc, plan.entities)
            }
        }
    }

    @Test fun `actual authenticated import publishes the fold policy without incoming deletion commands and keeps both source sides unchanged`() = runBlocking<Unit> {
        for (mode in DesktopBackupRestoreMode.entries) {
            val base = Files.createTempDirectory("ccb-merge-transient-")
            val pc = Files.createDirectory(base.resolve("pc")); val keys = Files.createDirectory(base.resolve("keys"))
            val source = DesktopDataRootResolution.Resolved(pc, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, base.resolve("bootstrap.json"))
            val bootstrap = DesktopBootstrapSettingsStore(source.bootstrapPath)
            bootstrap.save((bootstrap.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(pc))
            val original = mapOf(fold(0, true), fold(2, true), deletion("pc")).mapValues { it.value.toString().toByteArray() }
            for ((relative, bytes) in original) {
                val path = pc.resolve(relative); Files.createDirectories(path.parent); Files.write(path, bytes)
            }
            val incoming = mapOf(fold(0, false), fold(1, false), deletion("phone")).mapValues { it.value.toString().toByteArray() }
            val wireScope = OfficialWireScope(emptySet(), 0, 0, 0, 0, 0, false, false, true, false)
            val archiveBytes = CensusWireFixtures.bytes(prefs = "{}", files = incoming, code = 88, version = 2, scope = wireScope)
            val archive = Files.write(base.resolve("incoming.cbbackup"), archiveBytes)
            val gate = DesktopDataOperationCoordinator()
            val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(source)).ownership
            val unavailable = DesktopApplicationHomeResult.Unavailable(DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT)
            val service = DesktopMergeImportService(source, gate, InMemoryDesktopSecretStore(), {}, { it() },
                DesktopMigrationDestinationPreparer({ unavailable }, DesktopDataRootOwnership::acquire),
                DesktopBootstrapAuthorityTransaction(storeFactory = { DesktopBootstrapSettingsStore(it) },
                    applicationHomeResolver = { unavailable }, portableRootResolver = DesktopPortableRootResolver()),
                freshAuthority = { DesktopFreshRootAuthority(it, keys, MergeRootSyntheticProtector) })
            try {
                val pending = service.inspect(archive, null)
                assertTrue(pending.data.conflicts.none { it.category == "pending_deletions" })
                val choices = if (mode == DesktopBackupRestoreMode.MERGE_CHOICES)
                    pending.data.conflicts.associate { it.key to DesktopMergePreview.Choice.TAKE_PHONE } else emptyMap()
                val destination = base.resolve("prepared")
                assertEquals(DesktopMergeImportService.Publication.COMMITTED,
                    service.commitModeWithProgress(pending, destination, choices, emptyMap(), true, false, mode) {})
                DesktopFreshRootAuthority(destination, keys, MergeRootSyntheticProtector).verifyAndResolve()
                assertFalse(Files.exists(destination.resolve("entities/pending_deletions/phone.json")))
                assertContentEquals(original.getValue("entities/pending_deletions/pc.json"),
                    Files.readAllBytes(destination.resolve("entities/pending_deletions/pc.json")))
                val preferences = NovelAiStudioStateRepository(JsonFileStorage(destination)).loadHistoryFoldPreferences()
                assertEquals(mode in setOf(DesktopBackupRestoreMode.UPDATE_EXISTING, DesktopBackupRestoreMode.ADD_COPIES),
                    preferences.getValue(0).enabled)
                assertFalse(preferences.getValue(1).enabled)
                assertEquals(mode != DesktopBackupRestoreMode.REPLACE_SELECTED, 2 in preferences)
                for ((relative, bytes) in original) assertContentEquals(bytes, Files.readAllBytes(pc.resolve(relative)))
                assertContentEquals(archiveBytes, Files.readAllBytes(archive))
            } finally {
                service.close(); gate.closeAndDrain(); owner.close(); check(base.toFile().deleteRecursively())
            }
        }
    }
}
