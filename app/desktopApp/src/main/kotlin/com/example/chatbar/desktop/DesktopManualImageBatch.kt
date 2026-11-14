package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/** Per-confirmation runtime only. Neither consent nor an active run is restored from stored drafts. */
internal suspend fun runDesktopManualImageBatch(
    plan: ImageBatchRunPlan,
    control: ImageBatchControl,
    credential: String,
    account: suspend () -> NovelAiAccountUsage,
    estimate: suspend (Int, NovelAiAccountUsage) -> NovelAiGenerationCost,
    validateOwner: suspend () -> Unit,
    onProgress: (ImageBatchProgress) -> Unit,
    runner: ImageBatchRunner = ImageBatchRunner(),
    generateAndSave: suspend (Int, NovelAiGenerationCost) -> Int,
): Int {
    require(credential.isNotBlank())
    val ledger = ManualImageAccountLedgers.forCredential(credential)
    var admittedCost: NovelAiGenerationCost? = null
    return runner.run(plan, control, quote = { count ->
        // No usable quote means no image request, including the first one.
        val current = ledger.reconcile(try { account() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw ImageBatchStopped("账户状态获取失败；本批请求尚未发出，请重新检查并确认") })
        val cost = estimate(count, current)
        admittedCost = cost
        ImageBatchQuote(cost.anlas.toLong(), current.anlas, cost.kind == NovelAiGenerationChargeKind.V5_ALLOWANCE)
    }, validateOwner = validateOwner, onProgress = onProgress) { count ->
        validateOwner()
        currentCoroutineContext().ensureActive()
        val cost = requireNotNull(admittedCost)
        // Conservative reservations include uncertain/limited attempts; never imply a refund or settled bill.
        ledger.reserve(cost, count)
        generateAndSave(count, cost)
    }
}
