package com.example.chatbar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun DesktopPrimaryShell(
    chrome: DesktopWindowChrome,
    navigation: DesktopPrimaryNavigationController,
    rootSwitchController: DesktopDataRootSwitchController,
    transferController: DesktopTypedTransferController,
    modelTemplateController: DesktopModelTemplateTransferController,
    managementController: DesktopManagementController,
    promptInspectorController: DesktopPromptInspectorController,
    primaryChatController: DesktopPrimaryChatController,
    modelSettingsController: DesktopModelSettingsController,
    backupSettingsController: DesktopAutomaticBackupSettingsController,
    characterEditorController: DesktopCharacterEditorController,
    formatCardEditorController: DesktopFormatCardEditorController,
    worldBookEditorController: DesktopWorldBookEditorController,
    uiLanguageController: DesktopUiLanguageController,
    appearanceController: DesktopAppearanceController,
    connectionTestController: DesktopConnectionTestController,
    novelAiSettingsController: DesktopNovelAiSettingsController,
    novelAiStudioController: DesktopNovelAiStudioController,
    onExitApplication: () -> Unit,
) {
    val rootState by rootSwitchController.state.collectAsState()
    val selectedRoute by navigation.selectedRoute.collectAsState()
    val locked = rootState !is DesktopDataRootSwitchState.Idle
    val route = navigation.currentRoute(rootState, selectedRoute)
    val colors = DesktopBootstrapColors
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val chromeLayout = remember(chrome) { DesktopChromeLayoutRecorder(chrome) }
    val composerLayout = remember { DesktopComposerLayoutState() }
    var requestedDesignModel by remember { mutableStateOf<String?>(null) }
    var openDesignModels by remember { mutableStateOf(false) }

    fun navigate(destination: DesktopPrimaryRoute) {
        if (route == DesktopPrimaryRoute.MANAGE && destination != DesktopPrimaryRoute.MANAGE &&
            (managementController.state.value.busy || transferController.state.value.busy ||
                modelTemplateController.state.value.busy)) return
        if (route == DesktopPrimaryRoute.CHAT && destination != DesktopPrimaryRoute.CHAT) {
            scope.launch { primaryChatController.requestSessionSettingsLeave {
                navigation.navigate(destination, rootState)
            } }
        } else if (route == DesktopPrimaryRoute.MANAGE && destination != DesktopPrimaryRoute.MANAGE) {
            worldBookEditorController.requestLeave {
                formatCardEditorController.requestLeave {
                    characterEditorController.requestLeave {
                        scope.launch { modelSettingsController.requestLeave {
                            if (!modelTemplateController.state.value.busy)
                                navigation.navigateFromManageWhenIdle(destination, rootState,
                                    managementController.state.value.busy, transferController.state.value.busy)
                        } }
                    }
                }
            }
        } else navigation.navigate(destination, rootState)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).onGloballyPositioned(chromeLayout::root)) {
        val size = DesktopShellLayoutPolicy.sizeForWidth(maxWidth.value)
        Column(Modifier.fillMaxSize()) {
            DesktopTitleBar(size, route, locked, chrome, chromeLayout, onNavigate = ::navigate)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (route) {
                    DesktopPrimaryRoute.CHAT -> DesktopPrimaryChatPanel(primaryChatController, size, composerLayout) { navigate(DesktopPrimaryRoute.TOOLS) }
                    DesktopPrimaryRoute.MANAGE -> DesktopManagePanel(
                        transferController = transferController,
                        modelTemplateController = modelTemplateController,
                        managementController = managementController,
                        modelSettingsController = modelSettingsController,
                        backupSettingsController = backupSettingsController,
                        characterEditorController = characterEditorController,
                        formatCardEditorController = formatCardEditorController,
                        worldBookEditorController = worldBookEditorController,
                        uiLanguageController = uiLanguageController,
                        appearanceController = appearanceController,
                        connectionTestController = connectionTestController,
                        novelAiSettingsController = novelAiSettingsController,
                        initialModels = openDesignModels, initialModelId = requestedDesignModel,
                        onInitialModelsConsumed = { openDesignModels = false; requestedDesignModel = null },
                        onStartCharacterChat = { id -> scope.launch {
                            val before = primaryChatController.state.value.sessions.map { it.id }.toSet()
                            primaryChatController.createSession(id)
                            if (primaryChatController.state.value.selectedSession?.id?.let { it !in before } == true) navigate(DesktopPrimaryRoute.CHAT)
                        } },
                    )
                    DesktopPrimaryRoute.TOOLS -> Column {
                        var studio by remember { mutableStateOf(true) }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            StudioAction("NovelAI Studio", selected = studio) { studio = true }
                            StudioAction("高级 / 诊断", selected = !studio, icon = DesktopAppIcons.Tools) { studio = false }
                        }
                        if (studio) DesktopNovelAiStudioPanel(novelAiStudioController) { id ->
                            requestedDesignModel = id; openDesignModels = true; navigate(DesktopPrimaryRoute.MANAGE)
                        } else DesktopPromptInspectorPanel(promptInspectorController)
                    }
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
