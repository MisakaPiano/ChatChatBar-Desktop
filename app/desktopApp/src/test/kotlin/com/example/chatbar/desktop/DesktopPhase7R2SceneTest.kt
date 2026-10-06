@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopPhase7R2SceneTest {
    @Test fun `Studio lays out at wide and compact sizes without launching a task`() = runBlocking {
        val root = Files.createTempDirectory("p7-r2-scene-")
        val container = DesktopAppContainer(resolvedRoot = DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        try {
            val controller = container.novelAiStudioController
            controller.load()
            controller.edit { it.copy(basePrompt = "landscape, blue sky", stylePrompt = "watercolor") }
            for ((width, height) in listOf(1280 to 800, 700 to 650)) {
                val scene = ImageComposeScene(width, height) { DesktopNovelAiStudioPanel(controller) }
                try {
                    repeat(5) { scene.render().close(); delay(50) }
                    scene.render().use { image ->
                        assertEquals(width, image.width); assertEquals(height, image.height)
                        val output = Path.of("build", "phase7-r2-studio-$width.png")
                        Files.createDirectories(output.parent)
                        image.encodeToData()?.use { Files.write(output, it.bytes) }
                    }
                    assertTrue(controller.state.value.ready)
                    assertTrue(controller.taskEntries.value.isEmpty(), "Layout must not start generation tasks")
                } finally { scene.close() }
            }
        } finally { container.close(); root.toFile().deleteRecursively() }
    }
}
