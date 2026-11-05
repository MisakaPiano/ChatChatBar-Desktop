package com.example.chatbar.domain.voice

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Register identity before execution so target drains see queued jobs as well as active ones. */
internal class VoiceBatchJobRegistry(private val scope: CoroutineScope) {
    data class Target(val batchId: String, val sessionId: String, val messageId: String)
    private data class Entry(val target: Target, val job: Job)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun launch(target: Target, block: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        val entry = Entry(target, job)
        if (entries.putIfAbsent(target.batchId, entry) != null) {
            job.cancel()
            error("语音任务仍在运行")
        }
        job.invokeOnCompletion { entries.remove(target.batchId, entry) }
        job.start()
        return job
    }

    fun cancel(batchId: String) { entries[batchId]?.job?.cancel() }
    fun isActive(batchId: String): Boolean = entries[batchId]?.job?.isActive == true

    suspend fun cancelAndJoinMatching(predicate: (Target) -> Boolean) {
        val targets = entries.values.filter { predicate(it.target) }
        targets.forEach { it.job.cancel() }
        targets.map { it.job }.joinAll()
    }
}
