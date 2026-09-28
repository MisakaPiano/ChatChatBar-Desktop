package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.domain.card.FormatCardTransferService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Imports only unseen FORMAT entries; later manifest versions never overwrite local edits. */
internal class DesktopFormatPresetBootstrap(
    private val source: DesktopFormatPresetSource,
    private val storage: JsonFileStorage,
    private val repository: FormatCardRepository,
    private val transfers: FormatCardTransferService,
) {
    private val lock = Mutex()
    private var initialized = false

    suspend fun initialize() = lock.withLock {
        if (initialized) return@withLock
        val previous = storage.loadSingleton("preset_import_state", JsonObject.serializer()) ?: JsonObject(emptyMap())
        val originalSeen = previous["seenVersions"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int }.orEmpty()
        val seen = originalSeen.toMutableMap()
        for (entry in source.entries) {
            val seenVersion = seen[entry.presetKey]
            if (seenVersion == null) {
                // Also handles an interrupted earlier import or state copied without its ledger.
                if (repository.getAll().none { it.sourcePresetKey == entry.presetKey }) {
                    transfers.importNew(source.packageFor(entry), entry.presetKey, entry.version)
                }
                seen[entry.presetKey] = entry.version
            } else if (entry.version > seenVersion) {
                // Formal baseline records the new version without replacing user-modified cards.
                seen[entry.presetKey] = entry.version
            }
        }
        if (seen != originalSeen) {
            val updated = JsonObject(previous + ("seenVersions" to JsonObject(seen.mapValues { JsonPrimitive(it.value) })))
            storage.saveSingleton("preset_import_state", updated, JsonObject.serializer())
        }
        initialized = true
    }
}
