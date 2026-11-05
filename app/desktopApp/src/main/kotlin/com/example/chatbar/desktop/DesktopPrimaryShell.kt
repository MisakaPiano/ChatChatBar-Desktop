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
    networkProxyController: DesktopNetworkProxyController? = null,
    momentsController: DesktopMomentsController? = null,
    providerUsage: DesktopProviderUsageOwner? = null,
    externalImportController: DesktopExternalImportController? = null,
    taskCenter: DesktopTaskCenter? = null,
    taskTray: DesktopTrayController? = null,
    onHideToTray: (() -> Unit)? = null,
    guideReminder: DesktopGuideReminderController? = null,
    communityController: DesktopCommunityController? = null,
) {
    val guideNotice = guideReminder?.state?.collectAsState()?.value
    LaunchedEffect(guideReminder) { guideReminder?.load() }
    val moments = momentsController?.state?.collectAsState()?.value
    val community = communityController?.state?.collectAsState()?.value
    LaunchedEffect(communityController) { communityController?.start() }
    val rootState by rootSwitchController.state.collectAsState()
    val selectedRoute by navigation.selectedRoute.collectAsState()
    val fullBackup by fullBackupController.state.collectAsState()
    val mergeState = mergeImportController?.state?.collectAsState()?.value
    val mergeLocked = mergeState?.let { it.busy || it.restartRequired } == true
    val backupLocked = fullBackup.busy || fullBackup.restartRequired || mergeLocked
    val locked = rootState !is DesktopDataRootSwitchState.Idle || backupLocked
    val centerLocked = rootState !is DesktopDataRootSwitchState.Idle || fullBackup.restartRequired || mergeState?.restartRequired == true
    val dataBusy = fullBackup.busy || mergeState?.busy == true
    val requestedRoute = if (backupLocked) DesktopPrimaryRoute.DATA else navigation.currentRoute(rootState, selectedRoute)
    val route = if ((requestedRoute == DesktopPrimaryRoute.MOMENTS && moments?.enabled != true) ||
        (requestedRoute == DesktopPrimaryRoute.COMMUNITY && community?.enabled != true)) DesktopPrimaryRoute.CHAT else requestedRoute
    LaunchedEffect(requestedRoute, community?.enabled) {
        if (requestedRoute == DesktopPrimaryRoute.COMMUNITY && community?.enabled != true && !locked)
            navigation.navigate(DesktopPrimaryRoute.CHAT, rootState)
    }
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
    var openImportRequest by remember { mutableStateOf(0) }
    var helpRoute by remember { mutableStateOf<DesktopPrimaryRoute?>(null) }
    var helpStartsInGuide by remember { mutableStateOf(false) }
    LaunchedEffect(locked) { if (locked) helpRoute = null }
    var externalManageKind by remember { mutableStateOf<DesktopExternalImportKind?>(null) }
    var externalManageTargetId by remember { mutableStateOf<String?>(null) }
    var taskManageTarget by remember { mutableStateOf<DesktopWorkTarget?>(null) }
    var taskDataDetail by remember { mutableStateOf<DesktopWorkDetail?>(null) }
    val colors = DesktopBootstrapColors
    val t = LocalDesktopUiStrings.current
    val scope = rememberCoroutineScope()
    val chromeLayout = remember(chrome) { DesktopChromeLayoutRecorder(chrome) }
    val composerLayout = remember { DesktopComposerLayoutState() }
    val compactChatNavigation = remember { DesktopCompactChatNavigation() }
    val floatingVoicePosition = remember { DesktopFloatingVoicePosition() }
    var requestedDesignModel by remember { mutableStateOf<String?>(null) }
    var openDesignModels by remember { mutableStateOf(false) }
    var requestedMomentCard by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var requestedPerson by remember { mutableStateOf<Pair<String, String>?>(null) }

    val centerState = taskCenter?.state?.collectAsState()?.value
    val centerRequest = taskCenter?.openRequests?.collectAsState()?.value ?: 0L
    var centerOpen by remember { mutableStateOf(false) }
    var consumedCenterRequest by remember { mutableStateOf(0L) }
    LaunchedEffect(centerRequest, centerLocked, dataBusy, modalActivity?.count) {
        if (centerRequest > consumedCenterRequest && centerOpen) consumedCenterRequest = centerRequest
        else if (centerRequest > consumedCenterRequest && !centerLocked &&
            (modalActivity?.otherCount ?: 0) == 0 && ((modalActivity?.backupCount ?: 0) == 0 || dataBusy)) {
            centerOpen = true; consumedCenterRequest = centerRequest
        }
    }

    fun navigateAfter(destination: DesktopPrimaryRoute, after: () -> Unit) {
        if (backupLocked) return
        if (route == DesktopPrimaryRoute.MANAGE && destination != DesktopPrimaryRoute.MANAGE &&
            (managementController.state.value.busy || transferController.state.value.busy ||
                modelTemplateController.state.value.busy)) return
        if (destination == DesktopPrimaryRoute.DATA && updateNotice?.pendingVersion != null) {
            updateNotices?.dismissStartupNotice()
            openUpdateRequest++
        }
        if (route == DesktopPrimaryRoute.CHAT && destination != DesktopPrimaryRoute.CHAT) {
            scope.launch { primaryChatController.requestSessionSettingsLeave {
                if (navigation.navigate(destination, rootState)) after()
            } }
        } else if (route == DesktopPrimaryRoute.MANAGE && destination != DesktopPrimaryRoute.MANAGE) {
            worldBookEditorController.requestLeave {
                formatCardEditorController.requestLeave {
                    characterEditorController.requestLeave {
                        scope.launch { modelSettingsController.requestLeave {
                            if (!modelTemplateController.state.value.busy)
                                if (navigation.navigateFromManageWhenIdle(destination, rootState,
                                    managementController.state.value.busy, transferController.state.value.busy)) after()
                        } }
                    }
                }
            }
        } else if (navigation.navigate(destination, rootState)) after()
    }
    fun navigate(destination: DesktopPrimaryRoute) = navigateAfter(destination) {}

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).onGloballyPositioned(chromeLayout::root)) {
        val size = DesktopShellLayoutPolicy.sizeForWidth(maxWidth.value)
        Column(Modifier.fillMaxSize()) {
            DesktopTitleBar(size, route, locked, chrome, chromeLayout,
                updatePending = updateNotice?.pendingVersion != null, onNavigate = ::navigate,
                momentsEnabled = moments?.enabled == true, momentsUnread = moments?.unread == true,
                communityEnabled = community?.enabled == true,
                activeTaskCount = centerState?.rows?.count { it.active } ?: 0, onOpenTasks = taskCenter?.let { it::requestOpen },
                helpEnabled = (modalActivity?.count ?: 0) == 0,
                onOpenHelp = { if (!locked && (modalActivity?.count ?: 0) == 0) { helpStartsInGuide = false; helpRoute = route } })
            externalImportController?.let { DesktopExternalImportNotice(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (route) {
                    DesktopPrimaryRoute.MOMENTS -> momentsController?.let { controller ->
                        DesktopMomentsPanel(controller,
                            onOpenSession = { id -> scope.launch {
                                primaryChatController.requestSessionSettingsLeave {
                                    scope.launch {
                                        primaryChatController.selectSession(id)
                                        if (primaryChatController.state.value.selectedSession?.id == id) navigate(DesktopPrimaryRoute.CHAT)
                                        else controller.navigationUnavailable()
                                    }
                                }
                            } },
                            onOpenCard = { id, focus -> navigateAfter(DesktopPrimaryRoute.MANAGE) {
                                openDesignModels = false; requestedDesignModel = null
                                requestedMomentCard = id to focus
                            } })
                    }
                    DesktopPrimaryRoute.COMMUNITY -> communityController?.let { DesktopCommunityPanel(it, managementController) }
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
                        initialMomentCard = requestedMomentCard, onInitialMomentCardConsumed = { requestedMomentCard = null },
                        initialImportKind = externalManageKind, initialImportTargetId = externalManageTargetId,
                        onInitialImportConsumed = { externalManageKind = null; externalManageTargetId = null },
                        initialTaskTarget = taskManageTarget, onInitialTaskConsumed = { taskManageTarget = null },
                        onTaskTargetUnavailable = { taskCenter?.targetUnavailable() },
                        chatUiController = primaryChatController.chatUi,
                        networkProxyController = networkProxyController,
                        momentsController = momentsController,
                        providerUsage = providerUsage,
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
                                onDataSettings = { navigate(DesktopPrimaryRoute.DATA) }) { id ->
                                requestedDesignModel = id; openDesignModels = true; navigate(DesktopPrimaryRoute.MANAGE)
                            }
                        }
                    }
                    DesktopPrimaryRoute.DATA -> DesktopDataWorkspace(rootSwitchController, fullBackupController,
                        mergeImportController, backupSettingsController, onExitApplication, openUpdateRequest,
                        onUpdateRequestConsumed = { openUpdateRequest = 0 }, openImportRequest = openImportRequest,
                        onImportRequestConsumed = { openImportRequest = 0 }, taskDetail = taskDataDetail,
                        onTaskDetailConsumed = { taskDataDetail = null },
                        diagnostics = { DesktopGlobalDiagnostics(primaryChatController.taskRuntime.diagnostics, promptInspectorController) })
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
        helpRoute?.let { DesktopContextHelpPanel(it, startInGuide = helpStartsInGuide) { helpRoute = null } }
        if (centerOpen && helpRoute == null && !centerLocked && (modalActivity?.otherCount ?: 0) == 0 && taskCenter != null) DesktopTaskCenterPanel(taskCenter,
            onClose = { centerOpen = false }, onHideToTray = onHideToTray, tray = taskTray,
            settingsEnabled = !backupLocked, onView = { target ->
                if (target.route == DesktopPrimaryRoute.MOMENTS && momentsController?.state?.value?.enabled != true) {
                    taskCenter.targetUnavailable()
                } else if (backupLocked || (route == DesktopPrimaryRoute.MANAGE && target.route != DesktopPrimaryRoute.MANAGE &&
                    (managementController.state.value.busy || transferController.state.value.busy || modelTemplateController.state.value.busy))) {
                    taskCenter.navigationBusy()
                } else {
                centerOpen = false
                navigateAfter(target.route) {
                    if (target.route == DesktopPrimaryRoute.MANAGE) taskManageTarget = target
                    if (target.route == DesktopPrimaryRoute.DATA) taskDataDetail = target.detail
                    if (target.route == DesktopPrimaryRoute.CHAT && target.sessionId != null)
                        scope.launch { primaryChatController.requestSessionSettingsLeave {
                            primaryChatController.selectSession(target.sessionId)
                            if (primaryChatController.state.value.selectedSession?.id != target.sessionId) taskCenter.targetUnavailable()
                            else if (target.detail == DesktopWorkDetail.SAVE_SLOT) {
                                if (primaryChatController.saveSlots?.revealTaskPanel(target.sessionId) != true) taskCenter.targetUnavailable()
                            }
                            else if (target.detail == DesktopWorkDetail.MEMORY) primaryChatController.memory?.open(target.sessionId)
                            else if (target.subjectId != null && !primaryChatController.revealMessage(target.sessionId, target.subjectId))
                                taskCenter.targetUnavailable()
                        } }
                }
                }
            })
        externalImportController?.let { external -> DesktopExternalImportHost(external, chat.sessions,
            onView = { kind -> when (kind) {
                DesktopExternalImportKind.CHARACTER, DesktopExternalImportKind.FORMAT, DesktopExternalImportKind.WORLD_BOOK,
                DesktopExternalImportKind.MODEL_TEMPLATE -> {
                    externalManageKind = kind; externalManageTargetId = external.state.value.targetId
                    navigate(DesktopPrimaryRoute.MANAGE)
                }
                DesktopExternalImportKind.IMAGE -> navigate(DesktopPrimaryRoute.TOOLS)
                DesktopExternalImportKind.BACKUP -> { openImportRequest++; navigate(DesktopPrimaryRoute.DATA) }
                else -> navigate(DesktopPrimaryRoute.CHAT)
            } }, onMerge = { openImportRequest++; navigate(DesktopPrimaryRoute.DATA) }) }
        val startupBlocked = locked || helpRoute != null || (modalActivity?.count ?: 0) > 0 || management.busy || transfer.busy || templates.busy || chat.sessionSettingsLeavePrompt ||
            tasks.any { it.status == DesktopTaskStatus.RUNNING } || repairs.values.any { it == DesktopRepairStatus.RUNNING }
        if (updateNotices != null && updateNotice != null) DesktopStartupUpdateNotice(updateNotice,
            blocked = startupBlocked,
            onView = { updateNotices.dismissStartupNotice(); navigate(DesktopPrimaryRoute.DATA) },
            onLater = updateNotices::dismissStartupNotice)
        if (guideReminder != null && guideNotice != null) DesktopGuideReminder(guideNotice,
            blocked = startupBlocked || centerState?.rows?.any { it.active } == true ||
                externalImportController?.state?.value?.let { it.item != null && it.shown } == true ||
                updateNotice?.let { desktopShowStartupUpdateNotice(it, startupBlocked) } == true,
            onView = {
                if (!guideReminder.state.value.busy && guideReminder.state.value.pending &&
                    rootSwitchController.state.value is DesktopDataRootSwitchState.Idle &&
                    !fullBackupController.state.value.busy && !fullBackupController.state.value.restartRequired &&
                    mergeImportController?.state?.value?.let { it.busy || it.restartRequired } != true &&
                    primaryChatController.taskRuntime.tasks.value.none { it.status == DesktopTaskStatus.RUNNING } &&
                    primaryChatController.taskRuntime.repairTasks.value.values.none { it == DesktopRepairStatus.RUNNING } &&
                    taskCenter?.state?.value?.rows?.any { it.active } != true &&
                    !managementController.state.value.busy && !transferController.state.value.busy && !modelTemplateController.state.value.busy &&
                    !primaryChatController.state.value.sessionSettingsLeavePrompt && (modalActivity?.count ?: 0) == 0) {
                    guideReminder.deferForRun(); helpStartsInGuide = true; helpRoute = route
                }
            }, onLater = guideReminder::deferForRun,
            onNever = { scope.launch { guideReminder.suppressFutureReminder() } },
            onRetry = { scope.launch { guideReminder.load() } })
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
