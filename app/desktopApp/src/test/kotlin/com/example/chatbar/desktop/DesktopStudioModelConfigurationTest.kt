@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.ModelConfig
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStudioModelConfigurationTest {
    private val edt = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block) }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render(System.nanoTime()).close(); delay(15) } }
    private suspend fun ImageComposeScene.until(predicate: () -> Boolean) { withTimeout(10000) { while (!predicate()) frames() } }
    private fun ImageComposeScene.text(): String {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text }
    }

    @Test fun `retained production Studio model binding refreshes edited added removed and default models without page reentry or exposing keys`() = runBlocking(edt) {
        val f = FinalProductDesignFixture()
        try {
            f.initialize(); val c = f.container.novelAiStudioController; val repo = f.container.modelRepository
            var projection: DesktopStudioModels? = null
            val scene = ImageComposeScene(800, 300) {
                val draft by c.draft.collectAsState()
                val state = rememberDesktopStudioModels(c, draft?.aiDesignModelId)
                projection = state
                Column {
                    DesktopDesignModelChoice(draft?.aiDesignModelId, (state.catalog as? DesktopStudioModelCatalog.Available)?.models.orEmpty(), state.authentication.model) {}
                    StatusText(state.authentication.status)
                }
            }
            try {
                scene.until { projection?.authentication?.configured == true }
                val original = assertNotNull(repo.getModel("local-design"))
                val prompt = c.draft.value!!.basePrompt
                repo.saveModel(original.copy(displayName = "Renamed local design", baseUrl = "https://changed.fixture.invalid/v1", modelName = "edited-model"))
                scene.until { projection?.authentication?.model == "Renamed local design" && projection?.authentication?.provider == "changed.fixture.invalid" }
                assertTrue(scene.text().contains("Renamed local design"))
                fun catalog() = projection!!.catalog as DesktopStudioModelCatalog.Available
                assertTrue(catalog().models.all { it.apiKey.isEmpty() })
                val local = ModelConfig("new-local", "New local model", "http://localhost:11434/v1", "", "local-model", createdAt = 1)
                repo.saveModel(local)
                scene.until { (projection?.catalog as? DesktopStudioModelCatalog.Available)?.models?.any { it.id == local.id } == true }
                assertEquals("local-design", c.draft.value!!.aiDesignModelId)
                repo.deleteModel("local-design")
                scene.until { (projection?.catalog as? DesktopStudioModelCatalog.Available)?.models?.none { it.id == "local-design" } == true && projection?.authentication?.modelId == null }
                assertFalse(projection!!.authentication.configured)
                assertEquals("local-design", c.draft.value!!.aiDesignModelId)
                assertTrue(scene.text().contains("local-design")) // Explicit stale choice is retained, not silently replaced.
                f.container.settingsRepository.updateAppSettings { it.copy(defaultImageModelId = local.id, allowCleartextModelApi = false) }
                assertTrue(c.edit { it.copy(aiDesignModelId = null) })
                scene.until { projection?.authentication?.modelId == local.id }
                assertFalse(projection!!.authentication.configured)
                val before = catalog()
                f.container.settingsRepository.updateAppSettings { it.copy(allowCleartextModelApi = true) }
                scene.until { projection?.authentication?.configured == true }
                assertEquals(before.models, catalog().models)
                assertTrue(catalog().configurationRevision > before.configurationRevision)
                assertEquals(prompt, c.draft.value!!.basePrompt)
                assertTrue(catalog().models.all { it.apiKey.isEmpty() })
                assertTrue(f.requests.isEmpty()); assertTrue(f.container.taskRuntime.tasks.value.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
}
