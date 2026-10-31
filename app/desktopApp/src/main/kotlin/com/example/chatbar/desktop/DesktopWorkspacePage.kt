package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

internal data class DesktopWorkspaceDestination(val id: String, val label: String, val pending: Boolean = false)

/** Keep transient drafts in composition while inactive pages have no placed/focusable content. */
@Composable
internal fun DesktopRetainedPane(visible: Boolean, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.focusProperties { canFocus = visible }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})) { children, constraints ->
        if (!visible) layout(0, 0) {}
        else {
            val placed = children.map { it.measure(constraints) }
            layout(placed.maxOfOrNull { it.width } ?: constraints.minWidth,
                placed.maxOfOrNull { it.height } ?: constraints.minHeight) { placed.forEach { it.placeRelative(0, 0) } }
        }
    }
}

/** Bounded navigation around existing feature surfaces; controllers retain their own workflows. */
@Composable
internal fun DesktopWorkspacePage(title: String, destinations: List<DesktopWorkspaceDestination>,
    selected: String, enabled: Boolean = true, onSelect: (String) -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(DesktopBootstrapColors.background)) {
        val wide = maxWidth >= 700.dp
        @Composable fun Navigation() {
            if (wide) Column(Modifier.width(164.dp).fillMaxHeight().background(DesktopBootstrapColors.card)
                .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ManageHeading(title)
                destinations.forEach { item -> DesktopWorkspaceNavigationItem(item, selected == item.id,
                    enabled, Modifier.fillMaxWidth()) { onSelect(item.id) } }
            } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                destinations.forEach { item -> DesktopWorkspaceNavigationItem(item, selected == item.id,
                    enabled, Modifier.width(132.dp)) { onSelect(item.id) } }
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) { Navigation(); Box(Modifier.weight(1f).fillMaxHeight()) { content() } }
        else Column(Modifier.fillMaxSize()) { Navigation(); Box(Modifier.weight(1f).fillMaxWidth()) { content() } }
    }
}

@Composable
internal fun DesktopWorkspaceNavigationItem(item: DesktopWorkspaceDestination, selected: Boolean,
    enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = DesktopBootstrapColors
    Box(modifier.height(40.dp).background(if (selected) colors.secondary else androidx.compose.ui.graphics.Color.Transparent,
        RoundedCornerShape(6.dp)).semantics {
        role = Role.Tab; contentDescription = if (item.pending) "${item.label}，有新版本" else item.label
        this.selected = selected; if (!enabled) disabled()
    }.clickable(enabled = enabled, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            BasicText(item.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = if (enabled) colors.foreground else colors.mutedForeground, fontSize = 14.sp))
            if (item.pending) Box(Modifier.padding(start = 6.dp).size(6.dp)
                .background(colors.primary, androidx.compose.foundation.shape.CircleShape))
        }
    }
}
