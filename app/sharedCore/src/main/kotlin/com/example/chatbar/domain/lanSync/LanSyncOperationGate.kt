package com.example.chatbar.domain.lanSync

import com.example.chatbar.data.operation.AppDataOperationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Android storage admission: ordinary readers/writers remain concurrent; sync drains them once. */
class LanSyncOperationGate : AppDataOperationGate {
    private val lock = Any()
    private val maintenance = Mutex()
    private var active = 0
    private var reopened: CompletableDeferred<Unit>? = null
    private var drained: CompletableDeferred<Unit>? = null
    private class Admission(val owner: LanSyncOperationGate) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Admission>
    }
    override suspend fun <T> withNormalOperation(operation: suspend () -> T): T {
        if (currentCoroutineContext()[Admission]?.owner === this) return operation()
        while (true) {
            val wait = synchronized(lock) { reopened.also { if (it == null) active++ } }
            if (wait == null) break
            wait.await()
        }
        try { return withContext(Admission(this)) { operation() } }
        finally { synchronized(lock) { active--; if (active == 0) drained?.complete(Unit) } }
    }
    suspend fun <T> exclusive(operation: suspend () -> T): T = maintenance.withLock {
        require(currentCoroutineContext()[Admission]?.owner !== this) { "SYNC_GATE_UPGRADE" }
        val wait = synchronized(lock) {
            reopened = CompletableDeferred()
            CompletableDeferred<Unit>().also { drained = it; if (active == 0) it.complete(Unit) }
        }
        try { wait.await(); withContext(Admission(this)) { operation() } }
        finally { synchronized(lock) { drained = null; reopened?.complete(Unit); reopened = null } }
    }
}

interface LanSyncPlatform {
    /** Quiesce long-running writers, enter exclusive storage, then run [work]. */
    suspend fun exclusive(work: suspend () -> Unit)
    /** Reload repository projections and visible UI after committed bytes, before releasing admission. */
    suspend fun reload(sessionIds: Set<String>)
}
