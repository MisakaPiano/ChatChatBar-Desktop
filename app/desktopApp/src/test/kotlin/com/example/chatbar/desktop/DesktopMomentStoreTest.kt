package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopMomentStoreTest {
    private val card = CharacterCard("card", "Fixture", createdAt = 0, updatedAt = 0)
    private val session = ChatSession("session", "card", "Fixture", createdAt = 0, updatedAt = 0)

    @Test fun `explicit sender edit keeps post image and task identity including card author with null person id`() = runBlocking {
        val root = Files.createTempDirectory("moment-author-edit-"); val gate = DesktopDataOperationCoordinator()
        try {
            val store = DesktopMomentStore(JsonFileStorage(root, gate), gate)
            val attempt = store.begin(MomentTask.create(card.id, session.id, 1), card, session)
            store.complete(attempt, attempt.placeholder.copy(isPlaceholder = false, text = "original",
                imagePath = "images/retained.png", imagePrompt = "retained prompt", userLiked = true))
            val original = store.posts().single(); val tasks = store.tasks()
            val sender = com.example.chatbar.domain.moment.MomentSender("person", "Selected", "images/avatar.png")
            store.edit(original.id, " edited ", sender)
            val changed = store.posts().single()
            assertEquals(original.copy(text = "edited", senderCharacterId = sender.characterId,
                senderName = sender.name, senderAvatar = sender.avatar, updatedAt = changed.updatedAt), changed)
            assertEquals(tasks, store.tasks())
            store.edit(changed.id, changed.text, com.example.chatbar.domain.moment.MomentSender(null, "Card", null))
            val cardAuthored = store.posts().single()
            assertNull(cardAuthored.senderCharacterId); assertNull(cardAuthored.senderAvatar)
            assertEquals("Card", cardAuthored.senderName)
            assertFails { store.edit(cardAuthored.id, "", sender) }
            assertFails { store.edit(cardAuthored.id, "valid", sender.copy(name = " ")) }
            assertEquals(cardAuthored, store.posts().single())
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `explicit recovery of missing placeholder preserves reserved identity and refuses stale task or saved final`() = runBlocking {
        val root = Files.createTempDirectory("moment-missing-placeholder-"); val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate); val store = DesktopMomentStore(storage, gate)
            store.saveTask(MomentTask.create(card.id, session.id, 1).copy(status = MomentTaskStatus.RUNNING, postId = "reserved"))
            val task = store.tasks().single()
            assertFailsWith<IllegalStateException> { store.begin(task, card, session) }
            assertFailsWith<IllegalStateException> { store.begin(task.copy(updatedAt = task.updatedAt + 1), card, session, recoverMissingPost = true) }
            val attempt = store.begin(task, card, session, recoverMissingPost = true)
            assertEquals("reserved", attempt.placeholder.id)
            store.complete(attempt, attempt.placeholder.copy(isPlaceholder = false, text = "recovered"))
            assertFailsWith<IllegalStateException> { store.begin(task, card, session, recoverMissingPost = true) }
            assertEquals("recovered", store.posts().single().text)
            assertEquals(MomentTaskStatus.COMPLETED, store.tasks().single().status)
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `image completion preserves concurrent like and refuses edited or deleted sources without resurrection`() = runBlocking {
        val root = Files.createTempDirectory("moment-image-publish-"); val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate); val media = DesktopCharacterResourceStore(root)
            val cleanup = DesktopMomentImageCleanup(root, storage, gate, DesktopOwnedImageCleanup(root, media, gate))
            val store = DesktopMomentStore(storage, gate, cleanup)
            val attempt = store.begin(MomentTask.create(card.id, session.id, 1), card, session)
            store.complete(attempt, attempt.placeholder.copy(isPlaceholder = false, text = "original"))
            val original = store.posts().single()
            val receipt = cleanup.prepareCandidate("images/new.png")
            Files.createDirectory(root.resolve("images")); Files.write(root.resolve("images/new.png"), byteArrayOf(1))
            val plan = NovelAiPromptPlan("synthetic tags", emptyList(), sizePreset = NovelAiImageSizePreset.SQUARE)
            val image = DesktopMomentImageResult("images/new.png", plan.toGeneratedImageMetadata("images/new.png", NovelAiImageSizePreset.SQUARE.imageSize), plan.baseCaption, "brief")
            store.toggleLike(original.id)
            assertNull(store.replaceImage(original, image))
            cleanup.finish(receipt)
            val saved = store.posts().single()
            assertTrue(saved.userLiked); assertEquals(original.text, saved.text); assertEquals(original.generatedAt, saved.generatedAt)
            assertTrue(Files.exists(root.resolve("images/new.png")))
            store.edit(saved.id, "edited while generating")
            assertFailsWith<IllegalStateException> { store.replaceImage(saved, image) }
            assertEquals("edited while generating", store.posts().single().text)
            store.delete(saved.id)
            assertFailsWith<IllegalStateException> { store.replaceImage(saved, image) }
            assertTrue(store.posts().isEmpty()); assertFalse(Files.exists(root.resolve("images/new.png")))
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `durable link and checkpoints precede completion and retry retains identity`() = runBlocking {
        val root = Files.createTempDirectory("moment-store-"); val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate); val store = DesktopMomentStore(storage, gate)
            val task = MomentTask.create(card.id, session.id, 1)
            val first = store.begin(task, card, session)
            assertEquals(first.placeholder.id, store.tasks().single().postId)
            store.checkpoint(first, "synthetic-checkpoint")
            store.failed(first, "synthetic interruption")
            val reopened = DesktopMomentStore(storage, gate)
            val post = reopened.posts().single(); val failed = reopened.tasks().single()
            assertEquals("synthetic-checkpoint", post.generationCheckpoint)
            assertEquals(MomentTaskStatus.FAILED, failed.status)
            val retry = reopened.begin(failed, card, session, post)
            reopened.complete(retry, post.copy(id = "discard-generated-id", text = "generated", isPlaceholder = false))
            assertEquals(post.id, reopened.posts().single().id)
            reopened.edit(post.id, "edited"); reopened.toggleLike(post.id)
            assertEquals("edited", reopened.posts().single().text)
            assertTrue(reopened.posts().single().userLiked)
            assertEquals(MomentTaskStatus.COMPLETED, reopened.tasks().single().status)
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `production cleaner runs after normal admission and retains referenced media`() = runBlocking {
        val root = Files.createTempDirectory("moment-cleaner-"); val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate); val resources = DesktopCharacterResourceStore(root)
            val cleaner = DesktopOwnedImageCleanup(root, resources, gate)
            val receipts = DesktopMomentImageCleanup(root, storage, gate, cleaner)
            val store = DesktopMomentStore(storage, gate, receipts)
            Files.createDirectory(root.resolve("images"))
            Files.write(root.resolve("images/own.png"), byteArrayOf(1))
            Files.write(root.resolve("images/shared.png"), byteArrayOf(2))
            val attempt = store.begin(MomentTask.create(card.id, session.id, 1), card, session)
            val final = attempt.placeholder.copy(isPlaceholder = false, imagePath = "images/own.png", senderAvatar = "images/shared.png", text = "one")
            store.complete(attempt, final)
            val other = final.copy(id = "other", imagePath = "images/shared.png")
            storage.saveEntity("moment_posts", other.id, other, MomentPost.serializer())
            store.delete(final.id)
            assertFalse(Files.exists(root.resolve("images/own.png")))
            assertTrue(Files.exists(root.resolve("images/shared.png")))
            assertEquals(listOf("other"), store.posts().map { it.id })
            assertEquals(DesktopDataOperationCoordinatorState.OPEN, gate.state)
            assertFailsWith<IllegalStateException> { store.complete(attempt, final) }
            assertEquals(listOf("other"), store.posts().map { it.id })
            assertTrue(receipts.resume())
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }

    @Test fun `explicit reconciliation links saved final and never resets interrupted work to pending`() = runBlocking {
        val root = Files.createTempDirectory("moment-reconcile-"); val gate = DesktopDataOperationCoordinator()
        try {
            val storage = JsonFileStorage(root, gate); val store = DesktopMomentStore(storage, gate)
            val attempt = store.begin(MomentTask.create(card.id, session.id, 1), card, session)
            storage.saveEntity("moment_posts", attempt.placeholder.id,
                attempt.placeholder.copy(isPlaceholder = false, text = "saved before interruption"), MomentPost.serializer())
            val interrupted = MomentTask.create(card.id, session.id, 2).copy(status = MomentTaskStatus.RUNNING, postId = "not-saved")
            store.saveTask(interrupted)
            assertEquals(1, store.reconcileSaved())
            assertEquals(MomentTaskStatus.COMPLETED, store.tasks().first { it.id == attempt.task.id }.status)
            assertEquals(interrupted, store.tasks().first { it.id == interrupted.id })
        } finally { gate.closeAndDrain(); root.toFile().deleteRecursively() }
    }
}
