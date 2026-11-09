@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.card.*
import com.example.chatbar.desktop.interop.official1411.CharacterCardPackage as Official1411Package
import com.example.chatbar.desktop.interop.official1411.validateForImport as validateOfficial1411
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopCharacterExportCompatibilityUiTest {
    @Test fun `actual PNG dialog selects a separate export image enables original mode and writes compatible pixels`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("cover-choice-ui-")
        fun picture(rgb: Int) = java.io.ByteArrayOutputStream().use { out ->
            val image = java.awt.image.BufferedImage(7, 11, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, rgb)
            check(javax.imageio.ImageIO.write(image, "png", out)); out.toByteArray()
        }
        val background = picture(0x125599); val replacement = picture(0x997722)
        val input = Files.write(parent.resolve("chosen.png"), replacement)
        val output = parent.resolve("export.png")
        var opens = 0; var saves = 0
        val picker = object : DesktopFilePicker {
            override fun pickOpenFile(type: DesktopFileType): Path { opens++; return input }
            override fun pickSaveFile(type: DesktopFileType, suggestedName: String): Path { saves++; return output }
        }
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() }, filePicker = picker,
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        try {
            val card = app.characterTransfers.importNew(CharacterCardPackage(card = PackagedCharacterCard("Synthetic PNG",
                chatBackgroundResourceId = "background"), images = mapOf("background" to PackagedImage("background.png",
                java.util.Base64.getEncoder().encodeToString(background)))))
            assertNull(card.coverImage)
            val controller = app.createTypedTransferController(); controller.beginCoverExport(card.id)
            val draft = assertNotNull(controller.state.value.coverExport)
            val scene = ImageComposeScene(900, 820, coroutineContext = edt) {
                DesktopCoverExportDialog(draft, controller, frame = { content -> content() })
            }
            try {
                scene.until { scene.nodes().any { it.text() == "导出 PNG" && !it.config.contains(SemanticsProperties.Disabled) } }
                val original = scene.nodes().single { it.text() == "保留原图外观" && it.config.contains(SemanticsActions.OnClick) }
                assertFalse(original.config.contains(SemanticsProperties.Disabled))
                assertEquals(androidx.compose.ui.state.ToggleableState.Off, original.config[SemanticsProperties.ToggleableState])
                scene.click("选择 / 替换导出封面")
                scene.until { opens == 1 && scene.nodes().any { it.text() == "撤销" && !it.config.contains(SemanticsProperties.Disabled) } }
                scene.click("保留原图外观")
                scene.until { scene.nodes().any { it.text().startsWith("原图模式保留") } }
                scene.capture("png-replacement")
                scene.click("导出 PNG")
                scene.until { !controller.state.value.busy && controller.state.value.coverExport == null }
                assertNull(controller.state.value.error); assertEquals(1, saves)
                assertContentEquals(replacement, OriginalCharacterCover.imageOnlyPng(Files.readAllBytes(output)))
                // Desktop ingestion attaches the visible original and upgrades its internal
                // package to10. Check the emitted wire with the frozen official reader instead.
                val payload = assertNotNull(PngTextChunks.extractTextChunk(Files.readAllBytes(output), PngTextChunks.CHATBAR_CHARACTER_KEYWORD))
                val raw = String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8)
                val data = app.transferJson.decodeFromString(Official1411Package.serializer(), raw).also { it.validateOfficial1411() }
                assertEquals(9, data.schemaVersion); assertFalse(raw.contains("coverImageResourceId"))
                assertEquals(card, app.characterRepository.getById(card.id))
                assertContentEquals(background, Files.readAllBytes(app.appDataRoot.resolve(assertNotNull(card.chatBackground))))
                assertContentEquals(replacement, Files.readAllBytes(input))
            } finally { scene.close() }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }

    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private suspend fun ImageComposeScene.until(predicate: () -> Boolean) = withTimeout(15000) {
        while (!predicate()) { render(System.nanoTime()).close(); delay(10) }
        render(System.nanoTime()).close()
    }
    private fun ImageComposeScene.click(text: String) {
        val node = nodes().single { it.text() == text && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(assertNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun ImageComposeScene.capture(name: String) {
        System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("character-export-$name.png"); Files.createDirectories(path.parent)
            render(System.nanoTime()).use { it.encodeToData()!!.use { data -> Files.write(path, data.bytes) } }
        }
    }

    @Test fun `real JSON dialog opens compatible selects full warning and resets compatibility on reopen`() = runBlocking(edt) {
        val parent = Files.createTempDirectory("character-export-ui-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { path -> check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray() })
        try {
            val card = app.characterTransfers.importNew(CharacterCardPackage(card = PackagedCharacterCard("Synthetic export")))
            val controller = app.createTypedTransferController(); controller.beginCharacterJsonExport(card.id)
            val modal = DesktopModalActivity()
            val scene = ImageComposeScene(900, 700) {
                CompositionLocalProvider(LocalDesktopModalActivity provides modal) {
                    DesktopCharacterExportDialogs(controller)
                    DesktopTypedTransferPanel(controller)
                }
            }
            try {
                val trigger = "文件格式 · 完整格式11（推荐） ▾"
                scene.until { !controller.state.value.busy && controller.state.value.characters.any { it.id == card.id } &&
                    scene.nodes().any { it.text() == trigger } }
                assertEquals(1, modal.count)
                assertEquals(1, scene.nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("关闭角色导出") })
                scene.capture("complete-default")
                scene.click(trigger)
                scene.until { scene.nodes().any { it.text() == "旧版兼容格式9" } }
                scene.click("旧版兼容格式9")
                scene.until { scene.nodes().any { it.text().contains("兼容旧版 CCB") } }
                scene.capture("compatibility9")
                controller.cancelCharacterJsonExport()
                scene.until { scene.nodes().none { it.text().contains("选择保存位置并导出") } }
                assertEquals(0, modal.count)
                controller.beginCharacterJsonExport(card.id)
                scene.until { scene.nodes().any { it.text() == trigger } }
                assertEquals(1, modal.count)
                assertTrue(scene.nodes().none { it.text().contains("兼容旧版 CCB") })
            } finally { scene.close() }
        } finally { app.close(); parent.toFile().deleteRecursively() }
    }
}
