package com.example.chatbar.desktop

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DesktopTaskKind { REAL_CHAT }

enum class DesktopTaskStatus { RUNNING, COMPLETED, FAILED, USER_STOPPED, CANCELLED }

data class DesktopTaskEntry(
    val taskId: String,
    val kind: DesktopTaskKind,
    val sessionId: String?,
    val createdAt: Long,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: DesktopTaskStatus = DesktopTaskStatus.RUNNING,
    val message: String = "Generating…",
    val contentPreview: String = "",
    val reasoningPreview: String = "",
)

class DesktopTaskAdmissionException(message: String) : IllegalStateException(message)

class DesktopTaskDrainTimeoutException(message: String) : IllegalStateException(message)

/** Application-owned jobs. Chat retains request, transport, and persistence ownership. */
internal class DesktopTaskRuntime(
    private val realChat: DesktopRealChatRuntime,
    val diagnostics: DesktopTransportDiagnosticsOwner = DesktopTransportDiagnosticsOwner(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val mutableTasks = MutableStateFlow<List<DesktopTaskEntry>>(emptyList())
    val tasks: StateFlow<List<DesktopTaskEntry>> = mutableTasks.asStateFlow()
    private val activeJobs = mutableMapOf<String, Job>()
    private val activeSessions = mutableSetOf<String>()
    private val stopControls = mutableMapOf<String, DesktopChatGenerationControl>()
    private var accepting = true

    fun launchChat(sessionId: String, content: String): String {
        val taskId = UUID.randomUUID().toString()
        val control = DesktopChatGenerationControl()
        val createdAt = clock()
        val job = synchronized(lock) {
            if (!accepting) throw DesktopTaskAdmissionException("Task runtime is closing")
            if (sessionId in activeSessions) {
                throw DesktopTaskAdmissionException("该会话已有正在生成的回复")
            }
            val launched = scope.launch(start = CoroutineStart.LAZY) {
                var recorder: DesktopTransportDiagnosticRecorder? = null
                try {
                    val result = realChat.sendText(
                        sessionId = sessionId,
                        content = content,
                        control = control,
                        observer = DesktopRealChatObserver { update ->
                            update(taskId) { current ->
                                current.copy(
                                    contentPreview = update.content.takeLast(PREVIEW_LIMIT),
                                    reasoningPreview = update.reasoningContent.takeLast(PREVIEW_LIMIT),
                                )
                            }
                        },
                        diagnosticsFactory = { model ->
                            diagnostics.begin(taskId, sessionId, model).also { recorder = it }
                        },
                    )
                    val status = if (result.failureMessage == null) {
                        DesktopTaskStatus.COMPLETED
                    } else {
                        DesktopTaskStatus.FAILED
                    }
                    finish(taskId, status, result.failureMessage ?: "Completed")
                    recorder?.finish(status, result.failureMessage, result.completion)
                } catch (stopped: DesktopUserStoppedChatException) {
                    finish(taskId, DesktopTaskStatus.USER_STOPPED, "Stopped by user")
                    recorder?.finish(DesktopTaskStatus.USER_STOPPED, null, null)
                } catch (cancelled: CancellationException) {
                    finish(taskId, DesktopTaskStatus.CANCELLED, "Cancelled")
                    recorder?.finish(DesktopTaskStatus.CANCELLED, null, null)
                } catch (failure: Throwable) {
                    val message = failure.message ?: failure::class.simpleName ?: "Task failed"
                    finish(taskId, DesktopTaskStatus.FAILED, message)
                    recorder?.finish(DesktopTaskStatus.FAILED, message, null)
                } finally {
                    synchronized(lock) {
                        activeJobs.remove(taskId)
                        activeSessions.remove(sessionId)
                        stopControls.remove(taskId)
                    }
                }
            }
            activeSessions += sessionId
            activeJobs[taskId] = launched
            stopControls[taskId] = control
            mutableTasks.value = bounded(
                listOf(
                    DesktopTaskEntry(
                        taskId = taskId,
                        kind = DesktopTaskKind.REAL_CHAT,
                        sessionId = sessionId,
                        createdAt = createdAt,
                        startedAt = clock(),
                    ),
                ) + mutableTasks.value,
            )
            launched
        }
        job.start()
        return taskId
    }

    fun requestUserStop(taskId: String): Boolean = synchronized(lock) {
        stopControls[taskId]?.requestUserStop() ?: false
    }

    /** If drain times out, callers must keep storage open and may retry close. */
    suspend fun closeAndDrain(timeoutMillis: Long = 10_000L) {
        require(timeoutMillis > 0)
        val jobs = synchronized(lock) {
            accepting = false
            activeJobs.values.toList()
        }
        jobs.forEach { it.cancel(CancellationException("Desktop application shutdown")) }
        val drained = withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMillis) {
                jobs.joinAll()
                supervisor.cancelAndJoin()
                true
            }
        }
        if (drained != true) {
            throw DesktopTaskDrainTimeoutException("Desktop tasks did not drain before storage shutdown")
        }
    }

    private fun update(taskId: String, change: (DesktopTaskEntry) -> DesktopTaskEntry) {
        synchronized(lock) {
            mutableTasks.value = mutableTasks.value.map { entry ->
                if (entry.taskId == taskId) change(entry) else entry
            }
        }
    }

    private fun finish(taskId: String, status: DesktopTaskStatus, message: String) {
        synchronized(lock) {
            mutableTasks.value = bounded(mutableTasks.value.map { entry ->
                if (entry.taskId == taskId) {
                    entry.copy(
                        status = status,
                        message = message.take(PREVIEW_LIMIT),
                        completedAt = clock(),
                    )
                } else {
                    entry
                }
            })
        }
    }

    private fun bounded(entries: List<DesktopTaskEntry>): List<DesktopTaskEntry> {
        var completed = 0
        return entries.filter { entry ->
            entry.status == DesktopTaskStatus.RUNNING || ++completed <= COMPLETED_HISTORY_LIMIT
        }
    }

    private companion object {
        const val PREVIEW_LIMIT = 2_048
        const val COMPLETED_HISTORY_LIMIT = 40
    }
}
