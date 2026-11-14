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
        repository.addMessage(anchor)
        val later = ChatMessage.create(session.id, MessageRole.USER, "later message")
        repository.addMessage(later)
        val batches = listOf(4, 4, 2).mapIndexed { index, count ->
            val paths = List(count) { "synthetic/${index}-$it.png" }
            val image = ChatMessage.create(session.id, MessageRole.ASSISTANT, "", images = paths,
                generatedFromMessageId = anchor.id,
                generatedImageMetadata = paths.map { GeneratedImageMetadata(imagePath = it, baseCaption = "unchanged synthetic prompt", negativePrompt = "", sizePreset = "PORTRAIT", width = 832, height = 1216) })
            repository.addMessageAfter(image, anchor.id)
        }
        val reopened = ChatRepository(JsonFileStorage(context))
        val rows = reopened.getMessages(session.id).sortedWith(ChatMessage.TimelineComparator)
        assertEquals(listOf(anchor.id) + batches.map { it.id } + later.id, rows.map { it.id })
        val saved = rows.filter { it.generatedFromMessageId == anchor.id }
        assertEquals(listOf(4, 4, 2), saved.map { it.images.size })
        assertEquals(10, saved.sumOf { it.generatedImageMetadata.size })
        assertTrue(saved.flatMap { it.generatedImageMetadata }.all { it.baseCaption == "unchanged synthetic prompt" })
    }
}
