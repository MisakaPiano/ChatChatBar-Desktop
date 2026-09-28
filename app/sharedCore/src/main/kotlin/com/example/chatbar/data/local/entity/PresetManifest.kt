package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class PresetManifest(
    val entries: List<PresetEntry> = emptyList(),
)

@Serializable
data class PresetEntry(
    val presetKey: String,
    val type: PresetType,
    val version: Int,
    val file: String,
    val displayName: String,
    val worldBookPresetKeys: List<String> = emptyList(),
)

@Serializable
enum class PresetType { CHARACTER, FORMAT, WORLD_BOOK, MODEL_CATALOG }
