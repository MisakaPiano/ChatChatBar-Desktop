package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 角色卡片仓库
 */
class CharacterRepository(private val storage: JsonFileStorage) {

    companion object {
        private const val ENTITY_TYPE = "character_cards"
    }

    private val _characters = MutableStateFlow<List<CharacterCard>>(emptyList())
    val characters: Flow<List<CharacterCard>> = _characters.asStateFlow()

    private var initialized = false
    private val writes = Mutex()

    /** 初始化 - 从磁盘加载全部角色卡 */
    suspend fun initialize() {
        if (initialized) return
        refreshCache()
        initialized = true
    }

    private suspend fun refreshCache() {
        _characters.value = storage.loadAll(ENTITY_TYPE, CharacterCard.serializer())
            .sortedByDescending { it.updatedAt }
    }

    suspend fun getAll(): List<CharacterCard> {
        initialize()
        return _characters.value
    }

    suspend fun getById(id: String): CharacterCard? {
        return storage.loadEntity(ENTITY_TYPE, id, CharacterCard.serializer())
    }

    suspend fun save(card: CharacterCard) = writes.withLock { saveLocked(card) }

    private suspend fun saveLocked(card: CharacterCard) {
        storage.saveEntity(ENTITY_TYPE, card.id, card, CharacterCard.serializer())
        refreshCache()
    }

    /** Merge a library operation into the current formal card rather than a UI snapshot. */
    suspend fun updateBackgroundLibrary(id: String, change: (CharacterCard) -> CharacterCard): CharacterCard = writes.withLock {
        val current = getById(id) ?: error("角色卡不存在")
        val next = change(current).copy(updatedAt = System.currentTimeMillis())
        require(next.id == id) { "背景库不能更改角色卡身份" }
        saveLocked(next)
        next
    }

    suspend fun update(card: CharacterCard) {
        val updated = card.copy(updatedAt = System.currentTimeMillis())
        save(updated)
    }

    suspend fun delete(id: String) = writes.withLock {
        storage.deleteEntity<CharacterCard>(ENTITY_TYPE, id)
        _characters.value = _characters.value.filterNot { it.id == id }
    }

    suspend fun exists(id: String): Boolean {
        return storage.exists(ENTITY_TYPE, id)
    }

    /** 搜索角色卡（名称模糊匹配） */
    suspend fun search(query: String): List<CharacterCard> {
        return getAll().filter { card ->
            card.name.contains(query, ignoreCase = true) ||
                card.characters.any { it.name.contains(query, ignoreCase = true) }
        }
    }
}
