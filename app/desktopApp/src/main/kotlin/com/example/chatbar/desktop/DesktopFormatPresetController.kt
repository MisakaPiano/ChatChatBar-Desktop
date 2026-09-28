package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.NamePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DesktopFormatPresetState(
    val entries: List<PresetEntry> = emptyList(),
    val cards: List<FormatCard> = emptyList(),
    val pendingEntry: PresetEntry? = null,
    val conflictId: String? = null,
    val status: String? = null,
    val error: String? = null,
)

/** Mirrors Android's explicit name-conflict choice: import as new or overwrite the chosen card. */
internal class DesktopFormatPresetController(
    private val source: DesktopFormatPresetSource,
    private val repository: FormatCardRepository,
    private val transfers: FormatCardTransferService,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(DesktopFormatPresetState())
    val state: StateFlow<DesktopFormatPresetState> = mutableState.asStateFlow()

    suspend fun load() = lock.withLock {
        guarded {
            mutableState.value = mutableState.value.copy(entries = source.entries, cards = repository.getAll(), error = null)
        }
    }

    suspend fun recover(entry: PresetEntry) = lock.withLock {
        guarded {
            val data = source.packageFor(entry)
            val cards = repository.getAll()
            val conflict = cards.firstOrNull { NamePolicy.isSame(it.name, data.name) }
            if (conflict == null) {
                transfers.importNew(data, entry.presetKey, entry.version)
                mutableState.value = mutableState.value.copy(cards = repository.getAll(), status = "FormatCard imported", error = null)
            } else {
                mutableState.value = mutableState.value.copy(
                    pendingEntry = entry, conflictId = conflict.id, error = null,
                )
            }
        }
    }

    suspend fun resolveConflict(overwrite: Boolean) = lock.withLock {
        val entry = mutableState.value.pendingEntry ?: return@withLock
        val id = mutableState.value.conflictId ?: return@withLock
        guarded {
            val data = source.packageFor(entry)
            if (overwrite) transfers.overwrite(id, data, entry.presetKey, entry.version)
            else transfers.importNew(data, entry.presetKey, entry.version)
            mutableState.value = mutableState.value.copy(
                cards = repository.getAll(), pendingEntry = null, conflictId = null,
                status = "FormatCard imported", error = null,
            )
        }
    }

    fun cancelConflict() {
        mutableState.value = mutableState.value.copy(pendingEntry = null, conflictId = null)
    }

    private suspend fun guarded(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "Unable to import bundled FormatCard")
        }
    }
}
