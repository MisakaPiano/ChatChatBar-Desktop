@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.image.NovelAiImageModel
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

/** Actual production container, overlay, parameter switch and Save callback; synthetic files only. */
class DesktopCharacterSaveUiIntegrationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `parameter only UI save does not inspect unrelated authority or discard its scroll position`() = runBlocking(edt) {
        fixture {
            val damaged = damagedAuthority()
            enableParameters()
            val expected = editor.state.value.card!!.defaultImageGenerationSettings
            val position = scrollPosition()
            assertTrue(position > 0f)
            click("保存")
            settled()
            capture("parameter-only")
            assertNull(editor.state.value.problem, editor.state.value.detail)
            assertEquals(expected, app.characterRepository.getById(card.id)!!.defaultImageGenerationSettings)
            assertTrue(Files.exists(root.resolve(card.avatar!!)))
            assertEquals("not valid JSON", Files.readString(damaged))
            assertEquals(position, scrollPosition(), 2f)
        }
    }

    @Test fun `actual save callback retains the visible scroll form while postcommit maintenance waits`() = runBlocking(edt) {
        fixture {
            enableParameters()
            val replacement = image("replacement.png")
            editor.edit { it.copy(avatar = replacement) }
            frames()
            val position = scrollPosition()
            assertTrue(position > 0f)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker = CoroutineScope(Dispatchers.Default).launch {
                app.dataOperationCoordinator.withNormalOperation { entered.complete(Unit); release.await() }
            }
            entered.await()
            try {
                click("保存")
                await { app.dataOperationCoordinator.state == DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING }
                assertEquals(replacement, app.characterRepository.getById(card.id)!!.avatar)
                capture("maintenance-wait")
                assertTrue(nodes().any { tagged(it, "character-editor-scroll") }, "Committed maintenance must not remove the form")
                assertEquals(position, scrollPosition(), 2f)
                editor.edit { it.copy(creatorNotes = "Must not mutate a save in progress") }
                assertNotEquals("Must not mutate a save in progress", editor.state.value.card!!.creatorNotes)
            } finally { release.complete(Unit); blocker.join() }
            settled()
            assertNull(editor.state.value.problem, editor.state.value.detail)
            assertEquals(position, scrollPosition(), 2f)
            assertFalse(Files.exists(root.resolve(card.avatar!!)))
            assertTrue(Files.exists(root.resolve(replacement)))
        }
    }

    @Test fun `real removed resource failure stays visible and can be retried through the actual UI without another save`() = runBlocking(edt) {
        fixture {
            val damaged = damagedAuthority()
            enableParameters()
            val replacement = image("replacement.png")
            editor.edit { it.copy(avatar = replacement) }
            click("保存")
            settled()
            assertEquals(CharacterEditorProblem.RESOURCE_FAILED, editor.state.value.problem)
            val durable = requireNotNull(app.characterRepository.getById(card.id))
            assertEquals(replacement, durable.avatar)
            assertTrue(Files.exists(root.resolve(card.avatar!!)))
            assertTrue(Files.exists(root.resolve(replacement)))
            frames()
            capture("resource-maintenance-failed")
            assertTrue(nodes().any { text(it, "资源处理失败") || text(it, "角色卡已保存；旧资源清理未完成，原资源已保留。") })
            // Repair only the synthetic failure, then use the production retry action, not Save.
            Files.delete(damaged)
            click("重试资源清理")
            await { editor.state.value.problem == null }
            assertFalse(Files.exists(root.resolve(card.avatar!!)))
            assertTrue(Files.exists(root.resolve(replacement)))
            assertEquals(durable, app.characterRepository.getById(card.id), "Cleanup retry must not rewrite the saved card")
        }
    }

    private inner class Fixture(val root: Path, val app: DesktopAppContainer, val card: CharacterCard) {
        val editor = app.characterEditorController
        val activity = DesktopModalActivity()
        val scene = ImageComposeScene(1100, 900, coroutineContext = edt) {
            val state by editor.state.collectAsState()
            CompositionLocalProvider(LocalDesktopModalActivity provides activity) { DesktopCharacterEditorOverlay(editor, state) }
        }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
        }
        fun tagged(node: SemanticsNode, tag: String) = node.config.getOrNull(SemanticsProperties.TestTag) == tag
        fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        suspend fun frames() { repeat(5) { scene.render(System.nanoTime()).close(); delay(15) } }
        suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) {
            while (!predicate()) { scene.render(System.nanoTime()).close(); delay(10) }
            frames()
        }
        suspend fun click(label: String) {
            frames()
            fun containsLabel(node: SemanticsNode): Boolean = text(node, label) || node.children.any(::containsLabel)
            val matching = nodes().filter { containsLabel(it) && it.config.contains(SemanticsActions.OnClick) }
            assertEquals(1, matching.size, "Expected one real clickable: $label")
            assertTrue(matching.single().config[SemanticsActions.OnClick].action!!.invoke())
            frames()
        }
        suspend fun enableParameters() {
            click("画风与生图参数")
            await { nodes().any { text(it, "▸ 生图高级设置") && it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= 900f } }
            click("▸ 生图高级设置")
            click("使用角色专属生图参数")
            assertNotNull(editor.state.value.card!!.defaultImageGenerationSettings)
        }
        suspend fun settled() = await { !editor.state.value.dirty && !editor.state.value.busy && editor.state.value.problem != CharacterEditorProblem.SAVE_COMMITTED_WARNING }
        fun scrollPosition() = nodes().single { tagged(it, "character-editor-scroll") }.config[SemanticsProperties.VerticalScrollAxisRange].value()
        fun image(name: String): String {
            val reference = "images/$name"
            Files.createDirectories(root.resolve("images"))
            Files.write(root.resolve(reference), DesktopImageEditing.png(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)))
            return reference
        }
        fun damagedAuthority(): Path = root.resolve("entities/unrelated_authority/broken.json").also {
            Files.createDirectories(it.parent); Files.writeString(it, "not valid JSON")
        }
        fun capture(name: String) {
            System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                val path = Path.of(directory).resolve("card-save-$name.png")
                Files.createDirectories(path.parent)
                scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
            }
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val parent = Files.createTempDirectory("card-save-ui-integration-")
        val root = parent.resolve("data")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, parent.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { """{"entries":[]}""".toByteArray() })
        var f: Fixture? = null
        try {
            val reference = "images/original.png"
            Files.createDirectories(root.resolve("images"))
            Files.write(root.resolve(reference), DesktopImageEditing.png(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)))
            val card = CharacterCard("synthetic-save-ui", "合成保存验证", greeting = "Synthetic greeting", avatar = reference,
                editMode = CharacterEditMode.FREEFORM, freeformCharacterText = "合成正文\n".repeat(150),
                defaultNovelAiImageModel = NovelAiImageModel.V5_FULL, createdAt = 1, updatedAt = 1)
            app.characterRepository.save(card)
            app.characterEditorController.openExisting(card.id)
            f = Fixture(root, app, card)
            f.frames(); f.block()
        } finally { f?.scene?.close(); app.close(); check(parent.toFile().deleteRecursively()) }
    }
}
