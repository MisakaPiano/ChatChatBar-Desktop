@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class DesktopSessionImageGalleryTest {
    private fun bytes(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() }
    }
    private suspend fun repository(block: suspend (Path, JsonFileStorage, ChatRepository) -> Unit) {
        val root = Files.createTempDirectory("session-gallery-")
        try {
            val storage = JsonFileStorage(root)
            val repo = ChatRepository(storage)
            repo.createSession(ChatSession("s", "card", "Synthetic gallery", createdAt = 1, updatedAt = 1))
            block(root, storage, repo)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `strict full history projection preserves occurrences and never repairs indexes or restores archives`() = runBlocking {
        repository { root, storage, repo ->
            val old = ChatMessage("old", "s", MessageRole.ASSISTANT, "large source body".repeat(1000),
                images = listOf("same.png", "same.png", OMITTED_SAVE_SLOT_IMAGE_PREFIX + "omitted"),
                alternatives = listOf("one", "two"), createdAt = 1, updatedAt = 1, orderKey = 1)
            val latest = old.copy(id = "new", images = listOf("same.png", "missing.png"), alternatives = emptyList(), createdAt = 99, orderKey = 99)
            for (message in listOf(latest, old)) storage.saveEntity("chat_messages", "s_${message.id}", message, ChatMessage.serializer())
            val unrelated = root.resolve("entities/chat_messages/other_broken.json")
            Files.writeString(unrelated, "not json")
            Files.createDirectories(root.resolve("entities/chat_message_indexes"))
            Files.writeString(root.resolve("entities/chat_message_indexes/s.json"), "invalid index remains unchanged")
            Files.createDirectories(root.resolve("entities/save_slots"))
            Files.writeString(root.resolve("entities/save_slots/archived.json"), "archive-only-image.png")
            Files.write(root.resolve("unreferenced.png"), byteArrayOf(1))
            val before = bytes(root)
            val gallery = DesktopSessionImageGallery(ChatRepository(JsonFileStorage(root))).read("s")
            assertEquals(2, gallery.messageCount)
            assertEquals(1, gallery.omittedCount)
            assertEquals(listOf("old" to 0, "old" to 1, "new" to 0, "new" to 1), gallery.occurrences.map { it.key })
            assertEquals(listOf("same.png", "same.png", "same.png", "missing.png"), gallery.occurrences.map { it.reference })
            assertEquals(listOf(2, 2, 1, 1), gallery.occurrences.map { it.alternativeCount })
            assertEquals(before, bytes(root))
        }
    }

    @Test fun `corrupt or foreign selected message refuses a false complete gallery without writes`() = runBlocking {
        repository { root, storage, repo ->
            val selected = root.resolve("entities/chat_messages/s_bad.json")
            Files.createDirectories(selected.parent)
            Files.writeString(selected, "bad selected json")
            val before = bytes(root)
            assertTrue(runCatching { DesktopSessionImageGallery(repo).read("s") }.isFailure)
            assertEquals(before, bytes(root))
            storage.saveEntity("chat_messages", "s_bad", ChatMessage("bad", "other", MessageRole.ASSISTANT,
                "foreign record", createdAt = 1, updatedAt = 1), ChatMessage.serializer())
            val foreign = bytes(root)
            assertTrue(runCatching { DesktopSessionImageGallery(repo).read("s") }.isFailure)
            assertEquals(foreign, bytes(root))
        }
    }

    @Test fun `actual controller gallery finds old unloaded image and reveal preserves draft`() = runBlocking {
        val root = Files.createTempDirectory("gallery-controller-")
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root.resolve("data"),
            DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json")),
            secretStoreFactory = { InMemoryDesktopSecretStore() }, bundledAssets = { path ->
                check(path == "presets/manifest.json"); """{"entries":[]}""".toByteArray()
            })
        try {
            val card = CharacterCard.create("Synthetic gallery")
            app.characterRepository.save(card)
            app.chatRepository.createSession(ChatSession("s", card.id, "Synthetic gallery", createdAt = 1, updatedAt = 1))
            repeat(180) { index ->
                val m = ChatMessage("m$index", "s", if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                    "Synthetic turn $index", images = if (index == 1 || index == 179) listOf("same.png") else emptyList(),
                    createdAt = index.toLong() + 1, updatedAt = index.toLong() + 1, orderKey = index.toLong() + 1)
                app.jsonFileStorage.saveEntity("chat_messages", "s_${m.id}", m, ChatMessage.serializer())
            }
            val controller = app.primaryChatController
            controller.refresh(); controller.selectSession("s")
            assertTrue(controller.state.value.messages.none { it.id == "m1" }, "Old source must actually be outside the bounded window")
            controller.editComposer("Unsent gallery draft")
            controller.persistComposer()
            val before = bytes(app.appDataRoot)
            val snapshot = controller.sessionImageGallery("s")
            assertEquals(listOf("m1", "m179"), snapshot.occurrences.map { it.messageId })
            assertEquals(before, bytes(app.appDataRoot))
            assertTrue(controller.revealMessage("s", "m1"))
            assertTrue(controller.state.value.messages.any { it.id == "m1" })
            assertEquals("Unsent gallery draft", controller.state.value.composerDraft)
            assertFalse(controller.revealMessage("other", "m1"))
        } finally { app.close(); root.toFile().deleteRecursively() }
    }

    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.label(value: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true ||
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
    private suspend fun ImageComposeScene.frames() { repeat(8) { render(System.nanoTime()).close(); delay(20) } }

    @Test fun `narrow and wide gallery keeps missing occurrences visible and returns exact source`() = runBlocking(edt) {
        val occurrences = List(80) { index -> DesktopSessionImageOccurrence("m$index", 0, "missing", MessageRole.ASSISTANT, 0, 2) }
        val snapshot = DesktopSessionImageGallerySnapshot("s", "Synthetic gallery", occurrences, 80, 1)
        for (width in listOf(380, 1100)) {
            var preview: Int? = null
            var source: DesktopSessionImageOccurrence? = null
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val scene = ImageComposeScene(width, 720, coroutineContext = edt) {
                DesktopSessionImageGalleryContent(snapshot, false, null, read = { reads.incrementAndGet(); error("Synthetic missing image") },
                    onRefresh = {}, onPreview = { preview = it }, onSource = { source = it })
            }
            try {
                scene.frames()
                assertTrue(scene.nodes().any { it.label("Synthetic gallery · 全部历史 80 处图片") })
                assertTrue(reads.get() < 80, "Only visible grid cells should decode")
                val first = scene.nodes().first { it.label("查看会话图片 1") && it.config.contains(SemanticsActions.OnClick) }
                assertTrue(first.boundsInRoot.width > 0 && first.boundsInRoot.right <= width)
                first.config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(0, preview)
                scene.nodes().first { it.label("回到消息") && it.config.contains(SemanticsActions.OnClick) }
                    .config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(occurrences.first(), source)
                assertTrue(scene.nodes().any { it.label("Synthetic missing image") })
                System.getenv("CCB_SESSION_GALLERY_REVIEW_DIR")?.let { folder ->
                    Files.createDirectories(Path.of(folder))
                    scene.render().use { image -> image.encodeToData()!!.use { Files.write(Path.of(folder, "gallery-$width.png"), it.bytes) } }
                }
            } finally { scene.close() }
        }
    }

    @Test fun `viewer source action reads current page identity before another composition`() = runBlocking(edt) {
        val selection = DesktopViewerSelection(listOf("same", "middle", "same"), 2)
        var index = -1
        val scene = ImageComposeScene(400, 120, coroutineContext = edt) { DesktopViewerSourceAction(selection) { index = it } }
        try {
            scene.frames()
            val action = scene.nodes().first { it.label("回到来源消息") && it.config.contains(SemanticsActions.OnClick) }
                .config[SemanticsActions.OnClick].action!!
            action(); assertEquals(2, index)
            selection.previous(); action(); assertEquals(1, index)
            selection.previous(); action(); assertEquals(0, index)
        } finally { scene.close() }
    }
}
