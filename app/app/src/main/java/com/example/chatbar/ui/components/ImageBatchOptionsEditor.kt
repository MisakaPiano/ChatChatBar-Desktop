package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.kit.*

/** Product settings only: mock responses and payment budgets never appear here. */
@Composable
fun ImageBatchOptionsEditor(
    value: ImageBatchConfiguration,
    onChange: (ImageBatchConfiguration) -> Unit,
    onValidChange: (Boolean) -> Unit = {},
    showCount: Boolean = true
) {
    val batches = value.numberOfBatches ?: if (value.continuous)
        ((value.target + value.requestCount - 1) / value.requestCount).coerceIn(1, 25) else 1
    var details by remember { mutableStateOf(false) }
    var fieldsValid by remember { mutableStateOf(mapOf<String, Boolean>()) }
    val valid = fieldsValid.values.all { it } && value.validationError() == null
    LaunchedEffect(valid) { onValidChange(valid) }
    fun publish(count: Int = value.requestCount, times: Int = batches) {
        onChange(value.copy(requestCount = count, numberOfBatches = times,
            continuous = times > 1, target = count * times))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showCount) Column(Modifier.weight(1f)) {
                CbText("每批张数")
                BoundedBatchNumber(value.requestCount, 1, 4, "每批张数",
                    { publish(count = it) }, { fieldsValid = fieldsValid + ("count" to it) })
            }
            Column(Modifier.weight(1f)) {
                CbText("生成批数")
                BoundedBatchNumber(batches, 1, 25, "生成批数",
                    { publish(times = it) }, { fieldsValid = fieldsValid + ("batches" to it) })
            }
        }
        CbText("共 ${value.requestCount * batches} 张 · $batches 批请求")
        if (batches > 1) {
            CbText("批次间等待")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImageBatchInterval.entries.forEach { mode ->
                    CbButton(when (mode) { ImageBatchInterval.NONE -> "不等待"; ImageBatchInterval.FIXED -> "固定"; ImageBatchInterval.RANGE -> "随机" },
                        { onChange(value.copy(numberOfBatches = batches, pacing = value.pacing.copy(mode = mode))) },
                        modifier = Modifier.weight(1f), autoSizeText = true,
                        variant = if (value.pacing.mode == mode) ButtonVariant.Default else ButtonVariant.Outline)
                }
            }
            if (value.pacing.mode == ImageBatchInterval.FIXED) CbField("等待秒数") {
                BoundedBatchNumber(value.pacing.fixedSeconds, 0, 3600, "固定等待秒数",
                    { onChange(value.copy(numberOfBatches = batches, pacing = value.pacing.copy(fixedSeconds = it))) },
                    { fieldsValid = fieldsValid + ("fixed" to it) })
            }
            if (value.pacing.mode == ImageBatchInterval.RANGE) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    CbText("最少秒数")
                    BoundedBatchNumber(value.pacing.minSeconds, 0, 3600, "随机最小秒数",
                        { onChange(value.copy(numberOfBatches = batches, pacing = value.pacing.copy(minSeconds = it, maxSeconds = maxOf(it, value.pacing.maxSeconds)))) },
                        { fieldsValid = fieldsValid + ("min" to it) })
                }
                Column(Modifier.weight(1f)) {
                    CbText("最多秒数")
                    BoundedBatchNumber(value.pacing.maxSeconds, value.pacing.minSeconds, 3600, "随机最大秒数",
                        { onChange(value.copy(numberOfBatches = batches, pacing = value.pacing.copy(maxSeconds = it))) },
                        { fieldsValid = fieldsValid + ("max" to it) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CbText("允许消耗 Anlas")
                CbText("关闭时拦截预计收费的生成", style = ChatBarTheme.typography.caption,
                    color = ChatBarTheme.colors.mutedForeground)
            }
            CbSwitch(value.allowAnlasSpending, { checked ->
                onChange(value.copy(numberOfBatches = batches, allowAnlasSpending = checked,
                    budget = if (checked) 100000 else 0))
            })
        }
        CbButton(if (details) "收起费用与限流说明" else "费用与限流说明", { details = !details }, variant = ButtonVariant.Ghost)
        if (details) CbText(ImageBatchRiskText.NOTICE, style = ChatBarTheme.typography.caption,
            color = ChatBarTheme.colors.mutedForeground)
    }
}

@Composable
private fun BoundedBatchNumber(value: Int, minimum: Int, maximum: Int, label: String,
    onChange: (Int) -> Unit, onValid: (Boolean) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    CbNumberInput(text, { incoming ->
        text = if (incoming.isBlank()) incoming else incoming.toBigIntegerOrNull()?.let {
            it.max(minimum.toBigInteger()).min(maximum.toBigInteger()).toString()
        } ?: maximum.toString()
        onValid(text.isNotBlank())
        text.toIntOrNull()?.let(onChange)
    }, modifier = Modifier.semantics { contentDescription = label },
        onFocusChanged = { focused -> if (!focused && text.isBlank()) { text = value.toString(); onValid(true) } })
}
