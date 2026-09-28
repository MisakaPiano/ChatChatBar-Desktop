package com.example.chatbar.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoleplaySpeakerPresentationTest {
    @Test
    fun `marked speaker uses unique matching appearance image`() {
        val speaker = resolveRoleplaySpeakerIdentity(
            " alice ", listOf(RoleplaySpeakerCandidate("Alice", "images/alice.png")),
            "images/card.png", "Card",
        )

        assertEquals("Alice", speaker.displayName)
        assertEquals("images/alice.png", speaker.avatarReference)
    }

    @Test
    fun `unmarked speaker uses card fallback and ambiguous names do not pick arbitrary image`() {
        val candidates = listOf(
            RoleplaySpeakerCandidate("Alice", "images/a.png"),
            RoleplaySpeakerCandidate("alice", "images/b.png"),
        )
        val unmarked = resolveRoleplaySpeakerIdentity(null, candidates, "images/card.png", "Card")
        val ambiguous = resolveRoleplaySpeakerIdentity("Alice", candidates, "images/card.png", "Card")

        assertNull(unmarked.displayName)
        assertEquals("images/card.png", unmarked.avatarReference)
        assertEquals("Alice", ambiguous.displayName)
        assertNull(ambiguous.avatarReference)
    }
}
