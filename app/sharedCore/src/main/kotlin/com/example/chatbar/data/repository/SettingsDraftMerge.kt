package com.example.chatbar.data.repository

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Apply only edited properties. Runtime fields from a newer snapshot remain intact. */
fun <T> mergeSettingsDraft(serializer: KSerializer<T>, baseline: T, draft: T, latest: T): T {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val before = json.encodeToJsonElement(serializer, baseline).jsonObject
    val edited = json.encodeToJsonElement(serializer, draft).jsonObject
    val current = json.encodeToJsonElement(serializer, latest).jsonObject
    val merged = current.toMutableMap()
    // EncodeDefault.NEVER omits a cleared default. Absence can therefore be an explicit edit.
    // Keys introduced only by a newer runtime snapshot remain outside this edited-key union.
    for (key in before.keys + edited.keys) if (before[key] != edited[key]) {
        if (key in edited) merged[key] = edited.getValue(key) else merged.remove(key)
    }
    return json.decodeFromJsonElement(serializer, JsonObject(merged))
}
