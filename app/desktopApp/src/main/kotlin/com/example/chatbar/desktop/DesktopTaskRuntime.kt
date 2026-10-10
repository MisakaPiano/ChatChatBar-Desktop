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

enum class DesktopTaskKind { REAL_CHAT, NOVELAI }
enum class DesktopChatOperation { SEND, REGENERATE }

enum class DesktopTaskStatus { RUNNING, COMPLETED, FAILED, USER_STOPPED, CANCELLED }

data class DesktopTaskEntry(
    val taskId: String,
    val kind: DesktopTaskKind,
    val sessionId: String?,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val status: DesktopTaskStatus = DesktopTaskStatus.RUNNING,
    val message: String = "Generating…",
    val contentPreview: String = "",
    val reasoningPreview: String = "",
    val operation: DesktopChatOperation = DesktopChatOperation.SEND,
    val targetMessageId: String? = null,
    val canRetry: Boolean = false,
)

class DesktopTaskAdmissionException(message: String) : IllegalStateException(message)

class DesktopTaskDrainTimeoutException(message: String) : IllegalStateException(message)

/** Application-owned jobs. Chat retains request, transport, and persistence ownership. */
internal class DesktopTaskRuntime(
    private val realChat: DesktopRealChatRuntime,
    val diagnostics: DesktopTransportDiagnosticsOwner = DesktopTransportDiagnosticsOwner(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val completedHistoryLimit: Int = 40,
    private val onChatCompleted: suspend (DesktopRealChatResult, () -> Boolean) -> String? = { _, _ -> null },
) {
    init { require(completedHistoryLimit > 0) }
    private val lock = Any()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    val imageProgress = DesktopImageTaskProgress(scope)
    private val mutableTasks = MutableStateFlow<List<DesktopTaskEntry>>(emptyList())
    val tasks: StateFlow<List<DesktopTaskEntry>> = mutableTasks.asStateFlow()
    private val activeJobs = mutableMapOf<String, Job>()
    private val activeSessions = mutableSetOf<String>()
    private val stopControls = mutableMapOf<String, DesktopChatGenerationControl>()
    // In-memory closures retain immutable launch inputs and their original validity checks.
    // Never serialized, included in diagnostics, or used for automatic retry.
    private class ImageRetryCheckpoint(val label: String, val sessionId: String?, val targetMessageId: String?,
        val work: suspend ((String) -> Unit) -> Unit)
    private val imageRetryCheckpoints = mutableMapOf<String, ImageRetryCheckpoint>()
    private val userStoppedImages = mutableSetOf<String>()
    private var accepting = true

    fun launchChat(sessionId: String, content: String, attachments: List<DesktopPendingImage> = emptyList()): String =
        launchGeneration(sessionId, content, null, attachments)

    fun launchRegeneration(sessionId: String, messageId: String): String =
        launchGeneration(sessionId, "", messageId)

    /** Feature work shares admission, Stop, task history and shutdown drain with chat. */
    fun launchNovelAi(
        label: String,
        sessionId: String? = null,
        targetMessageId: String? = null,
        retryable: Boolean = false,
        work: suspend (report: (String) -> Unit) -> Unit,
    ): String = synchronized(lock) {
        if (!accepting) throw DesktopTaskAdmissionException("Task runtime is closing")
        if (mutableTasks.value.any { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING })
            throw DesktopTaskAdmissionException("已有图像任务运行，请等待或停止")
        val id = UUID.randomUUID().toString()
        if (retryable) imageRetryCheckpoints[id] = ImageRetryCheckpoint(label, sessionId, targetMessageId, work)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            update(id) { it.copy(startedAt = clock()) }
            try {
                work(DesktopImageTaskReporter(
                    status = { message -> update(id) { if (it.status == DesktopTaskStatus.RUNNING) it.copy(message = message.take(PREVIEW_LIMIT)) else it } },
                    designSnapshot = { text -> reportImageProgress(id) { imageProgress.design(id, text) } },
                    generationStatus = { text -> reportImageProgress(id) { imageProgress.generation(id, text) } },
                ))
                synchronized(lock) { finish(id, DesktopTaskStatus.COMPLETED, "完成") }
            } catch (_: CancellationException) {
                synchronized(lock) { finish(id, DesktopTaskStatus.CANCELLED, "已停止；已保存的结果保留") }
            } catch (_: Exception) {
                // Features publish their safe status; never propagate provider exception bodies here.
                synchronized(lock) { finish(id, DesktopTaskStatus.FAILED, "图像任务失败；已保存结果保留") }
            }
        }
        activeJobs[id] = job
        if (sessionId != null && targetMessageId != null) imageProgress.admit(id)
        mutableTasks.value = bounded(listOf(DesktopTaskEntry(id, DesktopTaskKind.NOVELAI, sessionId,
            clock(), message = label, targetMessageId = targetMessageId)) + mutableTasks.value)
        job.invokeOnCompletion {
            synchronized(lock) {
                activeJobs.remove(id)
                if (mutableTasks.value.any { it.taskId == id && it.status == DesktopTaskStatus.RUNNING })
                    finish(id, DesktopTaskStatus.CANCELLED, "已停止")
            }
        }
        job.start()
        id
    }

    /** Same job ownership, shutdown drain and Stop surface as chat; never launched by credential save. */
    fun launchNovelAiSmoke(
        runtime: DesktopNovelAiRuntime,
        userConfirmedCredential: Boolean,
        prompt: com.example.chatbar.domain.image.NovelAiPromptPlan,
        settings: com.example.chatbar.domain.image.NovelAiGenerationSettings,
    ): String = synchronized(lock) {
        if (!accepting) throw DesktopTaskAdmissionException("Task runtime is closing")
        check(mutableTasks.value.none { it.kind == DesktopTaskKind.NOVELAI && it.status == DesktopTaskStatus.RUNNING }) {
            "已有 NovelAI 请求运行"
        }
        val id = UUID.randomUUID().toString()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            update(id) { it.copy(startedAt = clock()) }
            try {
                runtime.liveSmoke(userConfirmedCredential, prompt, settings) { step, progress ->
                    update(id) { it.copy(message = "NovelAI · Step $step · ${(progress * 100).toInt()}%") }
                }
                synchronized(lock) { finish(id, DesktopTaskStatus.COMPLETED, "图片已安全保存") }
            } catch (_: CancellationException) {
                synchronized(lock) { finish(id, DesktopTaskStatus.CANCELLED, "NovelAI 已停止") }
            } catch (_: Exception) {
                synchronized(lock) { finish(id, DesktopTaskStatus.FAILED, "NovelAI 验证失败；未自动重试") }
            }
        }
        activeJobs[id] = job
        mutableTasks.value = bounded(listOf(DesktopTaskEntry(id, DesktopTaskKind.NOVELAI, null, clock(),
            message = "NovelAI 安全检查")) + mutableTasks.value)
        job.invokeOnCompletion {
            synchronized(lock) {
                activeJobs.remove(id)
                if (mutableTasks.value.any { it.taskId == id && it.status == DesktopTaskStatus.RUNNING })
                    finish(id, DesktopTaskStatus.CANCELLED, "NovelAI 已停止")
            }
        }
        job.start()
        id
    }

    private fun launchGeneration(sessionId: String, content: String, messageId: String?, attachments: List<DesktopPendingImage> = emptyList()): String {
        val taskId = UUID.randomUUID().toString()
        val control = DesktopChatGenerationControl()
        val createdAt = clock()
        synchronized(lock) {
            if (!accepting) throw DesktopTaskAdmissionException("Task runtime is closing")
            if (sessionId in activeSessions) {
                throw DesktopTaskAdmissionException("该会话已有正在生成的回复")
            }
            val launched = scope.launch(start = CoroutineStart.LAZY) {
                update(taskId) { it.copy(startedAt = clock()) }
                var recorder: DesktopTransportDiagnosticRecorder? = null
                var terminalStatus = DesktopTaskStatus.CANCELLED
                var terminalMessage = "Cancelled"
                var terminalCompletion: com.example.chatbar.domain.chat.ProviderCompletionMetadata? = null
                try {
                    val observer = DesktopRealChatObserver { update ->
                        val safeContent = recorder?.safeText(update.content)
                            ?: DesktopDiagnosticScrubber("").text(update.content, PREVIEW_LIMIT)
                        val safeReasoning = recorder?.safeText(update.reasoningContent)
                            ?: DesktopDiagnosticScrubber("").text(update.reasoningContent, PREVIEW_LIMIT)
                        update(taskId) { current ->
                            current.copy(
                                contentPreview = safeContent.takeLast(PREVIEW_LIMIT),
                                reasoningPreview = safeReasoning.takeLast(PREVIEW_LIMIT),
                                targetMessageId = update.targetMessageId ?: current.targetMessageId,
                                message = update.status ?: current.message,
                            )
                        }
                    }
                    val diagnosticFactory: (com.example.chatbar.data.local.entity.ModelConfig) ->
                        com.example.chatbar.domain.chat.ProviderTransportDiagnostics = { model ->
                        diagnostics.begin(taskId, sessionId, model).also { recorder = it }
                    }
                    val result = if (messageId == null) {
                        realChat.sendText(sessionId, content, observer, control, diagnosticFactory, attachments)
                    } else {
                        realChat.regenerate(sessionId, messageId, observer, control, diagnosticFactory)
                    }
                    terminalStatus = if (result.failureMessage == null) {
                        DesktopTaskStatus.COMPLETED
                    } else {
                        DesktopTaskStatus.FAILED
                    }
                    terminalMessage = result.failureMessage ?: result.inputNotice?.let { "Completed · $it" } ?: "Completed"
                    terminalCompletion = result.completion
                    try {
                        onChatCompleted(result, control::isStopRequested)?.let { terminalMessage += " · $it" }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { terminalMessage += " · 自动生图未启动" }
                } catch (stopped: DesktopUserStoppedChatException) {
                    terminalStatus = DesktopTaskStatus.USER_STOPPED
                    terminalMessage = "Stopped by user"
                } catch (cancelled: CancellationException) {
                    terminalStatus = DesktopTaskStatus.CANCELLED
                    terminalMessage = "Cancelled"
                } catch (failure: Throwable) {
                    terminalStatus = DesktopTaskStatus.FAILED
                    terminalMessage = failure.message ?: failure::class.simpleName ?: "Task failed"
                } finally {
                    val safeMessage = recorder?.safeText(terminalMessage)
                        ?: DesktopDiagnosticScrubber("").text(terminalMessage, PREVIEW_LIMIT)
                    recorder?.finish(
                        terminalStatus,
                        terminalMessage.takeIf { terminalStatus == DesktopTaskStatus.FAILED },
                        terminalCompletion,
                    )
                    synchronized(lock) {
                        activeJobs.remove(taskId)
                        activeSessions.remove(sessionId)
                        stopControls.remove(taskId)
                        finish(taskId, terminalStatus, safeMessage)
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
                        operation = if (messageId == null) DesktopChatOperation.SEND else DesktopChatOperation.REGENERATE,
                    ),
                ) + mutableTasks.value,
            )
            // Cancellation before the coroutine body is dispatched still retires admission state.
            launched.invokeOnCompletion {
                synchronized(lock) {
                    if (activeJobs[taskId] === launched) {
                        activeJobs.remove(taskId)
                        activeSessions.remove(sessionId)
                        stopControls.remove(taskId)
                        finish(taskId, DesktopTaskStatus.CANCELLED, "Cancelled")
                    }
                }
            }
            launched.start()
        }
        return taskId
    }

    fun requestUserStop(taskId: String): Boolean = synchronized(lock) {
        stopControls[taskId]?.requestUserStop() ?: activeJobs[taskId]?.let {
            if (mutableTasks.value.any { entry -> entry.taskId == taskId && entry.kind == DesktopTaskKind.NOVELAI && entry.sessionId != null && entry.targetMessageId != null })
                userStoppedImages += taskId
            it.cancel(); true
        } ?: false
    }

    /** Explicit user action. Admission and the original work's source/opt-in checks still apply. */
    fun retryImageTask(taskId: String): String? = synchronized(lock) {
        val entry = mutableTasks.value.firstOrNull { it.taskId == taskId && it.canRetry } ?: return@synchronized null
        val checkpoint = imageRetryCheckpoints[entry.taskId] ?: return@synchronized null
        val replacement = launchNovelAi(checkpoint.label, checkpoint.sessionId, checkpoint.targetMessageId,
            retryable = true, work = checkpoint.work)
        dismissImageTask(taskId)
        replacement
    }

    /** Retires presentation/history only; owns no generated files or persisted messages. */
    fun dismissImageTask(taskId: String): Boolean = synchronized(lock) {
        if (mutableTasks.value.none { it.taskId == taskId && it.kind == DesktopTaskKind.NOVELAI && it.status != DesktopTaskStatus.RUNNING })
            return@synchronized false
        mutableTasks.value = mutableTasks.value.filterNot { it.taskId == taskId }
        imageRetryCheckpoints.remove(taskId)
        imageProgress.retain(mutableTasks.value.map { it.taskId }.toSet())
        true
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
        synchronized(lock) { imageRetryCheckpoints.clear() }
    }

    private fun update(taskId: String, change: (DesktopTaskEntry) -> DesktopTaskEntry) {
        synchronized(lock) {
            mutableTasks.value = mutableTasks.value.map { entry ->
                if (entry.taskId == taskId) change(entry) else entry
            }
        }
    }

    private fun reportImageProgress(taskId: String, report: () -> Unit) = synchronized(lock) {
        if (mutableTasks.value.any { it.taskId == taskId && it.status == DesktopTaskStatus.RUNNING }) report()
    }

    private fun finish(taskId: String, status: DesktopTaskStatus, message: String) {
        val finalStatus = if (userStoppedImages.remove(taskId) && status == DesktopTaskStatus.CANCELLED) DesktopTaskStatus.USER_STOPPED else status
        imageProgress.flush(taskId)
        mutableTasks.value = bounded(mutableTasks.value.map { entry ->
            if (entry.taskId == taskId) {
                entry.copy(status = finalStatus, message = message.take(PREVIEW_LIMIT), completedAt = clock(),
                    canRetry = taskId in imageRetryCheckpoints && status in setOf(DesktopTaskStatus.FAILED, DesktopTaskStatus.CANCELLED, DesktopTaskStatus.USER_STOPPED))
            } else {
                entry
            }
        })
    }

    private fun bounded(entries: List<DesktopTaskEntry>): List<DesktopTaskEntry> {
        var completed = 0
        val retained = entries.filter { entry ->
            entry.status == DesktopTaskStatus.RUNNING || ++completed <= completedHistoryLimit
        }
        imageRetryCheckpoints.keys.retainAll(retained.filter { it.status == DesktopTaskStatus.RUNNING || it.canRetry }.map { it.taskId }.toSet())
        imageProgress.retain(retained.map { it.taskId }.toSet())
        return retained
    }

    private companion object {
        const val PREVIEW_LIMIT = 2_048
    }
}
