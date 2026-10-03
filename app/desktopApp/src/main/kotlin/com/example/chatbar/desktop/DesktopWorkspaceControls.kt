package com.example.chatbar.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Compact visual icon, full 48dp keyboard/pointer target; no changes to legacy button callers. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun DesktopIconAction(label: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = DesktopBootstrapColors
    TooltipArea(tooltip = {
        Box(Modifier.background(colors.card, RoundedCornerShape(6.dp)).padding(8.dp)) { StatusText(label) }
    }) {
    Box(Modifier.size(48.dp)
        .semantics { contentDescription = label }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Image(rememberVectorPainter(icon), null, Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(if (destructive) colors.destructive else colors.mutedForeground))
    }
    }
}
