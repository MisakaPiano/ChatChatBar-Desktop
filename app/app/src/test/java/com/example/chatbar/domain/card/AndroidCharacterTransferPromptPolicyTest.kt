package com.example.chatbar.domain.card

import com.example.chatbar.domain.prompt.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidCharacterTransferPromptPolicyTest {
    @Test
    fun delegatesThroughPromptTemplatesFacade() {
        assertEquals(
            PromptTemplates.defaultCharacterNaiNegativePrompt(),
            AndroidCharacterTransferPromptPolicy.defaultCharacterNaiNegativePrompt(),
        )
        listOf("", "  \n", " custom ").forEach { value ->
            assertEquals(
                PromptTemplates.effectiveCharacterNaiNegativePrompt(value),
                AndroidCharacterTransferPromptPolicy.effectiveCharacterNaiNegativePrompt(value),
            )
        }
    }
}
