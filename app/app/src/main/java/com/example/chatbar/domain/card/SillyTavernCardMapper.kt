package com.example.chatbar.domain.card

import android.util.Log

/** Android Prompt/log wiring for the shared SillyTavern mapping authority. */
internal object AndroidSillyTavernCardMapper {
    val delegate = SillyTavernCardMapper(
        promptPolicy = AndroidCharacterTransferPromptPolicy,
        errorReporter = SillyTavernMappingErrorReporter { error ->
            Log.e("ChatBar", "解析角色卡内嵌世界书失败: ${error.message}", error)
        },
    )

    fun toCharacterCardPackage(card: SillyTavernCard): CharacterCardPackage =
        delegate.toCharacterCardPackage(card)
}
