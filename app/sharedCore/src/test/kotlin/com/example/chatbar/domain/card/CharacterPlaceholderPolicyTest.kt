package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharacterPlaceholderPolicyTest {
    @Test
    fun emptyEditorCharacterIsPlaceholder() {
        assertTrue(CharacterPlaceholderPolicy.isEmpty(CharacterInfo.create("")))
    }

    @Test
    fun unnamedEditorCharacterWithContentIsNotPlaceholder() {
        assertFalse(
            CharacterPlaceholderPolicy.isEmpty(
                CharacterInfo.create("").copy(profile = "不能丢失的设定")
            )
        )
    }

    @Test
    fun emptyPackagedCharacterIsPlaceholder() {
        assertTrue(CharacterPlaceholderPolicy.isEmpty(PackagedCharacter(name = "  ")))
    }

    @Test
    fun unnamedPackagedCharacterWithImageIsNotPlaceholder() {
        assertFalse(
            CharacterPlaceholderPolicy.isEmpty(
                PackagedCharacter(name = "", appearanceImageResourceId = "portrait")
            )
        )
    }
}
