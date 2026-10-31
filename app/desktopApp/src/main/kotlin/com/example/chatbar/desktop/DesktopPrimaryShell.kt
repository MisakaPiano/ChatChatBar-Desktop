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
import androidx.compose.ui.Alignment
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
    fullBackupController: DesktopFullBackupController,
    onExitApplication: () -> Unit,
    mergeImportController: DesktopMergeImportController? = null,
) {
    val rootState by rootSwitchController.state.collectAsState()
    val selectedRoute by navigation.selectedRoute.collectAsState()
    val fullBackup by fullBackupController.state.collectAsState()
    val mergeState = mergeImportController?.state?.collectAsState()?.value
    val mergeLocked = mergeState?.let { it.busy || it.restartRequired } == true
    val backupLocked = fullBackup.busy || fullBackup.restartRequired || mergeLocked
    val locked = rootState !is DesktopDataRootSwitchState.Idle || backupLocked
    val route = if (backupLocked) DesktopPrimaryRoute.DATA else navigation.currentRoute(rootState, selectedRoute)
    val updateNotices = LocalDesktopUpdateNotices.current
    val updateNotice = updateNotices?.state?.collectAsState()?.value
    val modalActivity = LocalDesktopModalActivity.current
    val tasks by primaryChatController.taskRuntime.tasks.collectAsState()
    val repairs by primaryChatController.taskRuntime.repairTasks.collectAsState()
    val management by managementController.state.collectAsState()
    val transfer by transferController.state.collectAsState()
    val templates by modelTemplateController.state.collectAsState()
    val chat by primaryChatController.state.collectAsState()
    var openUpdateRequest by remember { mutableStateOf(0) }
    val colors = DesktopBootstrapColors
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val chromeLayout = remember(chrome) { DesktopChromeLayoutRecorder(chrome) }
    val composerLayout = remember { DesktopComposerLayoutState() }
    val compactChatNavigation = remember { DesktopCompactChatNavigation() }
    val floatingVoicePosition = remember { DesktopFloatingVoicePosition() }
    var requestedDesignModel by remember { mutableStateOf<String?>(null) }
    var openDesignModels by remember { mutableStateOf(false) }
    var requestedPerson by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun navigate(destination: DesktopPrimaryRoute) {
        if (backupLocked) return
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
            DesktopTitleBar(size, route, locked, chrome, chromeLayout,
                updatePending = updateNotice?.pendingVersion != null, onNavigate = { destination ->
                    if (destination == DesktopPrimaryRoute.DATA && updateNotice?.pendingVersion != null) {
                        updateNotices?.dismissStartupNotice(); openUpdateRequest++
                    }
                    navigate(destination)
                })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (route) {
                    DesktopPrimaryRoute.CHAT -> DesktopPrimaryChatPanel(primaryChatController, size, composerLayout,
                        onOpenStudio = { navigate(DesktopPrimaryRoute.TOOLS) }, compactNavigation = compactChatNavigation,
                        onEditPerson = { card, person -> scope.launch {
                            primaryChatController.requestSessionSettingsLeave {
                                navigation.navigate(DesktopPrimaryRoute.MANAGE, rootState)
                                openDesignModels = false; requestedDesignModel = null
                                requestedPerson = card to person
                            }
                        }; Unit })
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
                        initialPerson = requestedPerson, onInitialPersonConsumed = { requestedPerson = null },
                        chatUiController = primaryChatController.chatUi,
                        onStartCharacterChat = { id -> scope.launch {
                            val before = primaryChatController.state.value.sessions.map { it.id }.toSet()
                            primaryChatController.createSession(id)
                            val selected = primaryChatController.state.value.selectedSession?.id
                            if (selected != null && selected !in before &&
                                compactChatNavigation.onSessionEntered(selected, primaryChatController.state.value))
                                navigate(DesktopPrimaryRoute.CHAT)
                        } },
                    )
                    DesktopPrimaryRoute.TOOLS -> {
                        val studioState by novelAiStudioController.state.collectAsState()
                        DesktopStudioContentFrame(studioState.status) {
                            DesktopNovelAiStudioPanel(novelAiStudioController,
                                diagnostics = { DesktopPromptInspectorPanel(promptInspectorController) }) { id ->
                                requestedDesignModel = id; openDesignModels = true; navigate(DesktopPrimaryRoute.MANAGE)
                            }
                        }
                    }
                    DesktopPrimaryRoute.DATA -> DesktopDataWorkspace(rootSwitchController, fullBackupController,
                        mergeImportController, backupSettingsController, onExitApplication, openUpdateRequest,
                        onUpdateRequestConsumed = { openUpdateRequest = 0 })
                }
                primaryChatController.voices?.let { runtime ->
                    val player by runtime.playback.state.collectAsState()
                    val chat by primaryChatController.state.collectAsState()
                    if (route != DesktopPrimaryRoute.CHAT || chat.selectedSession?.id != player.sessionId) {
                        DesktopFloatingVoicePlayerHost(runtime, primaryChatController.chatUi, floatingVoicePosition)
                    }
                }
            }
        }
        if (updateNotices != null && updateNotice != null) DesktopStartupUpdateNotice(updateNotice,
            blocked = locked || (modalActivity?.count ?: 0) > 0 || management.busy || transfer.busy || templates.busy || chat.sessionSettingsLeavePrompt ||
                tasks.any { it.status == DesktopTaskStatus.RUNNING } || repairs.values.any { it == DesktopRepairStatus.RUNNING },
            onView = { updateNotices.dismissStartupNotice(); openUpdateRequest++; navigate(DesktopPrimaryRoute.DATA) },
            onLater = updateNotices::dismissStartupNotice)
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
