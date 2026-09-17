package com.example.chatbar.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFormatRepairServiceTest {
    @Test
    fun `repair stream allows long silent model responses`() {
        assertEquals(600L, MessageFormatRepairService.FORMAT_REPAIR_READ_TIMEOUT_SECONDS)
    }
}
