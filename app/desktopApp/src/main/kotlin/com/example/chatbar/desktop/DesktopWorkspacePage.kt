package com.example.chatbar.desktop

import androidx.compose.foundation.background
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

internal data class DesktopWorkspaceDestination(val id: String, val label: String)

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
                destinations.forEach { item -> BootstrapButton(item.label, enabled = enabled,
                    secondary = selected != item.id) { onSelect(item.id) } }
            } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                destinations.forEach { item -> BootstrapButton(item.label, enabled = enabled,
                    secondary = selected != item.id) { onSelect(item.id) } }
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) { Navigation(); Box(Modifier.weight(1f).fillMaxHeight()) { content() } }
        else Column(Modifier.fillMaxSize()) { Navigation(); Box(Modifier.weight(1f).fillMaxWidth()) { content() } }
    }
}
