package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.kit.*

/** Shared count editor and policy fields. Consent and spending budget are runtime-only. */
@Composable
fun ImageBatchOptionsEditor(
    value: ImageBatchConfiguration,
    onChange: (ImageBatchConfiguration) -> Unit,
    onValidChange: (Boolean) -> Unit = {},
    showCount: Boolean = true
) {
    var count by remember { mutableStateOf(value.requestCount.toString()) }
    var target by remember { mutableStateOf(value.target.toString()) }
    var fixed by remember { mutableStateOf(value.pacing.fixedSeconds.toString()) }
    var minimum by remember { mutableStateOf(value.pacing.minSeconds.toString()) }
    var maximum by remember { mutableStateOf(value.pacing.maxSeconds.toString()) }
    var budget by remember { mutableStateOf(value.budget.toString()) }
    val parsed = runCatching { value.copy(
        requestCount = if (showCount) count.toInt() else value.requestCount,
        target = target.toInt(), budget = budget.toLong(),
        pacing = value.pacing.copy(fixedSeconds = fixed.toInt(), minSeconds = minimum.toInt(), maxSeconds = maximum.toInt())
    ) }.getOrNull()
    val error = parsed?.validationError() ?: if (parsed == null) "请填写有效整数；不能留空或溢出" else null
    LaunchedEffect(count, target, fixed, minimum, maximum, budget, value.continuous, value.requestCount, value.pacing.mode) {
        onValidChange(error == null)
        if (parsed != null && error == null) onChange(parsed)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showCount) {
            CbText("单次请求张数（1–4）")
            NovelAiBatchSizeInput(count, { count = it }, enabled = true)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CbText("连续生成", Modifier.weight(1f))
            CbSwitch(value.continuous, { onChange(value.copy(continuous = it)) })
        }
        if (value.continuous) {
            CbField("成功保存总张数（1–100）") { CbNumberInput(target, { target = it }, isError = target.toIntOrNull() !in 1..100) }
            CbText("批次间等待；首批立即开始，最后一批后不等待")
            ImageBatchInterval.entries.forEach { mode ->
                CbButton(when (mode) { ImageBatchInterval.NONE -> "不等待"; ImageBatchInterval.FIXED -> "固定"; ImageBatchInterval.RANGE -> "范围随机" },
                    { onChange(value.copy(pacing = value.pacing.copy(mode = mode))) },
                    variant = if (value.pacing.mode == mode) ButtonVariant.Default else ButtonVariant.Outline)
            }
            if (value.pacing.mode == ImageBatchInterval.FIXED) CbField("固定秒数（0–3600）") {
                CbNumberInput(fixed, { fixed = it }, isError = fixed.toIntOrNull() !in 0..3600)
            }
            if (value.pacing.mode == ImageBatchInterval.RANGE) {
                CbField("随机最小秒数（0–3600）") { CbNumberInput(minimum, { minimum = it }, isError = minimum.toIntOrNull() !in 0..3600) }
                CbField("随机最大秒数（至少等于最小值）") { CbNumberInput(maximum, { maximum = it }, isError = maximum.toIntOrNull() !in 0..3600) }
            }
        }
        if (showCount || value.continuous || value.requestCount > 1) {
            CbField("本次估算 Anlas 预算（默认 0，仅估算免 Anlas）", description = "每次新任务需重新确认；不是服务器保证的扣费上限。") {
                CbNumberInput(budget, { budget = it }, isError = budget.toLongOrNull()?.let { it !in 0..100000 } != false)
            }
        }
        if (error != null) CbText(error, color = ChatBarTheme.colors.destructive)
        if (parsed != null && error == null) CbText(parsed.freeze(ImageBatchOrigin.CHAT_MANUAL).summary())
        CbText(ImageBatchRiskText.NOTICE, style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.mutedForeground)
        CbText("HTTP 429 会暂停并尊重 Retry-After；累计 3 次后停止。退出会话界面停止手动连续任务。", style = ChatBarTheme.typography.caption)
    }
}
