package com.example.chatbar.data.local.entity

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetImportStateContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun sharedStateKeepsAndroidLedgerShapeAndMissingDefault() {
        val state = PresetImportState(mapOf("character-a" to 3))
        assertEquals("{\"seenVersions\":{\"character-a\":3}}",
            json.encodeToString(PresetImportState.serializer(), state))
        assertEquals(state, json.decodeFromString(PresetImportState.serializer(),
            "{\"seenVersions\":{\"character-a\":3}}"))
        assertEquals(PresetImportState(), json.decodeFromString(PresetImportState.serializer(), "{}"))
    }
}
