package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.image.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Read-only code86 wire validation, pinned to official b4fa75d2 (1.4.7).
 * The retained JsonObject is never normalized or used to restore a Desktop entity.
 * A separately validated projection checks unchanged fields with their existing serializer.
 * No extra field is ignored: every removed projection field has its own strict wire schema.
 */
internal object Official147EntityWire {
    fun validate(type: String, value: JsonObject, appVersionCode: Int) {
        if (appVersionCode != 86) {
            backupJson.decodeFromJsonElement(requireNotNull(DesktopBackupPolicy.schemas[type]), value)
            return
        }
        when (type) {
            "character_cards" -> {
                value["defaultImageGenerationSettings"]?.takeUnless { it == JsonNull }?.let {
                    strictBoolean(it.jsonObject, "varietyPlus")
                    backupJson.decodeFromJsonElement<CharacterImageSettingsWire>(it)
                }
                backupJson.decodeFromJsonElement<CharacterCard>(JsonObject(value - "defaultImageGenerationSettings"))
            }
            "novelai_studio_draft" -> {
                listOf("v45Settings", "v5Settings").forEach { field ->
                    value[field]?.let {
                        strictBoolean(it.jsonObject, "varietyPlus")
                        strictBoolean(it.jsonObject, "useCharacterPositions")
                        backupJson.decodeFromJsonElement<GenerationSettingsWire>(it)
                    }
                }
                backupJson.decodeFromJsonElement<NovelAiStudioDraft>(JsonObject(value - "v45Settings" - "v5Settings"))
            }
            else -> backupJson.decodeFromJsonElement(requireNotNull(DesktopBackupPolicy.schemas[type]), value)
        }
    }

    private fun strictBoolean(value: JsonObject, field: String) {
        value[field]?.let { element ->
            val primitive = element as? JsonPrimitive ?: error("OFFICIAL147_BOOLEAN_TYPE")
            require(!primitive.isString && primitive.booleanOrNull != null) { "OFFICIAL147_BOOLEAN_TYPE" }
        }
    }

    // Exact wire fields/defaults from official domain/image/NovelAiCharacterImageSettings.kt.
    @Serializable
    private data class CharacterImageSettingsWire(
        val sampler: SamplerWire = SamplerWire.EULER_ANCESTRAL,
        val steps: Int = 28,
        val guidance: Float = 8f,
        val cfgRescale: Float = 0f,
        val varietyPlus: Boolean = false
    )

    // Exact wire fields/defaults from official domain/image/NovelAiStudioModels.kt:56-70.
    // The second-generation sampler is absent from the current Desktop runtime enum.
    @Serializable
    private enum class SamplerWire {
        EULER_ANCESTRAL, EULER, DPM_PLUS_PLUS_2S_ANCESTRAL, DPM_PLUS_PLUS_2M,
        DPM_PLUS_PLUS_SDE, DPM_PLUS_PLUS_2M_SDE, DDIM
    }

    @Serializable
    private data class GenerationSettingsWire(
        val model: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        val sizeTier: NovelAiSizeTier = NovelAiSizeTier.NORMAL,
        val aspectRatio: NovelAiAspectRatio = NovelAiAspectRatio.PORTRAIT,
        val count: Int = 1,
        val steps: Int = 28,
        val guidance: Float = 6f,
        val seedMode: NovelAiSeedMode = NovelAiSeedMode.RANDOM,
        val seed: Long = 0L,
        val sampler: SamplerWire = SamplerWire.EULER_ANCESTRAL,
        val cfgRescale: Float = 0f,
        val varietyPlus: Boolean = false,
        val customWidth: Int? = null,
        val customHeight: Int? = null,
        val useCharacterPositions: Boolean = false
    )
}
