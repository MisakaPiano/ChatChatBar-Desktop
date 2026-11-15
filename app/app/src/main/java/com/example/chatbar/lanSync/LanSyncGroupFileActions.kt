package com.example.chatbar.lanSync

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.chatbar.domain.lanSync.*
import com.example.chatbar.ui.components.CreateOpenableDocument
import com.example.chatbar.ui.kit.*
import java.io.IOException
import java.text.NumberFormat

/** SAF locations remain temporary UI values; owners alone authenticate, prepare and write bytes. */
@Composable
internal fun LanSyncGroupFileActions(coordinator: LanSyncGroupCoordinator, state: LanSyncGroupPanelState) {
    val recovery = coordinator.fileRecovery
    val exporter = coordinator.fileExport
    val recoveryState = recovery?.state?.collectAsState()?.value ?: LanSyncGroupFileRecoveryState()
    val exportState = exporter?.state?.collectAsState()?.value ?: LanSyncGroupEncryptedFileExportState()
    val cloudBusy = coordinator.cloudRecovery?.state?.collectAsState()?.value?.busy == true
    val context = LocalContext.current
    val resolver = context.applicationContext.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    // These must remain outside visible-modal branches: SAF normally stops the Activity.
    var selectedFile by remember(coordinator) { mutableStateOf<Uri?>(null) }
    var inputPickerPending by remember(coordinator) { mutableStateOf(false) }
    var outputBinding by remember(coordinator) { mutableStateOf<String?>(null) }
    var outputFile by remember(coordinator) { mutableStateOf<Uri?>(null) }
    var pickerNotice by remember(coordinator) { mutableStateOf<String?>(null) }
    var code by remember(coordinator, state.privacyRevision) { mutableStateOf("") }
    var secretRevision by remember(coordinator) { mutableStateOf(0) }
    var confirmed by remember(recoveryState.review?.binding, state.privacyRevision) { mutableStateOf(false) }
    fun clearSecrets() { code = ""; confirmed = false; secretRevision++ }
    val clearCurrentSecrets by rememberUpdatedState(::clearSecrets)
    val canRecover = state.stage == LanSyncGroupPanelStage.EMPTY || state.stage == LanSyncGroupPanelStage.JOINING || state.canResumeExternalRecovery

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (inputPickerPending) {
            inputPickerPending = false; clearSecrets()
            if (canRecover) {
                if (uri != null) selectedFile = uri
                pickerNotice = if (uri == null) "未选择文件" else null
                coordinator.openFileRecovery()
            } else pickerNotice = "此设备已加入设备组，未开始文件恢复"
        }
    }
    val saveFile = rememberLauncherForActivityResult(CreateOpenableDocument("application/octet-stream")) { uri ->
        if (outputBinding != null) {
            if (uri == null) {
                outputBinding = null; outputFile = null; pickerNotice = "未选择保存位置"
                coordinator.openFileExport()
            } else outputFile = uri
        }
    }

    DisposableEffect(lifecycle, recovery, exporter) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                Lifecycle.Event.ON_STOP -> {
                    clearCurrentSecrets(); recovery?.hide(); exporter?.hide()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(coordinator) {
        onDispose {
            clearCurrentSecrets(); selectedFile = null; inputPickerPending = false; outputBinding = null; outputFile = null
            recovery?.hide(); exporter?.hide()
        }
    }
    LaunchedEffect(recoveryState.visible, recoveryState.review?.binding) { clearSecrets() }
    LaunchedEffect(outputFile, outputBinding, resumed, exporter) {
        val uri = outputFile ?: return@LaunchedEffect
        val binding = outputBinding ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        outputFile = null; outputBinding = null
        val owner = exporter
        if (state.stage != LanSyncGroupPanelStage.READY || owner == null || owner.state.value.preparedBinding != binding || owner.state.value.busy) {
            pickerNotice = "这次文件选择已失效，请重新准备或选择保存位置"
            return@LaunchedEffect
        }
        pickerNotice = null; coordinator.openFileExport()
        owner.write(binding, writer = { streamer ->
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("SYNC_GROUP_FILE_OUTPUT_UNAVAILABLE")
            output.use { streamer(it) }
        }, readback = {
            resolver.openInputStream(uri) ?: throw IOException("SYNC_GROUP_FILE_READBACK_UNAVAILABLE")
        })
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (canRecover) CbButton(if (state.stage == LanSyncGroupPanelStage.READY) "接续加密文件恢复" else "从加密文件恢复设备组", {
            pickerNotice = null; clearSecrets(); coordinator.openFileRecovery()
        }, enabled = recovery != null && !state.busy && !cloudBusy && !exportState.busy && !inputPickerPending,
            variant = ButtonVariant.Secondary)
        if (state.stage == LanSyncGroupPanelStage.READY) CbButton("导出加密同步文件", {
            pickerNotice = null; coordinator.openFileExport()
        }, enabled = exporter != null && !state.busy && !cloudBusy && !recoveryState.busy && outputBinding == null,
            variant = ButtonVariant.Secondary)
        pickerNotice?.let { CbText(it, color = ChatBarTheme.colors.mutedForeground) }
    }

    if (recoveryState.visible && recovery != null) {
        DisposableEffect(context) {
            val window = fileActionsActivity(context)?.window
            val alreadySecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        fun closeRecovery() {
            clearSecrets(); selectedFile = null; inputPickerPending = false; pickerNotice = null
            recovery.lock(); coordinator.open()
        }
        CbDialog(onDismissRequest = ::closeRecovery, title = "从加密文件恢复设备组", dismissOnClickOutside = false,
            confirm = { CbButton(if (recoveryState.busy) "取消并关闭" else "关闭", ::closeRecovery, variant = ButtonVariant.Secondary) }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CbText("原设备不在线时，可用加密同步文件和对应的恢复码恢复原设备组，并将此设备登记为同组的新日常设备。")
                CbText("只能恢复文件中实际可取得并已核验的历史，不能保证是原设备组的最新状态。读取和核对阶段不会签署新的组管理操作。", color = ChatBarTheme.colors.warning)
                CbText(recoveryState.status)
                recoveryState.error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
                pickerNotice?.let { CbText(it, color = ChatBarTheme.colors.mutedForeground) }
                if (recoveryState.pending) CbText("仍有资料待核对或审阅，请按同步页的实际状态继续处理。", color = ChatBarTheme.colors.warning)
                val review = recoveryState.review
                if (review != null && !recoveryState.complete) {
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CbText("原设备组：${review.group}")
                        CbText("原管理身份：${review.rootFingerprint}")
                        CbText("已核对的组检查点：${review.checkpoint}")
                        CbText("已核对的设备成员数：${review.memberCount}")
                        CbText("新日常设备：${review.name}")
                        CbText("新设备身份：${review.device}")
                    } }
                    CbText("确认后会在原设备组中登记此设备并恢复资料。冲突、删除和缺失依赖仍按实际状态处理；文件核验不代表网盘或其他设备已收到资料。")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CbCheckbox(confirmed, { confirmed = it }, enabled = !recoveryState.busy && canRecover,
                            contentDescription = "已核对原设备组与新设备身份，并同意登记此设备")
                        CbText("我已核对以上身份，并同意登记此设备", modifier = Modifier.weight(1f))
                    }
                    CbButton("登记此设备并恢复资料", {
                        confirmed = false; recovery.confirm(review.binding)
                    }, enabled = confirmed && !recoveryState.busy && canRecover)
                } else if (!recoveryState.complete) {
                    CbText(if (selectedFile == null) "尚未选择加密同步文件" else "已选择加密同步文件；返回应用后请重新输入恢复码")
                    CbButton("选择加密同步文件", {
                        clearSecrets(); pickerNotice = null; inputPickerPending = true; recovery.hide()
                        try { openFile.launch(arrayOf("*/*")) } catch (_: Exception) {
                            inputPickerPending = false; pickerNotice = "无法打开文件选择器，请重试"; coordinator.openFileRecovery()
                        }
                    }, enabled = !recoveryState.busy && !inputPickerPending && canRecover && !cloudBusy, variant = ButtonVariant.Secondary)
                    key(secretRevision, state.privacyRevision) {
                        CbField("设备组恢复码") { FileRecoveryCodeInput(code, { code = it }, !recoveryState.busy && canRecover) }
                    }
                    CbButton("读取并核对设备组", {
                        val uri = selectedFile ?: return@CbButton
                        val captured = code; clearSecrets(); pickerNotice = null
                        if (!coordinator.prepareFileRecovery({ resolver.openInputStream(uri) ?: throw IOException("SYNC_GROUP_FILE_INPUT_UNAVAILABLE") }, captured))
                            pickerNotice = "另一恢复正在进行，请先继续或停止当前恢复"
                    }, enabled = !recoveryState.busy && !cloudBusy && selectedFile != null && code.isNotBlank() && canRecover)
                }
            }
        }
    }
    if (exportState.visible && exporter != null) {
        CbDialog(onDismissRequest = exporter::hide, title = "导出加密同步文件", dismissOnClickOutside = false,
            confirm = { CbButton("关闭", exporter::hide, variant = ButtonVariant.Secondary) }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CbText("导出本次已核验的设备组密文。恢复时还需要单独保存的设备组恢复码。")
                CbText(exportState.status)
                exportState.error?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
                pickerNotice?.let { CbText(it, color = ChatBarTheme.colors.mutedForeground) }
                exportState.bytes?.let { CbText("文件大小：${NumberFormat.getIntegerInstance().format(it)} 字节") }
                CbText(if (exportState.writeCompleted) "写出：已完成" else "写出：尚未确认")
                CbText(if (exportState.readbackVerified) "回读核验：已通过" else "回读核验：尚未完成")
                CbText("回读核验只确认所选文件的完整字节，不等于已送达其他设备或网盘。", color = ChatBarTheme.colors.mutedForeground)
                CbButton("准备加密同步文件", { pickerNotice = null; exporter.prepare() },
                    enabled = !exportState.busy && outputBinding == null && state.stage == LanSyncGroupPanelStage.READY)
                exportState.preparedBinding?.let { binding -> CbButton("选择保存位置并写出", {
                    pickerNotice = null; outputBinding = binding; outputFile = null; exporter.hide()
                    try { saveFile.launch("ChatBar.cbgroup") } catch (_: Exception) {
                        outputBinding = null; pickerNotice = "无法打开保存位置，请重试"; exporter.open()
                    }
                }, enabled = !exportState.busy && outputBinding == null, variant = ButtonVariant.Secondary) }
                if (exportState.busy || exportState.preparedBinding != null) CbButton("取消本次导出", {
                    outputBinding = null; outputFile = null; exporter.lock()
                }, variant = ButtonVariant.Secondary)
            }
        }
    }
}

@Composable
private fun FileRecoveryCodeInput(value: String, onChange: (String) -> Unit, enabled: Boolean) {
    val colors = ChatBarTheme.colors
    BasicTextField(value, onChange, enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(colors.input, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(12.dp),
        textStyle = ChatBarTheme.typography.body.copy(color = colors.foreground), cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
}

private fun fileActionsActivity(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}
