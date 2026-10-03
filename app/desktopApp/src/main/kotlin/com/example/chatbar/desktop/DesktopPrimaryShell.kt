package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
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
    managementController: DesktopManagementController,
    promptInspectorController: DesktopPromptInspectorController,
    primaryChatController: DesktopPrimaryChatController,
    modelSettingsController: DesktopModelSettingsController,
    characterEditorController: DesktopCharacterEditorController,
    formatCardEditorController: DesktopFormatCardEditorController,
    worldBookEditorController: DesktopWorldBookEditorController,
    uiLanguageController: DesktopUiLanguageController,
    appearanceController: DesktopAppearanceController,
    formatPresetController: DesktopFormatPresetController,
    connectionTestController: DesktopConnectionTestController,
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
        if (route == DesktopPrimaryRoute.MANAGE && destination != DesktopPrimaryRoute.MANAGE &&
            (managementController.state.value.busy || transferController.state.value.busy)) return
        if (route == DesktopPrimaryRoute.CHAT && destination != DesktopPrimaryRoute.CHAT) {
            scope.launch { primaryChatController.requestSessionSettingsLeave {
                navigation.navigate(destination, rootState)
            } }
        } else if (route == DesktopPrimaryRoute.MANAGE && destination != DesktopPrimaryRoute.MANAGE) {
            worldBookEditorController.requestLeave {
                formatCardEditorController.requestLeave {
                    characterEditorController.requestLeave {
                        scope.launch { modelSettingsController.requestLeave {
                            navigation.navigateFromManageWhenIdle(destination, rootState,
                                managementController.state.value.busy, transferController.state.value.busy)
                        } }
                    }
                }
            }
        } else navigation.navigate(destination, rootState)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val size = DesktopShellLayoutPolicy.sizeForWidth(maxWidth.value)
        val presentation = desktopShellPresentation(size, route)
        Column(Modifier.fillMaxSize()) {
            if (presentation.placement == DesktopNavigationPlacement.TOP && !locked) {
                Row(
                    Modifier.fillMaxWidth().background(colors.card).padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Image(painterResource(DesktopBrandResources.LOGO_RESOURCE), null, Modifier.size(28.dp))
                    presentation.routes.forEach { (destination, _) ->
                        RouteControl(destination, route, compact = true) {
                            navigate(destination)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxSize()) {
                if (presentation.placement == DesktopNavigationPlacement.RAIL && !locked) {
                    Column(
                        Modifier.width(presentation.railWidthDp.dp)
                            .fillMaxHeight().background(colors.card)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Image(painterResource(DesktopBrandResources.LOGO_RESOURCE), null, Modifier.size(32.dp))
                            if (size == DesktopShellSize.WIDE) StatusText("ChatChatBar")
                        }
                        presentation.routes.forEach { (destination, _) ->
                            RouteControl(destination, route, compact = size != DesktopShellSize.WIDE) {
                                navigate(destination)
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (route) {
                        DesktopPrimaryRoute.CHAT -> DesktopPrimaryChatPanel(primaryChatController, size)
                        DesktopPrimaryRoute.MANAGE -> DesktopManagePanel(
                            transferController = transferController,
                            managementController = managementController,
                            modelSettingsController = modelSettingsController,
                            characterEditorController = characterEditorController,
                            formatCardEditorController = formatCardEditorController,
                            worldBookEditorController = worldBookEditorController,
                            uiLanguageController = uiLanguageController,
                            appearanceController = appearanceController,
                            formatPresetController = formatPresetController,
                            connectionTestController = connectionTestController,
                        )
                        DesktopPrimaryRoute.TOOLS -> DesktopPromptInspectorPanel(promptInspectorController)
                        DesktopPrimaryRoute.DATA -> ShellScrollPanel {
                            if (locked) StatusText(t(DesktopUiText.DATA_OPERATION), colors.warning)
                            ShellHeading(t(DesktopUiText.DATA_DIRECTORY))
                            DesktopDataRootPanel(rootSwitchController, onExitApplication)
                        }
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
    val icon = when (destination) {
        DesktopPrimaryRoute.CHAT -> DesktopAppIcons.Chat
        DesktopPrimaryRoute.MANAGE -> DesktopAppIcons.Settings
        DesktopPrimaryRoute.TOOLS -> DesktopAppIcons.Tools
        DesktopPrimaryRoute.DATA -> DesktopAppIcons.Data
    }
    Column(
        modifier = Modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .heightIn(min = 48.dp)
            .background(if (active) colors.secondary else Color.Transparent, RoundedCornerShape(8.dp))
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(rememberVectorPainter(icon), null, Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(if (active) colors.primary else colors.mutedForeground))
        BasicText(
            t(when (destination) {
                DesktopPrimaryRoute.CHAT -> DesktopUiText.CHAT
                DesktopPrimaryRoute.MANAGE -> DesktopUiText.MANAGE
                DesktopPrimaryRoute.TOOLS -> DesktopUiText.TOOLS
                DesktopPrimaryRoute.DATA -> DesktopUiText.DATA
            }),
            style = TextStyle(
                color = if (active) colors.primary else colors.foreground,
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
            .background(DesktopBootstrapColors.background)
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
