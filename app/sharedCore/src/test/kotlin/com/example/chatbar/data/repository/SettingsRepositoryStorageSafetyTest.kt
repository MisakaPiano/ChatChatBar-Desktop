package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CURRENT_WEB_SEARCH_SETTINGS_VERSION
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.ThemeMode
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SettingsRepositoryStorageSafetyTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("settings-repository-safety-")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun readExistingAppSettingsIsReadOnlyForMissingExistingAndCorruptSingletons() = runTest {
        val caseRoot = root.resolve("read-existing")
        val repository = SettingsRepository(JsonFileStorage(caseRoot))
        assertNull(repository.readExistingAppSettings())
        assertFalse(Files.exists(caseRoot.resolve("entities/app_settings.json")))
        assertFalse(Files.exists(caseRoot.resolve("entities/player_setting.json")))

        repository.saveAppSettings(AppSettings(themeMode = ThemeMode.DARK))
        val path = caseRoot.resolve("entities/app_settings.json")
        val before = Files.readAllBytes(path)
        assertEquals(ThemeMode.DARK, SettingsRepository(JsonFileStorage(caseRoot))
            .readExistingAppSettings()?.themeMode)
        assertTrue(before.contentEquals(Files.readAllBytes(path)))
        assertFalse(Files.exists(caseRoot.resolve("entities/player_setting.json")))

        Files.write(path, "{ malformed".toByteArray())
        val corrupt = Files.readAllBytes(path)
        expectFailure<JsonFileStorage.SingletonReadException> {
            SettingsRepository(JsonFileStorage(caseRoot)).readExistingAppSettings()
        }
        assertTrue(corrupt.contentEquals(Files.readAllBytes(path)))
    }

    @Test
    fun corruptSettingsAreNotResetOrOverwrittenAfterRestart() = runTest {
        for (type in listOf("app_settings", "player_setting")) {
            val caseRoot = Files.createDirectory(root.resolve(type))
            val file = caseRoot.resolve("entities/$type.json")
            Files.createDirectories(file.parent)
            Files.write(file, "{\"unfinished\":".toByteArray())
            val before = Files.readAllBytes(file)
            val storage = JsonFileStorage(caseRoot)
            val repository = SettingsRepository(storage)

            val error = expectFailure<JsonFileStorage.SingletonReadException> { repository.initialize() }

            assertEquals(type, error.entityType)
            assertTrue(error.corrupt)
            assertFalse(repository.isInitialized.first())
            assertTrue(storage.singletonReadFailures.value.containsKey(type))
            expectFailure<JsonFileStorage.SingletonReadException> {
                val restarted = SettingsRepository(JsonFileStorage(caseRoot))
                if (type == "app_settings") restarted.saveAppSettings(AppSettings())
                else restarted.savePlayerSetting(PlayerSetting())
            }
            assertTrue(before.contentEquals(Files.readAllBytes(file)))
        }
    }

    @Test
    fun missingSettingsInitializeNormallyAndConcurrentUpdatesRemainSerialized() = runTest {
        val caseRoot = Files.createDirectory(root.resolve("updates"))
        val repository = SettingsRepository(JsonFileStorage(caseRoot))

        (1..40).map {
            async(Dispatchers.Default) {
                repository.updateAppSettings { settings ->
                    settings.copy(lastSeenChatAt = settings.lastSeenChatAt + 1)
                }
            }
        }.awaitAll()

        assertEquals(40L, repository.currentAppSettings.lastSeenChatAt)
        assertEquals(
            40L,
            SettingsRepository(JsonFileStorage(caseRoot)).getAppSettings().lastSeenChatAt,
        )
        assertTrue(repository.isInitialized.first())
        assertTrue(Files.isRegularFile(caseRoot.resolve("entities/player_setting.json")))
    }

    @Test
    fun initializeRestartForceReloadDraftMergeNormalizationAndPlayerTimestampKeepBaselineBehavior() = runTest {
        val caseRoot = Files.createDirectory(root.resolve("lifecycle"))
        val storage = JsonFileStorage(caseRoot)
        val repository = SettingsRepository(storage)

        repository.initialize()
        val baseline = repository.currentAppSettings
        assertEquals(CURRENT_WEB_SEARCH_SETTINGS_VERSION, baseline.webSearchSettingsVersion)
        assertEquals(AppSettings().defaultModelId, baseline.defaultModelId)
        assertTrue(Files.isRegularFile(caseRoot.resolve("entities/app_settings.json")))
        assertTrue(Files.isRegularFile(caseRoot.resolve("entities/player_setting.json")))

        val updated = repository.updateAppSettings {
            it.copy(defaultModelId = "chat", chatBackgroundImageOpacity = 2f)
        }
        assertEquals("chat", updated.defaultModelId)
        assertEquals(1f, updated.chatBackgroundImageOpacity)
        assertEquals(updated, SettingsRepository(JsonFileStorage(caseRoot)).getAppSettings())

        val external = updated.copy(themeMode = ThemeMode.DARK, lastSeenChatAt = 80L)
        SettingsRepository(JsonFileStorage(caseRoot)).saveAppSettings(external)
        repository.initialize(forceReload = true)
        assertEquals(external, repository.currentAppSettings)

        val draft = external.copy(defaultModelId = "chosen")
        val newer = external.copy(lastSeenChatAt = 120L)
        SettingsRepository(JsonFileStorage(caseRoot)).saveAppSettings(newer)
        repository.initialize(forceReload = true)
        val merged = repository.saveAppSettingsDraft(external, draft)
        assertEquals("chosen", merged.defaultModelId)
        assertEquals(120L, merged.lastSeenChatAt)

        repository.completeTutorial(3)
        repository.completeTutorial(2)
        assertEquals(3, repository.currentAppSettings.tutorialVersion)

        val beforeSave = System.currentTimeMillis()
        repository.savePlayerSetting(PlayerSetting(playerName = "Player", updatedAt = 1L))
        val player = repository.getPlayerSetting()
        assertEquals("Player", player.playerName)
        assertTrue(player.updatedAt >= beforeSave)
        assertEquals(player, SettingsRepository(JsonFileStorage(caseRoot)).getPlayerSetting())
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.simpleName}")
    }
}
