@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.desktop.security.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Real management controls and protected routing; invented credentials, no transport. */
class DesktopAccountPresentationTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private suspend fun ImageComposeScene.frames() { repeat(6) { render().close(); yield(); delay(15) } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.matches(label: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true || children.any { it.matches(label) }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.matches(label) && it.config.contains(SemanticsActions.OnClick) }
        assertFalse(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frames()
    }
    private fun ImageComposeScene.shot(name: String) {
        val directory = System.getenv("CCB_SELECTED_FIX_REVIEW_DIR") ?: return
        val file = Path.of(directory).resolve(name)
        Files.createDirectories(file.parent)
        render().use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
    }
    @Test fun `account dialog creates selects and separates services without exposing a saved key`() = runBlocking(edt) {
        val root = Files.createTempDirectory("ccb-account-ui-")
        val raw = InMemoryDesktopSecretStore()
        val container = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")), secretStoreFactory = { raw })
        val controller = container.apiAccountController
        container.modelRepository.saveModel(ModelConfig("sample", "对话模型", "https://synthetic.invalid/v1", "", "sample-model", createdAt = 1))
        controller.opened.value = DesktopAccountService.NOVELAI
        val scene = ImageComposeScene(1100, 900, coroutineContext = edt) {
            CompositionLocalProvider(LocalDesktopApiAccounts provides controller) { DesktopApiAccountDialog() }
        }
        try {
            scene.frames(); scene.click("添加账户")
            val fields = scene.nodes().filter { it.config.contains(SemanticsActions.SetText) }.sortedBy { it.boundsInRoot.top }
            assertEquals(3, fields.size)
            fields[0].config[SemanticsActions.SetText].action!!(AnnotatedString("绘图主账户"))
            fields[2].config[SemanticsActions.SetText].action!!(AnnotatedString("SYNTHETIC_NAI_UI"))
            scene.frames(); scene.click("保存账户")
            withTimeout(5000) { while (container.apiAccounts.accounts(DesktopAccountService.NOVELAI).isEmpty()) scene.frames() }
            scene.frames(); scene.click("选择")
            withTimeout(5000) { while (container.apiAccounts.selected(DesktopAccountService.NOVELAI) == null) scene.frames() }
            assertEquals("SYNTHETIC_NAI_UI", container.desktopSecretStore.load(DesktopCredentialKey.NovelAiToken))
            scene.frames(); scene.shot("accounts-novelai.png")
            scene.click("编辑")
            assertTrue(scene.nodes().filter { it.config.contains(SemanticsActions.SetText) }
                .all { !it.config[SemanticsProperties.EditableText].text.contains("SYNTHETIC_") })
            scene.click("Fish Audio")
            assertTrue(scene.nodes().any { it.matches("放弃当前账户编辑？") })
            scene.click("放弃编辑")
            assertEquals(DesktopAccountService.FISH, controller.opened.value)
            assertNull(container.apiAccounts.selected(DesktopAccountService.FISH))
            assertTrue(container.apiAccounts.accounts(DesktopAccountService.FISH).isEmpty())
            scene.click("LLM"); scene.shot("accounts-llm.png")
        } finally { scene.close(); container.close(); root.toFile().deleteRecursively() }
    }
}
