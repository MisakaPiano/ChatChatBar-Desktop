package com.example.chatbar.domain.image

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable enum class ImageBatchInterval { NONE, FIXED, RANGE }
@Serializable data class ImageBatchPacing(
    val mode: ImageBatchInterval = ImageBatchInterval.FIXED,
    val fixedSeconds: Int = 15,
    val minSeconds: Int = 15,
    val maxSeconds: Int = 45
) {
    fun validationError(): String? = when {
        fixedSeconds !in 0..3600 || minSeconds !in 0..3600 || maxSeconds !in 0..3600 -> "等待秒数须在 0–3600 之间"
        minSeconds > maxSeconds -> "随机等待最小值不能大于最大值"
        else -> null
    }
    fun sampleSeconds(random: Random = Random.Default): Int {
        require(validationError() == null) { validationError().orEmpty() }
        return when (mode) {
            ImageBatchInterval.NONE -> 0
            ImageBatchInterval.FIXED -> fixedSeconds
            ImageBatchInterval.RANGE -> random.nextInt(minSeconds, maxSeconds + 1)
        }
    }
    fun label(): String = when (mode) {
        ImageBatchInterval.NONE -> "不等待"
        ImageBatchInterval.FIXED -> "固定 $fixedSeconds 秒"
        ImageBatchInterval.RANGE -> "随机 $minSeconds–$maxSeconds 秒"
    }
}

enum class ImageBatchOrigin { STUDIO, CHAT_MANUAL }
data class ImageBatchConfiguration(
    val requestCount: Int = 1,
    val continuous: Boolean = false,
    val target: Int = 10,
    val pacing: ImageBatchPacing = ImageBatchPacing(),
    val budget: Long = 0
) {
    fun validationError(): String? = when {
        requestCount !in 1..4 -> "单次请求张数须在 1–4 之间"
        continuous && target !in 1..100 -> "连续目标须在 1–100 张之间"
        budget !in 0..100000 -> "估算 Anlas 预算须在 0–100000 之间"
        else -> pacing.validationError()
    }
    fun freeze(origin: ImageBatchOrigin): ImageBatchRunPlan {
        require(validationError() == null) { validationError().orEmpty() }
        return ImageBatchRunPlan(requestCount, if (continuous) target else requestCount, pacing, budget, origin)
    }
}
data class ImageBatchRunPlan(
    val requestCount: Int,
    val targetTotalImages: Int,
    val pacing: ImageBatchPacing,
    val maxEstimatedAnlas: Long,
    val origin: ImageBatchOrigin
) {
    init {
        require(requestCount in 1..4 && targetTotalImages in 1..100 && maxEstimatedAnlas in 0..100000)
        require(pacing.validationError() == null)
    }
    val batches: List<Int> get() = buildList {
        var left = targetTotalImages
        while (left > 0) { val size = minOf(requestCount, left); add(size); left -= size }
    }
    fun summary() = "预计 $targetTotalImages 张 · ${batches.size} 批请求（${batches.joinToString(" + ")}）\n批间${pacing.label()} · 本次估算预算 $maxEstimatedAnlas Anlas"
}

data class ImageBatchQuote(val anlas: Long, val availableAnlas: Long, val usesV5Allowance: Boolean = false)
class ImageBatchRateLimited(val retryAfterMillis: Long) : Exception("HTTP 429，连续任务已暂停；请稍后手动继续，随机等待不能保证消除限流")
class ImageBatchStopped(message: String) : Exception(message)

enum class ImageBatchStage { RUNNING, WAITING, PAUSED, COMPLETE }
data class ImageBatchProgress(
    val stage: ImageBatchStage = ImageBatchStage.RUNNING,
    val saved: Int = 0,
    val target: Int = 0,
    val batch: Int = 0,
    val batches: Int = 0,
    val remainingSeconds: Int = 0,
    val estimatedSpent: Long = 0,
    val nextEstimatedAnlas: Long? = null,
    val usesV5Allowance: Boolean = false,
    val detail: String = ""
) {
    fun label() = "已保存 $saved/$target 张 · 第 $batch/$batches 批 · 已预留估算 $estimatedSpent Anlas" +
        (nextEstimatedAnlas?.let { " · 本批估算 $it Anlas" } ?: "") +
        (if (usesV5Allowance) " · 另消耗 V5 使用额度" else "") +
        (if (remainingSeconds > 0) " · 等待 $remainingSeconds 秒" else "") +
        (if (detail.isNotBlank()) "\n$detail" else "")
}

/** Runtime only. Never serialize a running task, consent, countdown or resume signal. */
class ImageBatchControl {
    private val paused = MutableStateFlow(false)
    fun pause() { paused.value = true }
    fun resume() { paused.value = false }
    val isPaused: Boolean get() = paused.value
    suspend fun awaitReady(onPaused: () -> Unit) {
        if (paused.value) { onPaused(); paused.first { !it } }
        currentCoroutineContext().ensureActive()
    }
}

/** One in-flight batch across manual Studio/Chat runs; neither pause nor pacing holds admission. */
class ImageBatchRunner(
    private val admission: Mutex = manualAdmission,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun run(
        plan: ImageBatchRunPlan,
        control: ImageBatchControl,
        quote: suspend (Int) -> ImageBatchQuote?,
        validateOwner: suspend () -> Unit = {},
        onProgress: (ImageBatchProgress) -> Unit = {},
        generateAndSave: suspend (Int) -> Int
    ): Int {
        var state = ImageBatchProgress(target = plan.targetTotalImages, batches = plan.batches.size)
        var rateLimits = 0
        fun emit(next: ImageBatchProgress) { state = next; onProgress(next) }
        suspend fun ready() = control.awaitReady { emit(state.copy(stage = ImageBatchStage.PAUSED, detail = state.detail.ifBlank { "已暂停；继续不会跳过剩余等待" })) }
        suspend fun waitSeconds(seconds: Int, detail: String) {
            for (left in seconds downTo 1) {
                ready(); validateOwner()
                emit(state.copy(stage = ImageBatchStage.WAITING, remainingSeconds = left, detail = detail))
                sleep(1000)
            }
            emit(state.copy(remainingSeconds = 0, detail = ""))
        }
        for ((index, count) in plan.batches.withIndex()) {
            if (index > 0) waitSeconds(plan.pacing.sampleSeconds(random), "批次间等待；不占用生图许可")
            var committed = false
            while (!committed) {
                ready(); validateOwner()
                try {
                    admission.withLock {
                        if (control.isPaused) return@withLock
                        validateOwner()
                        val cost = quote(count)
                        if (cost == null) throw ImageBatchStopped("账户状态或计费预计未知，任务停止；请检查后重新确认，未发起本批请求")
                        if (control.isPaused) return@withLock
                        validateOwner()
                        currentCoroutineContext().ensureActive()
                        if (cost.anlas < 0 || cost.anlas > plan.maxEstimatedAnlas - state.estimatedSpent)
                            throw ImageBatchStopped("本批预计超出剩余 Anlas 预算，任务停止；不会自动转为付费。调整后须重新确认")
                        if (cost.availableAnlas < cost.anlas) throw ImageBatchStopped("账户 Anlas 余额不足，未发起本批请求")
                        emit(state.copy(stage = ImageBatchStage.RUNNING, batch = index + 1, nextEstimatedAnlas = cost.anlas,
                            usesV5Allowance = cost.usesV5Allowance, detail = "余额和费用均为当前估算；V5 额度另计"))
                        emit(state.copy(estimatedSpent = state.estimatedSpent + cost.anlas))
                        val saved = generateAndSave(count)
                        check(saved == count) { "批次保存数量不符；任务停止，不自动重新提交" }
                        emit(state.copy(saved = state.saved + saved))
                        committed = true
                    }
                } catch (limited: ImageBatchRateLimited) {
                    rateLimits++
                    if (rateLimits >= 3) throw ImageBatchStopped("已累计收到 3 次 HTTP 429，任务停止；请勿反复强制重试")
                    if (limited.retryAfterMillis > 3600000) throw ImageBatchStopped("HTTP 429 冷却超过一小时，任务停止；不会缩短服务端等待")
                    control.pause()
                    emit(state.copy(stage = ImageBatchStage.PAUSED, detail = limited.message.orEmpty()))
                    ready()
                    // A resume click cannot shorten the server-directed cooldown.
                    waitSeconds(((limited.retryAfterMillis.coerceIn(1000, 3600000) + 999) / 1000).toInt(), "HTTP 429 服务端冷却，继续也不能跳过")
                }
            }
        }
        emit(state.copy(stage = ImageBatchStage.COMPLETE, nextEstimatedAnlas = null, detail = "任务完成；重启不会自动继续请求"))
        return state.saved
    }
    companion object {
        private val manualAdmission = Mutex()
        suspend fun <T> ordinaryStudioRequest(block: suspend () -> T): T = manualAdmission.withLock { block() }
    }
}

object ImageBatchRiskText {
    const val NOTICE = "一次请求多张通常消耗额外 Anlas；多次单张请求也可能收费。Opus 单张优惠受模型、尺寸、Steps 和参考图限制；V5 另消耗使用额度，耗尽后可能转为 Anlas。固定或随机等待仅用于节奏控制，不保证符合服务规则、免限流或账号安全。估算预算不是服务器扣费上限。停止后已发送的请求仍可能被处理和计费；网络中断不会自动重发不确定的付费请求。"
}
