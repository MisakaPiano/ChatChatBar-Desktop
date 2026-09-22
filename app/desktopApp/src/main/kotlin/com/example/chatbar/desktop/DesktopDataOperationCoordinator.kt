package com.example.chatbar.desktop

import com.example.chatbar.data.operation.AppDataOperationGate
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class DesktopDataOperationCoordinatorState {
    OPEN,
    MAINTENANCE_PENDING,
    EXCLUSIVE,
    RESTART_REQUIRED,
    CLOSING,
    CLOSED,
}

internal enum class DesktopDataOperationRejectionReason {
    RESTART_REQUIRED,
    CLOSING,
    CLOSED,
    NORMAL_TO_EXCLUSIVE_UPGRADE,
    NESTED_EXCLUSIVE,
    CLOSE_FROM_ACTIVE_OPERATION,
}

internal class DesktopDataOperationRejectedException(
    val reason: DesktopDataOperationRejectionReason,
    message: String,
) : IllegalStateException(message)

internal interface DesktopExclusiveMaintenanceScope {
    fun requireRestart()
}

/**
 * Desktop process 内的 app-data operation admission gate。
 *
 * Normal operations 可以彼此并发；maintenance waiter 到达后停止接纳新的 unrelated normal
 * operations，等待已登记工作 drain，再按 FIFO 执行 exclusive maintenance。这里不提供跨进程
 * ownership；同一 data root 的 process ownership 由独立的后续边界负责。
 *
 * 同一 structured coroutine context 内的 nested normal call 复用最外层登记。Callback 可以使用
 * direct suspend call、withContext、coroutineScope、supervisorScope，以及会在外层返回前完成的
 * structured launch/async。GlobalScope、external scope fire-and-forget 或手动把 marker 复制给
 * detached work 都不得借用这次登记；这类 root I/O 必须重新进入 gate。
 */
internal class DesktopDataOperationCoordinator : AppDataOperationGate {
    private val stateLock = Any()
    private val identity = Any()
    private val normalWaiters = ArrayDeque<NormalWaiter>()
    private val exclusiveWaiters = ArrayDeque<ExclusiveWaiter>()
    private val closeSignal = CompletableDeferred<Unit>()

    private var currentState = DesktopDataOperationCoordinatorState.OPEN
    private var activeNormalCount = 0
    private var exclusiveActive = false

    val state: DesktopDataOperationCoordinatorState
        get() = synchronized(stateLock) { currentState }

    val isRestartRequired: Boolean
        get() = state == DesktopDataOperationCoordinatorState.RESTART_REQUIRED

    override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
        val inherited = currentCoroutineContext()[DesktopDataOperationContext]
        if (inherited?.containsNormalOrExclusive(identity) == true) {
            return operation()
        }

        val waiter = acquireNormal()
        try {
            val context = inherited.orEmpty().withNormal(identity)
            return withContext(context) { operation() }
        } finally {
            releaseNormal(waiter)
        }
    }

    suspend fun <T> withExclusiveMaintenance(
        operation: suspend DesktopExclusiveMaintenanceScope.() -> T,
    ): T {
        val inherited = currentCoroutineContext()[DesktopDataOperationContext]
        if (inherited?.containsExclusive(identity) == true) {
            throw DesktopDataOperationRejectedException(
                DesktopDataOperationRejectionReason.NESTED_EXCLUSIVE,
                "Nested exclusive maintenance is not supported",
            )
        }
        if (inherited?.containsNormal(identity) == true) {
            throw DesktopDataOperationRejectedException(
                DesktopDataOperationRejectionReason.NORMAL_TO_EXCLUSIVE_UPGRADE,
                "A normal operation cannot upgrade to exclusive maintenance",
            )
        }

        val waiter = acquireExclusive()
        val scope = ExclusiveScope()
        try {
            val context = inherited.orEmpty().withExclusive(identity)
            return withContext(context) { scope.operation() }
        } finally {
            releaseExclusive(waiter, scope.restartRequired)
        }
    }

    suspend fun closeAndDrain() {
        val inherited = currentCoroutineContext()[DesktopDataOperationContext]
        if (inherited?.containsNormalOrExclusive(identity) == true) {
            throw DesktopDataOperationRejectedException(
                DesktopDataOperationRejectionReason.CLOSE_FROM_ACTIVE_OPERATION,
                "Cannot close the data-operation coordinator from an active operation",
            )
        }

        val rejected = mutableListOf<CompletableDeferred<Unit>>()
        val completedNow = synchronized(stateLock) {
            if (currentState == DesktopDataOperationCoordinatorState.CLOSED) {
                true
            } else {
                currentState = DesktopDataOperationCoordinatorState.CLOSING
                while (normalWaiters.isNotEmpty()) rejected += normalWaiters.removeFirst().signal
                while (exclusiveWaiters.isNotEmpty()) rejected += exclusiveWaiters.removeFirst().signal
                finishCloseIfDrainedLocked()
            }
        }
        rejected.forEach { signal ->
            signal.completeExceptionally(rejection(DesktopDataOperationRejectionReason.CLOSING))
        }
        if (!completedNow) closeSignal.await()
    }

    private suspend fun acquireNormal(): NormalWaiter {
        val waiter = NormalWaiter()
        val rejection = synchronized(stateLock) {
            when (currentState) {
                DesktopDataOperationCoordinatorState.OPEN -> {
                    waiter.granted = true
                    activeNormalCount++
                    null
                }

                DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING,
                DesktopDataOperationCoordinatorState.EXCLUSIVE,
                -> {
                    normalWaiters.addLast(waiter)
                    null
                }

                DesktopDataOperationCoordinatorState.RESTART_REQUIRED ->
                    rejection(DesktopDataOperationRejectionReason.RESTART_REQUIRED)

                DesktopDataOperationCoordinatorState.CLOSING ->
                    rejection(DesktopDataOperationRejectionReason.CLOSING)

                DesktopDataOperationCoordinatorState.CLOSED ->
                    rejection(DesktopDataOperationRejectionReason.CLOSED)
            }
        }
        if (rejection != null) throw rejection
        if (!waiter.granted) {
            try {
                waiter.signal.await()
            } catch (error: Throwable) {
                cancelNormalWaiter(waiter)
                throw error
            }
        }
        return waiter
    }

    private suspend fun acquireExclusive(): ExclusiveWaiter {
        val waiter = ExclusiveWaiter()
        val rejection = synchronized(stateLock) {
            when (currentState) {
                DesktopDataOperationCoordinatorState.OPEN -> {
                    if (activeNormalCount == 0 && exclusiveWaiters.isEmpty()) {
                        waiter.granted = true
                        exclusiveActive = true
                        currentState = DesktopDataOperationCoordinatorState.EXCLUSIVE
                    } else {
                        exclusiveWaiters.addLast(waiter)
                        currentState = DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING
                    }
                    null
                }

                DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING,
                DesktopDataOperationCoordinatorState.EXCLUSIVE,
                -> {
                    exclusiveWaiters.addLast(waiter)
                    if (!exclusiveActive) {
                        currentState = DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING
                    }
                    null
                }

                DesktopDataOperationCoordinatorState.RESTART_REQUIRED ->
                    rejection(DesktopDataOperationRejectionReason.RESTART_REQUIRED)

                DesktopDataOperationCoordinatorState.CLOSING ->
                    rejection(DesktopDataOperationRejectionReason.CLOSING)

                DesktopDataOperationCoordinatorState.CLOSED ->
                    rejection(DesktopDataOperationRejectionReason.CLOSED)
            }
        }
        if (rejection != null) throw rejection
        if (!waiter.granted) {
            try {
                waiter.signal.await()
            } catch (error: Throwable) {
                cancelExclusiveWaiter(waiter)
                throw error
            }
        }
        return waiter
    }

    private fun releaseNormal(waiter: NormalWaiter) {
        if (!waiter.markReleased()) return
        val wakeups = synchronized(stateLock) {
            check(activeNormalCount > 0)
            activeNormalCount--
            advanceLocked()
        }
        wake(wakeups)
    }

    private fun releaseExclusive(waiter: ExclusiveWaiter, restartRequired: Boolean) {
        if (!waiter.markReleased()) return
        val wakeups = synchronized(stateLock) {
            check(exclusiveActive)
            exclusiveActive = false
            if (restartRequired && currentState != DesktopDataOperationCoordinatorState.CLOSING) {
                currentState = DesktopDataOperationCoordinatorState.RESTART_REQUIRED
                val rejected = mutableListOf<CompletableDeferred<Unit>>()
                while (normalWaiters.isNotEmpty()) rejected += normalWaiters.removeFirst().signal
                while (exclusiveWaiters.isNotEmpty()) rejected += exclusiveWaiters.removeFirst().signal
                Wakeups(
                    rejected = rejected,
                    rejectionReason = DesktopDataOperationRejectionReason.RESTART_REQUIRED,
                )
            } else {
                advanceLocked()
            }
        }
        wake(wakeups)
    }

    private fun cancelNormalWaiter(waiter: NormalWaiter) {
        val wakeups = synchronized(stateLock) {
            if (waiter.markReleased()) {
                if (waiter.granted) {
                    check(activeNormalCount > 0)
                    activeNormalCount--
                } else {
                    normalWaiters.remove(waiter)
                }
            }
            advanceLocked()
        }
        wake(wakeups)
    }

    private fun cancelExclusiveWaiter(waiter: ExclusiveWaiter) {
        val wakeups = synchronized(stateLock) {
            if (waiter.markReleased()) {
                if (waiter.granted) {
                    check(exclusiveActive)
                    exclusiveActive = false
                } else {
                    exclusiveWaiters.remove(waiter)
                }
            }
            advanceLocked()
        }
        wake(wakeups)
    }

    private fun advanceLocked(): Wakeups {
        if (currentState == DesktopDataOperationCoordinatorState.CLOSING) {
            return if (finishCloseIfDrainedLocked()) Wakeups(closeCompleted = true) else Wakeups()
        }
        if (currentState == DesktopDataOperationCoordinatorState.RESTART_REQUIRED ||
            currentState == DesktopDataOperationCoordinatorState.CLOSED
        ) {
            return Wakeups()
        }
        if (exclusiveActive) {
            currentState = DesktopDataOperationCoordinatorState.EXCLUSIVE
            return Wakeups()
        }
        if (activeNormalCount == 0 && exclusiveWaiters.isNotEmpty()) {
            val next = exclusiveWaiters.removeFirst()
            next.granted = true
            exclusiveActive = true
            currentState = DesktopDataOperationCoordinatorState.EXCLUSIVE
            return Wakeups(grantedExclusive = next.signal)
        }
        if (exclusiveWaiters.isNotEmpty()) {
            currentState = DesktopDataOperationCoordinatorState.MAINTENANCE_PENDING
            return Wakeups()
        }

        currentState = DesktopDataOperationCoordinatorState.OPEN
        val admitted = mutableListOf<CompletableDeferred<Unit>>()
        while (normalWaiters.isNotEmpty()) {
            val next = normalWaiters.removeFirst()
            next.granted = true
            activeNormalCount++
            admitted += next.signal
        }
        return Wakeups(grantedNormals = admitted)
    }

    private fun finishCloseIfDrainedLocked(): Boolean {
        if (activeNormalCount != 0 || exclusiveActive) return false
        currentState = DesktopDataOperationCoordinatorState.CLOSED
        return true
    }

    private fun wake(wakeups: Wakeups) {
        wakeups.grantedExclusive?.complete(Unit)
        wakeups.grantedNormals.forEach { it.complete(Unit) }
        wakeups.rejected.forEach { signal ->
            signal.completeExceptionally(rejection(wakeups.rejectionReason!!))
        }
        if (wakeups.closeCompleted) closeSignal.complete(Unit)
    }

    private fun rejection(reason: DesktopDataOperationRejectionReason) =
        DesktopDataOperationRejectedException(reason, "Desktop data operation rejected: $reason")

    private class NormalWaiter {
        val signal = CompletableDeferred<Unit>()
        var granted = false
        private var released = false

        fun markReleased(): Boolean {
            if (released) return false
            released = true
            return true
        }
    }

    private class ExclusiveWaiter {
        val signal = CompletableDeferred<Unit>()
        var granted = false
        private var released = false

        fun markReleased(): Boolean {
            if (released) return false
            released = true
            return true
        }
    }

    private class ExclusiveScope : DesktopExclusiveMaintenanceScope {
        @Volatile
        var restartRequired = false
            private set

        override fun requireRestart() {
            restartRequired = true
        }
    }

    private data class Wakeups(
        val grantedNormals: List<CompletableDeferred<Unit>> = emptyList(),
        val grantedExclusive: CompletableDeferred<Unit>? = null,
        val rejected: List<CompletableDeferred<Unit>> = emptyList(),
        val rejectionReason: DesktopDataOperationRejectionReason? = null,
        val closeCompleted: Boolean = false,
    )
}

private class DesktopDataOperationContext(
    val normalIdentities: Set<Any>,
    val exclusiveIdentities: Set<Any>,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopDataOperationContext>

    fun containsNormal(identity: Any): Boolean = normalIdentities.any { it === identity }

    fun containsExclusive(identity: Any): Boolean = exclusiveIdentities.any { it === identity }

    fun containsNormalOrExclusive(identity: Any): Boolean =
        containsNormal(identity) || containsExclusive(identity)

    fun withNormal(identity: Any) = DesktopDataOperationContext(
        normalIdentities = normalIdentities + identity,
        exclusiveIdentities = exclusiveIdentities,
    )

    fun withExclusive(identity: Any) = DesktopDataOperationContext(
        normalIdentities = normalIdentities,
        exclusiveIdentities = exclusiveIdentities + identity,
    )
}

private fun DesktopDataOperationContext?.orEmpty() = this ?: DesktopDataOperationContext(
    normalIdentities = emptySet(),
    exclusiveIdentities = emptySet(),
)
