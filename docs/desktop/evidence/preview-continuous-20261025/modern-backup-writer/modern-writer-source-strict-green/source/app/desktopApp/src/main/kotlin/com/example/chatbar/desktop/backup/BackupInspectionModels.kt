@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.chatbar.desktop.backup

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

// Transport-only DTOs from official 1.4.7 b4fa75d2 AppBackupModels / BackupExportSelection.
// Deliberately separate from the v1 restore manifest. No runtime entity registration.
@Serializable
internal data class InspectionManifest(
    val formatVersion: Int = 1, val appVersionCode: Int, val appVersionName: String,
    val createdAt: Long, val sourceFilesRoot: String, val counts: Map<String, Int>,
    val fileCount: Int, val totalBytes: Long, val preferencesSha256: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val scope: InspectionScope? = null
)
@Serializable
internal data class InspectionScope(
    val globalOptions: Set<String>, val characters: Int, val sessions: Int,
    val sessionsWithImages: Int, val sessionsWithVoice: Int, val sessionsWithArchives: Int,
    val studioSettings: Boolean, val studioHistory: Boolean, val studioHistoryImages: Boolean,
    val moments: Boolean, val momentsImages: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sessionChoices: Map<String, InspectionSessionChoice> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sessionDefaultChoice: InspectionSessionChoice? = null
)
@Serializable
internal data class InspectionSessionChoice(val images: Boolean = true, val voice: Boolean = true, val archives: Boolean = true)
@Serializable
internal data class InspectionPreferences(
    val novelAiToken: String? = null, val fishAudioKey: String? = null,
    val communitySession: String? = null, val brushSize: Float = 36f,
    val brushType: String = "Mosaic", @EncodeDefault(EncodeDefault.Mode.NEVER) val retainedPreferences: Set<String> = emptySet()
)
internal val inspectionGlobalOptions = setOf(
    "defaultModelId",
    "defaultImageModelId",
    "modelConfigurationMode",
    "siliconFlowApiKey",
    "allowCleartextModelApi",
    "defaultEmbeddingId",
    "models",
    "embedding",
    "defaultFormatCardId",
    "automaticFormatCheckEnabled",
    "formatRepairModelId",
    "memoryRagTopK",
    "memoryRagSimilarityThreshold",
    "docRagTopK",
    "docRagSimilarityThreshold",
    "ragInjectionMode",
    "defaultContextWindowSize",
    "episodeMaxSourceTurns",
    "excludeAssistantStatusFromHistory",
    "resources",
    "themeMode",
    "themeColor",
    "chatBubbleFontScale",
    "chatBackgroundImageOpacity",
    "assistantSegmentedBubblesEnabled",
    "tutorialVersion",
    "player",
    "characterAutoFillWebSearchEnabled",
    "characterRewriteWebSearchEnabled",
    "characterAutoFillResearchSourceMode",
    "characterRewriteResearchSourceMode",
    "worldBookAiResearchSourceMode",
    "webSearchMaxResultsPerQuery",
    "novelAiImageModel",
    "novelAiImageAspectRatio",
    "novelAiPromptTranslationConsent",
    "imagePromptToolPreference",
    "novelAiCredentials",
    "imagePreferences",
    "tagCatalog",
    "fishAudioTtsModelId",
    "voiceTagModelId",
    "audiobookModeEnabled",
    "fishAudioCredentials",
    "momentsEnabled",
    "momentsImagesEnabled",
    "momentsMinDelayHours",
    "momentsMaxDelayHours",
    "momentsBackgroundGuideDismissed",
    "community",
    "editorDrafts"
)
