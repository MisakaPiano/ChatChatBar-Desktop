package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.nio.file.*

/** Offline staging only. Validate links without opening a model, repairing data or filling defaults. */
internal object DesktopFullBackupGraphValidation {
    suspend fun validate(root: Path,
        historical: com.example.chatbar.desktop.backup.DesktopHistoricalVoiceCustody.Evidence? = null,
        futureRoot: Path? = null) {
        val localHistory = com.example.chatbar.desktop.backup.DesktopHistoricalVoiceCustody.validate(root)
        val history = if (historical == null) localHistory else localHistory.plus(historical)
        com.example.chatbar.desktop.backup.DesktopPreservationStore.validate(root)
        com.example.chatbar.desktop.backup.DesktopSaveSlotSourceCustody.validate(root)
        // Decode one bounded image at a time. Hash-valid corrupt media must not become a live resource.
        for (folder in listOf("images", "draft_assets")) {
            val directory = root.resolve(folder)
            if (!Files.exists(directory)) continue
            Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { file ->
                if (file.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp")) {
                    check(Files.size(file) <= DesktopImageEditing.MAX_BYTES)
                    DesktopImageEditing.decode(Files.readAllBytes(file), longestSide = 256).flush()
                }
            } }
        }
        val storage = JsonFileStorage(root)
        val sessions = hashSetOf<String>()
        val messages = hashMapOf<String, Pair<String, Set<String>>>()
        storage.visitEntitiesStrict("chat_sessions", ChatSession.serializer()) { key, session ->
            check(key == session.id && sessions.add(session.id))
        }
        var versions = 0
        storage.visitEntitiesStrict("chat_messages", ChatMessage.serializer()) { key, message ->
            check(message.sessionId in sessions && key == "${message.sessionId}_${message.id}")
            val ids = MessageAlternativeVersionPolicy.versions(message).mapTo(hashSetOf()) { it.id }
            versions += ids.size; check(versions <= 80000)
            check(messages.put(message.id, message.sessionId to ids) == null)
        }
        storage.visitEntitiesStrict("generated_voice_messages", GeneratedVoiceMessage.serializer()) { key, voice ->
            check(key == voice.id)
            val owner = requireNotNull(messages[voice.messageId]) { "RECORDING_MESSAGE_MISSING" }
            check(owner.first == voice.sessionId) { "RECORDING_SESSION_MISMATCH" }
            val version = voice.messageVersionId
            if (version != null && version !in owner.second) {
                check(version.isNotBlank()) { "RECORDING_VERSION_BLANK" }
                val expected = requireNotNull(history.audioProof(voice)) { "RECORDING_VERSION_UNRESOLVED_WITHOUT_SOURCE_CUSTODY" }
                val logical = Path.of(voice.audioPath).toAbsolutePath().normalize()
                val audio = when {
                    logical.startsWith(root) -> logical
                    futureRoot != null && logical.startsWith(futureRoot) -> root.resolve(futureRoot.relativize(logical))
                    else -> error("HISTORICAL_VOICE_AUDIO_OUTSIDE_ROOT")
                }
                check(Files.size(audio) == voice.byteLength && DesktopArchiveFiles.hash(audio, 16L shl 20) == expected) { "HISTORICAL_VOICE_LIVE_AUDIO_CHANGED" }
            }
            // Live-root absolute audio paths are installed later; the staged receipt was verified by its publisher.
        }
        DesktopSessionInventory(root).read("validation-only") // One pass validates all physical owner names.
        val nodes = hashMapOf<String, Pair<String, List<String>>>()
        var edges = 0
        storage.visitEntitiesStrict("memory_nodes", MemoryNode.serializer()) { key, node ->
            check(key == node.id && node.sessionId in sessions)
            edges += node.childIds.size; check(edges <= 80000)
            check(nodes.put(node.id, node.sessionId to node.childIds) == null)
        }
        nodes.forEach { (_, node) -> node.second.forEach { child -> check(nodes[child]?.first == node.first) } }
        val finished = hashSetOf<String>()
        for (start in nodes.keys) {
            if (start in finished) continue
            val path = hashSetOf<String>()
            val stack = java.util.ArrayDeque<Pair<String, Boolean>>()
            stack.push(start to false)
            while (stack.isNotEmpty()) {
                val (id, leaving) = stack.pop()
                if (leaving) { path.remove(id); finished.add(id); continue }
                check(id !in path) { "MEMORY_GRAPH_CYCLE" }
                if (id in finished) continue
                path.add(id); stack.push(id to true)
                nodes.getValue(id).second.asReversed().forEach { stack.push(it to false) }
            }
        }
        storage.visitEntitiesStrict("memory_states", MemorySessionState.serializer()) { key, state ->
            check(key == state.sessionId && state.sessionId in sessions)
            (state.activeNodeIds + state.legacyReferenceNodeIds).forEach { check(nodes[it]?.first == state.sessionId) }
        }
        storage.visitEntitiesStrict("save_slots", SaveSlot.serializer()) { _, slot ->
            if (slot.schemaVersion == 8) {
                val reference = requireNotNull(slot.packageRef)
                check(reference.fileName.matches(Regex("[A-Za-z0-9_-]{1,128}\\.cbsave")))
                // Match production SaveSlot Archive's explicit historical custody policy.
                // Unknown nonblank versions stay historical; all owner/audio/length checks remain.
                check(DesktopSaveSlotInspector(retainHistoricalVoices = true).inspect(root.resolve("save_slot_packages/${reference.fileName}")).status == SlotInspectStatus.STRUCTURE_VALID)
            } else check(slot.schemaVersion in 1..7)
        }
    }
}
