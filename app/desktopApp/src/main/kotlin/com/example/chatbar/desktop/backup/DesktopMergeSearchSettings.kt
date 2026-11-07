package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.withCurrentWebSearchDefaults
import kotlinx.serialization.json.*

/** Derived search-only output; immutable source records remain available for publication checks. */
internal object DesktopMergeSearchSettings {
    private val fields = setOf("webSearchSettingsVersion", "webSearchEnabled", "characterAutoFillWebSearchEnabled",
        "characterRewriteWebSearchEnabled", "characterAutoFillResearchSourceMode", "characterRewriteResearchSourceMode",
        "webSearchMaxResultsPerQuery")

    fun overrides(computer: JsonObject?, incoming: JsonObject?, selectedFields: Set<String>,
        settingsActivity: Boolean): JsonObject {
        if (!settingsActivity) return JsonObject(emptyMap())
        val defaults = backupJson.encodeToJsonElement(AppSettings.serializer(), AppSettings()).jsonObject
        fun normalize(raw: JsonObject?): JsonObject {
            val value = raw ?: defaults
            val original = backupJson.decodeFromJsonElement(AppSettings.serializer(), value)
            val normalized = original.withCurrentWebSearchDefaults()
            return if (normalized === original) value else JsonObject(value +
                backupJson.encodeToJsonElement(AppSettings.serializer(), normalized).jsonObject.filterKeys { it in fields })
        }
        val local = normalize(computer)
        val source = normalize(incoming)
        return JsonObject(local.filterKeys { it in fields } + selectedFields.intersect(fields).associateWith {
            source[it] ?: defaults.getValue(it)
        })
    }
}
