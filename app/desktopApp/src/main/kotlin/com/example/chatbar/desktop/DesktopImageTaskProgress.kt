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

    fun design(id: String, snapshot: String, credential: String = "") = synchronized(lock) {
        val old = latest[id] ?: return@synchronized
        val safe = boundedDesignText(snapshot, credential)
        // Only complete, known transcript section headers; bracketed model prose is content.
        val candidate = STAGE_HEADER.findAll(safe).lastOrNull()?.groupValues?.get(1)
        val stage = candidate?.takeIf { STAGES.indexOf(it) >= STAGES.indexOf(old.stage) } ?: old.stage
        latest[id] = old.copy(phase = DesktopImageProcessPhase.DESIGN, stage = stage,
            designText = safe, truncated = snapshot.length > TEXT_LIMIT)
        if (stage != old.stage) publish(id) else schedule(id)
    }

    fun generation(id: String, status: String) = synchronized(lock) {
        val old = latest[id] ?: return@synchronized
        latest[id] = old.copy(phase = DesktopImageProcessPhase.GENERATION, stage = "图片生成",
            generationStatus = DesktopDiagnosticScrubber("").text(status.take(2048), 2048))
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

    companion object {
        const val TEXT_LIMIT = 65_536
        private const val HALF = TEXT_LIMIT / 2
        private const val OMITTED = "\n… [truncated]\n"
        private val STAGES = listOf("AI 图片画面设计", "AI 检索词规划", "AI 修改需求检索规划",
            "Danbooru 词条库批量搜索", "本地 NovelAI 法典召回", "最终 Prompt 设计", "JSON 修复")
        private val STAGE_HEADER = Regex("(?:^|\\n\\n)【(${STAGES.joinToString("|") { Regex.escape(it) }})】\\n")

        internal fun boundedDesignText(snapshot: String, credential: String): String {
            // Bound work before any regex/redaction. Include key-length lookahead so a key
            // crossing the retained prefix boundary is redacted before clipping.
            if (credential.length > TEXT_LIMIT) return "[REDACTED]$OMITTED"
            val scrubber = DesktopDiagnosticScrubber(credential)
            if (snapshot.length <= TEXT_LIMIT) return scrubber.text(snapshot, TEXT_LIMIT)
            val prefix = scrubber.text(snapshot.take(HALF + credential.length), Int.MAX_VALUE).take(HALF)
            val tail = scrubber.text(snapshot.takeLast(HALF + credential.length), Int.MAX_VALUE).takeLast(HALF)
            // Start the tail at a complete line, never in the middle of a credential/token.
            val line = tail.indexOf('\n')
            val suffix = if (line < 0) "" else tail.substring(line + 1)
            return prefix + OMITTED + suffix
        }
    }
}

/** Keeps the existing callable report contract while carrying task-owned process callbacks. */
internal class DesktopImageTaskReporter(
    private val status: (String) -> Unit,
    private val designSnapshot: (String, String) -> Unit,
    private val generationStatus: (String) -> Unit,
) : (String) -> Unit {
    override fun invoke(message: String) = status(message)
    fun design(snapshot: String, credential: String) { designSnapshot(snapshot, credential); status("正在设计 Prompt") }
    fun generation(message: String) { generationStatus(message); status(message) }
}

internal fun ((String) -> Unit).designSnapshot(snapshot: String, credential: String = "") {
    if (this is DesktopImageTaskReporter) design(snapshot, credential) else invoke("正在设计 Prompt")
}

internal fun ((String) -> Unit).generationStatus(status: String) {
    if (this is DesktopImageTaskReporter) generation(status) else invoke(status)
}
