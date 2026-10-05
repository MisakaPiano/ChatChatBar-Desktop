package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiV5TextPromptPolicyTest {
    @Test
    fun singleQuotesPreserveContractionsAndPossessives() {
        val source = "girl's sign says 'don't stop', ‘it’s fine’, \"OK\", “Yes”, 「Bye」"
        assertEquals(
            "$source, teXt: don't stop\n\nit’s fine\n\nOK\n\nYes\n\nBye",
            expand(source).baseCaption
        )
        assertEquals("girl's hat, boy's coat", expand("girl's hat, boy's coat").baseCaption)
    }

    @Test
    fun cjkReversesWithinEachFieldButPreservesCharacterOrder() {
        val base = "“你好” “再见”"
        val first = "'谢谢' '晚安'"
        val second = "「早上好」 「欢迎」"
        val result = expand(base, first, second)
        assertEquals("$base, teXt: 再见\n\n你好\n\n晚安\n\n谢谢\n\n欢迎\n\n早上好", result.baseCaption)
        assertEquals(listOf(first, second), result.characterCaptions.map { it.prompt })
    }

    @Test
    fun cjkThresholdUsesAllFieldsAndIsStrictlyGreaterThanThirtyPercent() {
        val source = "\"中文日\" \"abcdefg\""
        assertEquals("$source, teXt: 中文日\n\nabcdefg", expand(source).baseCaption)
        assertEquals("$source, teXt: abcdefg\n\n中文日\n\n中", expand(source, "\"中\"").baseCaption)
    }

    @Test
    fun explicitTextDetectionMatchesOfficialBoundaries() {
        for (prefix in listOf("Text:", "teXt:", "。TEXT:", "[text:", "text:", " text:")) {
            val source = "$prefix manual \"quoted\""
            assertEquals(source, expand(source).baseCaption)
        }
        for (prefix in listOf("text::", "text :", "context:", "(text:", "中文text:")) {
            val source = "$prefix \"quoted\""
            assertEquals("$source, teXt: quoted", expand(source).baseCaption)
        }
        assertEquals("says \"base\"", expand("says \"base\"", "Text: manual").baseCaption)
    }

    @Test
    fun onlyFirstBaseChunkIsExpandedAndRandomizerPipesArePreserved() {
        val source = "||red|blue|| sign \"OPEN\",  |second \"SKIP\""
        assertEquals(
            "||red|blue|| sign \"OPEN\", teXt: OPEN|second \"SKIP\"",
            expand(source).baseCaption
        )
    }

    @Test
    fun unmatchedEmptyAndUnsupportedQuotesDoNotProduceText() {
        val source = "『ignored』, \"\", ‘ ’, \"unfinished"
        assertEquals(source, expand(source).baseCaption)
    }

    @Test
    fun repeatedTextIsRetainedAndExpansionIsIdempotent() {
        val result = expand("\"Hello\" \"Hello\"")
        assertEquals("\"Hello\" \"Hello\", teXt: Hello\n\nHello", result.baseCaption)
        assertEquals(result, NovelAiV5TextPromptPolicy.apply(result, NovelAiImageModel.V5_FULL))
    }

    @Test
    fun onlyPositiveV5RequestCopyChanges() {
        val original = NovelAiPromptPlan(
            baseCaption = "",
            characterCaptions = listOf(
                NovelAiCharacterCaption("'Hello'", DesignedCharacterCenter(0.5f, 0.5f), "\"negative\"")
            ),
            negativePrompt = "Text: negative",
            stylePrompt = "stored style"
        )
        assertEquals(original.copy(baseCaption = "teXt: Hello"), NovelAiV5TextPromptPolicy.apply(original, NovelAiImageModel.V5_FULL))
        assertEquals(original, NovelAiV5TextPromptPolicy.apply(original, NovelAiImageModel.V4_5_FULL))
        assertEquals("", original.baseCaption)
    }

    private fun expand(base: String, vararg characters: String): NovelAiPromptPlan =
        NovelAiV5TextPromptPolicy.apply(
            NovelAiPromptPlan(
                baseCaption = base,
                characterCaptions = characters.mapIndexed { index, text ->
                    NovelAiCharacterCaption(text, DesignedCharacterCenter(1f - index * 0.2f, 0.5f))
                }
            ),
            NovelAiImageModel.V5_FULL
        )
}
