package com.example.chatbar.desktop.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** Shared Entity serializers plus explicit pinned wire-version exclusions, never a second Entity model. */
@OptIn(ExperimentalSerializationApi::class)
internal object DesktopOfficialBackupEntitySchema {
    fun validate(type: String, value: JsonObject, appVersionCode: Int) {
        require(appVersionCode in 84..86)
        val serializer = requireNotNull(DesktopBackupPolicy.schemas[type])
        versioned(serializer.descriptor, value, appVersionCode, 0)
        backupJson.decodeFromJsonElement(serializer, value)
    }

    fun validateVersion(type: String, value: JsonObject, appVersionCode: Int) {
        versioned(requireNotNull(DesktopBackupPolicy.schemas[type]).descriptor, value, appVersionCode, 0)
    }

    private fun versioned(descriptor: SerialDescriptor, value: JsonElement, code: Int, depth: Int) {
        require(depth <= 64)
        if (value == JsonNull) return
        if (code == 84 && descriptor.serialName == "com.example.chatbar.domain.image.NovelAiSampler") {
            require(value.jsonPrimitive.content != "DPM_PLUS_PLUS_2M_SDE") { "OFFICIAL145_SAMPLER_UNSUPPORTED" }
        }
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = value as? JsonObject ?: return // The actual serializer diagnoses its expected type.
                if (code == 84) {
                    if (descriptor.serialName == "com.example.chatbar.data.local.entity.CharacterCard")
                        require("defaultImageGenerationSettings" !in obj) { "OFFICIAL145_CARD_FIELD_UNSUPPORTED" }
                    if (descriptor.serialName == "com.example.chatbar.domain.image.NovelAiGenerationSettings")
                        require("varietyPlus" !in obj) { "OFFICIAL145_SETTINGS_FIELD_UNSUPPORTED" }
                }
                obj.forEach { (key, child) ->
                    val index = descriptor.getElementIndex(key)
                    if (index >= 0) versioned(descriptor.getElementDescriptor(index), child, code, depth + 1)
                }
            }
            StructureKind.LIST -> (value as? JsonArray)?.forEach { versioned(descriptor.getElementDescriptor(0), it, code, depth + 1) }
            StructureKind.MAP -> (value as? JsonObject)?.values?.forEach { versioned(descriptor.getElementDescriptor(1), it, code, depth + 1) }
            PrimitiveKind.BOOLEAN -> {
                val primitive = value as? JsonPrimitive ?: return
                require(!primitive.isString && primitive.booleanOrNull != null) { "OFFICIAL_BOOLEAN_TYPE" }
            }
            else -> Unit
        }
    }
}
