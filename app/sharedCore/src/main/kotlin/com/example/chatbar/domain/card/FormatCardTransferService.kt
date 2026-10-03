package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.repository.FormatCardRepository
import java.util.UUID
import kotlinx.serialization.json.Json

class FormatCardTransferService(
    private val repository: FormatCardRepository,
    private val json: Json
) {
    suspend fun exportJson(id: String): String {
        val card = repository.getById(id) ?: error("格式卡不存在")
        return json.encodeToString(
            FormatCardPackage.serializer(),
            FormatCardPackage(
                name = card.name,
                content = card.content,
                userTools = card.userTools,
                sourcePresetKey = card.sourcePresetKey,
                sourcePresetVersion = card.sourcePresetVersion
            )
        )
    }

    fun decode(rawJson: String): FormatCardPackage =
        json.decodeFromString(FormatCardPackage.serializer(), rawJson).also {
            it.validateForImport()
        }

    suspend fun duplicate(id: String): FormatCard {
        val source = repository.getById(id) ?: error("格式卡不存在")
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = NamePolicy.nextCopyName(source.name, repository.getAll().map { it.name }),
            isDefault = false,
            sourcePresetKey = null,
            sourcePresetVersion = null,
            createdAt = System.currentTimeMillis()
        )
        repository.save(copy)
        return copy
    }

    /** 角色卡携带的共享格式卡：内容一致才复用，冲突时保留双方。 */
    suspend fun importCharacterDefault(packageData: FormatCardPackage): FormatCard =
        importCharacterDefaultTracked(packageData) {}

    internal suspend fun importCharacterDefaultTracked(
        packageData: FormatCardPackage,
        onCreating: (String) -> Unit,
    ): FormatCard {
        packageData.validateForImport()
        return repository.getAll().firstOrNull {
            NamePolicy.isSame(it.name, packageData.name) &&
                it.content == packageData.content && it.userTools == packageData.userTools
        } ?: importNew(packageData, onCreating = onCreating)
    }

    suspend fun importNew(
        packageData: FormatCardPackage,
        presetKey: String? = null,
        presetVersion: Int? = null,
    ): FormatCard = importNew(packageData, presetKey, presetVersion, onCreating = {})

    /** Opt-in exact target/commit observations for a Desktop typed ingress. */
    suspend fun importNewObserved(
        packageData: FormatCardPackage,
        onPrepared: (FormatCard) -> Unit,
        onCommitted: () -> Unit,
    ): FormatCard = importNew(packageData, onCreating = {}, onPrepared = onPrepared, onCommitted = onCommitted)

    private suspend fun importNew(
        packageData: FormatCardPackage,
        presetKey: String? = null,
        presetVersion: Int? = null,
        onCreating: (String) -> Unit,
        onPrepared: (FormatCard) -> Unit = {},
        onCommitted: (() -> Unit)? = null,
    ): FormatCard {
        packageData.validateForImport()
        val all = repository.getAll()
        val name = if (all.any { NamePolicy.isSame(it.name, packageData.name) }) {
            NamePolicy.nextCopyName(packageData.name, all.map { it.name })
        } else NamePolicy.normalize(packageData.name)
        val id = UUID.randomUUID().toString()
        onCreating(id)
        val card = FormatCard(
            id = id,
            name = name,
            content = packageData.content,
            userTools = packageData.userTools,
            isDefault = false,
            sourcePresetKey = presetKey ?: packageData.sourcePresetKey,
            sourcePresetVersion = presetVersion ?: packageData.sourcePresetVersion,
            createdAt = System.currentTimeMillis()
        )
        onPrepared(card)
        if (onCommitted == null) repository.save(card) else repository.saveObserved(card, onCommitted)
        return card
    }

    suspend fun overwrite(existingId: String, packageData: FormatCardPackage, presetKey: String? = null, presetVersion: Int? = null): FormatCard {
        return overwriteImpl(existingId, packageData, presetKey, presetVersion, {}, null)
    }

    suspend fun overwriteObserved(
        existingId: String,
        packageData: FormatCardPackage,
        onPrepared: (FormatCard) -> Unit,
        onCommitted: () -> Unit,
    ): FormatCard = overwriteImpl(existingId, packageData, null, null, onPrepared, onCommitted)

    private suspend fun overwriteImpl(existingId: String, packageData: FormatCardPackage,
        presetKey: String?, presetVersion: Int?, onPrepared: (FormatCard) -> Unit,
        onCommitted: (() -> Unit)?): FormatCard {
        packageData.validateForImport()
        val existing = repository.getById(existingId) ?: error("待覆盖格式卡不存在")
        val updated = existing.copy(
            name = NamePolicy.normalize(existing.name),
            content = packageData.content,
            userTools = packageData.userTools,
            sourcePresetKey = presetKey ?: packageData.sourcePresetKey,
            sourcePresetVersion = presetVersion ?: packageData.sourcePresetVersion
        )
        onPrepared(updated)
        if (onCommitted == null) repository.save(updated) else repository.saveObserved(updated, onCommitted)
        return updated
    }
}
