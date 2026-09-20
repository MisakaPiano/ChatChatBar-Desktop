package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CharacterSessionServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun oldCharacterDataHasNoFormatBinding() {
        val card = Json.decodeFromString(
            CharacterCard.serializer(),
            """{"id":"old","name":"角色","createdAt":1,"updatedAt":1}"""
        )
        assertNull(card.defaultFormatCardId)
    }

    @Test
    fun newSessionsUseBindingWhileExistingSessionsKeepTheirOwnChoice() = runTest {
        val storage = JsonFileStorage(temp.newFolder().toPath())
        val characters = CharacterRepository(storage)
        val chats = ChatRepository(storage)
        val formats = FormatCardRepository(storage)
        val service = CharacterSessionService(characters, chats, formats)
        val first = FormatCard.create("初始格式", "内容一")
        val second = FormatCard.create("修改格式", "内容二")
        formats.save(first)
        formats.save(second)
        val card = CharacterCard.create("角色", greeting = "开场白").copy(defaultFormatCardId = first.id)
        characters.save(card)
        val session = requireNotNull(chats.getSession(service.createSessionForCharacter(card.id)))
        assertEquals(first.id, session.formatCardId)
        chats.updateSession(session.copy(formatCardId = second.id))
        characters.save(card.copy(defaultFormatCardId = null))
        assertEquals(second.id, chats.getSession(session.id)?.formatCardId)
        val unbound = requireNotNull(chats.getSession(service.createSessionForCharacter(card.id)))
        assertNull(unbound.formatCardId)
        characters.save(card)
        assertNull(chats.getSession(unbound.id)?.formatCardId)
        assertEquals(first.id, chats.getSession(service.createSessionForCharacter(card.id))?.formatCardId)
    }

}
