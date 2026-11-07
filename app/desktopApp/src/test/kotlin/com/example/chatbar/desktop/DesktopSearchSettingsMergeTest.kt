package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.desktop.backup.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopSearchSettingsMergeTest {
    @Test fun `actual scoped modes normalize each settings source before combining selected fields and reopening settings repository`() = runBlocking<Unit> {
        val unavailable = DesktopApplicationHomeResult.Unavailable(DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT)
        val current = CURRENT_WEB_SEARCH_SETTINGS_VERSION
        val scenarios = listOf(current to 0, 0 to current, 4 to 3, current to current)
        for (mode in listOf(DesktopBackupRestoreMode.UPDATE_EXISTING, DesktopBackupRestoreMode.ADD_COPIES,
            DesktopBackupRestoreMode.REPLACE_SELECTED)) for ((pcVersion, phoneVersion) in scenarios) {
            val base = Files.createTempDirectory("search-settings-merge-")
            val root = Files.createDirectory(base.resolve("pc")); val keys = Files.createDirectory(base.resolve("keys"))
            val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, base.resolve("bootstrap.json"))
            val bootstrap = DesktopBootstrapSettingsStore(resolved.bootstrapPath)
            bootstrap.save((bootstrap.load() as DesktopBootstrapLoadResult.Missing).document, DesktopDataRootSelection.custom(root))
            val gate = DesktopDataOperationCoordinator()
            val owner = assertIs<DesktopDataRootOwnershipResult.Acquired>(DesktopDataRootOwnership.acquire(resolved)).ownership
            val pc = AppSettings(webSearchSettingsVersion = pcVersion, webSearchEnabled = false,
                characterAutoFillWebSearchEnabled = false, characterRewriteWebSearchEnabled = pcVersion == 4,
                characterAutoFillResearchSourceMode = CharacterResearchSourceMode.MANUAL_URLS,
                characterRewriteResearchSourceMode = CharacterResearchSourceMode.MANUAL_URLS,
                worldBookAiResearchSourceMode = CharacterResearchSourceMode.MANUAL_URLS, webSearchMaxResultsPerQuery = 8,
                themeMode = ThemeMode.DARK, chatBubbleFontScale = 1.4f)
            val incoming = AppSettings(webSearchSettingsVersion = phoneVersion, webSearchEnabled = false,
                characterAutoFillWebSearchEnabled = false, characterRewriteWebSearchEnabled = false,
                characterAutoFillResearchSourceMode = CharacterResearchSourceMode.MANUAL_URLS,
                characterRewriteResearchSourceMode = CharacterResearchSourceMode.NONE,
                webSearchMaxResultsPerQuery = 4, themeMode = ThemeMode.LIGHT, chatBubbleFontScale = 1.6f)
            val settingsPath = root.resolve("entities/app_settings.json")
            Files.createDirectories(settingsPath.parent)
            val pcBytes = backupJson.encodeToString(AppSettings.serializer(), pc).toByteArray()
            Files.write(settingsPath, pcBytes)
            val phoneBytes = backupJson.encodeToString(AppSettings.serializer(), incoming).toByteArray()
            val fields = setOf("characterAutoFillWebSearchEnabled", "characterAutoFillResearchSourceMode", "webSearchMaxResultsPerQuery")
            val scope = OfficialWireScope(fields, 0, 0, 0, 0, 0, false, false, false, false)
            val archive = Files.write(base.resolve("phone.cbbackup"), CensusWireFixtures.bytes(prefs = "{}",
                files = mapOf("entities/app_settings.json" to phoneBytes), code = 88, version = 2, scope = scope))
            val archiveBytes = Files.readAllBytes(archive)
            val service = DesktopMergeImportService(resolved, gate, InMemoryDesktopSecretStore(), {}, { it() },
                DesktopMigrationDestinationPreparer({ unavailable }, DesktopDataRootOwnership::acquire),
                DesktopBootstrapAuthorityTransaction(storeFactory = { DesktopBootstrapSettingsStore(it) },
                    applicationHomeResolver = { unavailable }, portableRootResolver = DesktopPortableRootResolver()),
                freshAuthority = { DesktopFreshRootAuthority(it, keys, MergeRootSyntheticProtector) })
            try {
                val preview = service.inspect(archive, null)
                service.previewMode(preview, mode)
                val target = base.resolve("prepared")
                assertEquals(DesktopMergeImportService.Publication.COMMITTED,
                    service.commitModeWithProgress(preview, target, emptyMap(), emptyMap(), true, false, mode) {})
                DesktopFreshRootAuthority(target, keys, MergeRootSyntheticProtector).verifyAndResolve()
                val normalizedPc = pc.withCurrentWebSearchDefaults()
                val normalizedPhone = incoming.withCurrentWebSearchDefaults()
                val storage = JsonFileStorage(target)
                val persisted = assertNotNull(storage.loadSingleton("app_settings", AppSettings.serializer()))
                assertEquals(current, persisted.webSearchSettingsVersion)
                assertEquals(normalizedPhone.characterAutoFillWebSearchEnabled, persisted.characterAutoFillWebSearchEnabled)
                assertEquals(normalizedPhone.characterAutoFillResearchSourceMode, persisted.characterAutoFillResearchSourceMode)
                assertEquals(normalizedPhone.webSearchMaxResultsPerQuery, persisted.webSearchMaxResultsPerQuery)
                assertEquals(normalizedPc.webSearchEnabled, persisted.webSearchEnabled)
                assertEquals(normalizedPc.characterRewriteWebSearchEnabled, persisted.characterRewriteWebSearchEnabled)
                assertEquals(normalizedPc.characterRewriteResearchSourceMode, persisted.characterRewriteResearchSourceMode)
                assertEquals(pc.worldBookAiResearchSourceMode, persisted.worldBookAiResearchSourceMode)
                assertEquals(pc.themeMode, persisted.themeMode)
                assertEquals(pc.chatBubbleFontScale, persisted.chatBubbleFontScale)
                val beforeRead = Files.readAllBytes(target.resolve("entities/app_settings.json"))
                assertEquals(persisted, SettingsRepository(storage).getAppSettings())
                assertContentEquals(beforeRead, Files.readAllBytes(target.resolve("entities/app_settings.json")))
                assertContentEquals(pcBytes, Files.readAllBytes(settingsPath))
                assertContentEquals(archiveBytes, Files.readAllBytes(archive))
            } finally {
                service.close(); gate.closeAndDrain(); owner.close(); check(base.toFile().deleteRecursively())
            }
        }
    }

    @Test fun `no incoming settings activity manual choices and absent legacy scope retain raw settings decisions`() {
        val settings = backupJson.encodeToJsonElement(AppSettings.serializer(), AppSettings(webSearchSettingsVersion = 0,
            webSearchEnabled = false, characterAutoFillWebSearchEnabled = false, webSearchMaxResultsPerQuery = 8)).jsonObject
        val pc = mapOf("entities/app_settings.json" to settings)
        val scope = InspectionScope(emptySet(), 0, 0, 0, 0, 0, false, false, false, false)
        val preview = DesktopMergePreview.create(pc, emptyMap(), emptyMap(), emptyMap(), scope)
        val selected = preview.select(emptyMap())
        DesktopMergeDestinationPlan.create(selected, emptyMap(), emptyMap()).use { assertEquals(settings, it.entities["entities/app_settings.json"]) }
        for (mode in listOf(DesktopBackupRestoreMode.UPDATE_EXISTING, DesktopBackupRestoreMode.ADD_COPIES)) {
            DesktopMergeDestinationPlan.create(preview.modePlan(mode).selection, emptyMap(), emptyMap()).use {
                assertEquals(settings, it.entities["entities/app_settings.json"])
            }
            val legacy = DesktopMergePreview.create(pc, emptyMap(), emptyMap(), emptyMap()).modePlan(mode)
            DesktopMergeDestinationPlan.create(legacy.selection, emptyMap(), emptyMap()).use { assertEquals(settings, it.entities["entities/app_settings.json"]) }
        }
    }
}
