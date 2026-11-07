@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopMomentAlbumNavigationStateTest {
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private class Fixture {
        val parent = Files.createTempDirectory("moment-album-navigation-")
        val root = Files.createDirectory(parent.resolve("data"))
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() })
        val store = DesktopMomentStore(app.jsonFileStorage, app.dataOperationCoordinator)
        val controller = DesktopMomentsController(store, app.settingsRepository, app.characterRepository,
            app.chatRepository, app.dataOperationCoordinator, app.taskRuntime,
            DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("Album navigation must not generate") })
        val firstDate = LocalDate.of(2026, 11, 10)
        suspend fun seed() {
            for (card in listOf("a", "b")) {
                app.jsonFileStorage.saveEntity("character_cards", card, CharacterCard(card, "Card $card", createdAt = 1, updatedAt = 1), CharacterCard.serializer())
                app.chatRepository.createSession(ChatSession("session-$card", card, "Session $card", createdAt = 1, updatedAt = 1))
            }
            repeat(60) { index ->
                val card = if (index % 2 == 0) "a" else "b"
                val time = firstDate.plusDays((index % 3).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + index
                val post = MomentPost("post-$index", card, "session-$card", senderName = "Person $card",
                    text = "needle post $index", scheduledAt = time, generatedAt = time)
                app.jsonFileStorage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
            }
            app.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            withTimeout(10_000) { controller.state.first { it.posts.size == 60 && it.enabled } }
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
    private fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.has(label) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private fun ImageComposeScene.search() = nodes().first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("搜索相册") == true && it.config.contains(SemanticsActions.SetText)
    }
    private suspend fun ImageComposeScene.frames() { repeat(12) { render(System.nanoTime()).close(); delay(18) } }
    private fun ImageComposeScene.grid() = nodes().single {
        it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    }

    @Test fun `actual parent album keeps query date granularity and selected card after locating its original post`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.seed()
            val originals = f.controller.state.value.posts.associate { it.id to Files.readAllBytes(f.root.resolve("entities/moment_posts/${it.id}.json")) }
            val scene = ImageComposeScene(1000, 850, coroutineContext = edt) { DesktopMomentsPanel(f.controller) }
            try {
                scene.frames(); scene.click("朋友圈相册"); scene.frames()
                assertTrue(scene.search().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("needle"))); scene.frames()
                scene.click("日期 · 全部日期 ▾"); scene.frames(); scene.click(f.firstDate.toString()); scene.frames()
                scene.click("日期范围 · 按日 ▾"); scene.frames(); scene.click("按月"); scene.frames()
                scene.click("相册分组 a"); scene.frames()
                val tile = scene.nodes().first { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("返回朋友圈动态 ") } == true &&
                    n.config.contains(SemanticsActions.OnClick) }
                val postId = tile.config[SemanticsProperties.ContentDescription].first { it.startsWith("返回朋友圈动态 ") }.removePrefix("返回朋友圈动态 ")
                assertEquals("a", f.controller.state.value.posts.single { it.id == postId }.characterCardId)
                assertTrue(tile.config[SemanticsActions.OnClick].action!!.invoke()); scene.frames()
                assertTrue(scene.nodes().any { it.has("朋友圈相册") })
                scene.click("朋友圈相册"); scene.frames()
                assertEquals("needle", scene.search().config[SemanticsProperties.EditableText].text)
                assertTrue(scene.nodes().any { it.has("日期 · ${f.firstDate} ▾") })
                assertTrue(scene.nodes().any { it.has("日期范围 · 按月 ▾") })
                assertTrue(scene.nodes().any { it.has("返回相册分组") })
                assertTrue(scene.nodes().any { it.has("Card a") })
                originals.forEach { (id, bytes) -> assertContentEquals(bytes, Files.readAllBytes(f.root.resolve("entities/moment_posts/$id.json"))) }
            } finally { scene.close() }
        } finally { f.close() }
    }

    @Test fun `actual album filter edit returns a scrolled grid to top even when matched posts stay identical`() = runBlocking(edt) {
        val f = Fixture()
        try {
            f.seed()
            val scene = ImageComposeScene(800, 700, coroutineContext = edt) { DesktopMomentsPanel(f.controller) }
            try {
                scene.frames(); scene.click("朋友圈相册"); scene.frames(); scene.click("按角色卡分组"); scene.frames()
                assertTrue(scene.grid().config[SemanticsActions.ScrollBy].action!!.invoke(0f, 1000f)); scene.frames()
                assertTrue(scene.grid().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
                assertTrue(scene.search().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("needle"))); scene.frames()
                assertEquals(0f, scene.grid().config[SemanticsProperties.VerticalScrollAxisRange].value())
                assertEquals(60, f.controller.state.value.posts.size)
            } finally { scene.close() }
        } finally { f.close() }
    }
}
