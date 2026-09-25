package com.example.chatbar.domain.card

import com.example.chatbar.domain.prompt.CharacterNaiPromptDefaults

/** Character transfer production policy；Prompt literal 与 helper semantics 只由 Prompt domain 持有。 */
object AuthoritativeCharacterTransferPromptPolicy : CharacterTransferPromptPolicy {
    override fun defaultCharacterNaiNegativePrompt(): String =
        CharacterNaiPromptDefaults.defaultCharacterNaiNegativePrompt()

    override fun effectiveCharacterNaiNegativePrompt(value: String): String =
        CharacterNaiPromptDefaults.effectiveCharacterNaiNegativePrompt(value)
}
