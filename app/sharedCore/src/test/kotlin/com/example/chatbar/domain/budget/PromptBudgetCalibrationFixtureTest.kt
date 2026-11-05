package com.example.chatbar.domain.budget

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Invented text only. The cloud calibration consumes the actual Kotlin estimator's output. */
class PromptBudgetCalibrationFixtureTest {
    @Test fun `export synthetic text measurements for independent tokenizer calibration`() {
        val samples = linkedMapOf(
            "empty" to "",
            "english" to "A quiet station. Two travelers compare the map before the train arrives.",
            "han" to "清晨的车站里，两位旅客正在核对地图，等待下一班列车。",
            "mixed" to "小宁 checks platform 12：下一班 08:45，带上 map.json。",
            "unicode" to "🙂🚉 cafe\u0301 カタカナ 한글 𠮷",
            "structured" to "{\"scene\":\"station\",\"people\":[\"小宁\",\"Traveler\"],\"turn\":12}\n",
            "repeated" to "合成场景 Synthetic scene 12.\n".repeat(250),
        )
        val input = buildJsonObject {
            put("schema", 1); put("kind", "SYNTHETIC_TEXT_TOKENIZER_CALIBRATION")
            put("estimator", WeightedCharacterTokenEstimator.METHOD_ID)
            put("samples", buildJsonArray {
                samples.forEach { (id, text) ->
                    val estimate = WeightedCharacterTokenEstimator.estimate(text)
                    assertEquals(text.length, estimate.utf16Units)
                    add(buildJsonObject {
                        put("id", id); put("text", text)
                        put("estimatedTokens", estimate.tokens); put("utf16Units", estimate.utf16Units)
                    })
                }
            })
        }
        val output = Path.of(System.getProperty("ccb.prompt.calibration.output", "build/reports/prompt-budget-calibration/input.json"))
        Files.createDirectories(output.parent)
        Files.writeString(output, input.toString())
    }
}
