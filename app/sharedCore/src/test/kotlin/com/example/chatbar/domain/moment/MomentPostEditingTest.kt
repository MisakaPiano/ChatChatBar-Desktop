package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.repository.MomentRepository
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MomentPostEditingTest {
    @Test fun `shared repository edits explicit author and text without changing media or stable post identity`() = runBlocking {
        val root = Files.createTempDirectory("moment-shared-author-")
        try {
            val repository = MomentRepository(JsonFileStorage(root))
            repository.savePost(MomentPost("post", "card", "session", senderCharacterId = "first", senderName = "Same",
                text = "old", imagePath = "images/retained.png", imagePrompt = "retained prompt", scheduledAt = 1, generatedAt = 2))
            val original = requireNotNull(repository.getPost("post"))
            val sender = MomentSender("second", "Same", "images/other.png")
            val changed = requireNotNull(repository.updatePostContent("post", " new ", sender))
            assertEquals(original.copy(text = "new", senderCharacterId = sender.characterId,
                senderName = sender.name, senderAvatar = sender.avatar, updatedAt = changed.updatedAt), changed)
            assertEquals(changed, repository.updatePostText("post", "new"))
            val file = root.resolve("entities/moment_posts/post.json")
            val bytes = Files.readAllBytes(file)
            val failure = runCatching { repository.updatePostContent("post", "valid", sender.copy(name = " ")) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertArrayEquals(bytes, Files.readAllBytes(file))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `sender choices retain ids and use same avatar precedence as generation`() {
        val card = CharacterCard(id = "card", name = "卡", avatar = "card.png", characters = listOf(
            CharacterInfo("a", "同名", appearanceImage = "a.png"),
            CharacterInfo("b", "同名", appearanceImage = " ")
        ), createdAt = 0, updatedAt = 0)
        val options = MomentPostEditing.senderOptions(card)
        assertEquals(3, options.map { it.key }.distinct().size)
        assertEquals(MomentSender(null, "卡", "card.png"), options[0].sender)
        assertEquals(MomentSender("a", "同名", "a.png"), options[1].sender)
        assertEquals(MomentSender("b", "同名", "card.png"), options[2].sender)
        assertTrue(MomentPostEditing.senderOptions(null).isEmpty())
    }
}
