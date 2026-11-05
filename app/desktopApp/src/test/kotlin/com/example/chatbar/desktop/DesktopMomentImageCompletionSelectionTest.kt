@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.desktop.security.InMemoryDesktopSecretStore
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopMomentImageCompletionSelectionTest {
    private enum class Result { SUCCESS, FAILURE, CANCEL, CONTENT, CANDIDATES, DELETE_POST, SESSION, CARD }
    private val edt = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
    }

    @Test fun `real held image owner selects only successful appended image and retains saved state on failure cancellation or stale source`() = runBlocking {
        for (result in Result.entries) {
            val f = Fixture(result)
            try {
                f.prepare()
                f.owner.generateImage(f.post)
                withTimeout(5_000) { f.entered.await() }
                assertTrue(f.owner.state.value.busy)
                f.owner.selectImageVersion(f.owner.state.value.posts.single(), f.post.desktopImageVersions.first().id)
                assertEquals(f.post.desktopImageVersions.first().imagePath, f.store.posts().single().imagePath)
                when (result) {
                    Result.CONTENT -> f.store.edit(f.post.id, "Explicit source edit")
                    Result.CANDIDATES -> f.store.replaceImage(f.store.posts().single(), f.image(3))
                    Result.DELETE_POST -> DesktopMomentStore(f.app.jsonFileStorage, f.app.dataOperationCoordinator).delete(f.post.id)
                    Result.SESSION -> f.app.chatRepository.updateSession(f.session.copy(characterCardId = f.otherCard.id))
                    Result.CARD -> f.app.characterRepository.delete(f.card.id)
                    else -> Unit
                }
                val beforeCompletion = f.store.posts().singleOrNull()
                if (result == Result.CANCEL) f.owner.stop()
                f.release.complete(Unit)
                val work = withTimeout(5_000) { f.owner.workRows.first { it.isNotEmpty() && it.none { row -> row.active } } }.single()
                assertEquals(when (result) {
                    Result.SUCCESS -> DesktopWorkPhase.COMPLETED
                    Result.CANCEL -> DesktopWorkPhase.CANCELLED
                    else -> DesktopWorkPhase.FAILED
                }, work.phase, result.name)
                val reopened = DesktopMomentStore(JsonFileStorage(f.root, f.app.dataOperationCoordinator), f.app.dataOperationCoordinator)
                    .posts().singleOrNull()
                if (result == Result.SUCCESS) {
                    val saved = assertNotNull(reopened)
                    assertEquals(f.output.path, saved.imagePath)
                    assertEquals(f.output.imagePrompt, saved.imagePrompt)
                    assertEquals(f.output.metadata, saved.generatedImageMetadata)
                    assertEquals(f.output.imageBrief, saved.imageBrief)
                    assertEquals(saved.desktopImageVersions.last().imagePath, saved.imagePath)
                    assertEquals(f.post.desktopImageVersions + saved.desktopImageVersions.last(), saved.desktopImageVersions)
                    assertEquals(f.post.text, saved.text); assertTrue(saved.userLiked)
                    assertTrue(Files.exists(f.root.resolve(f.output.path)))
                } else {
                    assertEquals(beforeCompletion, reopened, result.name)
                    assertFalse(Files.exists(f.root.resolve(f.output.path)), result.name)
                }
                assertEquals(reopened, f.owner.state.value.posts.singleOrNull())
                if (result != Result.DELETE_POST) {
                    assertTrue(f.post.desktopImageVersions.all { Files.exists(f.root.resolve(it.imagePath)) }, result.name)
                }
            } finally { f.close() }
        }
    }

    @Test fun `actual busy feed shows selected old image then appended newest image and matching count after completion and reopen`() = runBlocking(edt) {
        for (width in listOf(420, 1100)) {
            val f = Fixture(Result.SUCCESS)
            try {
                f.prepare()
                val scene = ImageComposeScene(width, 740, coroutineContext = edt) {
                    val state by f.owner.state.collectAsState()
                    val scope = rememberCoroutineScope()
                    DesktopMomentFeedCard(state.posts.single(), f.media, state.busy,
                        onOpenSession = {}, onOpenCard = {}, onPreviewAvatar = {}, onPreviewImage = {},
                        onEdit = {}, onCopy = {}, onLike = {}, onRetry = {}, onGenerateImage = {},
                        onRegenerateImage = {}, onVersions = {}, onSelectVersion = { version ->
                            scope.launch { f.owner.selectImageVersion(f.owner.state.value.posts.single(), version) }
                        }, onDelete = {})
                }
                try {
                    var nanos = 0L
                    suspend fun frames() { repeat(5) { nanos += 16_666_667L; scene.render(nanos).close(); delay(10) } }
                    fun nodes(): List<SemanticsNode> {
                        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                        return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    }
                    fun SemanticsNode.label(text: String): Boolean =
                        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true || children.any { it.label(text) }
                    fun button(text: String) = nodes().first { it.label(text) && it.config.contains(SemanticsActions.OnClick) }
                    suspend fun awaitImage(path: String, count: String) = withTimeout(5_000) {
                        while (nodes().none { it.label(count) } || nodes().none {
                            it.config.getOrNull(DesktopDecodedImageIdentity) == desktopDecodedImageIdentity(path)
                        }) frames()
                    }
                    frames(); awaitImage(f.post.imagePath!!, "2 / 2")
                    f.owner.generateImage(f.owner.state.value.posts.single())
                    withTimeout(5_000) { f.entered.await() }; frames()
                    val previous = button("上一张动态图片")
                    assertFalse(previous.config.contains(SemanticsProperties.Disabled))
                    assertTrue(previous.config[SemanticsActions.OnClick].action!!.invoke())
                    awaitImage(f.post.desktopImageVersions.first().imagePath, "1 / 2")
                    assertTrue(f.owner.workRows.value.single().active)
                    f.release.complete(Unit)
                    withTimeout(5_000) { f.owner.workRows.first { it.isNotEmpty() && it.none { row -> row.active } } }
                    awaitImage(f.output.path, "3 / 3")
                    assertTrue(button("下一张动态图片").config.contains(SemanticsProperties.Disabled))
                    assertFalse(button("上一张动态图片").config.contains(SemanticsProperties.Disabled))
                    val reopened = DesktopMomentStore(JsonFileStorage(f.root, f.app.dataOperationCoordinator), f.app.dataOperationCoordinator)
                        .posts().single()
                    assertEquals(f.output.path, reopened.imagePath)
                    assertEquals(f.output.metadata, reopened.generatedImageMetadata)
                    assertEquals(3, reopened.desktopImageVersions.size)
                    System.getenv("CCB_FORMAT_AI_REVIEW_DIR")?.let { directory ->
                        val path = Path.of(directory).resolve("moment-success-autoselect-$width.png")
                        Files.createDirectories(path.parent)
                        nanos += 16_666_667L
                        scene.render(nanos).use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
                    }
                } finally { scene.close() }
            } finally { f.close() }
        }
    }

    private class Fixture(private val result: Result) {
        val parent = Files.createTempDirectory("moment-completion-selection-")
        val root = Files.createDirectory(parent.resolve("data"))
        val app = DesktopAppContainer(DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE,
            parent.resolve("bootstrap.json")), secretStoreFactory = { InMemoryDesktopSecretStore() },
            bundledAssets = { """{"entries":[]}""".toByteArray() })
        val media = DesktopCharacterResourceStore(root)
        val cleanup = DesktopMomentImageCleanup(root, app.jsonFileStorage, app.dataOperationCoordinator,
            DesktopOwnedImageCleanup(root, media, app.dataOperationCoordinator))
        val store = DesktopMomentStore(app.jsonFileStorage, app.dataOperationCoordinator, cleanup)
        val card = CharacterCard("card", "Synthetic card", createdAt = 1, updatedAt = 1)
        val otherCard = card.copy(id = "other")
        val session = ChatSession("session", card.id, "Synthetic session", createdAt = 1, updatedAt = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var post: MomentPost
        lateinit var output: DesktopMomentImageResult
        lateinit var owner: DesktopMomentsController

        suspend fun prepare() {
            app.characterRepository.save(card); app.characterRepository.save(otherCard)
            app.jsonFileStorage.saveEntity("chat_sessions", session.id, session, ChatSession.serializer())
            app.settingsRepository.updateAppSettings { it.copy(momentsEnabled = true) }
            Files.createDirectories(root.resolve("images"))
            post = MomentPost("post", card.id, session.id, senderName = "Synthetic", text = "Original post",
                userLiked = true, scheduledAt = 1, generatedAt = 1)
            repeat(2) { post = DesktopMomentImageVersions.append(post, image(it), it + 1L) }
            app.jsonFileStorage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
            output = image(2)
            owner = DesktopMomentsController(store, app.settingsRepository, app.characterRepository, app.chatRepository,
                app.dataOperationCoordinator, app.taskRuntime,
                DesktopMomentGenerator { _, _, _, _, _, _, _, _, _, _ -> error("No text or provider call") },
                finishImageCleanup = cleanup::finish, resources = media,
                imageGenerator = DesktopMomentImageGenerator { _, _, _, _, _, register, _ ->
                    register(cleanup.prepareCandidate(output.path))
                    entered.complete(Unit); release.await()
                    if (result == Result.FAILURE) error("Synthetic image generation failure")
                    output
                })
            withTimeout(5_000) { owner.state.first { it.enabled && it.posts.size == 1 } }
        }

        fun image(index: Int): DesktopMomentImageResult {
            val path = "images/generated-$index.png"
            val pixels = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
            pixels.createGraphics().let { graphics ->
                try { graphics.color = Color(40 + index * 40, 100, 180); graphics.fillRect(0, 0, 16, 16) }
                finally { graphics.dispose() }
            }
            Files.write(root.resolve(path), DesktopImageEditing.png(pixels))
            return DesktopMomentImageResult(path, GeneratedImageMetadata(path, "Scene $index", negativePrompt = "Negative",
                sizePreset = "SQUARE", width = 16, height = 16), "Prompt $index", "Brief $index")
        }

        suspend fun close() {
            release.complete(Unit)
            if (::owner.isInitialized) owner.closeAndDrain()
            app.close()
            check(parent.toFile().deleteRecursively())
        }
    }
}
