package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Static Desktop instructions. Opening help has no feature-owner or navigation dependency. */
internal fun desktopContextHelp(route: DesktopPrimaryRoute): Pair<DesktopUiText, List<DesktopUiText>> = when (route) {
    DesktopPrimaryRoute.CHAT -> DesktopUiText.CHAT to listOf(DesktopUiText.HELP_CHAT_LIST, DesktopUiText.HELP_CHAT_SEND,
        DesktopUiText.HELP_CHAT_PEOPLE, DesktopUiText.HELP_CHAT_AUDIO, DesktopUiText.HELP_CHAT_MORE)
    DesktopPrimaryRoute.MOMENTS -> DesktopUiText.MOMENTS to listOf(DesktopUiText.HELP_MOMENTS_EDIT,
        DesktopUiText.HELP_MOMENTS_RETRY, DesktopUiText.HELP_MOMENTS_SCHEDULE, DesktopUiText.HELP_MOMENTS_ENABLE)
    DesktopPrimaryRoute.MANAGE -> DesktopUiText.MANAGE to listOf(DesktopUiText.HELP_MANAGE_LIST, DesktopUiText.HELP_MANAGE_SWITCH,
        DesktopUiText.HELP_MANAGE_AI, DesktopUiText.HELP_MANAGE_ACCOUNTS, DesktopUiText.HELP_MANAGE_WORLD)
    DesktopPrimaryRoute.TOOLS -> DesktopUiText.TOOLS to listOf(DesktopUiText.HELP_TOOLS_LAYOUT,
        DesktopUiText.HELP_TOOLS_DESIGN, DesktopUiText.HELP_TOOLS_HISTORY, DesktopUiText.HELP_TOOLS_IMAGE)
    DesktopPrimaryRoute.DATA -> DesktopUiText.DATA to listOf(DesktopUiText.HELP_DATA_EXPORT, DesktopUiText.HELP_DATA_IMPORT,
        DesktopUiText.HELP_DATA_LOCATION, DesktopUiText.HELP_DATA_UPDATE, DesktopUiText.HELP_DATA_AUTO)
}

@Composable
internal fun DesktopContextHelpPanel(route: DesktopPrimaryRoute, onClose: () -> Unit) {
    val t = LocalDesktopUiStrings.current
    val colors = LocalDesktopPalette.current
    var guide by remember(route) { mutableStateOf(false) }
    val (title, steps) = desktopContextHelp(route)
    DesktopModalSurface {
        Box(Modifier.fillMaxSize().background(colors.dim).padding(16.dp), contentAlignment = Alignment.Center) {
            if (guide) DesktopTutorialGuide(onBack = { guide = false }, onClose = onClose)
            else Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().heightIn(max = 560.dp)
                .background(colors.card, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { DesktopDataSectionHeading("${t(DesktopUiText.CONTEXT_HELP)} · ${t(title)}") }
                    DesktopIconAction(t(DesktopUiText.CLOSE_CONTEXT_HELP), DesktopAppIcons.Close, onClick = onClose)
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    steps.forEachIndexed { index, step -> StatusText("${index + 1}. ${t(step)}") }
                }
                BootstrapButton(t(DesktopUiText.GUIDE), secondary = true) { guide = true }
            }
        }
    }
}
