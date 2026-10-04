package com.example.chatbar.interop

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.operation.AppDataOperationGate
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.card.CharacterCardTransferCore
import com.example.chatbar.domain.card.CharacterDocumentRagCleanup
import com.example.chatbar.domain.card.CharacterResourceStore
import com.example.chatbar.domain.card.CharacterTransferCharacterStore
import com.example.chatbar.domain.card.CharacterTransferFormatCardStore
import com.example.chatbar.domain.card.CharacterTransferPromptPolicy
import com.example.chatbar.domain.card.CharacterTransferWorldBookStore
import com.example.chatbar.domain.card.FormatCardPackage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

/** Test-only real shared transfer path: cancel after the durable Character callback, before publication. */
object CharacterTransferPostCommitCancellationFixture {
    fun create(
        characters: CharacterRepository,
        worlds: WorldBookRepository,
        formats: FormatCardRepository,
        resources: CharacterResourceStore,
        promptPolicy: CharacterTransferPromptPolicy,
        json: Json,
        gate: AppDataOperationGate,
        cancellation: CancellationException,
        afterDurableCommit: () -> Unit = {},
    ): CharacterCardTransferCore {
        val characterStore = object : CharacterTransferCharacterStore {
            override suspend fun getAll() = characters.getAll()
            override suspend fun getById(id: String) = characters.getById(id)
            override suspend fun save(card: CharacterCard, onCommitted: () -> Unit) = saveObserved(card, onCommitted)
            override suspend fun saveObserved(card: CharacterCard, onCommitted: () -> Unit) {
                characters.saveObserved(card) {
                    onCommitted()
                    afterDurableCommit()
                    throw cancellation
                }
            }
            override suspend fun readDurable(id: String): JsonFileStorage.EntityReadResult<CharacterCard> =
                characters.readDurable(id)
            override suspend fun delete(id: String, onCommitted: () -> Unit) {
                characters.delete(id)
                onCommitted()
            }
        }
        val worldStore = object : CharacterTransferWorldBookStore {
            override suspend fun getAll() = worlds.getAll()
            override suspend fun getById(id: String) = worlds.getById(id)
            override suspend fun save(book: WorldBook) = worlds.save(book)
            override suspend fun delete(id: String) = worlds.delete(id)
        }
        val formatStore = object : CharacterTransferFormatCardStore {
            override suspend fun getById(id: String): FormatCard? = formats.getById(id)
            override suspend fun importCharacterDefault(packageData: FormatCardPackage,
                onCreating: (String) -> Unit): FormatCard = error("No embedded FormatCard in this fixture")
            override suspend fun delete(id: String) = formats.delete(id)
        }
        return CharacterCardTransferCore(characterStore, worldStore, formatStore,
            resources, promptPolicy, CharacterDocumentRagCleanup {}, json, gate,
            Dispatchers.Unconfined, System::currentTimeMillis, { UUID.randomUUID().toString() })
    }
}
