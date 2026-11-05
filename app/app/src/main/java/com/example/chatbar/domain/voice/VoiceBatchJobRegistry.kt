package com.example.chatbar.domain.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Register identity before execution so target drains see queued jobs as well as active ones. */
internal class VoiceBatchJobRegistry(private val scope: CoroutineScope) {
    data class Target(val batchId: String, val sessionId: String, val messageId: String)
    private data class Entry(val target: Target, val job: Job)
    private val lock = Any()
    private val entries = mutableMapOf<String, MutableList<Entry>>()

    fun launch(target: Target, block: suspend () -> Unit): Job {
        var predecessors = emptyList<Job>()
        val job = scope.launch(start = CoroutineStart.LAZY) { predecessors.joinAll(); block() }
        val entry = Entry(target, job)
        try {
            synchronized(lock) {
                val batch = entries.getOrPut(target.batchId) { mutableListOf() }
                check(batch.all { it.target == target }) { "语音任务目标已改变" }
                predecessors = batch.map { it.job }
                batch += entry
            }
        } catch (error: Throwable) {
            job.cancel()
            throw error
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                entries[target.batchId]?.let { batch ->
                    batch.remove(entry)
                    if (batch.isEmpty()) entries.remove(target.batchId)
                }
            }
        }
        job.start()
        return job
    }

    fun cancel(batchId: String) {
        val targets = synchronized(lock) { entries[batchId]?.map { it.job }.orEmpty() }
        targets.forEach { it.cancel() }
    }
    fun isActive(batchId: String): Boolean = synchronized(lock) {
        entries[batchId]?.any { !it.job.isCompleted } == true
    }

    suspend fun cancelAndJoinMatching(predicate: (Target) -> Boolean) {
        val targets = synchronized(lock) { entries.values.flatten().filter { predicate(it.target) } }
        targets.forEach { it.job.cancel() }
        targets.map { it.job }.joinAll()
    }
}
