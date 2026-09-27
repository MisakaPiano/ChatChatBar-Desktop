package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DesktopPrimaryShell(
    navigation: DesktopPrimaryNavigationController,
    rootSwitchController: DesktopDataRootSwitchController,
    transferController: DesktopTypedTransferController,
    promptInspectorController: DesktopPromptInspectorController,
    alphaChatController: DesktopAlphaChatController,
    modelSettingsController: DesktopModelSettingsController,
    onExitApplication: () -> Unit,
) {
    val rootState by rootSwitchController.state.collectAsState()
    val selectedRoute by navigation.selectedRoute.collectAsState()
    val locked = rootState !is DesktopDataRootSwitchState.Idle
    val route = navigation.currentRoute(rootState, selectedRoute)
    val colors = DesktopBootstrapColors

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val size = DesktopShellLayoutPolicy.sizeForWidth(maxWidth.value)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(58.dp).background(colors.card)
                    .border(1.dp, colors.border).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    "ChatChatBar Desktop",
                    style = TextStyle(color = colors.foreground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                )
                if (locked) StatusText("Data operation · ${rootState.javaClass.simpleName}", colors.warning)
            }

            if (size == DesktopShellSize.COMPACT && !locked) {
                Row(
                    Modifier.fillMaxWidth().background(colors.card).padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DesktopPrimaryRoute.entries.forEach { destination ->
                        RouteControl(destination, route, compact = true) {
                            navigation.navigate(destination, rootState)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxSize()) {
                if (size != DesktopShellSize.COMPACT && !locked) {
                    Column(
                        Modifier.width(if (size == DesktopShellSize.WIDE) 176.dp else 154.dp)
                            .fillMaxHeight().background(colors.card)
                            .border(1.dp, colors.border).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DesktopPrimaryRoute.entries.forEach { destination ->
                            RouteControl(destination, route, compact = false) {
                                navigation.navigate(destination, rootState)
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                    when (route) {
                        DesktopPrimaryRoute.CHAT -> DesktopAlphaChatPanel(alphaChatController)
                        DesktopPrimaryRoute.MANAGE -> DesktopManagePanel(
                            transferController = transferController,
                            modelSettingsController = modelSettingsController,
                        )
                        DesktopPrimaryRoute.TOOLS -> DesktopPromptInspectorPanel(promptInspectorController)
                        DesktopPrimaryRoute.DATA -> ShellScrollPanel {
                            ShellHeading("Data directory")
                            DesktopDataRootPanel(rootSwitchController, onExitApplication)
                        }
                    }
                }

                if (size == DesktopShellSize.WIDE && !locked) {
                    Column(
                        Modifier.width(206.dp).fillMaxHeight().background(colors.card)
                            .border(1.dp, colors.border).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ShellHeading("Workspace")
                        StatusText(when (route) {
                            DesktopPrimaryRoute.CHAT -> "Chat · sessions, messages and task diagnostics"
                            DesktopPrimaryRoute.MANAGE -> "Manage · typed import and export"
                            DesktopPrimaryRoute.TOOLS -> "Tools · logical Prompt Inspector"
                            DesktopPrimaryRoute.DATA -> "Data · root authority and migration"
                        })
                        StatusText("This pane is informational. Current work stays in the main surface.")
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteControl(
    destination: DesktopPrimaryRoute,
    selected: DesktopPrimaryRoute,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val active = destination == selected
    val colors = DesktopBootstrapColors
    Box(
        modifier = Modifier
            .background(if (active) colors.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 12.dp),
    ) {
        BasicText(
            destination.name.lowercase().replaceFirstChar(Char::uppercase),
            style = TextStyle(
                color = if (active) colors.primaryForeground else colors.foreground,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun ShellScrollPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .border(1.dp, DesktopBootstrapColors.border, RoundedCornerShape(14.dp))
            .background(DesktopBootstrapColors.card, RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = { content() },
    )
}

@Composable
private fun ShellHeading(text: String) {
    BasicText(
        text,
        style = TextStyle(color = DesktopBootstrapColors.foreground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    )
}
