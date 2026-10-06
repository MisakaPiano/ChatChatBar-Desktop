package com.example.chatbar.desktop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.NovelAiStudioStateRepository
import com.example.chatbar.domain.image.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Explicit local-only evidence reuse. This runner has no transport or SecretStore dependency. */
object DesktopPhase7LocalFixtureCheck {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val source = Path.of(args.single()).toAbsolutePath().normalize()
        val json = Json { ignoreUnknownKeys = true }
        val evidence = json.parseToJsonElement(Files.readString(source.resolve("entities/desktop_phase7_novelai_smoke.json"))).jsonArray.single().jsonObject
        val bytes = DesktopCharacterResourceStore(source).readBytes(evidence.getValue("imagePath").jsonPrimitive.content)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val plan = json.decodeFromJsonElement<NovelAiPromptPlan>(evidence.getValue("prompt"))
        val settings = json.decodeFromJsonElement<NovelAiGenerationSettings>(evidence.getValue("settings"))
        check(settings.model == NovelAiImageModel.V4_5_FULL && settings.count == 1)
        val temp = Files.createTempDirectory("ccb-p7-local-evidence-")
        try {
            val gate = DesktopDataOperationCoordinator()
            val storage = JsonFileStorage(temp, gate)
            val resources = DesktopCharacterResourceStore(temp)
            val draft = plan.toRegenerationDraft()
            val recipe = NovelAiGenerationRecipe(stylePrompt = draft.stylePrompt, basePrompt = draft.baseCaption,
                characters = draft.characterPrompts.map { NovelAiCharacterPromptDraft(prompt = it.prompt, negativePrompt = it.negativePrompt) },
                negativePrompt = draft.negativePrompt, settings = settings)
            val entry = DesktopNovelAiGenerationStore(storage, resources, gate).persist(listOf(bytes), recipe)
            val repo = NovelAiStudioStateRepository(JsonFileStorage(temp))
            repo.applyHistory(entry, entry.images.single(), NovelAiHistoryApplyMode.FULL)
            check(repo.loadDraft().activeSettings.seed == settings.seed)
            check(resources.readBytes(entry.images.single().path).contentEquals(bytes))
            val png = temp.resolve("reused.png"); Files.write(png, bytes)
            check(NovelAiPngMetadataReader.readStudio(png.toString()) != null)
            org.jetbrains.skia.Data.makeFromBytes(bytes).use { data -> org.jetbrains.skia.Codec.makeFromData(data).use {
                check(it.width == settings.imageSize().width && it.height == settings.imageSize().height)
            } }
            val restored = DesktopImageTools.restore(DesktopImageTools.disguise(bytes))
            check(DesktopImageEditing.decode(restored).pixels().contentEquals(DesktopImageEditing.decode(bytes).pixels()))
            check(MessageDigest.getInstance("SHA-256").digest(DesktopCharacterResourceStore(source)
                .readBytes(evidence.getValue("imagePath").jsonPrimitive.content)).contentEquals(digest))
            println("LOCAL_ONLY: accepted V4.5 output persistence/restart/metadata/regeneration preparation/preview/APNG round-trip PASS; source unchanged; network requests=0")
        } finally { temp.toFile().deleteRecursively() }
    }
}
