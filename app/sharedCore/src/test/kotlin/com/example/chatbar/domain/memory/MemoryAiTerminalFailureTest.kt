package com.example.chatbar.domain.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryAiTerminalFailureTest {
    @Test
    fun responseBudgetFailureNeverConsumesTheRetryBudget() = runBlocking {
        var calls = 0
        val failure = runCatching {
            retryMemoryAiOutput(3, MemoryAiTaskStage.EPISODE) { _, _ ->
                calls++
                throw com.example.chatbar.domain.prompt.AiTaskResponseBudgetException()
            }
        }.exceptionOrNull()
        assertTrue(failure is com.example.chatbar.domain.prompt.AiTaskResponseBudgetException)
        assertEquals(1, calls)
    }
    @Test
    fun ordinaryValidationFailuresKeepExistingBoundedRetries() = runBlocking {
        var calls = 0
        val result = retryMemoryAiOutput(3, MemoryAiTaskStage.EPISODE) { _, _ ->
            calls++
            if (calls < 3) error("fixture invalid JSON")
            "valid"
        }
        assertEquals("valid", result)
        assertEquals(3, calls)
    }
}
