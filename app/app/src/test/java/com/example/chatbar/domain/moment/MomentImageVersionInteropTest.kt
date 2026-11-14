package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentPost
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MomentImageVersionInteropTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun post(versions: String = "") = json.decodeFromString<MomentPost>("""
        {"id":"synthetic-post","characterCardId":"card","sessionId":"session","senderName":"Tester",
         "text":"Synthetic scene","imagePath":"old.png","imagePrompt":"old prompt","imageBrief":"old brief",
         "scheduledAt":100,"generatedAt":200 $versions}
    """.trimIndent().replace("200 ,", "200,"))

    @Test fun sixFieldsSurviveDecodeSelectEncodeAndColdDecode() {
        val source = post(""", "imageVersions":[{"id":"other","imagePath":"other.png","imagePrompt":"other prompt",
          "imageBrief":"other brief","createdAt":1234,"generatedImageMetadata":null}]""")
        val selected = MomentImageVersions.select(source, "other")
        assertEquals("other brief", selected.imageBrief)
        val cold = json.decodeFromString<MomentPost>(json.encodeToString(selected))
        assertEquals(selected, cold)
        assertEquals(1234, cold.imageVersions.first().createdAt)
        assertEquals("old brief", cold.imageVersions.last().imageBrief)
        assertEquals(0, cold.imageVersions.last().createdAt)
    }
    @Test fun oldDesktopAliasAndFourFieldVersionsRetainUnknownTime() {
        val source = post(""", "desktopImageVersions":[{"id":"old","imagePath":"old.png","imagePrompt":"old prompt"}]""")
        assertEquals(1, source.imageVersions.size)
        assertEquals(0, source.imageVersions.single().createdAt)
        val encoded = json.encodeToString(source)
        assertFalse(encoded.contains("desktopImageVersions"))
        assertTrue(encoded.contains("imageVersions"))
    }
    @Test fun appendRetainsOldVersionAndOwnBriefAndTime() {
        val source = post()
        val appended = MomentImageVersions.append(source, "new.png", "new prompt", null, "new brief", 4567)
        assertEquals("new brief", appended.imageBrief)
        assertEquals(listOf("old.png", "new.png"), appended.imageVersions.map { it.imagePath })
        assertEquals(4567, appended.imageVersions.last().createdAt)
        assertEquals("old brief", MomentImageVersions.select(appended, appended.imageVersions.first().id).imageBrief)
        assertEquals("new brief", appended.imageVersions.last().imageBrief)
    }
}
