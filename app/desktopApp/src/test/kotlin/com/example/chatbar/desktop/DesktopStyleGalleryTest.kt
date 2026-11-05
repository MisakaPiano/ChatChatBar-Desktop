@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.domain.image.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopStyleGalleryTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    @Test fun `real card editor shortcut reaches style and expandable parameters before long character body without editing`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val parent = Files.createTempDirectory("style-editor-ui-")
            val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"),
                DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
                secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                    check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
                })
            try {
                val card = CharacterCard("synthetic-style", "合成画风入口", createdAt = 1, updatedAt = 1,
                    editMode = CharacterEditMode.FREEFORM, freeformCharacterText = "合成长正文\n".repeat(150),
                    defaultImagePrompt = "Synthetic original style", defaultImageNegativePrompt = "Synthetic negative")
                app.characterRepository.save(card)
                val editor = app.characterEditorController; editor.openExisting(card.id)
                val activity = DesktopModalActivity()
                val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                    val state by editor.state.collectAsState()
                    CompositionLocalProvider(LocalDesktopModalActivity provides activity) { DesktopCharacterEditorOverlay(editor, state) }
                }
                fun nodes(): List<SemanticsNode> {
                    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                }
                fun text(n: SemanticsNode, s: String) = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == s } == true
                try {
                    repeat(8) { scene.render(System.nanoTime()).close(); delay(20) }
                    val jump = nodes().single { text(it, "画风与生图参数") && it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(jump.boundsInRoot.height > 0f && jump.boundsInRoot.top >= 0f && jump.boundsInRoot.bottom <= 900f)
                    assertTrue(jump.config[SemanticsActions.OnClick].action!!.invoke())
                    withTimeout(5_000) {
                        while (nodes().none { text(it, "内置画风图集") && it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f &&
                                it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= 900f }) {
                            scene.render(System.nanoTime()).close(); delay(20)
                        }
                    }
                    repeat(15) { scene.render(System.nanoTime()).close(); delay(20) }
                    val advanced = nodes().first { text(it, "▸ 生图高级设置") && it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(advanced.config[SemanticsActions.OnClick].action!!.invoke())
                    repeat(8) { scene.render(System.nanoTime()).close(); delay(20) }
                    assertTrue(nodes().any { text(it, "使用角色专属生图参数") && it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f &&
                        it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= 900f })
                    assertEquals(card, editor.state.value.card); assertFalse(editor.state.value.dirty)
                    assertEquals(card, app.characterRepository.getById(card.id))
                    System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                        val path = Path.of(directory).resolve("style-editor-reachable-$width.png"); Files.createDirectories(path.parent)
                        scene.render(System.nanoTime()).use { it.encodeToData()!!.use { data -> Files.write(path, data.bytes) } }
                    }
                } finally { scene.close() }
            } finally { app.close(); check(parent.toFile().deleteRecursively()) }
        }
    }
    @Test fun `style gallery filters previews explicit apply and close keep source prompts intact at compact and wide widths`() = runBlocking(edt) {
        val styles = listOf(
            NovelAiStylePreset("v45", "合成旧模型画风", prompt = "Synthetic old style", modelSupport = NovelAiStyleModelSupport.V4_5,
                previewImage = "a.png"),
            NovelAiStylePreset("v5", "合成新模型画风", prompt = "Synthetic new style", modelSupport = NovelAiStyleModelSupport.V5,
                previewImage = "missing.png", previewAvailable = false),
        )
        val image = DesktopImageEditing.png(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB))
        val catalog = NovelAiStyleCatalogLoadResult(styles, errors = listOf("合成新模型画风：例图缺失"))
        for (width in listOf(420, 1100)) {
            var applied: NovelAiStylePreset? = null; var closed = false
            val scene = ImageComposeScene(width, 900, coroutineContext = edt) {
                DesktopStyleGalleryContent(catalog, "Synthetic original", true, { reference ->
                    assertEquals("asset:${styles[0].previewAssetPath}", reference); image
                }, { applied = it }, {}, { closed = true })
            }
            suspend fun frames() { repeat(5) { scene.render(System.nanoTime()).close(); delay(10) } }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            }
            fun matches(node: SemanticsNode, label: String) = node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            suspend fun click(label: String) {
                val node = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
                    it.config.contains(SemanticsActions.OnClick) }
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
            }
            try {
                frames(); assertNull(applied)
                assertTrue(nodes().any { matches(it, "选择画风 v45") })
                click("V5")
                assertFalse(nodes().any { matches(it, "选择画风 v45") })
                assertTrue(nodes().any { matches(it, "选择画风 v5") })
                val search = nodes().single { it.config.contains(SemanticsActions.SetText) }
                assertTrue(search.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("合成新"))); frames()
                click("选择画风 v5"); assertNull(applied)
                System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                    val path = Path.of(directory).resolve("style-gallery-$width.png"); Files.createDirectories(path.parent)
                    scene.render(System.nanoTime()).use { it.encodeToData()!!.use { data -> Files.write(path, data.bytes) } }
                }
                click("确认填入角色卡"); assertEquals(styles[1], applied)
                click("关闭图集"); assertTrue(closed)
                assertEquals("Synthetic new style", styles[1].prompt)
            } finally { scene.close() }
        }
    }
}
