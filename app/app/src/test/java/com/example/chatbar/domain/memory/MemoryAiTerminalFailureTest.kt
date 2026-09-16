package com.example.chatbar.domain.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryAiTerminalFailureTest {
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
