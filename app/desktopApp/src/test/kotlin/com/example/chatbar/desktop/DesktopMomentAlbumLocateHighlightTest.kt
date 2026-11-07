@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopMomentAlbumLocateHighlightTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private class Fixture {
        val parent = Files.createTempDirectory("moment-album-highlight-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(parent.resolve("data"), DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        val controller by lazy { DesktopMomentsController(DesktopMomentStore(app.jsonFileStorage, app.dataOperationCoordinator),
            app.settingsRepository, app.characterRepository, app.chatRepository, app.dataOperationCoordinator, app.taskRuntime,
            DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("Locate must not generate") }) }
        suspend fun seed() {
            app.jsonFileStorage.saveEntity("character_cards", "card", CharacterCard("card", "Synthetic card", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
            app.chatRepository.createSession(ChatSession("session", "card", "Synthetic session", createdAt = 1, updatedAt = 1))
            repeat(3) { i -> app.jsonFileStorage.saveEntity("moment_posts", "p$i", MomentPost("p$i", "card", "session",
                senderName = "Synthetic person", text = "Synthetic post $i", scheduledAt = i + 1L, generatedAt = i + 1L), MomentPost.serializer()) }
            app.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            withTimeout(10_000) { controller.state.first { it.posts.size == 3 && it.enabled } }
        }
        suspend fun close() { controller.closeAndDrain(); app.close(); check(parent.toFile().deleteRecursively()) }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.has(label: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || children.any { it.has(label) }
    private fun ImageComposeScene.action(label: String) = nodes().first { it.has(label) && it.config.contains(SemanticsActions.OnClick) }
    private fun ImageComposeScene.click(label: String) { assertTrue(action(label).config[SemanticsActions.OnClick].action!!.invoke()) }
    private suspend fun ImageComposeScene.frames() { repeat(3) { render(System.nanoTime()).close(); delay(20) } }
    private fun ImageComposeScene.selectedIds() = nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("moment-post-") }.toSet()
    private suspend fun ImageComposeScene.locate(id: String) {
        click("朋友圈相册"); frames()
        if (nodes().any { it.has("相册分组 card") && it.config.contains(SemanticsActions.OnClick) }) {
            click("相册分组 card"); frames()
        }
        click("返回朋友圈动态 $id"); frames()
        withTimeout(5_000) { while (selectedIds() != setOf(id)) frames() }
    }
    private fun ImageComposeScene.shot(name: String) { System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { folder ->
        val file = Path.of(folder).resolve(name); Files.createDirectories(file.parent)
        render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(file, it.bytes) } }
    } }

    @Test fun `actual located ID highlight lasts three and half seconds and older timer cannot clear newer target`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.seed()
            val original = f.controller.state.value.posts.associate { it.id to Files.readAllBytes(f.app.appDataRoot.resolve("entities/moment_posts/${it.id}.json")) }
            val scene = ImageComposeScene(1000, 1000, coroutineContext = edt) { DesktopMomentsPanel(f.controller) }
            try {
                scene.frames(); scene.locate("p0"); scene.shot("moment-locate-highlight-first.png")
                val first = System.nanoTime()
                delay(1700)
                scene.locate("p1"); scene.shot("moment-locate-highlight-new-target.png")
                // Beyond the first target's expiry, the second target must still be highlighted.
                while ((System.nanoTime() - first) / 1_000_000 < 3800) scene.frames()
                assertEquals(setOf("p1"), scene.selectedIds())
                withTimeout(5_000) { while (scene.selectedIds().isNotEmpty()) scene.frames() }
                scene.shot("moment-locate-highlight-expired.png")
                original.forEach { (id, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.app.appDataRoot.resolve("entities/moment_posts/$id.json"))) }
                assertTrue(f.app.taskRuntime.tasks.value.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `stale album tile for deleted post shows existing refusal and never highlights another row`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.seed()
            val scene = ImageComposeScene(1000, 1000, coroutineContext = edt) { DesktopMomentsPanel(f.controller) }
            try {
                scene.frames(); scene.click("朋友圈相册"); scene.frames(); scene.click("相册分组 card"); scene.frames()
                val stale = scene.action("返回朋友圈动态 p0").config[SemanticsActions.OnClick].action!!
                f.controller.delete("p0")
                assertFalse(f.controller.state.value.posts.any { it.id == "p0" })
                assertTrue(stale.invoke()); scene.frames()
                withTimeout(5_000) { while (scene.nodes().none { it.has("该动态已删除或不可用") }) scene.frames() }
                assertTrue(scene.selectedIds().isEmpty())
                assertEquals(setOf("p1", "p2"), f.controller.state.value.posts.map { it.id }.toSet())
                assertTrue(f.app.taskRuntime.tasks.value.isEmpty())
            } finally { scene.close() }
        } finally { f.close() }
    }
}
