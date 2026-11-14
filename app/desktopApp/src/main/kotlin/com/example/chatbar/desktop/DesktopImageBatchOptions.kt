package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DesktopImageBatchPacing(pacing: ImageBatchPacing, change: (ImageBatchPacing) -> Unit) {
    CompactChoice("批次间等待", ImageBatchInterval.entries.toList(), pacing.mode,
        { when (it) { ImageBatchInterval.NONE -> "不等待"; ImageBatchInterval.FIXED -> "固定"; ImageBatchInterval.RANGE -> "范围随机" } }) {
        change(pacing.copy(mode = it))
    }
    when (pacing.mode) {
        ImageBatchInterval.NONE -> StatusText("不等待会连续提交后续批次；开始前仍需确认请求计划。", DesktopBootstrapColors.warning)
        ImageBatchInterval.FIXED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText("等待秒数")
            StudioNumericInput("固定等待秒数", pacing.fixedSeconds.toString(), Modifier.width(90.dp)) {
                change(pacing.copy(fixedSeconds = it.toIntOrNull() ?: -1))
            }
        }
        ImageBatchInterval.RANGE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText("随机秒数范围")
            StudioNumericInput("随机等待最小秒数", pacing.minSeconds.toString(), Modifier.width(80.dp)) {
                change(pacing.copy(minSeconds = it.toIntOrNull() ?: -1))
            }
            StatusText("至")
            StudioNumericInput("随机等待最大秒数", pacing.maxSeconds.toString(), Modifier.width(80.dp)) {
                change(pacing.copy(maxSeconds = it.toIntOrNull() ?: -1))
            }
        }
    }
    pacing.validationError()?.let { StatusText(it, DesktopBootstrapColors.warning) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DesktopImageBatchOptions(config: ImageBatchConfiguration, origin: ImageBatchOrigin,
    change: (ImageBatchConfiguration) -> Unit, confirmed: Boolean? = null,
    onConfirmationChange: ((Boolean) -> Unit)? = null): ImageBatchRunPlan? {
    var localConfirmed by remember(config) { mutableStateOf(false) }
    val consent = confirmed ?: localConfirmed
    val error = config.validationError()
    val plan = remember(config) { if (error == null) config.freeze(origin) else null }
    StudioChips("每次请求图片数", (1..4).toList(), config.requestCount, { it.toString() }) { change(config.copy(requestCount = it)) }
    StudioSwitch("连续生成到指定总张数", config.continuous) { change(config.copy(continuous = !config.continuous)) }
    if (config.continuous) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("成功保存目标（1–100）")
        StudioNumericInput("批次总图片数", config.target.toString(), Modifier.width(90.dp)) { change(config.copy(target = it.toIntOrNull() ?: 0)) }
    }
    DesktopImageBatchPacing(config.pacing) { change(config.copy(pacing = it)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("本次估算预算（Anlas）")
        StudioNumericInput("本次Anlas预算", config.budget.toString(), Modifier.width(110.dp)) { change(config.copy(budget = it.toLongOrNull() ?: -1)) }
    }
    if (error != null) StatusText(error, DesktopBootstrapColors.warning)
    if (plan != null) {
        StatusText(plan.summary())
        StatusText("每批请求前重新核对账户和估算预算；当前批次只在完整保存后才计入目标。")
    }
    StatusText(ImageBatchRiskText.NOTICE, DesktopBootstrapColors.warning)
    StatusText("会话内的图片内容设计仍可能调用你选择的对话模型；这里的 Anlas 预算不包含该模型费用。")
    StatusText("任务切换页面后继续；可暂停或停止。退出后不会自动恢复，已保存图片保留。")
    StudioSwitch("我确认本次请求计划和估算预算", consent) {
        onConfirmationChange?.invoke(!consent) ?: run { localConfirmed = !consent }
    }
    return plan.takeIf { consent }
}

@Composable
internal fun DesktopStudioBatchConfirmation(draft: NovelAiStudioDraft, account: NovelAiAccountUsage?, busy: Boolean,
    status: String, onClose: () -> Unit, onStart: (ImageBatchRunPlan) -> Unit) {
    var config by remember(draft) { mutableStateOf(ImageBatchConfiguration(draft.activeSettings.count,
        draft.continuousModeEnabled, draft.continuousTargetCount, draft.batchPacing)) }
    DesktopImageToolWindow("确认生图批次", onClose, width = 650.dp, height = 720.dp) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ManageHeading("本次生图计划")
            StatusText("此处只调整本次任务；预算和运行状态不会随草稿保存。")
            val plan = DesktopImageBatchOptions(config, ImageBatchOrigin.STUDIO, { config = it })
            if (account == null) StatusText("账户费用暂未知；开始后仍须成功查询账户才会发出本批图片请求。", DesktopBootstrapColors.warning)
            else if (config.validationError() == null) {
                val counts = config.freeze(ImageBatchOrigin.STUDIO).batches
                val estimates = counts.map { NovelAiImageCostEstimator.estimate(draft.activeSettings.copy(count = it), account, draft.imageGuidance).anlas }
                StatusText("按当前账户缓存估算图像部分：${estimates.joinToString(" + ")} Anlas；参考图编码另计，每批发出前重新核费。")
            }
            if (status.isNotBlank()) StatusText(status)
            // Keep confirmation at the bottom of the scrollable content on narrow/short windows.
            StudioActions {
                BootstrapButton("取消", secondary = true, onClick = onClose)
                BootstrapButton("确认并开始", enabled = !busy && plan != null) {
                    val latest = if (config.validationError() == null) config.freeze(ImageBatchOrigin.STUDIO) else null
                    if (latest == plan) plan?.let(onStart)
                }
            }
        }
    }
}
