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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun DesktopPrimaryShell(
    navigation: DesktopPrimaryNavigationController,
    rootSwitchController: DesktopDataRootSwitchController,
    transferController: DesktopTypedTransferController,
    promptInspectorController: DesktopPromptInspectorController,
    primaryChatController: DesktopPrimaryChatController,
    modelSettingsController: DesktopModelSettingsController,
    uiLanguageController: DesktopUiLanguageController,
    appearanceController: DesktopAppearanceController,
    formatPresetController: DesktopFormatPresetController,
    onExitApplication: () -> Unit,
) {
    val rootState by rootSwitchController.state.collectAsState()
    val selectedRoute by navigation.selectedRoute.collectAsState()
    val locked = rootState !is DesktopDataRootSwitchState.Idle
    val route = navigation.currentRoute(rootState, selectedRoute)
    val colors = DesktopBootstrapColors
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()

    fun navigate(destination: DesktopPrimaryRoute) {
        if (route == DesktopPrimaryRoute.CHAT && destination != DesktopPrimaryRoute.CHAT) {
            scope.launch { primaryChatController.requestSessionSettingsLeave {
                navigation.navigate(destination, rootState)
            } }
        } else if (route == DesktopPrimaryRoute.MANAGE && destination != DesktopPrimaryRoute.MANAGE) {
            scope.launch { modelSettingsController.requestLeave { navigation.navigate(destination, rootState) } }
        } else navigation.navigate(destination, rootState)
    }

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
                if (locked) StatusText("${t(DesktopUiText.DATA_OPERATION)} · ${rootState.javaClass.simpleName}", colors.warning)
            }

            if (size == DesktopShellSize.COMPACT && !locked) {
                Row(
                    Modifier.fillMaxWidth().background(colors.card).padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DesktopPrimaryRoute.entries.forEach { destination ->
                        RouteControl(destination, route, compact = true) {
                            navigate(destination)
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
                                navigate(destination)
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                    when (route) {
                        DesktopPrimaryRoute.CHAT -> DesktopPrimaryChatPanel(primaryChatController, size)
                        DesktopPrimaryRoute.MANAGE -> DesktopManagePanel(
                            transferController = transferController,
                            modelSettingsController = modelSettingsController,
                            uiLanguageController = uiLanguageController,
                            appearanceController = appearanceController,
                            formatPresetController = formatPresetController,
                        )
                        DesktopPrimaryRoute.TOOLS -> DesktopPromptInspectorPanel(promptInspectorController)
                        DesktopPrimaryRoute.DATA -> ShellScrollPanel {
                            ShellHeading(t(DesktopUiText.DATA_DIRECTORY))
                            DesktopDataRootPanel(rootSwitchController, onExitApplication)
                        }
                    }
                }

                if (size == DesktopShellSize.WIDE && !locked && route != DesktopPrimaryRoute.CHAT) {
                    Column(
                        Modifier.width(206.dp).fillMaxHeight().background(colors.card)
                            .border(1.dp, colors.border).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ShellHeading(t(DesktopUiText.WORKSPACE))
                        StatusText(when (route) {
                            DesktopPrimaryRoute.CHAT -> t(DesktopUiText.CHAT_WORKSPACE_HINT)
                            DesktopPrimaryRoute.MANAGE -> t(DesktopUiText.MANAGE_WORKSPACE_HINT)
                            DesktopPrimaryRoute.TOOLS -> t(DesktopUiText.TOOLS_WORKSPACE_HINT)
                            DesktopPrimaryRoute.DATA -> t(DesktopUiText.DATA_WORKSPACE_HINT)
                        })
                        StatusText(t(DesktopUiText.WORKSPACE_HINT))
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
    val t = LocalDesktopUiStrings.current
    Box(
        modifier = Modifier
            .background(if (active) colors.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 12.dp),
    ) {
        BasicText(
            t(when (destination) {
                DesktopPrimaryRoute.CHAT -> DesktopUiText.CHAT
                DesktopPrimaryRoute.MANAGE -> DesktopUiText.MANAGE
                DesktopPrimaryRoute.TOOLS -> DesktopUiText.TOOLS
                DesktopPrimaryRoute.DATA -> DesktopUiText.DATA
            }),
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
