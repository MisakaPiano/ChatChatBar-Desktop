package com.example.chatbar.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.CirclePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.PanelLeftClose
import com.composables.icons.lucide.PanelLeftOpen
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Pin

/** Same semantic mappings as formal upstream AppIcons. */
internal object DesktopAppIcons {
    val Add: ImageVector get() = Lucide.CirclePlus
    val Edit: ImageVector get() = Lucide.Pencil
    val Star: ImageVector get() = Lucide.Star
    val Chat: ImageVector get() = Lucide.MessageCircle
    val Settings: ImageVector get() = Lucide.Settings
    val Tools: ImageVector get() = Lucide.Wrench
    val Data: ImageVector get() = Lucide.Database
    val Refresh: ImageVector get() = Lucide.RefreshCw
    val Collapse: ImageVector get() = Lucide.PanelLeftClose
    val Expand: ImageVector get() = Lucide.PanelLeftOpen
    val DetailsOpen: ImageVector get() = Lucide.ChevronDown
    val DetailsClosed: ImageVector get() = Lucide.ChevronRight
    val Delete: ImageVector get() = Lucide.Trash2
    val Pin: ImageVector get() = Lucide.Pin
}
