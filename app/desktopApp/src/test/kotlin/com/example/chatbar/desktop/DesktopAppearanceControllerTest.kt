package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.desktop.security.DesktopCredentialKey
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.appearance.ThemeColorHsv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopAppearanceControllerTest {
    @Test
    fun `missing AppSettings loads shared appearance defaults without creating files`() = runBlocking {
        withContainer { root, container, _ ->
            container.appearanceController.load()
            assertEquals(ThemeMode.SYSTEM, container.appearanceController.state.value.themeMode)
            assertEquals(DefaultThemeColorHsv, container.appearanceController.state.value.themeColor)
            assertFalse(Files.exists(root.resolve("entities/app_settings.json")))
            assertFalse(Files.exists(root.resolve("entities/player_setting.json")))
        }
    }

    @Test
    fun `existing LIGHT and DARK load without rewriting persisted settings`() = runBlocking {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            withContainer { root, container, _ ->
                container.settingsRepository.saveAppSettings(AppSettings(themeMode = mode))
                val path = root.resolve("entities/app_settings.json")
                val before = path.readBytes()
                val restarted = newContainer(root, InMemoryDesktopSecretStore())
                try {
                    restarted.appearanceController.load()
                    assertEquals(mode, restarted.appearanceController.state.value.themeMode)
                    assertContentEquals(before, path.readBytes())
                } finally { restarted.close() }
            }
        }
    }

    @Test
    fun `explicit modes and color persist across restart and preserve concurrent AppSettings fields`() = runBlocking {
        withContainer { root, container, secrets ->
            container.settingsRepository.saveAppSettings(AppSettings(lastSeenChatAt = 10L))
            container.appearanceController.load()
            container.settingsRepository.updateAppSettings { it.copy(lastSeenChatAt = 99L) }
            container.appearanceController.setMode(ThemeMode.LIGHT)
            container.appearanceController.setMode(ThemeMode.DARK)
            container.appearanceController.setMode(ThemeMode.SYSTEM)
            val color = ThemeColorHsv.fromRgb(0.25f, 0.42f, 0.91f)
            container.appearanceController.setColor(color)
            val persisted = container.settingsRepository.getAppSettings()
            assertEquals(ThemeMode.SYSTEM, persisted.themeMode)
            assertEquals(color.normalized(), persisted.themeColor)
            assertEquals(99L, persisted.lastSeenChatAt)
            val restarted = newContainer(root, secrets)
            try {
                restarted.appearanceController.load()
                assertEquals(ThemeMode.SYSTEM, restarted.appearanceController.state.value.themeMode)
                assertEquals(color.normalized(), restarted.appearanceController.state.value.themeColor)
            } finally { restarted.close() }
        }
    }

    @Test
    fun `theme mutation retains secure global key and sanitized JSON`() = runBlocking {
        withContainer { root, container, secrets ->
            container.settingsRepository.saveAppSettings(AppSettings(siliconFlowApiKey = "fake-theme-key"))
            container.appearanceController.setMode(ThemeMode.DARK)
            container.appearanceController.setColor(ThemeColorHsv.fromRgb(0.8f, 0.3f, 0.2f))
            assertNull(container.appearanceController.state.value.error)
            assertEquals("fake-theme-key", secrets.values[DesktopCredentialKey.SiliconFlowApiKey])
            assertEquals("fake-theme-key", container.settingsRepository.getAppSettings().siliconFlowApiKey)
            val raw = root.resolve("entities/app_settings.json").readText()
            assertFalse(raw.contains("fake-theme-key"))
            assertTrue(raw.contains("\"siliconFlowApiKey\": \"\""))
            assertFalse(container.appearanceController.state.value.toString().contains("fake-theme-key"))
        }
    }

    @Test
    fun `SYSTEM resolves simulated system appearance and palettes react to HSV`() {
        val light = desktopSemanticColors(ThemeMode.SYSTEM, DefaultThemeColorHsv, systemDark = false)
        val dark = desktopSemanticColors(ThemeMode.SYSTEM, DefaultThemeColorHsv, systemDark = true)
        assertTrue(light.background != dark.background)
        assertEquals(light, desktopSemanticColors(ThemeMode.LIGHT, DefaultThemeColorHsv, systemDark = true))
        assertEquals(dark, desktopSemanticColors(ThemeMode.DARK, DefaultThemeColorHsv, systemDark = false))
        val changed = desktopSemanticColors(ThemeMode.LIGHT, ThemeColorHsv.fromRgb(0.9f, 0.3f, 0.2f), false)
        assertTrue(changed.primary != light.primary)
        assertTrue(changed.accent != light.accent)
        listOf(DefaultThemeColorHsv, ThemeColorHsv.fromRgb(0.9f, 0.7f, 0.2f),
            ThemeColorHsv(Float.NaN, 2f, -1f)).forEach { color ->
            val palette = desktopSemanticColors(ThemeMode.LIGHT, color, false)
            assertTrue(desktopContrastRatio(palette.primary, palette.primaryForeground) >= 4.5)
        }
    }

    private suspend fun withContainer(block: suspend (Path, DesktopAppContainer, InMemoryDesktopSecretStore) -> Unit) {
        val parent = Files.createTempDirectory("desktop-appearance-")
        val root = parent.resolve("app-data")
        val secrets = InMemoryDesktopSecretStore()
        val container = newContainer(root, secrets)
        try { block(root, container, secrets) } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun newContainer(root: Path, secrets: InMemoryDesktopSecretStore) = DesktopAppContainer(
        DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            root.resolveSibling("bootstrap.json")),
        secretStoreFactory = { secrets },
    )
}
