package com.example.chatbar.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*

/** Discard unfinished hidden inputs on an explicit mode change, preserving valid dormant values. */
internal fun desktopImageBatchPacingMode(pacing: ImageBatchPacing, mode: ImageBatchInterval): ImageBatchPacing {
    val defaults = ImageBatchPacing()
    var next = pacing.copy(mode = mode)
    if (mode != ImageBatchInterval.FIXED && (pacing.fixedSeconds !in 0..3600 ||
            pacing.fixedMillis?.let { it !in 0..3600000L } == true))
        next = next.copy(fixedSeconds = defaults.fixedSeconds, fixedMillis = null)
    if (mode != ImageBatchInterval.RANGE && (pacing.minSeconds !in 0..3600 || pacing.maxSeconds !in 0..3600 ||
            listOfNotNull(pacing.minMillis, pacing.maxMillis).any { it !in 0..3600000L } || pacing.minWaitMillis > pacing.maxWaitMillis))
        next = next.copy(minSeconds = defaults.minSeconds, maxSeconds = defaults.maxSeconds, minMillis = null, maxMillis = null)
    return next
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DesktopImageBatchPacing(pacing: ImageBatchPacing, change: (ImageBatchPacing) -> Unit) {
    CompactChoice("批次间等待", ImageBatchInterval.entries.toList(), pacing.mode,
        { when (it) { ImageBatchInterval.NONE -> "不等待"; ImageBatchInterval.FIXED -> "固定"; ImageBatchInterval.RANGE -> "范围随机" } }) {
        change(desktopImageBatchPacingMode(pacing, it))
    }
    when (pacing.mode) {
        ImageBatchInterval.NONE -> StatusText("不等待会连续提交后续批次；开始前仍需确认请求计划。", DesktopBootstrapColors.warning)
        ImageBatchInterval.FIXED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText("等待秒数")
            StudioNumericInput("固定等待秒数", ImageBatchPacing.secondsText(pacing.fixedWaitMillis), Modifier.width(90.dp)) {
                val millis = ImageBatchPacing.parseSeconds(it)
                change(if (millis == null) pacing.copy(fixedMillis = -1) else
                    pacing.copy(fixedSeconds = ((millis + 999) / 1000).toInt(), fixedMillis = millis.takeIf { value -> value % 1000 != 0L }))
            }
        }
        ImageBatchInterval.RANGE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusText("随机秒数范围")
            StudioNumericInput("随机等待最小秒数", ImageBatchPacing.secondsText(pacing.minWaitMillis), Modifier.width(80.dp)) {
                val millis = ImageBatchPacing.parseSeconds(it)
                change(if (millis == null) pacing.copy(minMillis = -1) else
                    pacing.copy(minSeconds = ((millis + 999) / 1000).toInt(), minMillis = millis.takeIf { value -> value % 1000 != 0L }))
            }
            StatusText("至")
            StudioNumericInput("随机等待最大秒数", ImageBatchPacing.secondsText(pacing.maxWaitMillis), Modifier.width(80.dp)) {
                val millis = ImageBatchPacing.parseSeconds(it)
                change(if (millis == null) pacing.copy(maxMillis = -1) else
                    pacing.copy(maxSeconds = ((millis + 999) / 1000).toInt(), maxMillis = millis.takeIf { value -> value % 1000 != 0L }))
            }
        }
    }
    pacing.validationError()?.let { StatusText(it, DesktopBootstrapColors.warning) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DesktopImageBatchOptions(config: ImageBatchConfiguration, origin: ImageBatchOrigin,
    change: (ImageBatchConfiguration) -> Unit, confirmed: Boolean? = null,
    onConfirmationChange: ((Boolean) -> Unit)? = null, requireConfirmation: Boolean = true): ImageBatchRunPlan? {
    var localConfirmed by remember(config) { mutableStateOf(false) }
    val consent = confirmed ?: localConfirmed
    val error = config.validationError()
    val plan = remember(config) { if (error == null) config.freeze(origin) else null }
    StudioChips("每次请求图片数", (1..4).toList(), config.requestCount, { it.toString() }) { change(config.copy(requestCount = it)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusText("生成批数（1–${100 / config.requestCount.coerceIn(1, 4)}）")
        StudioNumericInput("生成批数", config.numberOfBatches.toString(), Modifier.width(90.dp)) {
            change(config.copy(numberOfBatches = it.toIntOrNull() ?: 0))
        }
    }
    DesktopImageBatchPacing(config.pacing) { change(config.copy(pacing = it)) }
    StudioSwitch("允许消耗 Anlas", config.allowAnlasSpending) { change(config.copy(allowAnlasSpending = !config.allowAnlasSpending)) }
    if (error != null) StatusText(error, DesktopBootstrapColors.warning)
    if (plan != null) {
        StatusText(plan.summary())
        StatusText("每批请求前重新核费；预计收费时按本轮系统报价确认，涨价后需重新确认。")
    }
    StudioDisclosure("费用与任务说明", initiallyOpen = false) {
        StatusText(ImageBatchRiskText.NOTICE, DesktopBootstrapColors.warning)
        StatusText("会话内的图片内容设计仍可能调用你选择的对话模型，费用另计。")
        StatusText("任务切换页面后继续；可暂停或停止。退出后不会自动恢复，已保存图片保留。")
    }
    if (requireConfirmation) StudioSwitch("我确认本次请求计划", consent) {
        onConfirmationChange?.invoke(!consent) ?: run { localConfirmed = !consent }
    }
    return plan.takeIf { !requireConfirmation || consent }
}

@Composable
internal fun DesktopSessionImageBatchSettings(preferences: ImageBatchPreferences, change: (ImageBatchPreferences) -> Unit) {
    StudioSwitch("启用会话批量计划", preferences.enabled) { change(preferences.copy(enabled = !preferences.enabled)) }
    DesktopImageBatchOptions(preferences.configuration(useSavedBatch = true), ImageBatchOrigin.CHAT_MANUAL,
        change = { config -> change(preferences.copy(imagesPerBatch = config.requestCount,
            numberOfBatches = config.numberOfBatches, pacing = config.pacing, allowAnlasSpending = config.allowAnlasSpending)) },
        requireConfirmation = false)
    StatusText("保存计划不会开启自动生图或启动请求。关闭计划时普通生成只生成一张；自动生图仍由自己的开关控制。")
}

@Composable
internal fun DesktopStudioBatchConfirmation(draft: NovelAiStudioDraft, account: NovelAiAccountUsage?, busy: Boolean,
    status: String, onClose: () -> Unit, onStart: (ImageBatchRunPlan) -> Unit) {
    var config by remember(draft) { mutableStateOf(draft.batchConfiguration()) }
    DesktopImageToolWindow("确认生图批次", onClose, width = 650.dp, height = 720.dp) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ManageHeading("本次生图计划")
            StatusText("此处只调整本次任务；付款确认与运行状态不会随草稿保存。")
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
