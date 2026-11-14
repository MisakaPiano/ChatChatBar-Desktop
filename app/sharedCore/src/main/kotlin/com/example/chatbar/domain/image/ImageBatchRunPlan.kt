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
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class ImageBatchPacing(
    val mode: ImageBatchInterval = ImageBatchInterval.FIXED,
    val fixedSeconds: Int = 15,
    val minSeconds: Int = 15,
    val maxSeconds: Int = 45,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fixedMillis: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val minMillis: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val maxMillis: Long? = null
) {
    val fixedWaitMillis: Long get() = fixedMillis ?: fixedSeconds.toLong() * 1000
    val minWaitMillis: Long get() = minMillis ?: minSeconds.toLong() * 1000
    val maxWaitMillis: Long get() = maxMillis ?: maxSeconds.toLong() * 1000
    fun validationError(): String? = when {
        fixedSeconds !in 0..3600 || minSeconds !in 0..3600 || maxSeconds !in 0..3600 -> "等待秒数须在 0–3600 之间"
        listOfNotNull(fixedMillis, minMillis, maxMillis).any { it !in 0..3600000L } -> "等待秒数须在 0–3600 之间"
        minWaitMillis > maxWaitMillis -> "随机等待最小值不能大于最大值"
        else -> null
    }
    fun sampleMillis(random: Random = Random.Default): Long {
        require(validationError() == null) { validationError().orEmpty() }
        return when (mode) {
            ImageBatchInterval.NONE -> 0
            ImageBatchInterval.FIXED -> fixedWaitMillis
            ImageBatchInterval.RANGE -> random.nextLong(minWaitMillis, maxWaitMillis + 1)
        }
    }
    fun sampleSeconds(random: Random = Random.Default): Int = ((sampleMillis(random) + 999) / 1000).toInt()
    fun label(): String = when (mode) {
        ImageBatchInterval.NONE -> "不等待"
        ImageBatchInterval.FIXED -> "固定 ${secondsText(fixedWaitMillis)} 秒"
        ImageBatchInterval.RANGE -> "随机 ${secondsText(minWaitMillis)}–${secondsText(maxWaitMillis)} 秒"
    }
    companion object {
        fun secondsText(millis: Long): String = java.math.BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()
        fun parseSeconds(raw: String): Long? {
            if (!raw.matches(Regex("[0-9]+(?:\\.[0-9]{0,3})?"))) return null
            return runCatching { raw.toBigDecimal().movePointRight(3).longValueExact() }.getOrNull()?.takeIf { it in 0..3600000L }
        }
    }
}

enum class ImageBatchOrigin { STUDIO, CHAT_MANUAL, CHAT_AUTOMATIC }
data class ImageBatchConfiguration(
    val requestCount: Int = 1,
    val numberOfBatches: Int = 1,
    val pacing: ImageBatchPacing = ImageBatchPacing(),
    val allowAnlasSpending: Boolean = false
) {
    fun validationError(): String? = when {
        requestCount !in 1..4 -> "单次请求张数须在 1–4 之间"
        numberOfBatches !in 1..25 -> "生成批数须在 1–25 之间"
        else -> pacing.validationError()
    }
    fun freeze(origin: ImageBatchOrigin): ImageBatchRunPlan {
        require(validationError() == null) { validationError().orEmpty() }
        return ImageBatchRunPlan(requestCount, numberOfBatches, pacing, allowAnlasSpending, origin)
    }
}
data class ImageBatchRunPlan(
    val requestCount: Int,
    val numberOfBatches: Int,
    val pacing: ImageBatchPacing,
    val allowAnlasSpending: Boolean,
    val origin: ImageBatchOrigin
) {
    init {
        require(requestCount in 1..4 && numberOfBatches in 1..25)
        require(pacing.validationError() == null)
    }
    val targetTotalImages: Int get() = requestCount * numberOfBatches
    val batches: List<Int> get() = List(numberOfBatches) { requestCount }
    fun summary() = "每批 $requestCount 张 × $numberOfBatches 批 = $targetTotalImages 张\n批间${pacing.label()} · " +
        (if (allowAnlasSpending) "允许消耗 Anlas；按本轮系统报价确认，涨价后重新确认" else "不允许消耗 Anlas") +
        "\n断线不自动重发；HTTP 429 最多两次手动继续，服务端冷却不会缩短"
}

data class ImageBatchQuote(val anlas: Long, val availableAnlas: Long, val usesV5Allowance: Boolean = false,
    val followingBatchAnlas: Long = anlas)
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
    val remainingMillis: Long = 0,
    val estimatedSpent: Long = 0,
    val nextEstimatedAnlas: Long? = null,
    val usesV5Allowance: Boolean = false,
    val paymentTicket: String? = null,
    val confirmationEstimatedTotal: Long? = null,
    val detail: String = ""
) {
    fun label() = (when (stage) {
        ImageBatchStage.RUNNING -> "执行中"
        ImageBatchStage.WAITING -> "等待下一批"
        ImageBatchStage.PAUSED -> "已暂停"
        ImageBatchStage.COMPLETE -> "已完成"
    }) + " · 已保存 $saved/$target 张 · 第 $batch/$batches 批 · 已预留估算 $estimatedSpent Anlas" +
        (nextEstimatedAnlas?.let { " · 本批估算 $it Anlas" } ?: "") +
        (if (usesV5Allowance) " · 另消耗 V5 使用额度" else "") +
        (if (remainingMillis > 0) " · 等待 ${ImageBatchPacing.secondsText(remainingMillis)} 秒"
            else if (remainingSeconds > 0) " · 等待 $remainingSeconds 秒" else "") +
        (if (detail.isNotBlank()) "\n$detail" else "")
}

/** Runtime only. Never serialize a running task, consent, countdown or resume signal. */
data class ImageBatchPayment(val ticket: String, val batch: Int, val anlas: Long, val batchQuotes: List<Long>)

class ImageBatchControl {
    private data class State(val paused: Boolean = false, val pending: ImageBatchPayment? = null,
        val approved: ImageBatchPayment? = null)
    private val state = MutableStateFlow(State())
    private val lock = Any()
    private val paymentScope = java.util.UUID.randomUUID().toString()
    private var sequence = 0L
    fun pause() = synchronized(lock) { state.value = state.value.copy(paused = true) }
    // Resuming pacing is deliberately not payment approval.
    fun resume() = synchronized(lock) { state.value = state.value.copy(paused = false) }
    val isPaused: Boolean get() = state.value.paused || state.value.pending != null
    fun requestPayment(batch: Int, anlas: Long, batchQuotes: List<Long>): ImageBatchPayment = synchronized(lock) {
        require(batch > 0 && anlas > 0 && batchQuotes.isNotEmpty() && batchQuotes.all { it >= 0 })
        ImageBatchPayment("$paymentScope:${++sequence}", batch, anlas, batchQuotes.toList()).also {
            state.value = state.value.copy(pending = it, approved = null)
        }
    }
    fun approvePayment(ticket: String): Boolean = synchronized(lock) {
        val pending = state.value.pending?.takeIf { it.ticket == ticket } ?: return@synchronized false
        state.value = state.value.copy(pending = null, approved = pending)
        true
    }
    fun paymentApproved(batch: Int, currentCost: Long, estimatedTotal: Long): Boolean = state.value.approved?.let {
        val expected = it.batchQuotes.getOrNull(batch - it.batch)
        expected != null && currentCost <= expected && estimatedTotal <= it.anlas
    } == true
    suspend fun awaitReady(onPaused: () -> Unit) {
        if (isPaused) { onPaused(); state.first { !it.paused && it.pending == null } }
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
        suspend fun waitMillis(millis: Long, detail: String) {
            var left = millis
            while (left > 0) {
                ready(); validateOwner()
                emit(state.copy(stage = ImageBatchStage.WAITING, remainingSeconds = ((left + 999) / 1000).toInt(),
                    remainingMillis = left, detail = detail))
                val slice = minOf(left, 1000L)
                sleep(slice)
                left -= slice
            }
            emit(state.copy(remainingSeconds = 0, remainingMillis = 0, detail = ""))
        }
        for ((index, count) in plan.batches.withIndex()) {
            if (index > 0) waitMillis(plan.pacing.sampleMillis(random), "批次间等待；不占用生图许可")
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
                        if (cost.anlas < 0 || cost.followingBatchAnlas < 0) throw ImageBatchStopped("费用未知，任务停止；未发起本批请求")
                        if (cost.anlas > 0 && !plan.allowAnlasSpending)
                            throw ImageBatchStopped("预计消耗 ${cost.anlas} Anlas；允许消耗 Anlas 已关闭，未发起本批请求")
                        if (cost.availableAnlas < cost.anlas) throw ImageBatchStopped("当前可用估算余额不足（含未核销请求预留），未发起本批请求")
                        val remainingQuotes = listOf(cost.anlas) + List(plan.batches.size - index - 1) { cost.followingBatchAnlas }
                        val estimatedTotal = try {
                            remainingQuotes.fold(state.estimatedSpent, Math::addExact)
                        } catch (_: ArithmeticException) { throw ImageBatchStopped("系统费用估算不可用，未发起本批请求") }
                        if (cost.anlas > 0 && !control.paymentApproved(index + 1, cost.anlas, estimatedTotal)) {
                            val payment = control.requestPayment(index + 1, estimatedTotal, remainingQuotes)
                            emit(state.copy(stage = ImageBatchStage.PAUSED, batch = index + 1,
                                nextEstimatedAnlas = cost.anlas, paymentTicket = payment.ticket,
                                confirmationEstimatedTotal = estimatedTotal,
                                detail = "本批约 ${cost.anlas} Anlas，后续每批约 ${cost.followingBatchAnlas} Anlas；确认只用于本轮，涨价后需要重新确认"))
                            return@withLock
                        }
                        emit(state.copy(stage = ImageBatchStage.RUNNING, batch = index + 1, nextEstimatedAnlas = cost.anlas,
                            paymentTicket = null, confirmationEstimatedTotal = null,
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
                    waitMillis(limited.retryAfterMillis.coerceIn(1000, 3600000), "HTTP 429 服务端冷却，继续也不能跳过")
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
    const val NOTICE = "一次请求多张通常消耗额外 Anlas；多次单张请求也可能收费。Opus 单张优惠受模型、尺寸、Steps 和参考图限制；V5 另消耗使用额度，耗尽后可能转为 Anlas。固定或随机等待仅用于节奏控制，不保证符合服务规则、免限流或账号安全。系统报价只是估算，不是服务器扣费上限。停止后已发送的请求仍可能被处理和计费；网络中断不会自动重发不确定的付费请求。"
}
