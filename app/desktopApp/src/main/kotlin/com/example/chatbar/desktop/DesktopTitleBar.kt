package com.example.chatbar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One recorder shared by shell root/title children; all positions are actual client pixel bounds. */
internal class DesktopChromeLayoutRecorder(private val chrome: DesktopWindowChrome) {
    private var width = 0f
    private var height = 0f
    private var title: ChromeRect? = null
    private var icon: ChromeRect? = null
    private val routes = mutableMapOf<DesktopPrimaryRoute, ChromeRect>()
    private val captions = mutableMapOf<ChromeHit, ChromeRect>()
    fun root(value: LayoutCoordinates) { width = value.size.width.toFloat(); height = value.size.height.toFloat(); publish() }
    fun title(value: LayoutCoordinates) { title = value.rect(); publish() }
    fun icon(value: LayoutCoordinates) { icon = value.rect(); publish() }
    fun route(route: DesktopPrimaryRoute, value: LayoutCoordinates) { routes[route] = value.rect(); publish() }
    fun caption(hit: ChromeHit, value: LayoutCoordinates) { captions[hit] = value.rect(); publish() }
    fun clearNavigation() { title = null; icon = null; routes.clear(); publish() }
    fun clearCaptions() { captions.clear(); publish() }
    private fun LayoutCoordinates.rect() = boundsInWindow().let { ChromeRect(it.left, it.top, it.right, it.bottom) }
    private fun publish() { chrome.updateLayout(DesktopChromeLayout(width, height, title, routes.values.toList(), captions.toMap(), icon)) }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun DesktopTitleBar(
    shellSize: DesktopShellSize,
    route: DesktopPrimaryRoute,
    locked: Boolean,
    chrome: DesktopWindowChrome,
    recorder: DesktopChromeLayoutRecorder,
    navigationVisible: Boolean = true,
    captionsVisible: Boolean = true,
    onNavigate: (DesktopPrimaryRoute) -> Unit,
) {
    val colors = LocalDesktopPalette.current
    val t = LocalDesktopUiStrings.current
    val state by chrome.state.collectAsState()
    val presentation = desktopTitleBarPresentation(shellSize, route, locked)
    DisposableEffect(recorder, navigationVisible, captionsVisible) {
        onDispose {
            if (navigationVisible) recorder.clearNavigation()
            if (captionsVisible) recorder.clearCaptions()
        }
    }
    Row(Modifier.fillMaxWidth().height(44.dp).background(colors.card)
        .then(if (navigationVisible) Modifier.onGloballyPositioned(recorder::title) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        if (navigationVisible) {
            Row(Modifier.width(if (presentation.showBrandName) 160.dp else 40.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(40.dp).fillMaxHeight().onGloballyPositioned(recorder::icon), contentAlignment = Alignment.Center) {
                    Image(painterResource(DesktopBrandResources.LOGO_RESOURCE), "ChatChatBar", Modifier.size(24.dp))
                }
                if (presentation.showBrandName) BasicText("ChatChatBar",
                    style = TextStyle(color = colors.foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }
            presentation.routes.forEach { item ->
                val label = t(when (item.route) {
                    DesktopPrimaryRoute.CHAT -> DesktopUiText.CHAT
                    DesktopPrimaryRoute.MANAGE -> DesktopUiText.MANAGE
                    DesktopPrimaryRoute.TOOLS -> DesktopUiText.TOOLS
                    DesktopPrimaryRoute.DATA -> DesktopUiText.DATA
                })
                val icon = when (item.route) {
                    DesktopPrimaryRoute.CHAT -> DesktopAppIcons.Chat
                    DesktopPrimaryRoute.MANAGE -> DesktopAppIcons.Settings
                    DesktopPrimaryRoute.TOOLS -> DesktopAppIcons.Tools
                    DesktopPrimaryRoute.DATA -> DesktopAppIcons.Data
                }
                TooltipArea(tooltip = {
                    Box(Modifier.background(colors.card, RoundedCornerShape(5.dp)).padding(8.dp)) { StatusText(label) }
                }) {
                    Row(Modifier.width(presentation.routeWidthDp.dp).height(36.dp)
                        .onGloballyPositioned { recorder.route(item.route, it) }
                        .background(if (item.selected) colors.secondary else Color.Transparent, RoundedCornerShape(6.dp))
                        .semantics { contentDescription = label }
                        .selectable(item.selected, enabled = item.enabled, role = Role.Tab) { onNavigate(item.route) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        val foreground = (if (item.selected) colors.primary else colors.mutedForeground)
                            .copy(alpha = if (item.enabled) 1f else 0.5f)
                        Image(rememberVectorPainter(icon), null, Modifier.size(17.dp), colorFilter = ColorFilter.tint(foreground))
                        if (presentation.showRouteLabels) {
                            Spacer(Modifier.width(6.dp))
                            BasicText(label, style = TextStyle(color = foreground, fontSize = 13.sp), maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (navigationVisible && state.failure != null) BasicText("!", Modifier.padding(4.dp).semantics { contentDescription = state.failure!! },
            style = TextStyle(color = colors.destructive))
        if (captionsVisible && DesktopWindowChrome.isWindows) listOf(ChromeHit.MINIMIZE, ChromeHit.MAXIMIZE, ChromeHit.CLOSE).forEach { hit ->
            val label = t(when (hit) {
                ChromeHit.MINIMIZE -> DesktopUiText.WINDOW_MINIMIZE
                ChromeHit.MAXIMIZE -> if (state.maximized) DesktopUiText.WINDOW_RESTORE else DesktopUiText.WINDOW_MAXIMIZE
                else -> DesktopUiText.CLOSE
            })
            val hover = state.hovered == hit
            val down = state.pressed == hit && hover
            val background = when {
                hover && hit == ChromeHit.CLOSE -> if (down) Color(0xFFC42B1C) else Color(0xFFE81123)
                down -> colors.border
                hover -> colors.secondary
                else -> Color.Transparent
            }
            Box(Modifier.width(presentation.captionWidthDp.dp).fillMaxHeight()
                .onGloballyPositioned { recorder.caption(hit, it) }
                .background(background).semantics { contentDescription = label }
                .clickable(role = Role.Button) { desktopCaptionCommand(hit, state.maximized)?.let(chrome::command) },
                contentAlignment = Alignment.Center) {
                val ink = if (hover && hit == ChromeHit.CLOSE) Color.White else colors.foreground
                Canvas(Modifier.size(12.dp)) {
                    val stroke = 1.dp.toPx()
                    when (hit) {
                        ChromeHit.MINIMIZE -> drawLine(ink, Offset(1.dp.toPx(), size.height / 2), Offset(size.width - 1.dp.toPx(), size.height / 2), stroke)
                        ChromeHit.MAXIMIZE -> {
                            if (state.maximized) {
                                drawRect(ink, Offset(3.dp.toPx(), 1.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()), style = Stroke(stroke))
                                drawRect(if (hover) background else colors.card, Offset(1.dp.toPx(), 3.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()))
                                drawRect(ink, Offset(1.dp.toPx(), 3.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()), style = Stroke(stroke))
                            } else drawRect(ink, Offset(1.dp.toPx(), 1.dp.toPx()), Size(10.dp.toPx(), 10.dp.toPx()), style = Stroke(stroke))
                        }
                        else -> {
                            drawLine(ink, Offset(1.dp.toPx(), 1.dp.toPx()), Offset(size.width - 1.dp.toPx(), size.height - 1.dp.toPx()), stroke)
                            drawLine(ink, Offset(size.width - 1.dp.toPx(), 1.dp.toPx()), Offset(1.dp.toPx(), size.height - 1.dp.toPx()), stroke)
                        }
                    }
                }
            }
        }
    }
}
