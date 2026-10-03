package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

internal enum class DesktopNavigationPlacement { RAIL, TOP }
internal data class DesktopNavigationItem(val route: DesktopPrimaryRoute, val selected: Boolean)
internal data class DesktopShellPresentation(
    val placement: DesktopNavigationPlacement,
    val railWidthDp: Int,
    val routes: List<DesktopNavigationItem>,
)

internal fun desktopShellPresentation(size: DesktopShellSize, selected: DesktopPrimaryRoute) = DesktopShellPresentation(
    placement = if (size == DesktopShellSize.COMPACT) DesktopNavigationPlacement.TOP else DesktopNavigationPlacement.RAIL,
    railWidthDp = if (size == DesktopShellSize.WIDE) 176 else 80,
    routes = DesktopPrimaryRoute.entries.map { DesktopNavigationItem(it, it == selected) },
)

/** One shared parent column bounds header, timeline, and composer, including with a hidden sidebar. */
internal object DesktopChatReadingWidth {
    const val MAX_DP = 880
    fun forAvailableWidth(widthDp: Float): Float = widthDp.coerceIn(0f, MAX_DP.toFloat())
}

internal data class DesktopSessionRowPresentation(
    val avatarReference: String?, val title: String, val pinned: Boolean, val archived: Boolean,
    val selected: Boolean, val preview: String?,
)
internal fun desktopSessionRowPresentation(item: DesktopPrimarySessionItem, selected: Boolean, preview: String?) =
    DesktopSessionRowPresentation(item.avatarReference, item.title, item.pinned, item.characterMissing,
        selected, preview?.takeIf(String::isNotBlank))

internal data class DesktopPersonRowPresentation(val id: String, val name: String, val avatarReference: String?, val profile: String?)
internal fun desktopPersonRow(entry: CharacterInfo, showProfile: Boolean = true) = DesktopPersonRowPresentation(
    entry.id, entry.name, entry.appearanceImage, entry.profile.takeIf { showProfile && it.isNotBlank() },
)
internal fun desktopPersonInitial(person: DesktopPersonRowPresentation) = person.name.trim().take(1).ifEmpty { "?" }
internal fun desktopPersonAvatar(person: DesktopPersonRowPresentation, read: (String?) -> ByteArray?): ImageBitmap? =
    runCatching { read(person.avatarReference)?.let { Image.makeFromEncoded(it).toComposeImageBitmap() } }.getOrNull()
internal enum class DesktopPersonDetailKind { STRUCTURED_FIELDS, NAME_AND_AVATAR }
internal data class DesktopPersonWorkspace(val rows: List<DesktopPersonRowPresentation>, val selected: CharacterInfo?, val avatarBook: Boolean) {
    val detailKind: DesktopPersonDetailKind
        get() = if (avatarBook) DesktopPersonDetailKind.NAME_AND_AVATAR else DesktopPersonDetailKind.STRUCTURED_FIELDS
}
internal fun desktopPersonWorkspace(card: CharacterCard, selectedId: String?) = DesktopPersonWorkspace(
    card.characters.map { desktopPersonRow(it, showProfile = card.editMode == CharacterEditMode.STRUCTURED) },
    card.characters.firstOrNull { it.id == selectedId } ?: card.characters.firstOrNull(),
    card.editMode == CharacterEditMode.FREEFORM,
)
internal object DesktopPersonLayout {
    const val MASTER_DETAIL_MIN_DP = 760
    fun sideBySide(widthDp: Float) = widthDp >= MASTER_DETAIL_MIN_DP
}
