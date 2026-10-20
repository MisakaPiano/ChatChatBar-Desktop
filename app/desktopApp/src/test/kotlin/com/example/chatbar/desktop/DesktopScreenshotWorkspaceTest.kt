package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.test.*

class DesktopScreenshotWorkspaceTest {
    private fun message(id: String, role: MessageRole, content: String = id) = ChatMessage(id, "s", role, content, createdAt = 1, updatedAt = 1)

    @Test fun `groups preserve irregular repository order and do not invent source turns`() {
        val messages = listOf(message("a0",MessageRole.ASSISTANT),message("s0",MessageRole.SYSTEM),
            message("u1",MessageRole.USER),message("u2",MessageRole.USER),message("a1",MessageRole.ASSISTANT),message("a2",MessageRole.ASSISTANT))
        val groups = desktopScreenshotGroups(messages)
        assertEquals(listOf(null,"u1","u2"), groups.map { it.user?.id })
        assertEquals(listOf(2,1,3), groups.map { it.messages.size })
        assertEquals(messages,groups.flatMap { it.messages })
        assertTrue(groups.flatMap { it.messages }.all { it.sourceTurnId == null })
        assertTrue(desktopScreenshotGroups(emptyList()).isEmpty())
    }

    @Test fun `collapse and bulk selection retain official block identities while excluding recordings`() {
        val m = message("a",MessageRole.ASSISTANT,"旁白。<n=\"Alice\"/>“你好”。")
        val ids = desktopScreenshotBlockIds(m)
        val all = ids.toSet(); val voice = roleplayVoiceBlockId(m.id,"v1")
        var tree = DesktopScreenshotTreeState(setOf(ids.first()),setOf(m.id))
        tree = tree.toggleExpanded(m.id)
        assertTrue(tree.expanded.isEmpty()); assertEquals(setOf(ids.first()),tree.selected)
        tree = tree.toggleExpanded(m.id); assertEquals(setOf(m.id),tree.expanded)
        assertEquals(all,tree.selectAll(all).selected)
        assertEquals(all-tree.selected,tree.invert(all).selected)
        assertTrue(tree.clear().selected.isEmpty())
        assertEquals(listOf(m),orderedChatScreenshotMessages(listOf(m),tree.selected))
        assertEquals(tree.selected,cleanChatScreenshotSelection(tree.selected,listOf(m),true,mapOf(m.id to listOf("v1","v2"))))
    }

    @Test fun `initial owned window is eighty five percent and clamps to monitor work area`() {
        val parent = Rectangle(200,100,1200,800); val work=Rectangle(0,0,1920,1040)
        val bounds=desktopScreenshotWindowBounds(parent,work)
        assertEquals(1020,bounds.width);assertEquals(680,bounds.height);assertTrue(work.contains(bounds))
        val negativeMonitor=Rectangle(-1280,30,1280,690)
        assertTrue(negativeMonitor.contains(desktopScreenshotWindowBounds(Rectangle(-2000,-100,2400,1400),negativeMonitor)))
        assertTrue(Rectangle(0,0,280,180).contains(desktopScreenshotWindowBounds(parent,Rectangle(0,0,280,180))))
    }

    @Test fun `long preview stays bounded and maintains pointer anchor and center on resize`() {
        val p=DesktopScreenshotViewport(320,25000);p.resize(400,600);p.manualZoom(2.0);p.scrollTo(100.0,7000.0)
        val anchorX=(p.x+100)/p.zoom;val anchorY=(p.y+140)/p.zoom
        p.manualZoom(4.0,100.0,140.0)
        assertEquals(anchorX,(p.x+100)/p.zoom,.001);assertEquals(anchorY,(p.y+140)/p.zoom,.001)
        val centerY=(p.y+p.height/2.0)/p.zoom
        p.resize(500,800);assertEquals(centerY,(p.y+p.height/2.0)/p.zoom,.001)
        p.manualZoom(10000.0);assertEquals(8.0,p.zoom)
        p.scrollTo(Double.MAX_VALUE,Double.MAX_VALUE);assertEquals(p.contentHeight-p.height,p.y)
        p.manualZoom(.00001);assertEquals(.01,p.zoom);assertEquals(0.0,p.x)
        assertFailsWith<IllegalArgumentException>{p.manualZoom(Double.NaN)}
        assertFailsWith<IllegalArgumentException>{DesktopScreenshotViewport(1600,32000)}
    }

    @Test fun `both scrollbar adapters move independent axes and fit modes retain bounded extents`():Unit=runBlocking {
        val p=DesktopScreenshotViewport(640,10000);p.resize(400,500);p.manualZoom(2.0)
        p.adapter(true).scrollTo(120.0);p.adapter(false).scrollTo(3000.0)
        assertEquals(120.0,p.x);assertEquals(3000.0,p.y)
        p.fitTo(DesktopScreenshotFit.WIDTH);assertEquals(400.0,p.contentWidth,.001)
        p.fitTo(DesktopScreenshotFit.WINDOW);assertTrue(p.contentWidth<=400.001);assertTrue(p.contentHeight<=500.001)
        p.manualZoom(1.0);assertEquals(640.0,p.contentWidth);assertEquals(10000.0,p.contentHeight)
    }

    @Test fun `resource identity changes reject stale export and cancellation keeps PNG retryable`():Unit=runBlocking {
        val m=message("u",MessageRole.USER,"inline fixture").copy(images=listOf("images/test.png"))
        val original=DesktopImageEditing.png(BufferedImage(4,4,BufferedImage.TYPE_INT_RGB))
        val changed=original.copyOf().also { it[it.lastIndex]=(it.last()+1).toByte() }
        val colors=desktopSemanticColors(ThemeMode.LIGHT,DefaultThemeColorHsv,false)
        val png=DesktopChatScreenshotRenderer.render(DesktopScreenshotRequest("fixture",DesktopPrimaryChatState(),listOf(m),roleplayScreenshotBlockIds(m).toSet()),colors,{original})
        val before=png.bytes.copyOf();val root=Files.createTempDirectory("ccb-shot-r2-");val target=root.resolve("new.png")
        try {
            var reads=original;var proofs=0
            assertNull(saveDesktopScreenshotVerified(png,{proofs++},{reads}){null});assertEquals(1,proofs)
            assertFailsWith<IllegalStateException>{saveDesktopScreenshotVerified(png,{}, {reads}) { reads=changed;target }}
            assertFalse(Files.exists(target))
            reads=original
            assertEquals(target,saveDesktopScreenshotVerified(png,{}, {reads}){target})
            assertContentEquals(before,Files.readAllBytes(target));assertContentEquals(before,png.bytes)
            assertFailsWith<IllegalArgumentException>{saveDesktopScreenshotVerified(png,{}, {reads}){target}}
            assertContentEquals(before,Files.readAllBytes(target))
        } finally { Files.deleteIfExists(target);Files.delete(root) }
    }

    @Test fun `actual message mutation during chooser rejects save before file creation`():Unit=runBlocking {
        val f=DesktopAssistantImageActionTest.Fixture();val root=Files.createTempDirectory("ccb-shot-version-r2-");val target=root.resolve("stale.png")
        try {
            f.initialize();val c=f.c.primaryChatController;c.refresh();c.selectSession("session")
            val messages=c.screenshotTimeline();val png=DesktopScreenshotPng(byteArrayOf(1),640,1)
            assertFailsWith<IllegalStateException>{saveDesktopScreenshotVerified(png,{c.verifyScreenshotMessages(messages)},{error("no resource")}) {
                f.c.chatRepository.updateMessage(messages.first().copy(content="changed version",updatedAt=2));target
            }}
            assertFalse(Files.exists(target));assertTrue(f.requests.isEmpty())
        }finally{f.close();Files.deleteIfExists(target);Files.delete(root)}
    }

    @Test fun `wheel scrolling and control zoom preserve pointed content without changing source dimensions`() {
        val p=DesktopScreenshotViewport(640,10000);p.resize(400,500);p.manualZoom(2.0)
        p.scrollTo(0.0,0.0) // Zoom preserves the prior center; explicitly start this wheel fixture at origin.
        p.wheel(1.0,2.0,false,false,100.0,100.0);assertEquals(80.0,p.y)
        p.wheel(0.0,2.0,false,true,100.0,100.0);assertTrue(p.x>=80.0)
        val pointed=(p.y+100.0)/p.zoom
        p.wheel(0.0,-1.0,true,false,100.0,100.0)
        assertEquals(2.3,p.zoom,.001);assertEquals(pointed,(p.y+100.0)/p.zoom,.001)
        assertEquals(640,p.imageWidth);assertEquals(10000,p.imageHeight)
    }

    @Test fun `cancelling suspended export chooser never creates target and retains PNG`():Unit=runBlocking {
        val png=DesktopScreenshotPng(byteArrayOf(1,2,3),640,1)
        val root=Files.createTempDirectory("ccb-shot-cancel-r2-");val target=root.resolve("cancelled.png")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            val job=launch { saveDesktopScreenshotVerified(png,{}, {error("no resources")}) {entered.complete(Unit);release.await();target} }
            entered.await();job.cancelAndJoin()
            assertFalse(Files.exists(target));assertContentEquals(byteArrayOf(1,2,3),png.bytes)
        }finally{release.complete(Unit);Files.deleteIfExists(target);Files.delete(root)}
    }
}
