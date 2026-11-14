package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRepositoryBatchImagesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `three production appends under same anchor survive repository reopen in batch order`() = runTest {
        val root = temp.newFolder()
        val context = object : ContextWrapper(null) { override fun getFilesDir(): File = root }
        val repository = ChatRepository(JsonFileStorage(context))
        val session = ChatSession.create("synthetic-card", "Synthetic batch test").copy(id = "synthetic-session")
        repository.createSession(session)
        val anchor = ChatMessage.create(session.id, MessageRole.ASSISTANT, "synthetic anchor")
        val savedAnchor = repository.addMessage(anchor)
        val later = ChatMessage.create(session.id, MessageRole.USER, "later message")
        repository.addMessage(later)
        val batches = listOf(4, 4, 2).mapIndexed { index, count ->
            val paths = List(count) { "synthetic/${index}-$it.png" }
            val image = ChatMessage.create(session.id, MessageRole.ASSISTANT, "", images = paths,
                generatedFromMessageId = anchor.id,
                generatedImageMetadata = paths.map { GeneratedImageMetadata(imagePath = it, baseCaption = "unchanged synthetic prompt", negativePrompt = "", sizePreset = "PORTRAIT", width = 832, height = 1216) })
            repository.addMessageAfter(image, anchor.id, expectedAnchor = savedAnchor)
        }
        val reopened = ChatRepository(JsonFileStorage(context))
        val rows = reopened.getMessages(session.id).sortedWith(ChatMessage.TimelineComparator)
        assertEquals(listOf(anchor.id) + batches.map { it.id } + later.id, rows.map { it.id })
        val saved = rows.filter { it.generatedFromMessageId == anchor.id }
        assertEquals(listOf(4, 4, 2), saved.map { it.images.size })
        assertEquals(10, saved.sumOf { it.generatedImageMetadata.size })
        assertTrue(saved.flatMap { it.generatedImageMetadata }.all { it.baseCaption == "unchanged synthetic prompt" })
    }
    @Test fun `guarded batch cannot append after an anchor was deleted`() = runTest {
        val repository = repository()
        val session = ChatSession.create("synthetic-card", "Synthetic deletion guard")
        repository.createSession(session)
        val anchor = repository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "original"))
        repository.deleteMessage(anchor.id, session.id)
        val result = runCatching { repository.addMessageAfter(image(session.id), anchor.id, expectedAnchor = anchor) }
        assertTrue(result.isFailure); assertTrue(repository.getMessages(session.id).isEmpty())
    }
    @Test fun `guarded batch cannot append after an anchor was edited`() = runTest {
        val repository = repository()
        val session = ChatSession.create("synthetic-card", "Synthetic edit guard")
        repository.createSession(session)
        val anchor = repository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "original"))
        repository.updateMessage(anchor.copy(content = "edited"))
        val result = runCatching { repository.addMessageAfter(image(session.id), anchor.id, expectedAnchor = anchor) }
        assertTrue(result.isFailure); assertEquals(listOf("edited"), repository.getMessages(session.id).map { it.content })
    }
    @Test fun `guarded batch cannot resurrect a deleted session with an old anchor record`() = runTest {
        val repository = repository()
        val session = ChatSession.create("synthetic-card", "Synthetic session guard")
        repository.createSession(session)
        val anchor = repository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "original"))
        repository.deleteSessionRecord(session.id)
        val result = runCatching { repository.addMessageAfter(image(session.id), anchor.id, expectedAnchor = anchor) }
        assertTrue(result.isFailure); assertNull(repository.getSession(session.id))
        assertEquals(listOf(anchor.id), repository.getMessages(session.id).map { it.id })
    }
    private fun repository(): ChatRepository {
        val root = temp.newFolder()
        return ChatRepository(JsonFileStorage(object : ContextWrapper(null) { override fun getFilesDir(): File = root }))
    }
    private fun image(sessionId: String) = ChatMessage.create(sessionId, MessageRole.ASSISTANT, "", images = listOf("synthetic/late.png"))

}
