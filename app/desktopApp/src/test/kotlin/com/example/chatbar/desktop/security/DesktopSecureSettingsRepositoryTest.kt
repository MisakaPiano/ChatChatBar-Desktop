package com.example.chatbar.desktop.security

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.data.repository.SettingsRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class DesktopSecureSettingsRepositoryTest {
    private lateinit var root: Path
    private lateinit var secrets: InMemoryDesktopSecretStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("desktop-secure-settings-")
        secrets = InMemoryDesktopSecretStore()
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `settings save update draft restart and clear keep JSON sanitized and runtime hydrated`() = runTest {
        val repository = secureRepository(root)
        repository.saveAppSettings(
            AppSettings(
                siliconFlowApiKey = "fake-test-key",
                defaultModelId = "model-a",
                themeMode = ThemeMode.DARK,
                lastSeenChatAt = 1L,
            ),
        )

        assertEquals("fake-test-key", repository.currentAppSettings.siliconFlowApiKey)
        assertEquals("fake-test-key", secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
        assertSanitizedSettingsJson(root, forbidden = listOf("fake-test-key"))

        val restarted = secureRepository(root)
        val baseline = restarted.getAppSettings()
        assertEquals("fake-test-key", baseline.siliconFlowApiKey)
        assertEquals("model-a", baseline.defaultModelId)
        assertEquals(ThemeMode.DARK, baseline.themeMode)

        restarted.updateAppSettings { current ->
            current.copy(siliconFlowApiKey = "fake-updated-key", lastSeenChatAt = 2L)
        }
        assertEquals("fake-updated-key", restarted.currentAppSettings.siliconFlowApiKey)

        val draftBaseline = restarted.currentAppSettings
        val draft = draftBaseline.copy(
            siliconFlowApiKey = "fake-draft-key",
            defaultModelId = "model-from-draft",
        )
        restarted.updateAppSettings { current -> current.copy(lastSeenChatAt = 3L) }
        val merged = restarted.saveAppSettingsDraft(draftBaseline, draft)
        assertEquals("fake-draft-key", merged.siliconFlowApiKey)
        assertEquals("model-from-draft", merged.defaultModelId)
        assertEquals(3L, merged.lastSeenChatAt)
        assertSanitizedSettingsJson(
            root,
            forbidden = listOf("fake-test-key", "fake-updated-key", "fake-draft-key"),
        )

        restarted.updateAppSettings { current -> current.copy(siliconFlowApiKey = "") }
        assertNull(secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
        assertEquals("", secureRepository(root).getAppSettings().siliconFlowApiKey)
        assertSanitizedSettingsJson(root, forbidden = listOf("fake-draft-key"))
    }

    @Test
    fun `same global secret hydrates sanitized settings after app-data root change`() = runTest {
        val otherRoot = Files.createTempDirectory("desktop-secure-settings-other-")
        try {
            secureRepository(root).saveAppSettings(
                AppSettings(siliconFlowApiKey = "fake-test-key", defaultModelId = "model-a"),
            )
            SettingsRepository(JsonFileStorage(otherRoot)).saveAppSettings(
                AppSettings(defaultModelId = "model-b"),
            )

            val fromOtherRoot = secureRepository(otherRoot).getAppSettings()

            assertEquals("fake-test-key", fromOtherRoot.siliconFlowApiKey)
            assertEquals("model-b", fromOtherRoot.defaultModelId)
            assertSanitizedSettingsJson(root, forbidden = listOf("fake-test-key"))
            assertSanitizedSettingsJson(otherRoot, forbidden = listOf("fake-test-key"))
        } finally {
            otherRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing secret hydrates blank while secret failure remains explicit`() = runTest {
        SettingsRepository(JsonFileStorage(root)).saveAppSettings(AppSettings(defaultModelId = "model-a"))
        assertEquals("", secureRepository(root).getAppSettings().siliconFlowApiKey)

        secrets.failNextLoad()
        val error = assertFailsWith<DesktopSecretStoreException> {
            secureRepository(root).getAppSettings()
        }
        assertEquals(DesktopSecretStoreFailureKind.STORAGE_FAILURE, error.kind)
    }

    @Test
    fun `unexpected plaintext settings credential fails closed without rewriting JSON`() = runTest {
        SettingsRepository(JsonFileStorage(root)).saveAppSettings(
            AppSettings(siliconFlowApiKey = "fake-plaintext-key"),
        )
        val path = settingsPath(root)
        val before = Files.readAllBytes(path)

        assertFailsWith<UnsafeDesktopPlaintextCredentialException> {
            secureRepository(root).getAppSettings()
        }

        assertTrue(before.contentEquals(Files.readAllBytes(path)))
        assertNull(secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
    }

    @Test
    fun `failed settings entity persistence restores prior secure credential`() = runTest {
        val key = DesktopCredentialKey.SiliconFlowApiKey
        secrets.values[key] = "fake-existing-key"
        val policy = DesktopSettingsCredentialPersistencePolicy(secrets)

        val failure = assertFailsWith<IOException> {
            policy.persist(AppSettings(siliconFlowApiKey = "fake-replacement-key")) {
                throw IOException("Injected entity write failure")
            }
        }

        assertEquals("Injected entity write failure", failure.message)
        assertEquals("fake-existing-key", secrets.values[key])
    }

    @Test
    fun `failed settings rollback surfaces compound consistency failure`() = runTest {
        val key = DesktopCredentialKey.SiliconFlowApiKey
        secrets.values[key] = "fake-existing-key"
        secrets.failSave(call = 2)
        val policy = DesktopSettingsCredentialPersistencePolicy(secrets)

        val failure = assertFailsWith<DesktopCredentialConsistencyException> {
            policy.persist(AppSettings(siliconFlowApiKey = "fake-replacement-key")) {
                throw IOException("Injected entity write failure")
            }
        }

        assertIs<IOException>(failure.cause)
        assertEquals(1, failure.suppressed.size)
        assertIs<DesktopSecretStoreException>(failure.suppressed.single())
    }

    private fun secureRepository(dataRoot: Path) = SettingsRepository(
        storage = JsonFileStorage(dataRoot),
        credentialPersistencePolicy = DesktopSettingsCredentialPersistencePolicy(secrets),
    )

    private fun assertSanitizedSettingsJson(dataRoot: Path, forbidden: List<String>) {
        val raw = settingsPath(dataRoot).readText()
        forbidden.forEach { secret -> assertFalse(raw.contains(secret)) }
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), raw)
        assertEquals("", decoded.siliconFlowApiKey)
    }

    private fun settingsPath(dataRoot: Path): Path = dataRoot.resolve("entities/app_settings.json")
}
