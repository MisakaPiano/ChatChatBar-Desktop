package com.example.chatbar.domain.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticChatImageJudgeAndroidTest {
    @Test
    fun judgeInitializesAndParsesCodeWrappersOnAndroid() {
        // Exercise Android's ICU regex engine, which differs from desktop JVM regex parsing.
        val verdict = """{"complete":true,"continuesStory":true,"refused":false}"""
        listOf(
            verdict,
            "```json\n$verdict\n```",
            "```\n$verdict\n```",
            "`json$verdict",
            "`json$verdict`",
            "  ```JSON$verdict```  "
        ).forEach { assertNull(AutomaticChatImageJudge.parseSkipReason(it)) }

        assertNotNull(AutomaticChatImageJudge.parseSkipReason(
            "```json\n" + """{"complete":true,"continuesStory":true,"refused":true}""" + "\n```"
        ))
        listOf("```json{} ```", "```json$verdict$verdict```", "Cannot judge. $verdict").forEach {
            assertTrue(runCatching { AutomaticChatImageJudge.parseSkipReason(it) }.isFailure)
        }
    }
}
