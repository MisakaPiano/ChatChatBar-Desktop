package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.validateForImport
import kotlinx.serialization.json.Json

/** Desktop reads the bundled manifest and shared package contract; discovery never imports entities. */
internal class DesktopFormatPresetSource(
    private val assetReader: (String) -> ByteArray,
    private val json: Json,
) {
    val entries: List<PresetEntry> by lazy {
        json.decodeFromString(PresetManifest.serializer(), assetReader("presets/manifest.json").decodeToString())
            .entries.filter { it.type == PresetType.FORMAT }
    }

    fun packageFor(entry: PresetEntry): FormatCardPackage {
        require(entry.type == PresetType.FORMAT && entry in entries) { "Unknown bundled FormatCard preset" }
        val data = json.decodeFromString(FormatCardPackage.serializer(), assetReader(entry.file).decodeToString())
        data.validateForImport()
        return data.copy(sourcePresetKey = entry.presetKey, sourcePresetVersion = entry.version)
    }
}
