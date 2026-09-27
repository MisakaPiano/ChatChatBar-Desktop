package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.PresetModelCatalog
import com.example.chatbar.domain.model.PresetModelCatalogSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Loads only the model-catalog slice of the authoritative bundled Android preset assets. */
internal class DesktopPresetModelCatalogSource(
    private val assetReader: (String) -> ByteArray,
    private val json: Json,
) : PresetModelCatalogSource {
    private val modelCatalogEntry: DesktopPresetEntry? by lazy {
        json.parseToJsonElement(assetReader(MANIFEST_PATH).decodeToString())
            .jsonObject
            .getValue("entries")
            .jsonArray
            .map { element ->
                val entry = element.jsonObject
                DesktopPresetEntry(
                    type = entry.getValue("type").jsonPrimitive.content,
                    version = entry.getValue("version").jsonPrimitive.intOrNull
                        ?: error("Desktop bundled model catalog version 无效"),
                    file = entry.getValue("file").jsonPrimitive.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: error("Desktop bundled model catalog file 无效"),
                )
            }
            .singleOrNull { it.type == MODEL_CATALOG_TYPE }
    }

    override val catalog: PresetModelCatalog by lazy {
        modelCatalogEntry
            ?.let { decode(it.file, PresetModelCatalog.serializer()) }
            ?: PresetModelCatalog()
    }

    override val modelCatalogVersion: Int?
        get() = modelCatalogEntry?.version

    private fun <T> decode(path: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromString(serializer, assetReader(path).decodeToString())

    private companion object {
        const val MANIFEST_PATH = "presets/manifest.json"
        const val MODEL_CATALOG_TYPE = "MODEL_CATALOG"
    }
}

private data class DesktopPresetEntry(
    val type: String,
    val version: Int,
    val file: String,
)
