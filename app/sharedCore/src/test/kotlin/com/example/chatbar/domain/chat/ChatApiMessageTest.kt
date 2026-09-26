package com.example.chatbar.domain.chat

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatApiMessageTest {
    @Test
    fun textContentPreservesRoleAndLogicalString() {
        val message = ChatApiMessage.text("assistant", "exact text")

        assertEquals("assistant", message.role)
        assertEquals("exact text", message.content.jsonPrimitive.content)
    }

    @Test
    fun imageOnlyContentOmitsEmptyTextPart() {
        val content = ChatApiMessage.withImage("user", "", "abc").content.jsonArray

        assertEquals(1, content.size)
        assertEquals("image_url", content.first().jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "text" })
    }

    @Test
    fun multimodalContentKeepsTextBeforeImagesInInputOrder() {
        val content = ChatApiMessage.withImages("user", "describe this", listOf("abc", "def")).content.jsonArray

        assertEquals(listOf("text", "image_url", "image_url"), content.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        assertEquals("describe this", content.first().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,abc", content[1].jsonObject
            .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,def", content[2].jsonObject
            .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }
}
