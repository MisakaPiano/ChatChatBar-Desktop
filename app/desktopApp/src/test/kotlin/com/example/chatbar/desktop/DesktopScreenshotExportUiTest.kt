@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat

class DesktopScreenshotExportUiTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `format selection and successful path copy stay usable while cancelled and failed saves clear old success`() = runBlocking(edt) {
        val root = Files.createTempDirectory("ccb-screenshot-export-ui-")
        var nextTarget: Path? = null
        val chosen = mutableListOf<DesktopFileType>()
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path? = null
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path? {
                chosen += type
                assertTrue(suggestedName.endsWith(".${type.defaultExtension}"))
                return nextTarget
            }
        }
        var copied: String? = null
        val clipboard = object : Clipboard {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                copied = clipEntry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
            }
            override suspend fun getClipEntry(): ClipEntry? = null
            override val nativeClipboard: NativeClipboard get() = error("native clipboard is not used")
        }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker)
        try {
            app.characterRepository.save(CharacterCard.create("导出测试").copy(id = "card"))
            app.chatRepository.createSession(ChatSession(id = "session", characterCardId = "card", title = "合成聊天", createdAt = 1, updatedAt = 1))
            val message = app.chatRepository.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "可编辑的中文对话。"))
            val controller = app.primaryChatController
            controller.refresh(); controller.selectSession("session")
            val state = controller.state.value
            for (width in listOf(360, 1100)) {
                val snapshot = DesktopScreenshotWorkspaceSnapshot(state,
                    desktopSemanticColors(ThemeMode.DARK, com.example.chatbar.domain.appearance.DefaultThemeColorHsv, false),
                    listOf(message), emptyMap(), desktopScreenshotBlockIds(message).toSet(), message.id)
                val scene = ImageComposeScene(width, 640) {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        DesktopScreenshotWorkspace(controller, snapshot, {}, previewWindow = { _, _ -> })
                    }
                }
                fun nodes(): List<SemanticsNode> {
                    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                }
                fun actions(label: String) = nodes().filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> label in text } == true && it.config.contains(SemanticsActions.OnClick)
                }
                fun click(label: String) { actions(label).single().config[SemanticsActions.OnClick].action!!() }
                fun visibleText(node: SemanticsNode): String =
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + node.children.joinToString("") { visibleText(it) }
                fun formatOption(label: String) = nodes().filter {
                    it.config.contains(SemanticsActions.OnClick) && visibleText(it).trim().removePrefix("✓ ") == label
                }
                suspend fun until(check: () -> Boolean) {
                    try { withTimeout(15000) {
                        while (true) { scene.render(System.nanoTime()).close(); yield(); if (check()) break; delay(10) }
                    } } catch (e: TimeoutCancellationException) {
                        println(nodes().joinToString("\n") { node ->
                            "action=${node.config.contains(SemanticsActions.OnClick)} disabled=${node.config.contains(SemanticsProperties.Disabled)} label=${node.config.getOrNull(SemanticsProperties.ContentDescription)} text=${node.config.getOrNull(SemanticsProperties.Text)} bounds=${node.boundsInRoot}"
                        })
                        throw e
                    }
                }
                try {
                    until { actions("预览 PNG").isNotEmpty() }
                    assertTrue(actions("复制路径").isEmpty())
                    click("预览 PNG")
                    until { !actions("导出 PNG").single().config.contains(SemanticsProperties.Disabled) && actions("格式 ·").isNotEmpty() }
                    for ((index, format) in DesktopScreenshotExportFormat.entries.withIndex()) {
                        if (format != DesktopScreenshotExportFormat.PNG) {
                            click("格式 ·"); until { formatOption(format.label).isNotEmpty() }
                            formatOption(format.label).single().config[SemanticsActions.OnClick].action!!()
                            until { actions("导出 ${format.label}").isNotEmpty() }
                        }
                        nextTarget = root.resolve("ui-$width-$index.${format.extension}")
                        click("导出 ${format.label}")
                        until { actions("复制路径").isNotEmpty() }
                        assertEquals(listOf(format.extension), chosen.last().extensions)
                        assertTrue(Files.size(nextTarget!!) > 0)
                        click("复制路径")
                        until { copied == nextTarget.toString() }
                        val copyButton = actions("复制路径").single()
                        assertTrue(copyButton.boundsInRoot.left >= 0 && copyButton.boundsInRoot.right <= width)
                        assertTrue(copyButton.boundsInRoot.bottom <= 640)
                        if (format == DesktopScreenshotExportFormat.WORD) System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { folder ->
                            val dir = Files.createDirectories(Path.of(folder))
                            Files.copy(nextTarget, dir.resolve("editable-chat-$width.docx"))
                            scene.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                                Files.write(dir.resolve("screenshot-export-success-$width.png"), it.bytes)
                            } }
                        }
                        nextTarget = null
                        click("导出 ${format.label}")
                        until { !actions("导出 ${format.label}").single().config.contains(SemanticsProperties.Disabled) && actions("格式 ·").isNotEmpty() && actions("复制路径").isEmpty() }
                    }
                    // Reusing an existing file must fail rather than advertise the prior successful path.
                    nextTarget = root.resolve("ui-$width-2.docx")
                    val prior = Files.readAllBytes(nextTarget!!)
                    click("导出 Word")
                    until { nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { "无法保存 Word" in it.text } == true } }
                    assertTrue(actions("复制路径").isEmpty())
                    assertContentEquals(prior, Files.readAllBytes(nextTarget))
                    System.getenv("CCB_SCREENSHOT_REVIEW_DIR")?.let { folder ->
                        val dir = Files.createDirectories(Path.of(folder))
                        scene.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(dir.resolve("screenshot-export-$width.png"), it.bytes)
                        } }
                    }
                } finally { scene.close() }
            }
        } finally { app.close(); root.toFile().deleteRecursively() }
    }
}
