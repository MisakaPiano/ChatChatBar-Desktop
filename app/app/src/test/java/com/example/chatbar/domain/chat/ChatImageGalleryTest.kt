package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.*
import org.junit.Test

class ChatImageGalleryTest {
    private fun message(id: String, paths: List<String>) = ChatMessage(id=id,sessionId="session",role=MessageRole.ASSISTANT,
        content="synthetic",images=paths,createdAt=100,updatedAt=100,alternatives=listOf("a","b"))
    @Test fun duplicatePathsKeepEveryAttachmentIdentityButTextAlternativesDoNotMultiplyImages() {
        val collector=ChatImageGalleryCollector()
        collector.accept(message("m1",listOf("shared.png","shared.png")))
        collector.accept(message("m2",listOf("shared.png")))
        val snapshot=collector.snapshot()
        assertEquals(listOf("m1::0","m1::1","m2::0"),snapshot.images.map{it.key})
        assertEquals(3,snapshot.images.size)
    }
    @Test fun fullHistoryCollectionRetainsOldPicturesAndSeparatesMissingFromOmitted() {
        val collector=ChatImageGalleryCollector { it != "missing.png" }
        (1..180).forEach { index -> collector.accept(message("m$index",when(index){
            1 -> listOf("old.png")
            180 -> listOf(OMITTED_SAVE_SLOT_IMAGE_PREFIX+"proof","missing.png")
            else -> emptyList()
        })) }
        val snapshot=collector.snapshot()
        assertEquals(180,snapshot.scannedMessages)
        assertEquals(1,snapshot.omittedImages)
        assertEquals(listOf("m1","m180"),snapshot.images.map{it.messageId})
        assertTrue(snapshot.images.first().readable)
        assertFalse(snapshot.images.last().readable)
        assertEquals(1,snapshot.images.last().imageIndex)
    }
    @Test fun snapshotIsFrozenAcrossLaterCollectorAdditions() {
        val collector=ChatImageGalleryCollector(); collector.accept(message("m1",listOf("first.png")))
        val before=collector.snapshot()
        collector.accept(message("m2",listOf("second.png")))
        assertEquals(1,before.images.size);assertEquals(2,collector.snapshot().images.size)
    }
}
