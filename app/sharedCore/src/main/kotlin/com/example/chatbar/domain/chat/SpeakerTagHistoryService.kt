package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.data.local.entity.SpeakerTagRenameTask
import com.example.chatbar.data.local.entity.SpeakerTagMessageRewrite
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class SpeakerTagHistoryService(
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val rewrite: suspend (SpeakerTagRenameTask, suspend (List<SpeakerTagMessageRewrite>) -> Unit) -> Int =
        { task, persist -> chatRepository.rewriteSpeakerTagsForTask(task, persist) },
    private val updateTask: suspend (String, String, (SpeakerTagRenameTask) -> SpeakerTagRenameTask?) -> Unit =
        characterRepository::mutatePendingSpeakerTask,
    private val sourcesChanged: suspend (List<String>) -> Unit = {},
) {
    private val mutex = Mutex()

    fun createTask(
        characterCardId: String,
        expectedCardUpdatedAt: Long,
        renames: List<SpeakerTagRename>
    ): SpeakerTagRenameTask? {
        val effective = renames.filter { rename ->
            rename.oldName.isNotBlank() &&
                rename.newName.isNotBlank() &&
                !rename.oldName.trim().equals(rename.newName.trim(), ignoreCase = false)
        }
        if (effective.isEmpty()) return null
        return SpeakerTagRenameTask(
            id = UUID.randomUUID().toString(),
            characterCardId = characterCardId,
            expectedCardUpdatedAt = expectedCardUpdatedAt,
            renames = effective,
            createdAt = System.currentTimeMillis(),
            rewriteProtocolVersion = 1,
        )
    }

    suspend fun execute(characterCardId: String, taskId: String): Int = mutex.withLock {
        val card = characterRepository.getById(characterCardId) ?: return@withLock 0
        val task = card.pendingSpeakerRenameTasks.firstOrNull { it.id == taskId } ?: return@withLock 0
        try {
            require(task.characterCardId == characterCardId && card.pendingSpeakerRenameTasks.count { it.id == taskId } == 1) {
                "历史人物改名任务归属不匹配"
            }
            val overlapping = task.renames.any { rename -> task.renames.any { it.oldName.trim().equals(rename.newName.trim(), ignoreCase = true) } }
            require(task.rewriteProtocolVersion == 1 || task.rewriteProtocolVersion == null && task.rewritePlan == null && !overlapping) {
                "旧人物改名任务的部分执行状态无法确认，已保留任务与历史，请局部核对后处理"
            }
            var plan = task.rewritePlan
            val updated = try {
                rewrite(task) { prepared ->
                    updateTask(characterCardId, taskId) { current ->
                        require(current.copy(lastError = task.lastError, rewritePlan = task.rewritePlan) == task)
                        require(current.rewritePlan == null || current.rewritePlan == prepared)
                        current.copy(rewritePlan = prepared, rewriteProtocolVersion = 1)
                    }
                    require(characterRepository.getById(characterCardId)?.pendingSpeakerRenameTasks
                        ?.singleOrNull { it.id == taskId }?.rewritePlan == prepared) { "历史人物改名计划未确认保存" }
                    plan = prepared
                }
            } catch (error: Exception) {
                // A primary write may already be durable even when its index tail or cancellation failed.
                withContext(NonCancellable) {
                    try { sourcesChanged(plan.orEmpty().map { it.sessionId }.distinct()) }
                    catch (tail: Exception) { error.addSuppressed(tail) }
                }
                throw error
            }
            sourcesChanged(plan.orEmpty().map { it.sessionId }.distinct())
            updateTask(characterCardId, taskId) { current ->
                require(current.renames == task.renames && current.rewritePlan == plan)
                null
            }
            updated
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            try { updateTask(characterCardId, taskId) { it.copy(lastError = error.message ?: error::class.java.simpleName) } }
            catch (tail: Exception) { error.addSuppressed(tail) }
            throw error
        }
    }

    suspend fun resumePending() {
        characterRepository.initialize()
        chatRepository.initialize()
        characterRepository.getAll()
            .forEach { card ->
                for (task in card.pendingSpeakerRenameTasks.sortedBy(SpeakerTagRenameTask::createdAt)) {
                    try { execute(card.id, task.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { break }
                }
            }
    }

}
