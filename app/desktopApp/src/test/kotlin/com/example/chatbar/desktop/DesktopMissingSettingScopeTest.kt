package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopMissingSettingScopeTest {
    @Test fun `actual scoped modes use defaults for selected missing settings and own last seen fields without resetting unselected values`() = runBlocking<Unit> {
        val unavailable = DesktopApplicationHomeResult.Unavailable(DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT)
        val defaults = AppSettings()
        for (mode in listOf(DesktopBackupRestoreMode.UPDATE_EXISTING, DesktopBackupRestoreMode.ADD_COPIES,
            DesktopBackupRestoreMode.REPLACE_SELECTED)) for (scenario in 0..2) {
            val base = Files.createTempDirectory("missing-setting-scope-")
            val root = Files.createDirectory(base.resolve("pc")); val keys = Files.createDirectory(base.resolve("keys"))
            val source = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, base.resolve("bootstrap.json"))
            val bootstrap = DesktopBootstrapSettingsStore(source.bootstrapPath)
            bootstrap.save((bootstrap.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(root))
            val gate = DesktopDataOperationCoordinator()
            val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(source)).ownership
            val original = AppSettings(defaultModelId = "local-model", themeMode = ThemeMode.DARK, chatBubbleFontScale = 1.4f,
                lastSeenChatAt = 42, lastSeenMomentsAt = 43)
            val settingsFile = root.resolve("entities/app_settings.json")
            Files.createDirectories(settingsFile.parent)
            val settingsBytes = backupJson.encodeToString(AppSettings.serializer(), original).toByteArray()
            Files.write(settingsFile, settingsBytes)
            JsonFileStorage(root).saveEntity("model_configs", "local-model", ModelConfig("local-model", "Local model",
                "https://synthetic.invalid/v1", "", "synthetic", createdAt = 1), ModelConfig.serializer())
            val incoming = linkedMapOf("entities/character_cards/c.json" to backupJson.encodeToString(CharacterCard.serializer(),
                CharacterCard("c", "Incoming", createdAt = 1, updatedAt = 1)).toByteArray())
            if (scenario == 1) incoming["entities/chat_sessions/t.json"] = backupJson.encodeToString(ChatSession.serializer(),
                ChatSession("t", "c", "Incoming session", createdAt = 1, updatedAt = 1)).toByteArray()
            if (scenario != 0) incoming["entities/app_settings.json"] = buildJsonObject {
                put("chatBubbleFontScale", 1.6)
                put("lastSeenChatAt", 21)
                // An unselected incoming value must never replace its PC counterpart.
                if (scenario == 2) { put("themeMode", "LIGHT"); put("lastSeenMomentsAt", 22) }
            }.toString().toByteArray()
            val fields = if (scenario == 2) emptySet() else setOf("themeMode", "defaultModelId", "chatBubbleFontScale")
            val sessionCount = if (scenario == 1) 1 else 0
            val scope = OfficialWireScope(fields, 1, sessionCount, sessionCount, sessionCount, sessionCount,
                false, false, false, scenario == 1, sessionDefaultChoice = OfficialWireSessionChoice())
            val archive = Files.write(base.resolve("phone.cbbackup"), CensusWireFixtures.bytes(prefs = "{}", files = incoming,
                code = 88, version = 2, scope = scope))
            val archiveBytes = Files.readAllBytes(archive)
            val service = DesktopMergeImportService(source, gate, InMemoryDesktopSecretStore(), {}, { it() },
                DesktopMigrationDestinationPreparer({ unavailable }, DesktopDataRootOwnership::acquire),
                DesktopBootstrapAuthorityTransaction(storeFactory = { DesktopBootstrapSettingsStore(it) },
                    applicationHomeResolver = { unavailable }, portableRootResolver = DesktopPortableRootResolver()),
                freshAuthority = { DesktopFreshRootAuthority(it, keys, MergeRootSyntheticProtector) })
            try {
                val preview = service.inspect(archive, null)
                service.previewMode(preview, mode)
                val destination = base.resolve("prepared")
                assertEquals(DesktopMergeImportService.Publication.COMMITTED,
                    service.commitModeWithProgress(preview, destination, emptyMap(), emptyMap(), true, false, mode) {})
                DesktopFreshRootAuthority(destination, keys, MergeRootSyntheticProtector).verifyAndResolve()
                val settings = assertNotNull(JsonFileStorage(destination).loadSingleton("app_settings", AppSettings.serializer()))
                assertEquals(if (scenario == 2) original.themeMode else defaults.themeMode, settings.themeMode)
                assertEquals(if (scenario == 2) original.defaultModelId else defaults.defaultModelId, settings.defaultModelId)
                assertEquals(when (scenario) { 0 -> defaults.chatBubbleFontScale; 1 -> 1.6f; else -> original.chatBubbleFontScale }, settings.chatBubbleFontScale)
                assertEquals(if (scenario == 1) 21 else original.lastSeenChatAt, settings.lastSeenChatAt)
                assertEquals(if (scenario == 1) defaults.lastSeenMomentsAt else original.lastSeenMomentsAt, settings.lastSeenMomentsAt)
                assertContentEquals(settingsBytes, Files.readAllBytes(settingsFile))
                assertContentEquals(archiveBytes, Files.readAllBytes(archive))
            } finally {
                service.close(); gate.closeAndDrain(); owner.close(); check(base.toFile().deleteRecursively())
            }
        }
    }

    @Test fun `manual choices and legacy scope absence do not acquire modern missing setting defaults`() {
        val pc = mapOf("entities/app_settings.json" to buildJsonObject { put("themeMode", "DARK"); put("lastSeenChatAt", 42) })
        val scope = InspectionScope(setOf("themeMode"), 0, 0, 0, 0, 0, false, false, false, false)
        val manual = DesktopMergePreview.create(pc, emptyMap(), emptyMap(), emptyMap(), scope).select(emptyMap())
        assertTrue(manual.defaultSettingFields.isEmpty())
        DesktopMergeDestinationPlan.create(manual, emptyMap(), emptyMap()).use {
            assertEquals(pc, it.entities)
        }
        for (mode in listOf(DesktopBackupRestoreMode.UPDATE_EXISTING, DesktopBackupRestoreMode.ADD_COPIES,
            DesktopBackupRestoreMode.REPLACE_SELECTED)) {
            assertTrue(DesktopMergePreview.create(pc, emptyMap(), emptyMap(), emptyMap()).modePlan(mode).selection.defaultSettingFields.isEmpty())
        }
    }
}
