package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import kotlin.test.*

class CharacterRewritePolicyTest {
    @Test fun `materialized rewrite preserves retained person configuration while clearing deleting and adding requested content`() {
        val voice = FishAudioVoiceBinding("synthetic-voice", "合成声线")
        val retained = CharacterInfo("retained", "合成人物甲", profile = "原简介", appearance = "原外观",
            appearanceImage = "synthetic-avatar.png", fishAudioVoice = voice, desktopEnabled = false)
        val removed = CharacterInfo("removed", "合成人物乙", profile = "待删除")
        val card = CharacterCard("synthetic-card", "合成卡", greeting = "原开场",
            characters = listOf(retained, removed), createdAt = 1, updatedAt = 1)
        val raw = """{"greeting":"","deleteCharacterIds":["removed"],"characters":[
            {"name":"合成人物甲","profile":"新简介"},
            {"id":"retained","appearance":""},
            {"name":"合成人物丙","profile":"新增简介"}
        ]}"""
        val candidate = CharacterRewritePolicy.materializeDraft(card,
            assertNotNull(CharacterRewritePolicy.parseDraft(raw)))
        var nextId = 0
        val merged = CharacterRewritePolicy.mergeInto(card, candidate) { "new-${++nextId}" }
        assertEquals("", merged.greeting)
        assertEquals(listOf("retained", "new-1"), merged.characters.map { it.id })
        // The existing service claims the first matching patch for each person.
        assertEquals(retained.copy(profile = "新简介"), merged.characters.first())
        assertEquals(voice, merged.characters.first().fishAudioVoice)
        assertEquals("合成人物丙", merged.characters.last().name)
        assertEquals(1, nextId)
        assertEquals(listOf(retained, removed), card.characters)
        assertEquals("原开场", card.greeting)
    }

    @Test fun `freeform materialization ignores structured deletion and person patches without changing roster configuration`() {
        val person = CharacterInfo("freeform-person", "合成人物", desktopEnabled = false)
        val card = CharacterCard("freeform-card", "自由卡", editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = "原正文", characters = listOf(person), createdAt = 1, updatedAt = 1)
        val draft = CharacterRewriteDraft(freeformCharacterText = "替换正文",
            deleteCharacterIds = listOf(person.id),
            characters = listOf(CharacterRewriteCharacterDraft(id = person.id, name = "意外人物补丁")))
        val candidate = CharacterRewritePolicy.materializeDraft(card, draft)
        assertTrue(candidate.characters.isEmpty())
        assertTrue(candidate.deleteCharacterIds.isEmpty())
        val merged = CharacterRewritePolicy.mergeInto(card, candidate) { error("No person should be created") }
        assertEquals("替换正文", merged.freeformCharacterText)
        assertEquals(listOf(person), merged.characters)
        assertEquals("原正文", card.freeformCharacterText)
    }
}
