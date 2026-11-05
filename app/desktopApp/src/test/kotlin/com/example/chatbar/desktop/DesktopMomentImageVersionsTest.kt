package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopMomentImageVersionsTest {
    @Test fun `held image generation selects its appended image but refuses changed content candidates or deleted post`() = runBlocking {
        val root = Files.createTempDirectory("moment-version-concurrency-")
        val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate)
            val store = DesktopMomentStore(storage, gate)
            fun image(i: Int): DesktopMomentImageResult {
                val path = "images/concurrent-$i.png"
                return DesktopMomentImageResult(path, GeneratedImageMetadata(path, "Scene $i", negativePrompt = "Negative", sizePreset = "SQUARE", width = 2, height = 2),
                    "Prompt $i", "Brief $i")
            }
            var post = MomentPost("post", "card", "session", senderName = "Synthetic", text = "Original", scheduledAt = 1, generatedAt = 1)
            repeat(3) { post = DesktopMomentImageVersions.append(post, image(it), it + 1L) }
            storage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val generated = async {
                val expected = store.imageSource(post)
                entered.complete(Unit); release.await()
                store.replaceImage(expected, image(3))
            }
            entered.await()
            val choice = post.desktopImageVersions[1]
            store.selectImage(post, choice.id)
            val selected = store.posts().single()
            assertEquals(choice.imagePath, selected.imagePath)
            assertFalse(generated.isCompleted)
            release.complete(Unit); generated.await()
            val reopened = DesktopMomentStore(JsonFileStorage(root, gate), gate).posts().single()
            assertEquals(image(3).path, reopened.imagePath)
            assertEquals(image(3).imagePrompt, reopened.imagePrompt)
            assertEquals(image(3).metadata, reopened.generatedImageMetadata)
            assertEquals(image(3).imageBrief, reopened.imageBrief)
            assertEquals(4, reopened.desktopImageVersions.size)
            assertEquals(image(3).path, reopened.desktopImageVersions.last().imagePath)
            assertFails { store.replaceImage(selected, image(4)) } // Another append changed the captured candidate set.
            store.edit(post.id, "Explicitly edited text")
            assertFails { store.replaceImage(reopened, image(4)) }
            val edited = store.posts().single()
            assertEquals("Explicitly edited text", edited.text)
            store.delete(post.id)
            assertFails { store.replaceImage(edited, image(4)) }
            assertTrue(store.posts().isEmpty())
        } finally { gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }
    @Test fun `actual owner retains four candidates across reopen selection stale completion removal and post deletion`() = runBlocking {
        val root = Files.createTempDirectory("moment-versions-")
        val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate)
            val media = DesktopCharacterResourceStore(root)
            val cleanup = DesktopMomentImageCleanup(root, storage, gate, DesktopOwnedImageCleanup(root, media, gate))
            var store = DesktopMomentStore(storage, gate, cleanup)
            Files.createDirectory(root.resolve("images"))
            val png = DesktopImageEditing.png(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))
            fun image(index: Int): DesktopMomentImageResult {
                val path = "images/v$index.png"; Files.write(root.resolve(path), png)
                return DesktopMomentImageResult(path, GeneratedImageMetadata(path, "Scene $index", negativePrompt = "Negative $index",
                    sizePreset = "SQUARE", width = 2, height = 2), "Prompt $index", "Brief $index")
            }
            val old = image(0)
            val original = MomentPost("post", "card", "session", senderName = "Synthetic", senderAvatar = old.path,
                text = "Original text", userLiked = true, imagePath = old.path, imagePrompt = old.imagePrompt,
                generatedImageMetadata = old.metadata, imageBrief = old.imageBrief, scheduledAt = 1, generatedAt = 1)
            storage.saveEntity("moment_posts", original.id, original, MomentPost.serializer())
            for (i in 1..3) {
                val before = store.posts().single()
                val result = image(i)
                val receipt = cleanup.prepareCandidate(result.path)
                assertNull(store.replaceImage(before, result))
                cleanup.finish(receipt) // The worker's exact generated-candidate receipt must not delete a committed version.
                assertTrue(Files.exists(root.resolve(result.path)))
            }
            store = DesktopMomentStore(JsonFileStorage(root, gate), gate, cleanup)
            val all = store.posts().single()
            assertEquals((0..3).map { "images/v$it.png" }, all.desktopImageVersions.map { it.imagePath })
            assertEquals("images/v3.png", all.imagePath)
            assertEquals(all.desktopImageVersions.last().generatedImageMetadata, all.generatedImageMetadata)
            assertEquals(original.text, all.text); assertTrue(all.userLiked)
            store.selectImage(all, all.desktopImageVersions.last().id)
            val selected = store.posts().single()
            assertEquals("images/v3.png", selected.imagePath)
            assertEquals(selected.desktopImageVersions.last().generatedImageMetadata, selected.generatedImageMetadata)
            store.edit(selected.id, "Explicit edit while a previous generation was pending")
            val edited = store.posts().single()
            val stale = image(4); val staleReceipt = cleanup.prepareCandidate(stale.path)
            assertFails { store.replaceImage(all, stale) }
            cleanup.finish(staleReceipt)
            assertFalse(Files.exists(root.resolve(stale.path)))
            assertEquals(edited, store.posts().single())
            assertTrue((0..3).all { Files.exists(root.resolve("images/v$it.png")) })
            // A second post shares one retained candidate. Removing/deleting the first must keep that resource.
            storage.saveEntity("moment_posts", "other", original.copy(id = "other", imagePath = "images/v1.png",
                generatedImageMetadata = null, imagePrompt = "Shared", senderAvatar = null), MomentPost.serializer())
            val receipt = store.removeImage(edited, edited.desktopImageVersions[2].id)
            cleanup.finish(assertNotNull(receipt))
            assertFalse(Files.exists(root.resolve("images/v2.png")))
            assertTrue(Files.exists(root.resolve(old.path)))
            store.delete(original.id)
            assertFalse(Files.exists(root.resolve(old.path)))
            assertTrue(Files.exists(root.resolve("images/v1.png")))
            assertFalse(Files.exists(root.resolve("images/v3.png")))
            assertEquals("other", store.posts().single().id)
        } finally { gate.closeAndDrain(); check(root.toFile().deleteRecursively()) }
    }
}
