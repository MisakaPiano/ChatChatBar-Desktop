package com.example.chatbar.domain.card

import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthoritativeCharacterTransferPromptPolicyTest {
    @Test
    fun delegatesDefaultAndEffectiveSemanticsToPromptAuthority() {
        assertEquals(
            CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt(),
            AuthoritativeCharacterTransferPromptPolicy.defaultCharacterNaiNegativePrompt(),
        )
        listOf("", "  \n", " custom ").forEach { value ->
            assertEquals(
                CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(value),
                AuthoritativeCharacterTransferPromptPolicy.effectiveCharacterNaiNegativePrompt(value),
            )
        }
    }
}
