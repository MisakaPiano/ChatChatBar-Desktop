package com.example.chatbar.desktop

import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopSettingsCoherenceTest {
    @Test
    fun `legacy missing color style remains neutral and read-only until explicit style action`() = runBlocking {
        val seed = """{"formatVersion":1,"automaticBackup":{"enabled":false,"minimumBackupInterval":"PT24H","maximumSnapshotCount":7,"checkInterval":"PT1H"},"future":"keep"}"""
        withContainer(seed = seed) { root, container ->
            val path = root.resolve("desktop-settings.json")
            val before = path.readBytes()
            container.appearanceController.load()
            assertEquals(DesktopColorStyle.NEUTRAL, container.appearanceController.state.value.colorStyle)
            assertContentEquals(before, path.readBytes())
            container.appearanceController.setColorStyle(DesktopColorStyle.CUSTOM_ACCENT)
            assertEquals(DesktopColorStyle.CUSTOM_ACCENT, loaded(root).colorStyle)
            assertEquals("keep", Json.parseToJsonElement(path.toFile().readText()).jsonObject
                .getValue("future").jsonPrimitive.content)
        }
    }

    @Test
    fun `language survives a later backup setting mutation from stale runtime state`() = runBlocking {
        withContainer { root, container ->
            container.automaticBackupRuntime.initialize()
            container.uiLanguageController.select(DesktopUiLanguage.EN)
            val result = container.automaticBackupRuntime.applySettings(
                DesktopSettings(automaticBackup = DesktopAutomaticBackupSettings(maximumSnapshotCount = 11)),
            )
            assertIs<DesktopSettingsApplyResult.Applied>(result)
            assertEquals(DesktopUiLanguage.EN, loaded(root).uiLanguage)
            assertEquals(11, loaded(root).automaticBackup.maximumSnapshotCount)
            assertEquals(DesktopUiLanguage.EN, container.automaticBackupRuntime.state.value.effectiveSettings?.uiLanguage)
        }
    }

    @Test
    fun `backup mutation survives a later language change`() = runBlocking {
        withContainer { root, container ->
            container.automaticBackupRuntime.initialize()
            assertIs<DesktopSettingsApplyResult.Applied>(container.automaticBackupRuntime.applySettings(
                DesktopSettings(automaticBackup = DesktopAutomaticBackupSettings(maximumSnapshotCount = 13)),
            ))
            container.uiLanguageController.select(DesktopUiLanguage.EN)
            assertEquals(13, loaded(root).automaticBackup.maximumSnapshotCount)
            assertEquals(DesktopUiLanguage.EN, loaded(root).uiLanguage)
        }
    }

    @Test
    fun `interleaved field-owner saves retain both outcomes and unknown JSON`() = runBlocking {
        withContainer(seed = """{"formatVersion":1,"futureRoot":"retain","automaticBackup":{"enabled":false,"minimumBackupInterval":"PT24H","maximumSnapshotCount":7,"checkInterval":"PT1H","futureNested":"retain"}}""") { root, container ->
            container.automaticBackupRuntime.initialize()
            coroutineScope {
                listOf(
                    async { container.uiLanguageController.select(DesktopUiLanguage.EN) },
                    async { container.appearanceController.setColorStyle(DesktopColorStyle.CCB_NATIVE) },
                    async { container.automaticBackupRuntime.applySettings(
                        DesktopSettings(automaticBackup = DesktopAutomaticBackupSettings(maximumSnapshotCount = 17)),
                    ) },
                ).awaitAll()
            }
            val settings = loaded(root)
            assertEquals(DesktopUiLanguage.EN, settings.uiLanguage)
            assertEquals(17, settings.automaticBackup.maximumSnapshotCount)
            assertEquals(DesktopColorStyle.CCB_NATIVE, settings.colorStyle)
            val json = Json.parseToJsonElement(root.resolve("desktop-settings.json").toFile().readText()).jsonObject
            assertEquals("retain", json.getValue("futureRoot").jsonPrimitive.content)
            assertEquals("retain", json.getValue("automaticBackup").jsonObject.getValue("futureNested").jsonPrimitive.content)
        }
    }

    @Test
    fun `corrupt Desktop settings reject both owners without rewriting bytes`() = runBlocking {
        val seed = "{ malformed"
        withContainer(seed = seed) { root, container ->
            val path = root.resolve("desktop-settings.json")
            val original = path.readBytes()
            container.automaticBackupRuntime.initialize()
            container.uiLanguageController.select(DesktopUiLanguage.EN)
            assertNotNull(container.uiLanguageController.error.value)
            assertIs<DesktopSettingsApplyResult.Failed>(container.automaticBackupRuntime.applySettings(
                DesktopSettings(automaticBackup = DesktopAutomaticBackupSettings(maximumSnapshotCount = 10)),
            ))
            assertContentEquals(original, path.readBytes())
        }
    }

    @Test
    fun `invalid persisted color style fails closed without rewrite`() = runBlocking {
        val seed = """{"formatVersion":1,"colorStyle":"UNKNOWN","automaticBackup":{"enabled":false,"minimumBackupInterval":"PT24H","maximumSnapshotCount":7,"checkInterval":"PT1H"}}"""
        withContainer(seed = seed) { root, container ->
            val path = root.resolve("desktop-settings.json")
            val before = path.readBytes()
            container.appearanceController.load()
            assertNotNull(container.appearanceController.state.value.error)
            container.appearanceController.setColorStyle(DesktopColorStyle.CCB_NATIVE)
            assertNotNull(container.appearanceController.state.value.error)
            assertContentEquals(before, path.readBytes())
        }
    }

    @Test
    fun `missing Desktop settings stay absent through read-only startup`() = runBlocking {
        withContainer { root, container ->
            container.automaticBackupRuntime.initialize()
            container.uiLanguageController.load()
            container.appearanceController.load()
            container.primaryChatController.refresh()
            assertEquals(DesktopUiLanguage.ZH_CN, container.uiLanguageController.language.value)
            assertFalse(Files.exists(root.resolve("desktop-settings.json")))
            assertFalse(Files.exists(root.resolve("entities/app_settings.json")))
            if (Files.exists(root)) assertTrue(Files.walk(root).use { it.filter(Files::isRegularFile).toList() }.isEmpty())
        }
    }

    private suspend fun loaded(root: Path): DesktopSettings =
        assertIs<DesktopSettingsLoadResult.Loaded>(DesktopSettingsStore(root, DesktopDataOperationCoordinator()).load())
            .document.settings

    private suspend fun withContainer(seed: String? = null, block: suspend (Path, DesktopAppContainer) -> Unit) {
        val parent = Files.createTempDirectory("desktop-settings-coherence-")
        val root = parent.resolve("app-data")
        if (seed != null) {
            Files.createDirectories(root)
            root.resolve("desktop-settings.json").writeText(seed)
        }
        val container = DesktopAppContainer(
            DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
                parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() },
        )
        try { block(root, container) } finally {
            container.close()
            parent.toFile().deleteRecursively()
        }
    }
}
