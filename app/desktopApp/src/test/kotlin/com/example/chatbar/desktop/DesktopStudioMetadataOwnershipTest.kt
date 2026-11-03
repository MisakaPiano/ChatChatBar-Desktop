@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.PngTextChunks
import com.example.chatbar.domain.image.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopStudioMetadataOwnershipTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `actual import choice preserves raw PNG and restores logical text through durable global history`(): Unit = runBlocking(edt) {
        val parent = Files.createTempDirectory("studio-transparent-import-")
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        try {
            val controller = app.novelAiStudioController; controller.load()
            controller.edit { it.copy(basePrompt = "before import") }
            val comment = """{"model":"nai-diffusion-5-full","width":1024,"height":1024,
                "tag_hint_transparent_background":true,
                "v4_prompt":{"caption":{"base_caption":"synthetic scene, transparent background","char_captions":[]}}}"""
            val bytes = PngTextChunks.insertTextChunk(DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)), "Comment", comment)
            val file = parent.resolve("synthetic.png"); Files.write(file, bytes)
            val metadata = assertNotNull(DesktopImageMetadata.readStudio(file.toString()))
            assertTrue(metadata.hasAmbiguousTransparentSuffix)
            var selection by mutableStateOf(NovelAiStudioMetadataSelection())
            val scene = ImageComposeScene(520, 700, coroutineContext = edt) {
                Column(Modifier.background(DesktopBootstrapColors.background)) {
                    DesktopStudioMetadataOptions(metadata, selection) { selection = it }
                }
            }
            try {
                scene.frames(); assertTrue(scene.has("透明标签 · 保留在正文中 ▾"))
                controller.importMetadata(file, selection)
                assertEquals(metadata.positivePrompt, controller.draft.value!!.basePrompt)
                assertTrue(controller.undo()); assertEquals("before import", controller.draft.value!!.basePrompt)
                scene.click("透明标签 · 保留在正文中 ▾"); scene.click("交给透明背景开关管理")
                assertEquals(NovelAiTransparentSuffixImport.MANAGE_WITH_SWITCH, selection.transparentSuffix)
                controller.importMetadata(file, selection)
                val imported = controller.draft.value!!
                assertEquals("synthetic scene", imported.basePrompt); assertTrue(imported.activeSettings.transparentBackground)
                controller.edit { it.withActiveSettings(it.activeSettings.copy(transparentBackground = false)) }
                val off = controller.draft.value!!
                val wire = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                    off.toPromptPlan(), off.activeSettings.imageSize(), off.activeSettings)).jsonObject
                assertEquals("synthetic scene", wire.getValue("input").jsonPrimitive.content)
                assertFalse(wire.getValue("parameters").jsonObject.getValue("tag_hint_transparent_background").jsonPrimitive.boolean)
                val reopened = DesktopStudioHistoryRepository(JsonFileStorage(root)); reopened.initializeHistory()
                assertEquals(off, reopened.loadDraft())
                assertTrue(reopened.replay(false)); assertTrue(reopened.loadDraft().activeSettings.transparentBackground)
                assertTrue(reopened.replay(false)); assertEquals("before import", reopened.loadDraft().basePrompt)
                assertTrue(reopened.replay(true)); assertEquals("synthetic scene", reopened.loadDraft().basePrompt)
                assertContentEquals(bytes, Files.readAllBytes(file)); assertEquals(metadata, DesktopImageMetadata.readStudio(file.toString()))
                scene.frames()
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val output = Path.of(directory).resolve("studio-transparent-import-choice.png"); Files.createDirectories(output.parent)
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(output, it.bytes) } }
                }
            } finally { scene.close() }
        } finally { app.close(); check(parent.toFile().deleteRecursively()) }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.has(label: String): Boolean = config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || children.any { it.has(label) }
    private fun ImageComposeScene.has(label: String) = nodes().any { it.has(label) }
    private suspend fun ImageComposeScene.frames() { repeat(5) { render().close(); delay(15) } }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.has(label) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
}
