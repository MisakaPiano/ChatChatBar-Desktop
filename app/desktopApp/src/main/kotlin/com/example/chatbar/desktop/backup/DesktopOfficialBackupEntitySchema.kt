package com.example.chatbar.desktop.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** Shared Entity serializers plus explicit pinned wire-version exclusions, never a second Entity model. */
@OptIn(ExperimentalSerializationApi::class)
internal object DesktopOfficialBackupEntitySchema {
    fun validate(type: String, value: JsonObject, appVersionCode: Int, historicalDesktopV5: Boolean = false) {
        require(appVersionCode in 84..CURRENT_DESKTOP_BACKUP_CODE)
        val serializer = requireNotNull(DesktopBackupPolicy.schemas[type])
        versioned(serializer.descriptor, value, appVersionCode, 0, historicalDesktopV5)
        backupJson.decodeFromJsonElement(serializer, value)
    }

    fun validateVersion(type: String, value: JsonObject, appVersionCode: Int, historicalDesktopV5: Boolean = false) {
        versioned(requireNotNull(DesktopBackupPolicy.schemas[type]).descriptor, value, appVersionCode, 0, historicalDesktopV5)
    }

    private fun versioned(descriptor: SerialDescriptor, value: JsonElement, code: Int, depth: Int, historicalDesktopV5: Boolean) {
        require(depth <= 64)
        if (value == JsonNull) return
        // Nullable nested card settings retain the same version policy as their non-null type.
        val typeName = descriptor.serialName.removeSuffix("?")
        if (code == 84 && typeName == "com.example.chatbar.domain.image.NovelAiSampler") {
            require(value.jsonPrimitive.content != "DPM_PLUS_PLUS_2M_SDE") { "OFFICIAL145_SAMPLER_UNSUPPORTED" }
        }
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = value as? JsonObject ?: return // The actual serializer diagnoses its expected type.
                if (typeName == "com.example.chatbar.data.local.entity.MomentPost") {
                    require(!("imageVersions" in obj && "desktopImageVersions" in obj)) { "MOMENT_VERSION_ALIAS_CONFLICT" }
                }
                if (typeName in setOf("com.example.chatbar.data.local.entity.ChatSession", "com.example.chatbar.data.local.entity.SaveSlot")) {
                    require(!("characterParticipation" in obj && "desktopCharacterParticipation" in obj)) {
                        "OFFICIAL_PARTICIPATION_ALIAS_CONFLICT"
                    }
                    require(code >= 91 || "characterParticipation" !in obj) {
                        "OFFICIAL_PRE1412_PARTICIPATION_UNSUPPORTED"
                    }
                }
                if (code < 89) {
                    if (typeName == "com.example.chatbar.data.local.entity.AppSettings")
                        require("disableNarrativeModes" !in obj) { "OFFICIAL_PRE1410_NARRATIVE_DISABLE_UNSUPPORTED" }
                    if (typeName == "com.example.chatbar.data.local.entity.ChatMessage") {
                        require(obj["narrativeMode"] != JsonNull &&
                            (obj["alternativeNarrativeModes"] as? JsonArray)?.none { it == JsonNull } != false) {
                            "OFFICIAL_PRE1410_NULL_NARRATIVE_MODE_UNSUPPORTED"
                        }
                    }
                }
                if (code < 88) {
                    val newFields = when (typeName) {
                        "com.example.chatbar.data.local.entity.AppSettings" -> setOf("experimentalApiModeEnabled")
                        "com.example.chatbar.data.local.entity.ChatSession",
                        "com.example.chatbar.data.local.entity.SaveSlot" -> setOf("narrativeMode")
                        "com.example.chatbar.data.local.entity.ChatMessage" -> setOf("narrativeMode", "alternativeNarrativeModes")
                        else -> emptySet()
                    }
                    require(obj.keys.none { it in newFields }) { "OFFICIAL_PRE149_FIELDS_UNSUPPORTED" }
                }
                if (code < 87 && !(historicalDesktopV5 && typeName == "com.example.chatbar.domain.image.NovelAiGenerationSettings") && typeName in setOf(
                        "com.example.chatbar.domain.image.NovelAiCharacterImageSettings",
                        "com.example.chatbar.domain.image.NovelAiGenerationSettings")) {
                    require("effort" !in obj && "transparentBackground" !in obj) { "OFFICIAL_PRE148_V5_OPTIONS_UNSUPPORTED" }
                }
                if (code == 84) {
                    if (typeName == "com.example.chatbar.data.local.entity.CharacterCard")
                        require("defaultImageGenerationSettings" !in obj) { "OFFICIAL145_CARD_FIELD_UNSUPPORTED" }
                    if (typeName == "com.example.chatbar.domain.image.NovelAiGenerationSettings")
                        require("varietyPlus" !in obj && "varietyPlusSigma" !in obj) { "OFFICIAL145_SETTINGS_FIELD_UNSUPPORTED" }
                }
                obj.forEach { (key, child) ->
                    val index = if (key == "characterParticipation" && typeName in setOf(
                            "com.example.chatbar.data.local.entity.ChatSession", "com.example.chatbar.data.local.entity.SaveSlot")) {
                        descriptor.getElementIndex("desktopCharacterParticipation")
                    } else descriptor.getElementIndex(key)
                    if (index >= 0) versioned(descriptor.getElementDescriptor(index), child, code, depth + 1, historicalDesktopV5)
                }
            }
            StructureKind.LIST -> (value as? JsonArray)?.forEach { versioned(descriptor.getElementDescriptor(0), it, code, depth + 1, historicalDesktopV5) }
            StructureKind.MAP -> (value as? JsonObject)?.values?.forEach { versioned(descriptor.getElementDescriptor(1), it, code, depth + 1, historicalDesktopV5) }
            PrimitiveKind.BOOLEAN -> {
                val primitive = value as? JsonPrimitive ?: return
                require(!primitive.isString && primitive.booleanOrNull != null) { "OFFICIAL_BOOLEAN_TYPE" }
            }
            else -> Unit
        }
    }
}
