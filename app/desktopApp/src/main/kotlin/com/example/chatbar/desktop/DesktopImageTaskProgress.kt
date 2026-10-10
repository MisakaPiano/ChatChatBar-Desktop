package com.example.chatbar.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DesktopImageProcessPhase { DESIGN, GENERATION }

/** Model snapshots are cumulative, not deltas to append. Never a persisted task field. */
internal data class DesktopImageProcessProgress(
    val phase: DesktopImageProcessPhase = DesktopImageProcessPhase.DESIGN,
    val stage: String = "Prompt 设计",
    val designText: String = "",
    val generationStatus: String = "",
    val truncated: Boolean = false,
)

/** Bounded app-runtime memory; at most one trailing emission per task per 80ms. */
internal class DesktopImageTaskProgress(private val scope: CoroutineScope) {
    private val lock = Any()
    private val latest = mutableMapOf<String, DesktopImageProcessProgress>()
    private val pending = mutableMapOf<String, Job>()
    private val mutable = MutableStateFlow<Map<String, DesktopImageProcessProgress>>(emptyMap())
    val states = mutable.asStateFlow()

    fun admit(id: String) = synchronized(lock) {
        latest[id] = DesktopImageProcessProgress()
        publish(id)
    }

    fun design(id: String, snapshot: String) = synchronized(lock) {
        val old = latest[id] ?: return@synchronized
        // Extract only the authority's supplied heading; do not infer/model new reasoning.
        val stage = Regex("【([^】\\r\\n]{1,120})】").findAll(snapshot).lastOrNull()?.groupValues?.get(1) ?: "Prompt 设计"
        val safe = DesktopDiagnosticScrubber("").text(snapshot, TEXT_LIMIT)
        latest[id] = old.copy(phase = DesktopImageProcessPhase.DESIGN, stage = stage,
            designText = safe, truncated = snapshot.length > TEXT_LIMIT)
        if (stage != old.stage) publish(id) else schedule(id)
    }

    fun generation(id: String, status: String) = synchronized(lock) {
        val old = latest[id] ?: return@synchronized
        latest[id] = old.copy(phase = DesktopImageProcessPhase.GENERATION, stage = "图片生成",
            generationStatus = DesktopDiagnosticScrubber("").text(status, 2048))
        if (old.phase != DesktopImageProcessPhase.GENERATION) publish(id) else schedule(id)
    }

    fun flush(id: String) = synchronized(lock) { publish(id) }

    fun retain(ids: Set<String>) = synchronized(lock) {
        (latest.keys - ids).forEach { pending.remove(it)?.cancel(); latest.remove(it) }
        mutable.value = mutable.value.filterKeys { it in ids }
    }

    private fun schedule(id: String) {
        if (id in pending) return
        pending[id] = scope.launch {
            delay(80)
            synchronized(lock) { pending.remove(id); publish(id) }
        }
    }

    private fun publish(id: String) {
        pending.remove(id)?.cancel()
        latest[id]?.let { mutable.value = mutable.value + (id to it) }
    }

    companion object { const val TEXT_LIMIT = 65_536 }
}

/** Keeps the existing callable report contract while carrying task-owned process callbacks. */
internal class DesktopImageTaskReporter(
    private val status: (String) -> Unit,
    private val designSnapshot: (String) -> Unit,
    private val generationStatus: (String) -> Unit,
) : (String) -> Unit {
    override fun invoke(message: String) = status(message)
    fun design(snapshot: String) { designSnapshot(snapshot); status("正在设计 Prompt") }
    fun generation(message: String) { generationStatus(message); status(message) }
}

internal fun ((String) -> Unit).designSnapshot(snapshot: String, credential: String = "") {
    if (this is DesktopImageTaskReporter) design(DesktopDiagnosticScrubber(credential).text(snapshot, Int.MAX_VALUE)) else invoke("正在设计 Prompt")
}

internal fun ((String) -> Unit).generationStatus(status: String) {
    if (this is DesktopImageTaskReporter) generation(status) else invoke(status)
}
