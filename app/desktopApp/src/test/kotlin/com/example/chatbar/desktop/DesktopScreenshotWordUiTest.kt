@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopScreenshotWordUiTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `Word remains directly exportable after PNG preview rejects an unavailable background`() = runBlocking(edt) {
        val root = Files.createTempDirectory("direct-word-ui-").toAbsolutePath().normalize()
        val destination = root.resolve("direct.docx")
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? = null
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path {
                assertEquals(listOf("docx"), type.extensions); return destination
            }
        }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker,
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        try {
            app.characterRepository.save(CharacterCard("card", "合成角色卡", createdAt = 0, updatedAt = 0))
            app.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "合成会话", createdAt = 1, updatedAt = 1))
            val message = app.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT,
                "合成可编辑正文\n```status\n姓名：合成人物\n合成应排除状态\n```\n---\n【行动选项】\n1. 合成保留行动甲\n2. 合成保留行动乙\n---"))
            val controller = app.primaryChatController
            controller.refresh(); controller.selectSession("session")
            val state = controller.state.value.let { it.copy(selectedSession = it.selectedSession!!.copy(chatBackground = "images/synthetic-missing.png")) }
            val snapshot = DesktopScreenshotWorkspaceSnapshot(state,
                desktopSemanticColors(ThemeMode.DARK, com.example.chatbar.domain.appearance.DefaultThemeColorHsv, false),
                listOf(message), emptyMap(), desktopScreenshotBlockIds(message).toSet(), message.id)
            val scene = ImageComposeScene(760, 640) { DesktopScreenshotWorkspace(controller, snapshot, {}, previewWindow = { _, _ -> error("NO_PREVIEW_EXPECTED") }) }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
            }
            fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + node.children.joinToString("") { text(it) }
            fun actions(label: String) = nodes().filter { it.config.contains(SemanticsActions.OnClick) &&
                (it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value -> label in value } == true || text(it) == label) }
            suspend fun until(condition: () -> Boolean) = withTimeout(7_000) {
                while (!condition()) { scene.render().close(); delay(10) }
                repeat(3) { scene.render().close(); delay(10) }
            }
            fun click(label: String) { val node = actions(label).single(); assertFalse(node.config.contains(SemanticsProperties.Disabled)); node.config[SemanticsActions.OnClick].action!!() }
            try {
                until { actions("预览 PNG").isNotEmpty() }
                assertEquals(1, actions("包含状态栏").size); assertEquals(1, actions("包含行动选项").size)
                click("包含状态栏") // Independent: exclude status, keep the default-on action choices.
                scene.render().close()
                click("预览 PNG")
                until { nodes().any { "PREVIEW_RENDER" in text(it) } }
                assertFalse(Files.exists(destination))
                click("格式 ·")
                until { nodes().any { node -> node.config.contains(SemanticsActions.OnClick) && text(node).trim().removePrefix("✓ ") == "Word" } }
                nodes().single { it.config.contains(SemanticsActions.OnClick) && text(it).trim().removePrefix("✓ ") == "Word" }.config[SemanticsActions.OnClick].action!!()
                until { actions("导出 Word").isNotEmpty() }
                click("导出 Word")
                until { Files.isRegularFile(destination) && actions("复制路径").isNotEmpty() }
                var document = ""
                ZipInputStream(Files.newInputStream(destination)).use { zip ->
                    while (true) { val entry = zip.nextEntry ?: break
                        if (entry.name == "word/document.xml") document = zip.readBytes().decodeToString()
                        zip.closeEntry()
                    }
                }
                assertTrue("合成可编辑正文" in document)
                assertFalse("合成应排除状态" in document)
                assertTrue("合成保留行动甲" in document)
                assertTrue("合成应排除状态" in app.chatRepository.getMessage(message.id, message.sessionId)!!.displayContent)
            } finally { scene.close() }
        } finally {
            app.close()
            check(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            root.toFile().deleteRecursively()
        }
    }
}
