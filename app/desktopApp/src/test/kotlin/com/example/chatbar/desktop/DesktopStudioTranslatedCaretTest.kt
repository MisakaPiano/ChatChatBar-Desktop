@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.example.chatbar.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.*
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopStudioTranslatedCaretTest {
    @Test fun `translated multiline and wrapped source caret remains one font row including end of text`() {
        SwingUtilities.invokeAndWait {
            var annotated: TextLayoutResult? = null
            var glyph: TextLayoutResult? = null
            val source = "alpha beta gamma delta,\nnext source row"
            val scene = ImageComposeScene(320, 260) {
                val measurer = rememberTextMeasurer()
                glyph = measurer.measure(AnnotatedString("M"), TextStyle(fontSize = 14.sp))
                annotated = measurer.measure(AnnotatedString(source), TextStyle(fontSize = 14.sp, lineHeight = 34.sp,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None)),
                    constraints = Constraints(maxWidth = 110))
                StudioPromptTextEditor(androidx.compose.ui.text.input.TextFieldValue(source), {}, true, emptyList())
            }
            try {
                repeat(3) { scene.render().close() }
                val layout = assertNotNull(annotated)
                val font = assertNotNull(glyph)
                assertTrue(layout.lineCount > 2)
                for (offset in 0..source.length) {
                    val caret = studioPromptCaretRect(layout, offset, font)
                    val line = layout.getLineForOffset(offset)
                    assertEquals(font.size.height.toFloat(), caret.height, .01f)
                    assertTrue(caret.height < layout.getLineBottom(line) - layout.getLineTop(line), "Annotation leading must not stretch caret")
                    assertEquals(layout.getCursorRect(offset).left, caret.left, .01f)
                    assertEquals(layout.getLineBaseline(line), caret.top + font.getLineBaseline(0), .01f)
                    val visible = studioPromptSourceBand(layout, offset, font)
                    assertEquals(caret.left, visible.left, .01f); assertEquals(caret.right, visible.right, .01f)
                    assertEquals(maxOf(layout.getLineTop(line), caret.top), visible.top, .01f)
                    assertEquals(minOf(layout.getLineBottom(line), caret.bottom), visible.bottom, .01f)
                    assertTrue(visible.top >= layout.getLineTop(line) && visible.bottom <= layout.getLineBottom(line))
                    assertTrue(visible.height <= font.size.height && visible.height < layout.getLineBottom(line) - layout.getLineTop(line))
                }
                assertEquals(source, layout.layoutInput.text.text)
            } finally { scene.close() }
        }
    }
}
