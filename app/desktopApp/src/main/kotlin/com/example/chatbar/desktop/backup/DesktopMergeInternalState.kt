package com.example.chatbar.desktop.backup

import kotlinx.serialization.json.*

/** Choosing imported content must not replay completed bootstrap or migration work. */
internal object DesktopMergeInternalState {
    fun preserveCompleted(type: String, incoming: JsonObject, computer: JsonObject?, active: () -> Unit): JsonObject {
        if (computer == null) return incoming
        return when (type) {
            "preset_import_state" -> {
                val before = computer["seenVersions"]?.jsonObject.orEmpty()
                val after = incoming["seenVersions"]?.jsonObject.orEmpty()
                val seen = linkedMapOf<String, JsonElement>()
                for (key in before.keys + after.keys) {
                    active()
                    seen[key] = JsonPrimitive(maxOf(before[key]?.jsonPrimitive?.int ?: 0, after[key]?.jsonPrimitive?.int ?: 0))
                }
                JsonObject(incoming + ("seenVersions" to JsonObject(seen)))
            }
            "model_configuration_migration" -> JsonObject(incoming + ("version" to JsonPrimitive(maxOf(
                computer["version"]?.jsonPrimitive?.int ?: 0, incoming["version"]?.jsonPrimitive?.int ?: 0))))
            else -> incoming
        }
    }
}
