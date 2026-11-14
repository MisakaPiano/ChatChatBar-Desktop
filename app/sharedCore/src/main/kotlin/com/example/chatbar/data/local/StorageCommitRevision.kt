package com.example.chatbar.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/** Process-local invalidation token only; never a durable revision, sync receipt or causal order. */
class StorageCommitRevision {
    private val mutable = MutableStateFlow(0L)
    private val latestByType = MutableStateFlow<Map<String, Long>>(emptyMap())
    val state: StateFlow<Long> = mutable.asStateFlow()

    /** Called after an actual installation, with no payload, callback, suspension or extra I/O. */
    internal fun committed(entityType: String) {
        val token = mutable.updateAndGet { it + 1 }
        latestByType.update { previous ->
            if ((previous[entityType] ?: 0L) >= token) previous else previous + (entityType to token)
        }
    }

    /** Per-type retained tokens make conflation safe even when the latest commit is unrelated. */
    fun forTypes(types: Set<String>): Flow<Long> {
        val selected = types.toSet()
        return latestByType.map { tokens -> selected.maxOfOrNull { tokens[it] ?: 0L } ?: 0L }.distinctUntilChanged()
    }
}
