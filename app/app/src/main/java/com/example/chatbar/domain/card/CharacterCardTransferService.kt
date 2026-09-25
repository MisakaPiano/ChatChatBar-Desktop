package com.example.chatbar.domain.card

import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.rag.RagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Android API-compatible facade over the authoritative shared Character transfer core. */
class CharacterCardTransferService(
    private val app: ChatBarApp,
    private val repository: CharacterRepository,
    worldBookRepository: WorldBookRepository,
    formatCardRepository: FormatCardRepository,
    ragRepository: RagRepository,
    private val json: Json,
) {
    private val core = CharacterCardTransferCore(
        characterRepository = repository,
        worldBookRepository = worldBookRepository,
        formatCardRepository = formatCardRepository,
        resources = AndroidCharacterResourceStore(
            filesDir = app.filesDir,
            assetReader = app.assets::open,
        ),
        promptPolicy = AndroidCharacterTransferPromptPolicy,
        ragCleanup = AndroidCharacterDocumentRagCleanup(ragRepository),
        json = json,
    )

    suspend fun exportJson(id: String): String = core.exportJson(id)

    suspend fun exportPng(
        id: String,
        options: CharacterCardPngExportOptions = CharacterCardPngExportOptions(),
    ): ByteArray = withContext(Dispatchers.IO) {
        val export = core.prepareExport(id)
        val renderedPng = CharacterCardPngRenderer.render(app, export.card, options)
        CharacterCardPngPackageCodec.attach(renderedPng, export.packageData, json)
    }

    fun decode(rawJson: String): CharacterCardPackage = core.decode(rawJson)

    fun decodePng(pngBytes: ByteArray): CharacterCardPackage? = core.decodePng(pngBytes)

    suspend fun duplicate(id: String): CharacterCard = core.duplicate(id)

    suspend fun importNew(
        packageData: CharacterCardPackage,
        requestedName: String = packageData.card.name,
        presetKey: String? = null,
        presetVersion: Int? = null,
    ): CharacterCard = core.importNew(packageData, requestedName, presetKey, presetVersion)

    suspend fun overwrite(
        existingId: String,
        packageData: CharacterCardPackage,
        presetKey: String? = null,
        presetVersion: Int? = null,
    ): CharacterCard = core.overwrite(existingId, packageData, presetKey, presetVersion)

    suspend fun deleteCard(id: String) = core.deleteCard(id)
}
