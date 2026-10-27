@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.chatbar.desktop.backup

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Test-only wire authority. No Inspector DTO or production Json configuration is used.
// 1.4.5: 1198a13cf3008de1358b8a69aa5e40b64797c8d9 AppBackupModels.kt.
// 1.4.6/7: 9841e2f2 / b4fa75d2 AppBackupModels.kt; transport declarations are identical.
internal val officialWireJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }

@Serializable
internal data class Official145Manifest(
    val formatVersion: Int = 1,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAt: Long,
    val sourceFilesRoot: String,
    val counts: Map<String, Int>,
    val fileCount: Int,
    val totalBytes: Long,
    val preferencesSha256: String
)

@Serializable
internal data class Official145Preferences(
    val novelAiToken: String? = null,
    val fishAudioKey: String? = null,
    val communitySession: String? = null,
    val brushSize: Float = 36f,
    val brushType: String = "Mosaic"
)

@Serializable
internal data class OfficialModernManifest(
    val formatVersion: Int = 1,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAt: Long,
    val sourceFilesRoot: String,
    val counts: Map<String, Int>,
    val fileCount: Int,
    val totalBytes: Long,
    val preferencesSha256: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scope: OfficialWireScope? = null
)

@Serializable
internal data class OfficialWireScope(
    val globalOptions: Set<String>, val characters: Int, val sessions: Int,
    val sessionsWithImages: Int, val sessionsWithVoice: Int, val sessionsWithArchives: Int,
    val studioSettings: Boolean, val studioHistory: Boolean, val studioHistoryImages: Boolean,
    val moments: Boolean,
    val momentsImages: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sessionChoices: Map<String, OfficialWireSessionChoice> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sessionDefaultChoice: OfficialWireSessionChoice? = null
)

// BackupExportSelection.kt: independent image / voice / archive defaults.
@Serializable
internal data class OfficialWireSessionChoice(val images: Boolean = true, val voice: Boolean = true, val archives: Boolean = true)

@Serializable
internal data class OfficialModernPreferences(
    val novelAiToken: String? = null,
    val fishAudioKey: String? = null,
    val communitySession: String? = null,
    val brushSize: Float = 36f,
    val brushType: String = "Mosaic",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val retainedPreferences: Set<String> = emptySet()
)

@Serializable
internal data class OfficialWireFileRecord(val path: String, val size: Long, val sha256: String)
