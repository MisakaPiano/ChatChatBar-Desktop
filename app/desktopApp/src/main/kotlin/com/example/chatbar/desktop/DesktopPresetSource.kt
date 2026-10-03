package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.WorldBookPackage
import com.example.chatbar.domain.card.WorldBookTransferService
import kotlinx.serialization.json.Json

/** One manifest and one bundled asset tree for startup and explicit recovery. */
internal class DesktopPresetSource(
    private val assetReader: (String) -> ByteArray,
    private val json: Json,
    private val characters: CharacterCardTransferCore,
    private val formats: FormatCardTransferService,
    private val worlds: WorldBookTransferService,
) {
    val entries: List<PresetEntry> by lazy {
        json.decodeFromString(PresetManifest.serializer(), assetReader("presets/manifest.json").decodeToString()).entries
    }

    fun entries(type: PresetType): List<PresetEntry> = entries.filter { it.type == type }

    fun characterPackage(entry: PresetEntry): CharacterCardPackage {
        requireEntry(entry, PresetType.CHARACTER)
        return characters.decode(assetReader(entry.file).decodeToString())
    }

    fun formatPackage(entry: PresetEntry): FormatCardPackage {
        requireEntry(entry, PresetType.FORMAT)
        return formats.decode(assetReader(entry.file).decodeToString())
    }

    fun worldBookPackage(entry: PresetEntry): WorldBookPackage {
        requireEntry(entry, PresetType.WORLD_BOOK)
        return worlds.decode(assetReader(entry.file).decodeToString(), entry.displayName)
    }

    private fun requireEntry(entry: PresetEntry, type: PresetType) {
        require(entry.type == type && entry in entries) { "Unknown bundled preset: ${entry.presetKey}" }
    }
}
