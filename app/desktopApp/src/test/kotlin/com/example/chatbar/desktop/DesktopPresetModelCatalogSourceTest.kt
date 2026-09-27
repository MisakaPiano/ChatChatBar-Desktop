package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.PresetModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPresetModelCatalogSourceTest {
    @Test
    fun `source follows bundled manifest model entry and referenced authoritative catalog`() {
        val reader = DesktopBundledAssetReader()
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.parseToJsonElement(reader("presets/manifest.json").decodeToString()).jsonObject
        val entry = manifest.getValue("entries").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("type").jsonPrimitive.content == "MODEL_CATALOG" }
        val catalogPath = entry.getValue("file").jsonPrimitive.contentOrNull.orEmpty()
        val expected = json.decodeFromString(
            PresetModelCatalog.serializer(),
            reader(catalogPath).decodeToString(),
        )

        val source = DesktopPresetModelCatalogSource(reader, json)

        assertEquals(entry.getValue("version").jsonPrimitive.int, source.modelCatalogVersion)
        assertEquals(expected, source.catalog)
        assertTrue(source.catalog.chatModels.isNotEmpty())
    }
}
