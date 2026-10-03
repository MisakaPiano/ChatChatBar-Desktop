package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal data class DesktopCharacterManagementPresentation(
    val name: String,
    val avatar: BufferedImage?,
    val fallbackInitial: String,
    val characterCount: Int,
    val documentCount: Int,
)

/** Presentation-only decoding; the controller remains the resource-reference authority. */
internal fun desktopCharacterManagementPresentation(
    card: CharacterCard,
    imageBytes: (String?) -> ByteArray?,
): DesktopCharacterManagementPresentation = DesktopCharacterManagementPresentation(
    name = card.name,
    avatar = runCatching { imageBytes(card.avatar)?.inputStream()?.use(ImageIO::read) }.getOrNull(),
    fallbackInitial = card.name.trim().take(1).ifEmpty { "?" },
    characterCount = card.characters.size,
    documentCount = card.customDocuments.size,
)
