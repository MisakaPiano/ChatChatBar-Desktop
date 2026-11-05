package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
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
        var navigationViewport by remember { mutableStateOf(IntSize.Zero) }
        val horizontalNavigation = rememberScrollState()
        val density = LocalDensity.current
        val tabWidth = 132.dp
        val tabGap = 6.dp
        val tabPadding = 10.dp
        val destinationIds = destinations.map { it.id }
        LaunchedEffect(selected, wide, navigationViewport, density, destinationIds) {
            if (!wide && navigationViewport.width > 0) {
                // The selected item can be fully clipped. Wait for placement, then use the row's fixed geometry.
                withFrameNanos { }
                val index = destinations.indexOfFirst { it.id == selected }
                if (index >= 0) {
                    val left = with(density) { tabPadding.roundToPx() + index * (tabWidth.roundToPx() + tabGap.roundToPx()) }
                    val right = left + with(density) { tabWidth.roundToPx() }
                    val offset = horizontalNavigation.value
                    if (left < offset) horizontalNavigation.scrollTo(left)
                    else if (right > offset + navigationViewport.width)
                        horizontalNavigation.scrollTo(right - navigationViewport.width)
                }
            }
        }
        @Composable fun Navigation() {
            @Composable fun Item(item: DesktopWorkspaceDestination, modifier: Modifier) {
                val reveal = remember(item.id) { BringIntoViewRequester() }
                LaunchedEffect(selected, wide, navigationViewport) {
                    if (wide && selected == item.id && navigationViewport.width > 0 && navigationViewport.height > 0)
                        reveal.bringIntoView()
                }
                DesktopWorkspaceNavigationItem(item, selected == item.id, enabled,
                    modifier.bringIntoViewRequester(reveal)) { onSelect(item.id) }
            }
            if (wide) Column(Modifier.width(164.dp).fillMaxHeight().background(DesktopBootstrapColors.card)
                .onSizeChanged { navigationViewport = it }.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ManageHeading(title)
                destinations.forEach { item -> key(item.id) { Item(item, Modifier.fillMaxWidth()) } }
            } else Row(Modifier.fillMaxWidth().onSizeChanged { navigationViewport = it }.horizontalScroll(horizontalNavigation).padding(tabPadding),
                horizontalArrangement = Arrangement.spacedBy(tabGap)) {
                destinations.forEach { item -> key(item.id) { Item(item, Modifier.width(tabWidth)) } }
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
