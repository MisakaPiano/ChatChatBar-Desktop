package com.example.chatbar.desktop

import com.example.chatbar.domain.image.*
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Explicit, guarded Phase-7 runner. No credential arguments, environment variables or output. */
internal object DesktopNovelAiPhase7Smoke {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        check(System.getProperty("ccb.phase7.liveConfirmed") == "true") { "Live smoke requires explicit user confirmation" }
        val root = Path.of(requireNotNull(System.getProperty("ccb.phase7.outputRoot"))).toAbsolutePath()
        val prompt = requireNotNull(System.getProperty("ccb.phase7.smokePrompt"))
        check(prompt.isNotBlank())
        java.nio.file.Files.createDirectories(root)
        val resolved = DesktopDataRootResolution.Resolved(root, DesktopDataRootProvenance.CLI_OVERRIDE, root.resolve("bootstrap.json"))
        runDesktopApplicationWithDataRootOwnership(resolved) { runBlocking {
            val container = DesktopAppContainer(resolved)
            try {
                val id = container.taskRuntime.launchNovelAiSmoke(container.novelAiRuntime, true,
                    NovelAiPromptPlan(prompt, emptyList()),
                    NovelAiGenerationSettings(model = NovelAiImageModel.V4_5_FULL,
                        aspectRatio = NovelAiAspectRatio.SQUARE, count = 1, steps = 28))
                val terminal = withTimeout(300_000) {
                    container.taskRuntime.tasks.first { entries -> entries.any { it.taskId == id && it.status != DesktopTaskStatus.RUNNING } }
                        .single { it.taskId == id }
                }
                println("P7 NovelAI guarded smoke: ${terminal.status}")
                check(terminal.status == DesktopTaskStatus.COMPLETED) { "Live smoke stopped; do not retry automatically" }
            } finally { container.close() }
        } }
    }
}
