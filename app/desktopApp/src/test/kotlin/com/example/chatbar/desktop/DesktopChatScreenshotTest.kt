package com.example.chatbar.desktop
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import kotlinx.coroutines.*
import java.nio.file.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopChatScreenshotTest {
    private fun message(id:String="m",text:String="旁白\n<n=\"Alice\"/>[**你好**]()")=ChatMessage(id,"s",MessageRole.ASSISTANT,text,createdAt=1,updatedAt=1)
    @Test fun `official block selection supports segment whole multi ordering and rejects foreign ids`() {
        val a=message();val b=message("b","第二条");val all=roleplayScreenshotBlockIds(a)+roleplayScreenshotBlockIds(b)
        var selected=toggleChatScreenshotSelection(emptySet(),all.first(),all.toSet())
        assertEquals(setOf(all.first()),selected)
        assertEquals(selected,toggleChatScreenshotSelection(selected,"foreign::text::0",all.toSet()))
        selected=toggleChatScreenshotMessageSelection(selected,roleplayScreenshotBlockIds(b),all.toSet())
        assertEquals(listOf(a,b),orderedChatScreenshotMessages(listOf(a,b),selected))
        assertEquals(roleplayScreenshotBlockIds(b).toSet(),cleanChatScreenshotSelection(selected,listOf(b)))
        assertTrue(roleplayScreenshotBlockIds(a.copy(role=MessageRole.USER)).contains(roleplayLegacyTextBlockId(a.id)))
        assertTrue(roleplayScreenshotBlockIds(a,true,listOf("v")).contains(roleplayVoiceBlockId(a.id,"v")))
    }
    @Test fun `independent renderer exports actual PNG for selected segments and whole message in both themes`(): Unit =runBlocking {
        val m=message();val state=DesktopPrimaryChatState(messages=listOf(m))
        for(mode in listOf(ThemeMode.LIGHT,ThemeMode.DARK)) {
            val colors=desktopSemanticColors(mode,DefaultThemeColorHsv,false)
            val ids=roleplayScreenshotBlockIds(m)
            val segment=DesktopChatScreenshotRenderer.render(DesktopScreenshotRequest("截图测试",state,listOf(m),setOf(ids.first())),colors,{error("unexpected resource")})
            val whole=DesktopChatScreenshotRenderer.render(DesktopScreenshotRequest("截图测试",state,listOf(m),ids.toSet()),colors,{error("unexpected resource")})
            assertEquals(640,segment.width);assertTrue(whole.height>=segment.height)
            val decoded=ImageIO.read(ByteArrayInputStream(whole.bytes));assertEquals(640,decoded.width);assertEquals(whole.height,decoded.height)
            assertTrue(whole.bytes.size>100)
            val evidence=Path.of("build/p11-completion-20261021/renderer-previews")
            Files.createDirectories(evidence);Files.write(evidence.resolve("renderer-${mode.name}.png"),whole.bytes)
        }
    }
    @Test fun `render rejects stale or unknown blocks before resource reads and enforces bounded plan`(): Unit =runBlocking {
        val m=message();var reads=0
        assertFailsWith<IllegalArgumentException>{DesktopChatScreenshotRenderer.render(DesktopScreenshotRequest("x",DesktopPrimaryChatState(),listOf(m),setOf("other::text::0")),desktopSemanticColors(ThemeMode.LIGHT,DefaultThemeColorHsv,false),{reads++;byteArrayOf()})}
        assertEquals(0,reads)
        assertFailsWith<IllegalArgumentException>{DesktopChatScreenshotRenderer.validatePlan(640,32001)}
        assertFailsWith<IllegalArgumentException>{DesktopChatScreenshotRenderer.validatePlan(1600,32000)}
        assertFailsWith<IllegalArgumentException>{DesktopChatScreenshotRenderer.validatePlan(0,10)}
    }
    @Test fun `PNG export never overwrites external target and removes only its own incomplete file`() {
        val root=Files.createTempDirectory("ccb-screenshot-export-")
        val existing=root.resolve("keep.png");val target=root.resolve("new.png")
        Files.writeString(existing,"sentinel")
        try {
            val png=DesktopScreenshotPng(DesktopImageEditing.png(java.awt.image.BufferedImage(2,3,java.awt.image.BufferedImage.TYPE_INT_RGB)),2,3)
            assertFailsWith<IllegalArgumentException>{DesktopChatScreenshotRenderer.save(png,existing)}
            assertEquals("sentinel",Files.readString(existing))
            DesktopChatScreenshotRenderer.save(png,target);assertContentEquals(png.bytes,Files.readAllBytes(target));assertEquals(3,ImageIO.read(target.toFile()).height)
            assertFalse(Files.list(root).use { s->s.anyMatch { it.fileName.toString().endsWith(".part") } })
            assertFalse(buildChatScreenshotFileName("a/b:*?",0).contains('/'))
        } finally {Files.deleteIfExists(target);Files.deleteIfExists(existing);Files.delete(root)}
    }
    @Test fun `screenshot reader is bounded and rejects documents and outside references`() {
        val root=Files.createTempDirectory("ccb-shot-reader-");val images=Files.createDirectory(root.resolve("images"))
        val file=images.resolve("fixture.png");Files.write(file,ByteArray(128))
        try {
            val reader=DesktopCharacterResourceStore(root)
            assertFailsWith<IllegalArgumentException>{reader.readScreenshotImageBytes("images/fixture.png",16)}
            assertFailsWith<IllegalArgumentException>{reader.readScreenshotImageBytes("../outside.png")}
            assertFailsWith<IllegalArgumentException>{reader.readScreenshotImageBytes("documents/fixture.png")}
            assertEquals(128,reader.readScreenshotImageBytes("images/fixture.png",128).size)
        } finally {Files.delete(file);Files.delete(images);Files.delete(root)}
    }
    @Test fun `captured message version must still match before preview or save`(): Unit =runBlocking {
        val f=DesktopAssistantImageActionTest.Fixture()
        try {
            f.initialize();val controller=f.c.primaryChatController;controller.refresh();controller.selectSession("session")
            val snapshot=controller.screenshotTimeline();assertTrue(snapshot.isNotEmpty());controller.verifyScreenshotMessages(snapshot)
            f.c.chatRepository.updateMessage(snapshot.first().copy(content="changed",updatedAt=System.currentTimeMillis()))
            assertFailsWith<IllegalStateException>{controller.verifyScreenshotMessages(snapshot)}
        } finally {f.close()}
    }
    @Test fun `export respects JPEG orientation without altering source bytes`():Unit=runBlocking {
        val output=java.io.ByteArrayOutputStream()
        ImageIO.write(java.awt.image.BufferedImage(80,40,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpeg",output)
        val jpeg=output.toByteArray()
        val exif=byteArrayOf(0xff.toByte(),0xe1.toByte(),0,34,
            'E'.code.toByte(),'x'.code.toByte(),'i'.code.toByte(),'f'.code.toByte(),0,0,
            'M'.code.toByte(),'M'.code.toByte(),0,42,0,0,0,8,0,1,0x01,0x12,0,3,0,0,0,1,0,6,0,0,0,0,0,0)
        val oriented=jpeg.copyOfRange(0,2)+exif+jpeg.copyOfRange(2,jpeg.size)
        val before=oriented.copyOf()
        val m=message(text="").copy(images=listOf("images/photo.jpg"))
        val request=DesktopScreenshotRequest("JPEG",DesktopPrimaryChatState(),listOf(m),setOf(roleplayImageBlockId(m.id,0)))
        val colors=desktopSemanticColors(ThemeMode.LIGHT,DefaultThemeColorHsv,false)
        val normal=DesktopChatScreenshotRenderer.render(request,colors,{jpeg})
        val rotated=DesktopChatScreenshotRenderer.render(request,colors,{oriented})
        assertTrue(rotated.height>normal.height,"portrait EXIF image must retain portrait aspect in exported PNG")
        assertContentEquals(before,oriented)
    }

}
