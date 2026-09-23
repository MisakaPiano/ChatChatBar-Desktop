package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.PlayerSetting
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryStorageSafetyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun corruptSettingsAreNotResetOrOverwrittenAfterRestart() = runTest {
        for (type in listOf("app_settings", "player_setting")) {
            val root = temp.newFolder().toPath()
            val file = root.resolve("entities/$type.json")
            Files.createDirectories(file.parent)
            Files.write(file, "{\"unfinished\":".toByteArray())
            val before = Files.readAllBytes(file)
            val storage = JsonFileStorage(root)
            val repository = SettingsRepository(storage)

            val error = expectFailure<JsonFileStorage.SingletonReadException> { repository.initialize() }

            assertEquals(type, error.entityType)
            assertTrue(error.corrupt)
            assertFalse(repository.isInitialized.first())
            assertTrue(storage.singletonReadFailures.value.containsKey(type))
            expectFailure<JsonFileStorage.SingletonReadException> {
                val restarted = SettingsRepository(JsonFileStorage(root))
                if (type == "app_settings") restarted.saveAppSettings(AppSettings())
                else restarted.savePlayerSetting(PlayerSetting())
            }
            assertTrue(before.contentEquals(Files.readAllBytes(file)))
        }
    }

    @Test
    fun missingSettingsInitializeNormallyAndConcurrentUpdatesRemainSerialized() = runTest {
        val root = temp.newFolder().toPath()
        val repository = SettingsRepository(JsonFileStorage(root))

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
            SettingsRepository(JsonFileStorage(root)).getAppSettings().lastSeenChatAt,
        )
        assertTrue(repository.isInitialized.first())
        assertTrue(Files.isRegularFile(root.resolve("entities/player_setting.json")))
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}