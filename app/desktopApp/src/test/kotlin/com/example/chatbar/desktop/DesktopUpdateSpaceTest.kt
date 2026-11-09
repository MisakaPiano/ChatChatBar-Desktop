package com.example.chatbar.desktop

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopUpdateSpaceTest {
    @Test fun `space detail accepts only matching local cache and bounded numeric counts`() {
        val cache = Files.createTempDirectory("update-space-").toAbsolutePath()
        try {
            fun value(path: String = cache.toString(), required: JsonPrimitive = JsonPrimitive(616814014L), available: Long = 461258752L) = buildJsonObject {
                put("cache", path); put("requiredBytes", required); put("availableBytes", available)
            }
            val space = assertNotNull(desktopUpdateSpace(value(), cache))
            assertEquals(616814014L, space.required); assertEquals(461258752L, space.available)
            val shown = desktopUpdateSpaceMessage(space)
            assertTrue(cache.toString() in shown); assertTrue("616814014" in shown); assertTrue("461258752" in shown)
            assertNull(desktopUpdateSpace(value(path = cache.resolve("foreign").toString()), cache))
            assertNull(desktopUpdateSpace(value(required = JsonPrimitive("616814014")), cache))
            assertNull(desktopUpdateSpace(value(available = -1), cache))
            assertNull(desktopUpdateSpace(value(available = 616814014), cache))
        } finally { Files.delete(cache) }
    }
}
