package com.example.chatbar.desktop

import com.example.chatbar.domain.card.*
import java.util.Base64

/** Only the exported full package adopts the chosen base. The saved card and avatar stay unchanged. */
internal fun desktopCoverExportPackage(source: CharacterCardPackage, replacement: ByteArray?,
    compatibility: CharacterCardExportCompatibility): CharacterCardPackage {
    if (replacement == null || compatibility == CharacterCardExportCompatibility.OFFICIAL_V9)
        return source.forExportCompatibility(compatibility)
    DesktopImageEditing.requireStatic(replacement)
    DesktopImageEditing.decode(replacement)
    val bytes = if (PngTextChunks.isPng(replacement)) OriginalCharacterCover.imageOnlyPng(replacement) else replacement
    val oldCover = source.card.coverImageResourceId
    val usedElsewhere = source.card.avatarResourceId == oldCover || source.card.chatBackgroundResourceId == oldCover ||
        source.card.characters.any { it.appearanceImageResourceId == oldCover }
    val images = if (oldCover != null && !usedElsewhere) source.images - oldCover else source.images
    var id = "cover-export-base"
    while (id in images) id += "-1"
    return source.copy(card = source.card.copy(coverImageResourceId = id), images = images + (id to
        PackagedImage("cover-base.${desktopImageResourceExtension(bytes)}", Base64.getEncoder().encodeToString(bytes))))
        .forExportCompatibility(compatibility)
}
