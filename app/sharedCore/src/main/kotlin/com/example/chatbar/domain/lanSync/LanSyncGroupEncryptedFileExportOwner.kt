package com.example.chatbar.domain.lanSync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class LanSyncGroupEncryptedFileExportState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val status: String = "尚未准备加密导出文件",
    val error: String? = null,
    val preparedBinding: String? = null,
    val bytes: Long? = null,
    val writeCompleted: Boolean = false,
    val readbackVerified: Boolean = false
) {
    override fun toString() = "LanSyncGroupEncryptedFileExportState(REDACTED)"
}

/** Transfers the private package before a cancellable dispatcher can discard a returned value. */
internal fun interface LanSyncGroupFilePackagePreparer {
    suspend fun prepare(checkActive: () -> Unit, receive: (LanSyncGroupEncryptedFilePackage.Prepared) -> Unit)
}

/** Owns one private authenticated package; external writing never creates a delivery receipt. */
class LanSyncGroupEncryptedFileExportOwner internal constructor(
    private val preparer: LanSyncGroupFilePackagePreparer,
    private val dataWork: suspend (suspend () -> Boolean) -> Boolean = { it() }
) {
    /** Compatibility for return-value callers. Production uses the cancellable owned handoff. */
    constructor(prepare: suspend (checkActive: () -> Unit) -> LanSyncGroupEncryptedFilePackage.Prepared,
        dataWork: suspend (suspend () -> Boolean) -> Boolean = { it() }) : this(
        LanSyncGroupFilePackagePreparer { active, receive ->
            val preparingJob = currentCoroutineContext()[Job]
            withContext(NonCancellable) {
                val prepared = prepare {
                    if (preparingJob?.isCancelled == true) throw CancellationException("SYNC_GROUP_FILE_INACTIVE")
                    active()
                }
                try { receive(prepared) }
                catch (failure: Throwable) {
                    try { prepared.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        }, dataWork)

    private class Session(val generation: Long) {
        val binding = UUID.randomUUID().toString()
        var prepared: LanSyncGroupEncryptedFilePackage.Prepared? = null
        var pending = 0
        var retired = false
        var writeCompleted = false
        var readbackVerified = false
    }

    private val lifecycle = Any()
    private val cleanupLock = Any()
    private val admission = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = linkedSetOf<Job>()
    private val retired = linkedSetOf<Session>()
    private val failedCleanup = mutableListOf<LanSyncGroupEncryptedFilePackage.Prepared>()
    private var generation = 0L
    private var closed = false
    private var session: Session? = null
    private val mutableState = MutableStateFlow(LanSyncGroupEncryptedFileExportState())
    val state: StateFlow<LanSyncGroupEncryptedFileExportState> = mutableState.asStateFlow()

    fun open() = synchronized(lifecycle) {
        if (!closed) mutableState.value = mutableState.value.copy(visible = true)
    }

    /** Explicit preparation is the only operation which asks the application for a package. */
    fun prepare() {
        val pending: List<Job>
        val next: Job
        synchronized(lifecycle) {
            if (closed) return
            pending = jobs.toList()
            retireLocked(session)
            val value = Session(++generation)
            session = value
            mutableState.value = LanSyncGroupEncryptedFileExportState(visible = true, busy = true,
                status = "正在核验本机资料并准备加密文件")
            next = launchLocked(value, preparing = true) {
                var receiving = true
                var receives = 0
                try {
                    preparer.prepare({ checkSession(value) }) { prepared ->
                        val accepted = synchronized(lifecycle) {
                            receives = minOf(receives + 1, 2)
                            if (!receiving || receives != 1) false
                            else {
                                // A retired producer still transfers ownership; pending/drain owns disposal.
                                value.prepared = prepared
                                true
                            }
                        }
                        if (!accepted) {
                            if (synchronized(lifecycle) { value.prepared !== prepared }) closePrivate(prepared)
                            error("SYNC_GROUP_FILE_RECEIVE_CLOSED_OR_REPEATED")
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(lifecycle) {
                        checkSession(value)
                        check(receives == 1 && value.prepared != null) { "SYNC_GROUP_FILE_PREPARATION_NOT_RECEIVED" }
                    }
                } finally {
                    synchronized(lifecycle) { receiving = false }
                }
            }
        }
        pending.forEach { it.cancel() }
        cleanupRetired()
        next.start()
    }

    /** The platform owns its output stream. Its body must be borrowed once, synchronously. */
    fun write(
        binding: String,
        writer: (((OutputStream) -> Unit) -> Unit),
        readback: (() -> InputStream)? = null
    ) {
        val next: Job
        synchronized(lifecycle) {
            val value = session
            if (closed || value == null || mutableState.value.busy) return
            if (value.binding != binding || value.prepared == null) {
                mutableState.value = mutableState.value.copy(error = "这次文件选择已失效，请重新准备或选择。")
                return
            }
            value.writeCompleted = false
            value.readbackVerified = false
            mutableState.value = mutableState.value.copy(busy = true, error = null,
                writeCompleted = false, readbackVerified = false, status = "正在写出已核验的加密文件")
            next = launchLocked(value, preparing = false) {
                val prepared = requireNotNull(value.prepared)
                runInterruptible(Dispatchers.IO) {
                    checkSession(value)
                    val thread = Thread.currentThread()
                    val borrowed = Any()
                    var accepting = true
                    var calls = 0
                    var completed = false
                    try {
                        writer { output ->
                            synchronized(borrowed) {
                                calls++
                                check(accepting && Thread.currentThread() === thread && calls == 1) {
                                    "SYNC_GROUP_FILE_WRITER_CONTRACT"
                                }
                            }
                            checkSession(value)
                            prepared.writeTo(output)
                            synchronized(borrowed) { completed = true }
                        }
                    } finally {
                        synchronized(borrowed) { accepting = false }
                    }
                    synchronized(borrowed) {
                        check(calls == 1 && completed) { "SYNC_GROUP_FILE_WRITER_CONTRACT" }
                    }
                    checkSession(value)
                    synchronized(lifecycle) { checkSession(value); value.writeCompleted = true }
                    if (readback != null) {
                        readback().use { input ->
                            LanSyncGroupEncryptedFilePackage.verifyReadback(input, prepared.descriptor) { checkSession(value) }
                        }
                        checkSession(value)
                        synchronized(lifecycle) { checkSession(value); value.readbackVerified = true }
                    }
                }
            }
        }
        next.start()
    }

    /** Ciphertext preparation survives picker/page handoff; only an explicit stop invalidates it. */
    fun hide() = synchronized(lifecycle) {
        if (!closed) mutableState.value = mutableState.value.copy(visible = false)
    }

    fun lock() = invalidate(terminal = false)

    private fun invalidate(terminal: Boolean) {
        val pending = synchronized(lifecycle) {
            if (terminal) closed = true
            generation++
            retireLocked(session)
            session = null
            mutableState.value = LanSyncGroupEncryptedFileExportState(status =
                if (terminal) "加密文件导出已关闭" else "加密文件导出已取消")
            jobs.toList()
        }
        pending.forEach { it.cancel() }
        cleanupRetired()
    }

    private fun retireLocked(value: Session?) {
        if (value != null) { value.retired = true; retired += value }
    }

    /** Called under lifecycle so a stop cannot miss a newly admitted job. */
    private fun launchLocked(value: Session, preparing: Boolean, work: suspend () -> Unit): Job {
        value.pending++
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                admission.withLock {
                    checkSession(value)
                    val invocations = AtomicInteger()
                    val accepted = dataWork {
                        check(invocations.incrementAndGet() == 1) { "SYNC_GROUP_FILE_WORK_REPLAY" }
                        currentCoroutineContext().ensureActive()
                        checkSession(value)
                        work()
                        currentCoroutineContext().ensureActive()
                        checkSession(value)
                        true
                    }
                    check(accepted && invocations.get() == 1) { "SYNC_GROUP_FILE_WORK_UNAVAILABLE" }
                    synchronized(lifecycle) {
                        checkSession(value)
                        mutableState.value = mutableState.value.copy(busy = false, error = null,
                            preparedBinding = value.binding, bytes = requireNotNull(value.prepared).descriptor.length,
                            writeCompleted = value.writeCompleted, readbackVerified = value.readbackVerified,
                            status = when {
                                preparing -> "加密文件已准备好，请选择保存位置"
                                value.readbackVerified -> "文件已写出，并已完成回读核验"
                                else -> "文件已写出，尚未回读核验"
                            })
                    }
                }
            } catch (_: Exception) {
                var failedPreparation: LanSyncGroupEncryptedFilePackage.Prepared? = null
                synchronized(lifecycle) {
                    if (!closed && session === value && !value.retired) {
                        if (preparing) {
                            failedPreparation = value.prepared
                            value.prepared = null
                        }
                        mutableState.value = mutableState.value.copy(busy = false,
                            preparedBinding = value.prepared?.let { value.binding },
                            bytes = value.prepared?.descriptor?.length,
                            writeCompleted = value.writeCompleted, readbackVerified = value.readbackVerified,
                            status = if (preparing) "加密文件尚未准备完成" else "本次导出未完成核验",
                            error = when {
                                preparing -> "请检查本机资料后重新准备。"
                                value.writeCompleted -> "文件已写出，但回读核验未完成；请重新选择目标后重试。"
                                else -> "文件尚未确认写出；请重新选择目标后重试。"
                            })
                    }
                }
                failedPreparation?.let(::closePrivate)
            }
        }
        jobs += job
        job.invokeOnCompletion {
            synchronized(lifecycle) { value.pending-- }
            try { cleanupRetired() } finally { synchronized(lifecycle) { jobs -= job } }
        }
        return job
    }

    private fun checkSession(value: Session) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SYNC_GROUP_FILE_INTERRUPTED")
        synchronized(lifecycle) {
            if (closed || session !== value || value.retired || value.generation != generation)
                throw CancellationException("SYNC_GROUP_FILE_INACTIVE")
        }
    }

    private fun closePrivate(prepared: LanSyncGroupEncryptedFilePackage.Prepared) = synchronized(cleanupLock) {
        try { prepared.close() } catch (_: Exception) {
            synchronized(lifecycle) { failedCleanup += prepared }
        }
    }

    private fun cleanupRetired() = synchronized(cleanupLock) {
        val cleanup = synchronized(lifecycle) {
            retired.filter { it.pending == 0 }.mapNotNull { value ->
                retired -= value
                value.prepared.also { value.prepared = null }
            }
        }
        cleanup.forEach(::closePrivate)
    }

    /** Waits for actual admitted I/O, including a sink which has not yet honored cancellation. */
    suspend fun drain() = withContext(NonCancellable) {
        while (true) {
            val pending = synchronized(lifecycle) { jobs.toList() }
            if (pending.isEmpty()) break
            pending.joinAll()
            yield()
        }
        synchronized(cleanupLock) {
            cleanupRetired()
            val retry = synchronized(lifecycle) { failedCleanup.toList().also { failedCleanup.clear() } }
            retry.forEach(::closePrivate)
            if (synchronized(lifecycle) { failedCleanup.isNotEmpty() })
                throw IOException("SYNC_GROUP_FILE_PRIVATE_CLEANUP_INCOMPLETE")
        }
    }

    suspend fun closeAndDrain() = withContext(NonCancellable) {
        invalidate(terminal = true)
        drain()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    override fun toString() = "LanSyncGroupEncryptedFileExportOwner(REDACTED)"
}
