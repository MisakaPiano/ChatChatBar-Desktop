package com.example.chatbar.desktop

import kotlin.test.*

class DesktopWindowChromeTest {
    private val layout = DesktopChromeLayout(800f, 600f, ChromeRect(0f, 0f, 800f, 44f),
        listOf(ChromeRect(80f, 8f, 168f, 40f)),
        mapOf(ChromeHit.MINIMIZE to ChromeRect(662f, 0f, 708f, 44f),
            ChromeHit.MAXIMIZE to ChromeRect(708f, 0f, 754f, 44f),
            ChromeHit.CLOSE to ChromeRect(754f, 0f, 800f, 44f)), ChromeRect(8f, 8f, 40f, 40f))
    private fun hit(x: Float, y: Float, maximized: Boolean = false) =
        DesktopChromeHitTest.hit(x, y, 800f, 600f, 8f, maximized, layout)

    @Test fun `every responsive mode retains four discoverable routes and exactly one selected`() {
        for (size in DesktopShellSize.entries) for (route in DesktopPrimaryRoute.entries) {
            val p = desktopTitleBarPresentation(size, route, false)
            assertEquals(DesktopPrimaryRoute.entries.toList(), p.routes.map { it.route })
            assertEquals(listOf(route), p.routes.filter { it.selected }.map { it.route })
            assertTrue(p.routes.all { it.enabled })
        }
    }
    @Test fun `migration locks routes without removing title bar or caption reserve`() {
        for (size in DesktopShellSize.entries) {
            val p = desktopTitleBarPresentation(size, DesktopPrimaryRoute.DATA, true)
            assertEquals(4, p.routes.size)
            assertTrue(p.routes.none { it.enabled })
            assertEquals(DesktopPrimaryRoute.DATA, p.routes.single { it.selected }.route)
            assertTrue(p.captionWidthDp >= 42)
        }
    }
    @Test fun `compact controls reserve captions and fit 330 effective pixels for both languages`() {
        for (language in DesktopUiLanguage.entries) {
            val p = desktopTitleBarPresentation(DesktopShellSize.COMPACT, DesktopPrimaryRoute.CHAT, false)
            assertFalse(p.showRouteLabels)
            assertFalse(p.showBrandName)
            assertTrue(p.minimumContentWidthDp <= 330, language.name)
        }
        assertTrue(desktopTitleBarPresentation(DesktopShellSize.MEDIUM, DesktopPrimaryRoute.CHAT, false).minimumContentWidthDp <= 720)
        assertTrue(desktopTitleBarPresentation(DesktopShellSize.WIDE, DesktopPrimaryRoute.CHAT, false).minimumContentWidthDp <= 1100)
    }
    @Test fun `drag and system icon areas preserve native contracts while route remains client`() {
        assertEquals(ChromeHit.CAPTION, hit(400f, 20f))
        assertEquals(ChromeHit.SYSTEM_MENU, hit(20f, 20f))
        assertEquals(ChromeHit.CLIENT, hit(100f, 20f))
        assertEquals(ChromeHit.CLIENT, hit(400f, 100f))
    }
    @Test fun `caption visuals have their exact native hit codes`() {
        assertEquals(ChromeHit.MINIMIZE, hit(680f, 20f))
        assertEquals(ChromeHit.MAXIMIZE, hit(730f, 20f))
        assertEquals(9, hit(730f, 20f).nativeValue)
        assertEquals(ChromeHit.CLOSE, hit(780f, 20f))
    }
    @Test fun `all eight resize directions precede title and caption areas`() {
        val points = mapOf(ChromeHit.LEFT to (1f to 100f), ChromeHit.RIGHT to (799f to 100f),
            ChromeHit.TOP to (400f to 1f), ChromeHit.BOTTOM to (400f to 599f),
            ChromeHit.TOP_LEFT to (1f to 1f), ChromeHit.TOP_RIGHT to (799f to 1f),
            ChromeHit.BOTTOM_LEFT to (1f to 599f), ChromeHit.BOTTOM_RIGHT to (799f to 599f))
        points.forEach { (expected, point) -> assertEquals(expected, hit(point.first, point.second)) }
    }
    @Test fun `maximized suppresses every resize edge while preserving captions`() {
        assertEquals(ChromeHit.CAPTION, hit(400f, 1f, true))
        assertEquals(ChromeHit.CLOSE, hit(799f, 1f, true))
        assertEquals(ChromeHit.CLIENT, hit(1f, 599f, true))
        assertEquals(ChromeHit.CLIENT, hit(799f, 100f, true))
    }
    @Test fun `layout remains aligned at 100 125 150 and 200 percent with negative monitor origins`() {
        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
            val origin = -2560 to -1440
            val screen = (origin.first + (730 * scale).toInt()) to (origin.second + (20 * scale).toInt())
            val packed = ((screen.second.toLong() and 0xffff) shl 16) or (screen.first.toLong() and 0xffff)
            assertEquals(screen, DesktopChromeHitTest.screenPoint(packed))
            val client = DesktopChromeHitTest.screenToClient(screen.first, screen.second, origin.first, origin.second)
            assertEquals(ChromeHit.MAXIMIZE, DesktopChromeHitTest.hit(client.first.toFloat(), client.second.toFloat(),
                800 * scale, 600 * scale, 8 * scale, false, layout))
        }
    }
    @Test fun `unmeasured or outside client cannot invent a draggable area`() {
        assertEquals(ChromeHit.CLIENT, DesktopChromeHitTest.hit(200f, 20f, 800f, 600f, 8f, false, DesktopChromeLayout()))
        assertEquals(ChromeHit.CLIENT, hit(-2f, 10f))
    }
    @Test fun `window commands map minimize maximize restore and close without a new placement store`() {
        assertEquals(DesktopWindowCommand.MINIMIZE, desktopCaptionCommand(ChromeHit.MINIMIZE, false))
        assertEquals(DesktopWindowCommand.MAXIMIZE, desktopCaptionCommand(ChromeHit.MAXIMIZE, false))
        assertEquals(DesktopWindowCommand.RESTORE, desktopCaptionCommand(ChromeHit.MAXIMIZE, true))
        assertEquals(DesktopWindowCommand.CLOSE, desktopCaptionCommand(ChromeHit.CLOSE, true))
        assertNull(desktopCaptionCommand(ChromeHit.CAPTION, false))
    }
    @Test fun `close calls the existing request authority including deferral`() {
        var requests = 0
        val chrome = DesktopWindowChrome { requests++ }
        chrome.command(DesktopWindowCommand.CLOSE)
        assertEquals(1, requests)
        chrome.command(DesktopWindowCommand.CLOSE)
        assertEquals(2, requests)
    }
}
