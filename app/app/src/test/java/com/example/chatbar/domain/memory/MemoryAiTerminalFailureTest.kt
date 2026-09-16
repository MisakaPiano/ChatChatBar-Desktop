package com.example.chatbar.domain.memory

import com.example.chatbar.domain.prompt.AiTaskFailureKind
import com.example.chatbar.domain.prompt.AiTaskRefusalException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryAiTerminalFailureTest {
    @Test
    fun refusalAndCancellationNeverConsumeOutputRetryBudget() = runBlocking {
        listOf(
            AiTaskRefusalException(AiTaskFailureKind.REFUSAL),
            AiTaskRefusalException(AiTaskFailureKind.CONTENT_FILTER),
            CancellationException("fixture cancellation")
        ).forEach { terminal ->
            var calls = 0
            val caught = runCatching {
                retryMemoryAiOutput<Unit>(5, MemoryAiTaskStage.HEAD) { _, _ ->
                    calls++
                    throw terminal
                }
            }.exceptionOrNull()
            assertSame(terminal, caught)
            assertEquals(1, calls)
        }
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
