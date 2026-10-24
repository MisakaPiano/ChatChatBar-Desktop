package com.example.chatbar.desktop.backup

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DesktopOfficialEntityRoundTripTest {
    @Test fun officialCardSettingsSurviveActualRepositoryStorageAndReopen() = runBlocking<Unit> {
        val raw = """{"id":"synthetic","name":"Synthetic","defaultImageGenerationSettings":{"steps":32,"guidance":7.5,"cfgRescale":0.2,"varietyPlus":true},"createdAt":1,"updatedAt":1}"""
        val card = backupJson.decodeFromString(CharacterCard.serializer(), raw)
        assertEquals(true, card.defaultImageGenerationSettings?.varietyPlus)
        val root = Files.createTempDirectory("official-card-preservation-")
        JsonFileStorage(root).saveEntity("character_cards", card.id, card, CharacterCard.serializer())
        val restored = JsonFileStorage(root).loadEntity("character_cards", card.id, CharacterCard.serializer())
        assertEquals(card, restored)
        assertEquals(32, restored?.defaultImageGenerationSettings?.steps)
    }

    @Test fun defaultsDoNotInjectFutureFieldsIntoOfficial145Outputs() {
        val card = CharacterCard("synthetic", "Synthetic", createdAt = 1, updatedAt = 1)
        val raw = backupJson.encodeToJsonElement(CharacterCard.serializer(), card).jsonObject
        assertFalse("defaultImageGenerationSettings" in raw)
        val settings = backupJson.encodeToJsonElement(NovelAiGenerationSettings.serializer(), NovelAiGenerationSettings()).jsonObject
        assertFalse("varietyPlus" in settings)
        DesktopOfficialBackupEntitySchema.validate("character_cards", raw, 84)
    }

    @Test fun modernSourceIdentityIsCheckedWithoutMakingCode84MorePermissive() {
        val value = backupJson.parseToJsonElement("""{"id":"s","name":"Synthetic","defaultImageGenerationSettings":null,"createdAt":1,"updatedAt":1}""").jsonObject
        for (code in listOf(85, 86)) DesktopOfficialBackupEntitySchema.validate("character_cards", value, code)
        assertFails { DesktopOfficialBackupEntitySchema.validate("character_cards", value, 84) }
        for (code in listOf(84, 85, 86)) {
            assertFails { DesktopOfficialBackupEntitySchema.validate("character_cards", JsonObject(value + ("future" to JsonPrimitive(1))), code) }
        }
    }

    @Test fun modernStudioSettingsRoundTripAndRejectMalformedBooleans() {
        for (value in listOf("true", "false")) {
            val raw = """{"v45Settings":{"varietyPlus":$value},"v5Settings":{"varietyPlus":$value}}"""
            val obj = backupJson.parseToJsonElement(raw).jsonObject
            DesktopOfficialBackupEntitySchema.validate("novelai_studio_draft", obj, 86)
            val draft = backupJson.decodeFromString(NovelAiStudioDraft.serializer(), raw)
            assertEquals(value.toBoolean(), draft.v45Settings.varietyPlus)
            assertEquals(value.toBoolean(), draft.v5Settings.varietyPlus)
            assertEquals(draft, backupJson.decodeFromJsonElement(NovelAiStudioDraft.serializer(),
                backupJson.encodeToJsonElement(NovelAiStudioDraft.serializer(), draft)))
            assertFails { DesktopOfficialBackupEntitySchema.validate("novelai_studio_draft", obj, 84) }
        }
        for (value in listOf("\"false\"", "null", "0", "[]", "{}")) assertFails {
            DesktopOfficialBackupEntitySchema.validate("novelai_studio_draft",
                backupJson.parseToJsonElement("""{"v45Settings":{"varietyPlus":$value}}""").jsonObject, 86)
        }
    }

    @Test fun preservedVarietySettingsReachCorrectOfficialRequestProtocolWithoutNetwork() {
        fun request(model: NovelAiImageModel, variety: Boolean) = backupJson.parseToJsonElement(NovelAiImageService().buildRequestBody(
            NovelAiPromptPlan("Synthetic fixture", emptyList()), NovelAiImageSize(832, 1216, "Synthetic"),
            NovelAiGenerationSettings(model = model, varietyPlus = variety))).jsonObject.getValue("parameters").jsonObject
        assertEquals(58, request(NovelAiImageModel.V4_5_FULL, true).getValue("skip_cfg_above_sigma").jsonPrimitive.int)
        assertEquals(JsonNull, request(NovelAiImageModel.V4_5_FULL, false)["skip_cfg_above_sigma"])
        assertFalse("skip_cfg_above_sigma" in request(NovelAiImageModel.V5_FULL, true))
        assertFalse("skip_cfg_above_sigma" in request(NovelAiImageModel.V5_FULL, false))
    }

    @Test fun code84ExclusionsApplyToNestedTypedSettingsButNotOpaqueUserParameters() {
        val undo = backupJson.parseToJsonElement("""{"draft":{"v45Settings":{"varietyPlus":false}}}""").jsonObject
        assertFails { DesktopOfficialBackupEntitySchema.validate("novelai_studio_history_undo", undo, 84) }
        DesktopOfficialBackupEntitySchema.validate("novelai_studio_history_undo", undo, 86)
        DesktopOfficialBackupEntitySchema.validate("model_configs", backupJson.parseToJsonElement(
            CensusWireFixtures.model(key = "\"\"", extra = ",\"customParams\":{\"varietyPlus\":{\"type\":\"boolean\",\"value\":false}}")).jsonObject, 84)
    }
}
