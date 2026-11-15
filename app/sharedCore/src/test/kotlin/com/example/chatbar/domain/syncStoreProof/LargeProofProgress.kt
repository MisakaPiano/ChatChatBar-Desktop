package com.example.chatbar.domain.syncStoreProof

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** Diagnostic samples only. The existing proof owns the sampler job and every success assertion. */
internal class LargeProofProgress(private val kind: String, private val counters: () -> Map<String, Long> = { emptyMap() }) {
    private data class Phase(val name: String, val started: Long)
    private val started = System.nanoTime()
    private val current = AtomicReference(Phase("START", started))
    private val nextSample = AtomicLong(started + SAMPLE_NANOS)

    init {
        require(kind == "CLOUD" || kind == "FILE")
        emit("STAGE", current.get(), started)
    }

    fun stage(name: String) {
        require(name.matches(Regex("[A-Z][A-Z_0-9]{0,63}")))
        val now = System.nanoTime()
        val phase = Phase(name, now)
        current.set(phase)
        emit("STAGE", phase, now)
    }

    /** Called by the proof's existing 100ms memory/disk sampler; creates no thread or file. */
    fun sample() {
        val now = System.nanoTime()
        val due = nextSample.get()
        if (now >= due && nextSample.compareAndSet(due, now + SAMPLE_NANOS)) emit("SAMPLE", current.get(), now)
    }

    @Synchronized private fun emit(event: String, phase: Phase, now: Long) {
        val vm = Runtime.getRuntime()
        val diagnosticsClass = LargeProofProgress::class.java.name
        val output = buildJsonObject {
            put("kind", kind); put("event", event); put("phase", phase.name)
            put("elapsedMillis", (now - started).coerceAtLeast(0L) / 1_000_000L)
            put("phaseElapsedMillis", (now - phase.started).coerceAtLeast(0L) / 1_000_000L)
            put("heapUsedBytes", vm.totalMemory() - vm.freeMemory())
            try {
                put("counters", buildJsonObject {
                    counters().entries.take(16).forEach { (key, value) ->
                        require(key.matches(Regex("[a-zA-Z][a-zA-Z0-9]{0,47}")))
                        put(key, value)
                    }
                })
            } catch (failure: Exception) {
                put("counterDiagnosticErrorType", failure.javaClass.name)
            }
            if (event == "SAMPLE" && now - phase.started >= STACK_AFTER_NANOS) {
                try {
                    val cpu = ManagementFactory.getThreadMXBean()
                    put("executionLocations", buildJsonArray {
                        Thread.getAllStackTraces().entries.asSequence().filter { (_, frames) ->
                            frames.any { it.className.startsWith(PROJECT) && !it.className.startsWith(diagnosticsClass) }
                        }.sortedBy { it.key.id }.take(8).forEach { (thread, frames) ->
                            add(buildJsonObject {
                                put("threadId", thread.id); put("state", thread.state.name)
                                if (cpu.isThreadCpuTimeSupported && cpu.isThreadCpuTimeEnabled)
                                    put("cpuNanos", cpu.getThreadCpuTime(thread.id))
                                put("frames", buildJsonArray {
                                    frames.asSequence().filter { frame ->
                                        !frame.className.startsWith(diagnosticsClass) && (frame.className.startsWith(PROJECT) ||
                                            frame.className.startsWith("java.io.") || frame.className.startsWith("java.nio.") ||
                                            frame.className.startsWith("sun.nio.") || frame.className.startsWith("org.bouncycastle."))
                                    }.take(8).forEach { frame ->
                                        add(JsonPrimitive("${frame.className}.${frame.methodName}:${frame.lineNumber}"))
                                    }
                                })
                            })
                        }
                    })
                } catch (failure: Exception) {
                    put("diagnosticErrorType", failure.javaClass.name)
                }
            }
        }
        // No thread names, filenames, exception messages, arguments or payload values are logged.
        System.out.println("CCB_GROUP_LARGE_PROGRESS $output")
        System.out.flush()
    }

    companion object {
        private const val PROJECT = "com.example.chatbar."
        private const val SAMPLE_NANOS = 60_000_000_000L
        private const val STACK_AFTER_NANOS = 120_000_000_000L
    }
}
