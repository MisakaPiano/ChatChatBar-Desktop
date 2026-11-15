package com.example.chatbar.domain.lanSync

import kotlinx.serialization.json.*
import kotlin.test.*

class LanSyncCanonicalHashTest {
    @Test fun incrementalHashRetainsTheExistingSortedCompactUtf8BaselineIdentity() {
        val value = buildJsonObject {
            put("b", buildJsonArray { add(1); add("中文\n🚀") })
            put("a", buildJsonObject { put("x", true); put("quote", "\"\\\t") })
        }
        assertEquals("f870db6f2a41469505da8f9aaa38648089b5458d69ea74c670afbced32bea81c", lanSyncHash(value))
        assertEquals(lanSyncHash(JsonArray(listOf(value, JsonNull, JsonPrimitive(8)))),
            lanSyncHashSequence(sequenceOf(value, JsonNull, JsonPrimitive(8))))
        assertEquals(lanSyncHash(JsonArray(emptyList())), lanSyncHashSequence(emptySequence()))
    }
}
