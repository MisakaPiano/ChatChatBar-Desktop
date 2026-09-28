package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class PresetImportState(
    val seenVersions: Map<String, Int> = emptyMap()
)
