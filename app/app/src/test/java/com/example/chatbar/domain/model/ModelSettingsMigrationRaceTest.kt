package com.example.chatbar.domain.model

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.backup.LocalDataMaintenance
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelSettingsMigrationRaceTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalog = PresetModelCatalog(chatModels = listOf(
        PresetChatModel("hidden", "Synthetic hidden", "synthetic-hidden", selectableForChat = false),
        PresetChatModel("one", "Synthetic visible", "synthetic-model")
    ))
    private class Fixture(val root: File) {
        val context = object : ContextWrapper(null) { override fun getFilesDir(): File = root }
        val settings = SettingsRepository(JsonFileStorage(context))
        suspend fun cold() = SettingsRepository(JsonFileStorage(context)).getAppSettings()
    }
    private suspend fun fixture(): Fixture = Fixture(temp.newFolder()).also {
        it.settings.saveAppSettings(AppSettings(defaultEmbeddingId = "synthetic-legacy-embedding"))
        // This is the actual flag MainActivity uses to admit navigation/editing.
        it.settings.initialize()
        assertTrue(it.settings.isInitialized.first())
        assertFalse(LocalDataMaintenance.blocked.value)
    }

    @Test fun `startup migration retains settings saved while legacy model work is suspended`() = runTest {
        val f = fixture()
        val newSegmented = !f.settings.currentAppSettings.assistantSegmentedBubblesEnabled
        val read = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val migration = async {
            migrateLegacyModelSettings(f.settings, { legacyId ->
                assertEquals("synthetic-legacy-embedding", legacyId)
                read.complete(Unit)
                proceed.await()
            }, { catalog })
        }
        read.await()
        f.settings.updateAppSettings { it.copy(assistantSegmentedBubblesEnabled = newSegmented,
            formatRepairModelId = "synthetic-new-repair-model", allowCleartextModelApi = true) }
        proceed.complete(Unit)
        migration.await()
        val cold = f.cold()
        assertEquals("The migration must retain the user's completed settings write", newSegmented, cold.assistantSegmentedBubblesEnabled)
        assertTrue(cold.allowCleartextModelApi)
        assertEquals("synthetic-new-repair-model", cold.formatRepairModelId)
        assertNull(cold.defaultEmbeddingId)
    }

    @Test fun `startup migration retains a newly selected default chat model`() = runTest {
        val f = fixture()
        val read = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val migration = async { migrateLegacyModelSettings(f.settings, {
            read.complete(Unit); proceed.await()
        }, { catalog }) }
        read.await()
        f.settings.updateAppSettings { it.copy(defaultModelId = "synthetic-new-default") }
        proceed.complete(Unit)
        migration.await()
        assertEquals("synthetic-new-default", f.cold().defaultModelId)
    }

    @Test fun `legacy migration keeps a valid preset selection and existing unrelated settings`() = runTest {
        val f = fixture()
        f.settings.updateAppSettings { it.copy(presetDefaultModelKey = "one", defaultModelId = "existing-default",
            formatRepairModelId = "existing-repair") }
        val seen = mutableListOf<String?>()
        migrateLegacyModelSettings(f.settings, { seen += it }, { catalog })
        val cold = f.cold()
        assertEquals(listOf("synthetic-legacy-embedding"), seen)
        assertEquals("one", cold.presetDefaultModelKey)
        assertEquals("existing-default", cold.defaultModelId)
        assertEquals("existing-repair", cold.formatRepairModelId)
        assertNull(cold.defaultEmbeddingId)
        assertEquals(cold.modelConfigurationMode.normalized(), cold.modelConfigurationMode)
    }

    @Test fun `invalid legacy preset selection still uses first chat-selectable catalog model`() = runTest {
        val f = fixture()
        f.settings.updateAppSettings { it.copy(presetDefaultModelKey = "synthetic-removed-key") }
        migrateLegacyModelSettings(f.settings, {}, { catalog })
        assertEquals("one", f.cold().presetDefaultModelKey)
    }

    @Test fun `cancelling suspended model work retains user settings without a migration write`() = runTest {
        val f = fixture()
        val read = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val migration = async { migrateLegacyModelSettings(f.settings, {
            read.complete(Unit); never.await()
        }, { catalog }) }
        read.await()
        f.settings.updateAppSettings { it.copy(formatRepairModelId = "synthetic-during-cancel") }
        migration.cancelAndJoin()
        val cold = f.cold()
        assertEquals("synthetic-during-cancel", cold.formatRepairModelId)
        assertEquals("synthetic-legacy-embedding", cold.defaultEmbeddingId)
    }

    @Test fun `preset import retains an explicit model selection and current user settings`() = runTest {
        val f = fixture()
        f.settings.updateAppSettings { it.copy(defaultModelId = "synthetic-user-choice",
            presetDefaultModelKey = "one", assistantSegmentedBubblesEnabled = true) }
        val imported = listOf(ModelConfig.create("Synthetic preset", "https://synthetic.invalid/v1", "", "synthetic-model")
            .copy(id = PRESET_MODEL_ID_PREFIX + "one"))
        applyImportedPresetModelDefaults(f.settings, imported)
        val cold = f.cold()
        assertEquals("synthetic-user-choice", cold.defaultModelId)
        assertTrue(cold.assistantSegmentedBubblesEnabled)
        assertNull(cold.presetDefaultModelKey)
        assertNull(cold.defaultEmbeddingId)
    }

    @Test fun `preset import still resolves legacy default before falling back to first selectable model`() = runTest {
        val f = fixture()
        val first = ModelConfig.create("Synthetic first", "https://synthetic.invalid/v1", "", "synthetic-first")
            .copy(id = PRESET_MODEL_ID_PREFIX + "first")
        val selected = first.copy(id = PRESET_MODEL_ID_PREFIX + "one")
        f.settings.updateAppSettings { it.copy(defaultModelId = null, presetDefaultModelKey = "one") }
        applyImportedPresetModelDefaults(f.settings, listOf(first, selected))
        assertEquals(selected.id, f.cold().defaultModelId)
        f.settings.updateAppSettings { it.copy(defaultModelId = null, presetDefaultModelKey = "removed") }
        applyImportedPresetModelDefaults(f.settings, listOf(first, selected))
        assertEquals(first.id, f.cold().defaultModelId)
    }
}
