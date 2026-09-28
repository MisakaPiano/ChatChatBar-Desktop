package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopUiLanguageControllerTest {
    @Test
    fun `default and missing root load are Chinese and zero write`() = runBlocking {
        withRoot { root ->
            val store = DesktopSettingsStore(root, DesktopDataOperationCoordinator())
            val controller = DesktopUiLanguageController(store)
            assertEquals(DesktopUiLanguage.ZH_CN, controller.language.value)
            controller.load()
            assertEquals(DesktopUiLanguage.ZH_CN, controller.language.value)
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `container startup and default language do not write Desktop or business settings`() = runBlocking {
        withRoot { root ->
            val container = DesktopAppContainer(
                DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
                    root.resolveSibling("bootstrap.json")),
                secretStoreFactory = { InMemoryDesktopSecretStore() },
            )
            try {
                container.automaticBackupRuntime.initialize()
                container.uiLanguageController.load()
                container.primaryChatController.refresh()
                assertEquals(DesktopUiLanguage.ZH_CN, container.uiLanguageController.language.value)
                assertFalse(Files.exists(root.resolve("desktop-settings.json")))
                assertFalse(Files.exists(root.resolve("entities/app_settings.json")))
                if (Files.exists(root)) {
                    assertTrue(Files.walk(root).use { it.filter(Files::isRegularFile).toList() }.isEmpty())
                }
            } finally {
                container.close()
            }
        }
    }

    @Test
    fun `legacy settings without language load Chinese and English round trips across restart`() = runBlocking {
        withRoot { root ->
            Files.createDirectories(root)
            val store = DesktopSettingsStore(root, DesktopDataOperationCoordinator())
            store.settingsPath.writeText("""{"formatVersion":1,"futureField":"keep","automaticBackup":{"enabled":true,"minimumBackupInterval":"PT24H","maximumSnapshotCount":9,"checkInterval":"PT1H","futureNested":"keep"}}""")
            val first = DesktopUiLanguageController(store)
            first.load()
            assertEquals(DesktopUiLanguage.ZH_CN, first.language.value)
            first.select(DesktopUiLanguage.EN)
            assertEquals(DesktopUiLanguage.EN, first.language.value)
            val second = DesktopUiLanguageController(store)
            second.load()
            assertEquals(DesktopUiLanguage.EN, second.language.value)
            val raw = Json.parseToJsonElement(store.settingsPath.readText()).jsonObject
            assertEquals("EN", raw.getValue("uiLanguage").jsonPrimitive.content)
            assertEquals("keep", raw.getValue("futureField").jsonPrimitive.content)
            val backup = raw.getValue("automaticBackup").jsonObject
            assertEquals("true", backup.getValue("enabled").jsonPrimitive.content)
            assertEquals("9", backup.getValue("maximumSnapshotCount").jsonPrimitive.content)
            assertEquals("keep", backup.getValue("futureNested").jsonPrimitive.content)
        }
    }

    @Test
    fun `language save never reads or writes shared AppSettings`() = runBlocking {
        withRoot { root ->
            val container = DesktopAppContainer(
                DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
                    root.resolveSibling("bootstrap.json")),
                secretStoreFactory = { InMemoryDesktopSecretStore() },
            )
            try {
                container.uiLanguageController.select(DesktopUiLanguage.EN)
                assertTrue(Files.exists(root.resolve("desktop-settings.json")))
                assertFalse(Files.exists(root.resolve("entities/app_settings.json")))
                assertIs<DesktopSettingsLoadResult.Loaded>(DesktopSettingsStore(root,
                    DesktopDataOperationCoordinator()).load())
            } finally {
                container.close()
            }
        }
    }

    @Test
    fun `key navigation and chat strings exist in both languages`() {
        listOf(DesktopUiText.CHAT, DesktopUiText.NEW_CHAT, DesktopUiText.SESSION_SETTINGS,
            DesktopUiText.RESTORE_BUILT_IN, DesktopUiText.GLOBAL_API_KEY, DesktopUiText.LANGUAGE).forEach { key ->
            assertTrue(DesktopUiStrings(DesktopUiLanguage.ZH_CN)(key).isNotBlank())
            assertTrue(DesktopUiStrings(DesktopUiLanguage.EN)(key).isNotBlank())
        }
    }

    private suspend fun withRoot(block: suspend (java.nio.file.Path) -> Unit) {
        val parent = Files.createTempDirectory("desktop-ui-language-")
        try { block(parent.resolve("app-data")) } finally { parent.toFile().deleteRecursively() }
    }
}
