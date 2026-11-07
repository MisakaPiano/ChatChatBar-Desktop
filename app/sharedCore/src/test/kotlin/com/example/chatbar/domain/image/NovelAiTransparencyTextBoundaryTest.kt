package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.NovelAiPromptAuthority
import kotlinx.serialization.json.*
import kotlin.test.*

class NovelAiTransparencyTextBoundaryTest {
    @Test fun `actual transparent request inserts its hint before explicit text across official whitespace boundaries`() {
        val whitespace = "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"
        val service = NovelAiImageService()
        val tag = NovelAiPromptAuthority.NOVELAI_TRANSPARENT_BACKGROUND_TAG
        for (separator in whitespace) {
            val source = NovelAiPromptPlan(baseCaption = "scene${separator}teXt: literal caption | later chunk")
            for (effort in NovelAiEffort.entries) {
                val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
                    effort = effort, transparentBackground = true)
                val wire = Json.parseToJsonElement(service.buildRequestBody(source, settings.imageSize(), settings)).jsonObject
                assertEquals("scene, $tag, teXt: literal caption | later chunk",
                    wire.getValue("input").jsonPrimitive.content,
                    "explicit text boundary U+${separator.code.toString(16)} / $effort")
                assertEquals("scene${separator}teXt: literal caption | later chunk", source.baseCaption)
                for (inactive in listOf(settings.copy(transparentBackground = false), settings.copy(model = NovelAiImageModel.V4_5_FULL))) {
                    val unmodified = Json.parseToJsonElement(service.buildRequestBody(source, inactive.imageSize(), inactive)).jsonObject
                    assertEquals(source.baseCaption, unmodified.getValue("input").jsonPrimitive.content)
                }
            }
        }
    }
}
