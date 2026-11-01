package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** Existing rewrite candidate parsing, materialization and apply policy; no transport or persistence. */
object CharacterRewritePolicy {
    @OptIn(ExperimentalSerializationApi::class)
    val defaultJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        allowTrailingComma = true
    }

    fun parseDraft(raw: String, json: Json = defaultJson): CharacterRewriteDraft? {
        val decoded = raw.extractJsonObjectCandidates().mapIndexedNotNull { index, candidate ->
            runCatching {
                json.decodeFromString(CharacterRewriteDraft.serializer(), candidate)
                    .normalized()
                    .takeIf(CharacterRewriteDraft::hasAnyPatch)
                    ?.let { index to it }
            }.getOrNull()
        }
        return decoded.maxWithOrNull(
            compareBy<Pair<Int, CharacterRewriteDraft>>(
                { it.second.patchScore() },
                { it.first }
            )
        )?.second
    }

    fun materializeDraft(
        current: CharacterCard,
        draft: CharacterRewriteDraft
    ): CharacterRewriteDraft {
        val normalized = draft.constrainedTo(current)
        return when (current.editMode) {
            CharacterEditMode.STRUCTURED -> {
                val deletedIds = normalized.deleteCharacterIds.map(String::trim).filter(String::isNotBlank).toSet()
                val (patchesById, additions) = resolveRewriteCharacterTargets(
                    current = current.characters,
                    deletedIds = deletedIds,
                    drafts = normalized.characters
                )
                val kept = current.characters
                    .filterNot { it.id in deletedIds }
                    .map { it.toFullRewriteDraft(patchesById[it.id]) }
                CharacterRewriteDraft(
                    name = normalized.name.patch(current.name),
                    greeting = normalized.greeting.patch(current.greeting),
                    basicSetting = normalized.basicSetting.patch(current.basicSetting),
                    defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                    deleteCharacterIds = normalized.deleteCharacterIds,
                    characters = kept + additions.map(CharacterRewriteCharacterDraft::toFullNewCharacterDraft)
                )
            }
            CharacterEditMode.FREEFORM -> CharacterRewriteDraft(
                name = normalized.name.patch(current.name),
                greeting = normalized.greeting.patch(current.greeting),
                basicSetting = normalized.basicSetting.patch(current.basicSetting),
                defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                freeformCharacterText = normalized.freeformCharacterText.patch(current.freeformCharacterText)
            )
        }
    }

    fun mergeInto(
        current: CharacterCard,
        draft: CharacterRewriteDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): CharacterCard {
        val normalized = draft.normalized()
        return when (current.editMode) {
            CharacterEditMode.STRUCTURED -> current.copy(
                name = normalized.name.patch(current.name),
                greeting = normalized.greeting.patch(current.greeting),
                basicSetting = normalized.basicSetting.patch(current.basicSetting),
                defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                defaultImageNegativePrompt = CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(
                    current.defaultImageNegativePrompt
                ),
                characters = mergeStructuredCharacters(current.characters, normalized, idFactory)
            )
            CharacterEditMode.FREEFORM -> current.copy(
                name = normalized.name.patch(current.name),
                greeting = normalized.greeting.patch(current.greeting),
                basicSetting = normalized.basicSetting.patch(current.basicSetting),
                defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                defaultImageNegativePrompt = CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(
                    current.defaultImageNegativePrompt
                ),
                freeformCharacterText = normalized.freeformCharacterText.patch(current.freeformCharacterText)
            )
        }
    }
}

private fun CharacterInfo.toFullRewriteDraft(patch: CharacterRewriteCharacterDraft?): CharacterRewriteCharacterDraft =
    CharacterRewriteCharacterDraft(
        id = id,
        name = patch?.name.patch(name),
        profile = patch?.profile.patch(profile),
        appearance = patch?.appearance.patch(appearance),
        clothing = patch?.clothing.patch(clothing),
        abilities = patch?.abilities.patch(abilities),
        habits = patch?.habits.patch(habits),
        background = patch?.background.patch(background),
        relationships = patch?.relationships.patch(relationships),
        speakingStyle = patch?.speakingStyle.patch(speakingStyle),
        imagePrompt = patch?.imagePrompt.patch(imagePrompt)
    )

private fun CharacterRewriteCharacterDraft.toFullNewCharacterDraft(): CharacterRewriteCharacterDraft =
    CharacterRewriteCharacterDraft(
        id = null,
        name = name.orEmpty(),
        profile = profile.orEmpty(),
        appearance = appearance.orEmpty(),
        clothing = clothing.orEmpty(),
        abilities = abilities.orEmpty(),
        habits = habits.orEmpty(),
        background = background.orEmpty(),
        relationships = relationships.orEmpty(),
        speakingStyle = speakingStyle.orEmpty(),
        imagePrompt = imagePrompt.orEmpty()
    )

private fun mergeStructuredCharacters(
    current: List<CharacterInfo>,
    draft: CharacterRewriteDraft,
    idFactory: () -> String
): List<CharacterInfo> {
    val deletedIds = draft.deleteCharacterIds.map(String::trim).filter(String::isNotBlank).toSet()
    val (patchesById, additions) = resolveRewriteCharacterTargets(current, deletedIds, draft.characters)
    val kept = current
        .filterNot { it.id in deletedIds }
        .map { existing -> patchesById[existing.id]?.let(existing::rewriteWith) ?: existing }
        .toMutableList()
    additions.forEach { addition ->
        kept += CharacterInfo(
            id = idFactory(),
            name = addition.name.orEmpty(),
            profile = addition.profile.orEmpty(),
            appearance = addition.appearance.orEmpty(),
            clothing = addition.clothing.orEmpty(),
            abilities = addition.abilities.orEmpty(),
            habits = addition.habits.orEmpty(),
            background = addition.background.orEmpty(),
            relationships = addition.relationships.orEmpty(),
            speakingStyle = addition.speakingStyle.orEmpty(),
            imagePrompt = addition.imagePrompt.orEmpty()
        )
    }
    return kept
}

/**
 * 把 AI 输出的人物候选解析成补丁与新增：
 * 带 id 且匹配现有角色的条目按 id 打补丁；id 缺失或未知的条目先按名称匹配现有角色打补丁，
 * 无法匹配且含有可见内容时视为新增；同一角色被重复输出时保留第一次命中，避免重复人物。
 */
private fun resolveRewriteCharacterTargets(
    current: List<CharacterInfo>,
    deletedIds: Set<String>,
    drafts: List<CharacterRewriteCharacterDraft>
): Pair<Map<String, CharacterRewriteCharacterDraft>, List<CharacterRewriteCharacterDraft>> {
    val normalized = drafts.map(CharacterRewriteCharacterDraft::normalized)
    val currentIds = current.map { it.id }.toSet()
    val namesToCharacters = current
        .filterNot { it.id in deletedIds }
        .filter { it.name.isNotBlank() }
        .groupBy { it.name.trim().lowercase() }
    val claimed = mutableSetOf<String>()
    val patches = mutableMapOf<String, CharacterRewriteCharacterDraft>()
    val additions = mutableListOf<CharacterRewriteCharacterDraft>()

    fun claim(id: String, draft: CharacterRewriteCharacterDraft) {
        if (claimed.add(id)) {
            patches[id] = draft
        }
    }

    for (draft in normalized) {
        val id = draft.id?.trim()
        if (!id.isNullOrBlank() && id in currentIds && id !in deletedIds) {
            claim(id, draft)
            continue
        }
        val nameKey = draft.name?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val nameMatches = nameKey?.let { key -> namesToCharacters[key] }
        if (nameMatches != null && nameMatches.isNotEmpty()) {
            val nameMatch = nameMatches.firstOrNull { it.id !in claimed }
            if (nameMatch != null) {
                claim(nameMatch.id, draft)
            }
            continue
        }
        if (draft.hasVisibleContent()) additions += draft
    }
    return patches to additions
}

private fun CharacterInfo.rewriteWith(draft: CharacterRewriteCharacterDraft): CharacterInfo =
    copy(
        name = draft.name.patch(name),
        profile = draft.profile.patch(profile),
        appearance = draft.appearance.patch(appearance),
        clothing = draft.clothing.patch(clothing),
        abilities = draft.abilities.patch(abilities),
        habits = draft.habits.patch(habits),
        background = draft.background.patch(background),
        relationships = draft.relationships.patch(relationships),
        speakingStyle = draft.speakingStyle.patch(speakingStyle),
        imagePrompt = draft.imagePrompt.patch(imagePrompt)
    )

private fun CharacterRewriteDraft.constrainedTo(currentCard: CharacterCard): CharacterRewriteDraft {
    val normalized = normalized()
    return when (currentCard.editMode) {
        CharacterEditMode.STRUCTURED -> normalized.copy(freeformCharacterText = null)
        CharacterEditMode.FREEFORM -> normalized.copy(characters = emptyList(), deleteCharacterIds = emptyList())
    }
}

private fun CharacterRewriteDraft.normalized(): CharacterRewriteDraft =
    copy(
        name = name?.trim(),
        greeting = greeting?.trim(),
        basicSetting = basicSetting?.trim(),
        defaultImagePrompt = defaultImagePrompt?.trim(),
        freeformCharacterText = freeformCharacterText?.trim(),
        deleteCharacterIds = deleteCharacterIds.map(String::trim).filter(String::isNotBlank),
        characters = characters.map(CharacterRewriteCharacterDraft::normalized)
    )

private fun CharacterRewriteCharacterDraft.normalized(): CharacterRewriteCharacterDraft =
    copy(
        id = id?.trim(),
        name = name?.trim(),
        profile = profile?.trim(),
        appearance = appearance?.trim(),
        clothing = clothing?.trim(),
        abilities = abilities?.trim(),
        habits = habits?.trim(),
        background = background?.trim(),
        relationships = relationships?.trim(),
        speakingStyle = speakingStyle?.trim(),
        imagePrompt = imagePrompt?.trim()
    )

private fun CharacterRewriteDraft.hasAnyPatch(): Boolean =
    name != null ||
        greeting != null ||
        basicSetting != null ||
        defaultImagePrompt != null ||
        freeformCharacterText != null ||
        deleteCharacterIds.isNotEmpty() ||
        characters.any(CharacterRewriteCharacterDraft::hasAnyPatch)

private fun CharacterRewriteDraft.patchScore(): Int =
    listOf(name, greeting, basicSetting, defaultImagePrompt, freeformCharacterText).count { it != null } +
        deleteCharacterIds.size +
        characters.sumOf(CharacterRewriteCharacterDraft::patchScore)

private fun CharacterRewriteCharacterDraft.hasAnyPatch(): Boolean =
    id != null ||
        name != null ||
        profile != null ||
        appearance != null ||
        clothing != null ||
        abilities != null ||
        habits != null ||
        background != null ||
        relationships != null ||
        speakingStyle != null ||
        imagePrompt != null

private fun CharacterRewriteCharacterDraft.patchScore(): Int =
    listOf(
        id,
        name,
        profile,
        appearance,
        clothing,
        abilities,
        habits,
        background,
        relationships,
        speakingStyle,
        imagePrompt
    ).count { it != null }

private fun CharacterRewriteCharacterDraft.hasVisibleContent(): Boolean =
    listOf(name, profile, appearance, clothing, abilities, habits, background, relationships, speakingStyle, imagePrompt)
        .any { !it.isNullOrBlank() }

private fun String?.patch(current: String): String =
    this ?: current
