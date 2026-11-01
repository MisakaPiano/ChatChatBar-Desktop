package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class DesktopMomentAttempt(val task: MomentTask, val placeholder: MomentPost)

/** One mutation owner for UI and generation. A task's durable postId precedes its first request. */
internal class DesktopMomentStore(
    private val storage: JsonFileStorage,
    private val gate: DesktopDataOperationCoordinator,
    private val deletionCleanup: DesktopMomentImageCleanup? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    suspend fun posts(): List<MomentPost> = storage.scanEntitiesStrict("moment_posts", MomentPost.serializer()).map {
        val value = (it.result as? JsonFileStorage.EntityReadResult.Valid<MomentPost>)?.value
            ?: error("朋友圈记录不可读取；保留原文件")
        check(value.id == it.storageId); requireMomentStorageId(value.id)
        requireMomentStorageId(value.characterCardId); requireMomentStorageId(value.sessionId); value
    }.sortedByDescending { it.generatedAt }

    suspend fun tasks(): List<MomentTask> = storage.scanEntitiesStrict("moment_tasks", MomentTask.serializer()).map {
        val value = (it.result as? JsonFileStorage.EntityReadResult.Valid<MomentTask>)?.value
            ?: error("朋友圈计划不可读取；保留原文件")
        check(value.id == it.storageId); requireMomentStorageId(value.id)
        requireMomentStorageId(value.characterCardId); requireMomentStorageId(value.sessionId)
        value.postId?.let(::requireMomentStorageId); value
    }.sortedBy { it.scheduledAt }

    private suspend fun post(id: String): MomentPost? {
        requireMomentStorageId(id)
        return when (val read = storage.readEntityStrict("moment_posts", id, MomentPost.serializer())) {
        JsonFileStorage.EntityReadResult.Missing -> null
        is JsonFileStorage.EntityReadResult.Valid -> read.value.also { check(it.id == id) }
        else -> error("朋友圈记录不可读取；保留原文件")
        }
    }
    private suspend fun save(post: MomentPost) = storage.saveEntity("moment_posts", post.id, post, MomentPost.serializer())
    suspend fun saveTask(task: MomentTask) = storage.saveEntity("moment_tasks", task.id, task, MomentTask.serializer())
    private suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        gate.withImmediateNormalOperation { withContext(NonCancellable) { block() } }
    }

    suspend fun begin(task: MomentTask, card: CharacterCard, session: ChatSession,
        retryPost: MomentPost? = null, recoverMissingPost: Boolean = false): DesktopMomentAttempt = mutate {
        check(task.characterCardId == card.id && task.sessionId == session.id && session.characterCardId == card.id)
        val existing = tasks().firstOrNull { it.id == task.id }
        if (recoverMissingPost) {
            check(retryPost == null && existing == task &&
                task.status in setOf(MomentTaskStatus.RUNNING, MomentTaskStatus.FAILED))
            check(task.postId != null && post(task.postId) == null) { "任务关联动态已变化，请重新读取" }
        } else if (retryPost == null) {
            check(existing == null || existing == task && existing.status == MomentTaskStatus.PENDING)
        } else {
            check(post(retryPost.id) == retryPost && retryPost.isPlaceholder) { "动态已更改，请重新读取" }
            check(retryPost.characterCardId == card.id && retryPost.sessionId == session.id && retryPost.scheduledAt == task.scheduledAt)
            check(existing == null || existing.postId == retryPost.id && existing.status != MomentTaskStatus.COMPLETED)
        }
        val id = retryPost?.id ?: task.postId?.takeIf { recoverMissingPost } ?: UUID.randomUUID().toString()
        val running = task.copy(status = MomentTaskStatus.RUNNING, postId = id, failureReason = null, updatedAt = clock())
        val sender = card.characters.firstOrNull()
        val placeholder = retryPost?.copy(failureReason = "正在生成；退出或中断后需手动重试", updatedAt = clock())
            ?: MomentPost.createPlaceholder(card.id, session.id, sender?.id,
                sender?.name?.takeIf(String::isNotBlank) ?: card.name,
                sender?.appearanceImage?.takeIf(String::isNotBlank) ?: card.avatar,
                "正在生成；退出或中断后需手动重试", scheduledAt = task.scheduledAt, generatedAt = clock()).copy(id = id)
        // If interrupted between these writes, RUNNING is never selected for automatic replay.
        saveTask(running)
        check(retryPost != null || post(id) == null)
        save(placeholder)
        DesktopMomentAttempt(running, placeholder)
    }

    private suspend fun current(attempt: DesktopMomentAttempt): MomentPost {
        val current = post(attempt.placeholder.id) ?: error("动态已删除，本次结果不再写入")
        check(current.isPlaceholder && current.characterCardId == attempt.task.characterCardId &&
            current.sessionId == attempt.task.sessionId && current.scheduledAt == attempt.task.scheduledAt) {
            "动态来源已更改，本次结果不再写入"
        }
        val task = tasks().singleOrNull { it.id == attempt.task.id }
        check(task?.status == MomentTaskStatus.RUNNING && task.postId == current.id)
        return current
    }

    suspend fun checkpoint(attempt: DesktopMomentAttempt, encoded: String) = mutate {
        val latest = current(attempt)
        save(latest.copy(generationCheckpoint = encoded, updatedAt = clock()))
    }

    suspend fun complete(attempt: DesktopMomentAttempt, generated: MomentPost) = mutate {
        val latest = current(attempt)
        check(generated.characterCardId == latest.characterCardId && generated.sessionId == latest.sessionId)
        save(generated.copy(id = latest.id, userLiked = latest.userLiked, createdAt = latest.createdAt))
        saveTask(attempt.task.copy(status = MomentTaskStatus.COMPLETED, failureReason = null, updatedAt = clock()))
    }

    suspend fun failed(attempt: DesktopMomentAttempt, reason: String) = mutate {
        val latest = current(attempt)
        save(latest.copy(failureReason = reason, updatedAt = clock()))
        saveTask(attempt.task.copy(status = MomentTaskStatus.FAILED, failureReason = reason, updatedAt = clock()))
    }

    suspend fun skipped(attempt: DesktopMomentAttempt, reason: String) = mutate {
        current(attempt)
        saveTask(attempt.task.copy(status = MomentTaskStatus.SKIPPED, postId = null, failureReason = reason, updatedAt = clock()))
        storage.deleteEntity<MomentPost>("moment_posts", attempt.placeholder.id)
    }

    suspend fun toggleLike(id: String) = mutate {
        val latest = post(id) ?: return@mutate
        check(!latest.isPlaceholder)
        save(latest.copy(userLiked = !latest.userLiked, updatedAt = clock()))
    }

    suspend fun skipUnstarted(task: MomentTask, reason: String) = mutate {
        check(tasks().singleOrNull { it.id == task.id } == task && task.status == MomentTaskStatus.PENDING)
        saveTask(task.copy(status = MomentTaskStatus.SKIPPED, failureReason = reason, updatedAt = clock()))
    }
    suspend fun rebindPending(task: MomentTask, sessionId: String): MomentTask = mutate {
        check(tasks().singleOrNull { it.id == task.id } == task && task.status == MomentTaskStatus.PENDING)
        task.copy(sessionId = sessionId, updatedAt = clock()).also { saveTask(it) }
    }
    suspend fun deletePendingFutureTasks(now: Long) = mutate {
        tasks().filter { it.status == MomentTaskStatus.PENDING && it.scheduledAt > now }.forEach {
            storage.deleteEntity<MomentTask>("moment_tasks", it.id)
        }
    }
    suspend fun edit(id: String, text: String) = mutate {
        val latest = post(id) ?: error("动态已删除")
        check(!latest.isPlaceholder); require(text.isNotBlank())
        if (latest.text != text.trim()) save(latest.copy(text = text.trim(), updatedAt = clock()))
    }

    private fun sameImageSource(a: MomentPost, b: MomentPost) = !a.isPlaceholder && !b.isPlaceholder &&
        a.id == b.id && a.characterCardId == b.characterCardId && a.sessionId == b.sessionId &&
        a.senderCharacterId == b.senderCharacterId && a.text == b.text && a.imagePath == b.imagePath &&
        a.imagePrompt == b.imagePrompt && a.generatedImageMetadata == b.generatedImageMetadata && a.imageBrief == b.imageBrief

    suspend fun imageSource(expected: MomentPost): MomentPost = mutate {
        val latest = post(expected.id) ?: error("动态已删除")
        check(sameImageSource(latest, expected)) { "动态或图片已变化，请重新打开" }
        latest
    }
    suspend fun replaceImage(expected: MomentPost, image: DesktopMomentImageResult): String? = mutate {
        val latest = post(expected.id) ?: error("动态已删除，本次图片不再写入")
        check(sameImageSource(latest, expected)) { "动态或图片已变化，本次图片不再写入" }
        check(image.metadata.imagePath == image.path)
        val receipt = latest.imagePath?.takeIf { it.startsWith("images/") }?.let { deletionCleanup?.prepareCandidate(it) }
        save(latest.copy(imagePath = image.path, imagePrompt = image.imagePrompt, generatedImageMetadata = image.metadata,
            imageBrief = image.imageBrief, updatedAt = clock()))
        receipt
    }
    suspend fun delete(id: String) {
        val receipt = mutate {
            val latest = post(id) ?: return@mutate null
            val prepared = deletionCleanup?.prepare(latest)
            storage.deleteEntity<MomentPost>("moment_posts", id)
            prepared
        }
        // Production cleaner owns exclusive admission: never call it from inside normal mutation.
        if (receipt != null) deletionCleanup?.finish(receipt)
    }

    /** Explicit no-network repair only; a saved final post can finish its durable task link. */
    suspend fun reconcileSaved(): Int = mutate {
        val posts = posts().associateBy { it.id }; var count = 0
        tasks().filter { it.status == MomentTaskStatus.RUNNING }.forEach { task ->
            val saved = posts[task.postId]
            if (saved != null && !saved.isPlaceholder && saved.characterCardId == task.characterCardId &&
                saved.sessionId == task.sessionId && saved.scheduledAt == task.scheduledAt) {
                saveTask(task.copy(status = MomentTaskStatus.COMPLETED, failureReason = null, updatedAt = clock())); count++
            }
        }; count
    }
}
