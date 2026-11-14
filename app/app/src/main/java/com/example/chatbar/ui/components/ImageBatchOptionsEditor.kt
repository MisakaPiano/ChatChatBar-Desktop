package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import java.math.BigDecimal
import java.math.RoundingMode
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
        ((value.target + value.requestCount - 1) / value.requestCount).coerceIn(1, maximumImageBatchRuns(value.requestCount)) else 1
    var details by remember { mutableStateOf(false) }
    var fieldsValid by remember { mutableStateOf(mapOf<String, Boolean>()) }
    val activeFields = setOf("count", "batches") + when (value.pacing.mode) {
        ImageBatchInterval.NONE -> emptySet()
        ImageBatchInterval.FIXED -> setOf("fixed")
        ImageBatchInterval.RANGE -> setOf("min", "max")
    }
    val valid = fieldsValid.filterKeys { it in activeFields }.values.all { it } && value.validationError() == null
    LaunchedEffect(valid) { onValidChange(valid) }
    fun publish(count: Int = value.requestCount, times: Int = batches) {
        val safeTimes = times.coerceIn(1, maximumImageBatchRuns(count))
        onChange(value.copy(requestCount = count, numberOfBatches = safeTimes,
            continuous = safeTimes > 1, target = count * safeTimes))
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
                BoundedBatchNumber(batches, 1, maximumImageBatchRuns(value.requestCount), "生成批数",
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
                BoundedBatchDelay(value.pacing.effectiveFixedMillis, 0, 3_600_000, "固定等待秒数",
                    { onChange(value.copy(numberOfBatches = batches, pacing = value.pacing.copy(fixedSeconds = (it / 1000).toInt(), fixedMillis = it))) },
                    { fieldsValid = fieldsValid + ("fixed" to it) })
            }
            if (value.pacing.mode == ImageBatchInterval.RANGE) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    CbText("最少秒数")
                    BoundedBatchDelay(value.pacing.effectiveMinMillis, 0, 3_600_000, "随机最小秒数",
                        { onChange(value.copy(numberOfBatches = batches, pacing = value.pacing.copy(minSeconds = (it / 1000).toInt(), minMillis = it,
                            maxSeconds = (maxOf(it, value.pacing.effectiveMaxMillis) / 1000).toInt(),
                            maxMillis = maxOf(it, value.pacing.effectiveMaxMillis)))) },
                        { fieldsValid = fieldsValid + ("min" to it) })
                }
                Column(Modifier.weight(1f)) {
                    CbText("最多秒数")
                    BoundedBatchDelay(value.pacing.effectiveMaxMillis, value.pacing.effectiveMinMillis, 3_600_000, "随机最大秒数",
                        { onChange(value.copy(numberOfBatches = batches, pacing = value.pacing.copy(maxSeconds = (it / 1000).toInt(), maxMillis = it))) },
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

@Composable
private fun BoundedBatchDelay(value: Long, minimum: Long, maximum: Long, label: String,
    onChange: (Long) -> Unit, onValid: (Boolean) -> Unit) {
    fun millis(text: String): Long? = runCatching {
        text.toBigDecimalOrNull()?.multiply(BigDecimal(1000))?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()
    }.getOrNull()
    var text by remember { mutableStateOf(imageBatchSecondsText(value)) }
    LaunchedEffect(value) { if (millis(text) != value) text = imageBatchSecondsText(value) }
    CbInput(text, { incoming ->
        val decimal = incoming.toBigDecimalOrNull()
        if (incoming.isBlank() || decimal == null) {
            text = incoming
            onValid(false)
        } else {
            val bounded = decimal.multiply(BigDecimal(1000)).max(BigDecimal(minimum)).min(BigDecimal(maximum))
                .setScale(0, RoundingMode.HALF_UP).longValueExact()
            text = if (millis(incoming) == bounded && incoming.substringAfter('.', "").length <= 3) incoming
                else imageBatchSecondsText(bounded)
            onChange(bounded)
            onValid(true)
        }
    }, modifier = Modifier.semantics { contentDescription = label },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        onFocusChanged = { focused -> if (!focused && millis(text) == null) {
            text = imageBatchSecondsText(value); onValid(true)
        } })
}
