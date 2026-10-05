package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.domain.card.PackagedImage
import com.example.chatbar.domain.image.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Phase evidence retains successful output for local reuse; it is not Studio history authority. */
internal class DesktopNovelAiSmokeStore(
    private val storage: JsonFileStorage,
    private val resources: DesktopCharacterResourceStore,
    private val gate: AppDataOperationGate,
) {
    suspend fun persist(bytes: ByteArray, plan: NovelAiPromptPlan, settings: NovelAiGenerationSettings): String =
        gate.withNormalOperation {
            withContext(NonCancellable + Dispatchers.IO) {
                val old = storage.loadSingleton(KEY, JsonArray.serializer()) ?: JsonArray(emptyList())
                val path = resources.materializeImage(PackagedImage("result.png", Base64.getEncoder().encodeToString(bytes)),
                    System.currentTimeMillis(), "p7smoke" + UUID.randomUUID())
                try {
                    val entry = buildJsonObject {
                        put("imagePath", path)
                        put("createdAt", System.currentTimeMillis())
                        put("prompt", storage.json.encodeToJsonElement(NovelAiPromptPlan.serializer(), plan))
                        put("settings", storage.json.encodeToJsonElement(NovelAiGenerationSettings.serializer(), settings))
                    }
                    storage.saveSingleton(KEY, JsonArray(old + entry), JsonArray.serializer())
                    path
                } catch (failure: Throwable) {
                    // Only a definite, readable authority that does not reference this file permits rollback.
                    try {
                        val durable = storage.loadSingleton(KEY, JsonArray.serializer())
                        if (durable?.any { it.jsonObject["imagePath"]?.jsonPrimitive?.content == path } != true)
                            resources.deleteOwned(path)
                    } catch (_: Exception) { /* Retain ambiguous outcome. */ }
                    throw failure
                }
            }
        }

    companion object { const val KEY = "desktop_phase7_novelai_smoke" }
}
