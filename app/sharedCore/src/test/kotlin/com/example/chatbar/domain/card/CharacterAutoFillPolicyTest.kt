package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.*
import kotlin.test.*

class CharacterAutoFillPolicyTest {
    @Test fun `fill candidate preserves existing voice variant identity and filled fields while adding a new person once`() {
        val voice = FishAudioVoiceBinding("synthetic-voice", "合成音色")
        val person = CharacterInfo("retained-person", "合成人物甲", profile = "既有简介",
            appearanceImage = "synthetic-avatar.png", fishAudioVoice = voice, desktopEnabled = false)
        val card = CharacterCard("synthetic-card", "合成卡", characters = listOf(person), createdAt = 1, updatedAt = 1)
        val raw = """{"characters":[
            {"targetIndex":0,"name":"合成人物甲","profile":"候选简介","appearance":"合成外观"},
            {"name":"合成人物乙","profile":"新增简介"},
            {"name":"合成人物乙","profile":"重复候选"}
        ]}"""
        val candidate = CharacterAutoFillPolicy.constrainToTargets(
            assertNotNull(CharacterAutoFillPolicy.parseDraft(raw)), card)
        var generated = 0
        val merged = CharacterAutoFillPolicy.mergeCharacters(card.characters, candidate.characters) {
            "new-person-${++generated}"
        }
        assertEquals(1, generated)
        assertEquals(listOf("retained-person", "new-person-1"), merged.map { it.id })
        assertEquals(person.copy(appearance = "合成外观"), merged.first())
        assertEquals(voice, merged.first().fishAudioVoice)
        assertEquals(listOf(person), card.characters)
        assertEquals("合成人物乙", merged.last().name)
    }
}
