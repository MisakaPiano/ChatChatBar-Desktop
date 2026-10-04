package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopWorkspacePresentationTest {
    @Test fun `freeform suppresses profile and selects name avatar detail without changing the person`() {
        val person = CharacterInfo("person", "Name", profile = "Hidden profile", appearanceImage = "images/person.png")
        val card = CharacterCard("card", "Card", characters = listOf(person), createdAt = 1, updatedAt = 1)
        val structured = desktopPersonWorkspace(card, person.id)
        assertEquals("Hidden profile", structured.rows.single().profile)
        assertEquals(DesktopPersonDetailKind.STRUCTURED_FIELDS, structured.detailKind)
        val freeform = desktopPersonWorkspace(card.copy(editMode = CharacterEditMode.FREEFORM), person.id)
        assertNull(freeform.rows.single().profile)
        assertEquals(DesktopPersonDetailKind.NAME_AND_AVATAR, freeform.detailKind)
        assertEquals(person.name, freeform.rows.single().name)
        assertEquals(person.appearanceImage, freeform.rows.single().avatarReference)
        assertEquals(person, freeform.selected)
        assertEquals(structured, desktopPersonWorkspace(card, person.id))
    }

    @Test fun `avatar book detail uses only existing name and image controls not structured editor`() {
        val panel = source("DesktopCharacterEditorPanel.kt")
        assertTrue(panel.contains("DesktopPersonDetailKind.NAME_AND_AVATAR -> CharacterAvatarBookFields(selected, controller)"))
        val detail = panel.substringAfter("private fun CharacterAvatarBookFields(")
            .substringBefore("private fun CharacterEntryFields(")
        assertEquals(1, Regex("EditorField\\(").findAll(detail).count())
        assertTrue(detail.contains("DesktopUiText.CHARACTER_NAME"))
        assertEquals(1, Regex("ImageSlot\\(").findAll(detail).count())
        assertTrue(detail.contains("entry.appearanceImage"))
        assertFalse(detail.contains("CharacterEntryFields("))
        assertFalse(detail.contains("entry.profile"))
    }

    @Test fun `medium and wide title bar exposes every route with exactly one selected`() {
        for (size in listOf(DesktopShellSize.MEDIUM, DesktopShellSize.WIDE)) {
            for (selected in DesktopPrimaryRoute.entries) {
                val presentation = desktopTitleBarPresentation(size, selected, locked = false)
                assertEquals(DesktopPrimaryRoute.entries.toList(), presentation.routes.map { it.route })
                assertEquals(listOf(selected), presentation.routes.filter { it.selected }.map { it.route })
                assertTrue(presentation.showRouteLabels)
            }
        }
    }

    @Test fun `compact title bar retains all routes with caption space reserved`() {
        val presentation = desktopTitleBarPresentation(DesktopShellSize.COMPACT, DesktopPrimaryRoute.MANAGE, locked = false)
        assertTrue(presentation.minimumContentWidthDp <= 330)
        assertEquals(4, presentation.routes.size)
        assertTrue(presentation.routes.single { it.route == DesktopPrimaryRoute.MANAGE }.selected)
    }

    @Test fun `shell structure has one title bar using the native Windows bridge`() {
        val shell = source("DesktopPrimaryShell.kt")
        assertFalse(shell.contains("ChatChatBar Desktop"))
        assertFalse(shell.contains("DesktopUiText.WORKSPACE"))
        assertFalse(shell.contains("height(58.dp)"))
        assertTrue(shell.contains("DesktopTitleBar("))
        assertTrue(source("DesktopTitleBar.kt").contains("DesktopBrandResources.LOGO_RESOURCE"))
        assertTrue(source("Main.kt").contains("rememberDesktopWindowChrome("))
        assertTrue(source("DesktopWindowChrome.kt").contains("WindowsWindowChrome.install"))
    }

    @Test fun `wide reading column is bounded while smaller viewports use available width`() {
        assertEquals(880f, DesktopChatReadingWidth.forAvailableWidth(1600f))
        for (width in listOf(280f, 520f, 760f, 880f)) {
            assertEquals(width, DesktopChatReadingWidth.forAvailableWidth(width))
        }
        assertEquals(0f, DesktopChatReadingWidth.forAvailableWidth(0f))
    }

    @Test fun `header timeline and composer share a single reading column rather than independent widths`() {
        val panel = source("DesktopPrimaryChatPanel.kt")
        val column = panel.substringAfter("Column(Modifier.width(DesktopChatReadingWidth")
            .substringBefore("if (browser.settingsSessionId")
        assertTrue(column.contains("PrimaryHeading("))
        assertTrue(column.contains("PrimaryTimeline("))
        assertTrue(column.contains("PrimaryComposer("))
        assertEquals(1, Regex("DesktopChatReadingWidth").findAll(panel).count())
    }

    @Test fun `collapse restore is presentation only and retains clicked session and settings state`() {
        val initial = DesktopPrimaryChatBrowserState(expandedSessionId = "session", settingsSessionId = "session",
            renameSessionId = "other")
        assertTrue(initial.wideBrowserExpanded)
        for (size in listOf(DesktopShellSize.MEDIUM, DesktopShellSize.WIDE)) {
            assertTrue(initial.browserVisible(size, false))
            val hidden = initial.toggleWideBrowser()
            assertFalse(hidden.browserVisible(size, false))
            assertEquals(initial.settingsSessionId, hidden.settingsSessionId)
            assertEquals(initial.expandedSessionId, hidden.expandedSessionId)
            assertEquals(initial, hidden.toggleWideBrowser())
        }
        assertTrue(initial.browserVisible(DesktopShellSize.COMPACT, true))
        assertFalse(initial.browserVisible(DesktopShellSize.COMPACT, false))
    }

    @Test fun `session row keeps authoritative title avatar pin archive and preview projection`() {
        val item = DesktopPrimarySessionItem("session", "Full rendered session title", null, true, null,
            "Preview", "images/avatar.png")
        val row = desktopSessionRowPresentation(item, true, item.lastMessagePreview)
        assertEquals(item.title, row.title)
        assertEquals(item.avatarReference, row.avatarReference)
        assertTrue(row.pinned && row.archived && row.selected)
        assertEquals("Preview", row.preview)
        assertNull(desktopSessionRowPresentation(item, false, null).preview)
    }

    @Test fun `person rows expose appearance image name and optional profile without copying entities`() {
        val person = CharacterInfo("a", "完整名字", profile = "Profile", appearanceImage = "images/person.png")
        val row = desktopPersonRow(person)
        assertEquals(person.appearanceImage, row.avatarReference)
        assertEquals(person.name, row.name)
        assertEquals("Profile", row.profile)
        assertNull(desktopPersonRow(person.copy(profile = "  ")).profile)
        var requested: String? = null
        val bytes = ByteArrayOutputStream().use {
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", it)
            it.toByteArray()
        }
        assertNotNull(desktopPersonAvatar(row) { requested = it; bytes })
        assertEquals(person.appearanceImage, requested)
    }

    @Test fun `missing corrupt and unreadable person images keep reference and stable initial`() {
        val person = CharacterInfo("a", " 林月 ", appearanceImage = "images/unreadable.png")
        val row = desktopPersonRow(person)
        assertNull(desktopPersonAvatar(row) { null })
        assertNull(desktopPersonAvatar(row) { byteArrayOf(1, 2, 3) })
        assertNull(desktopPersonAvatar(row) { error("unreadable") })
        assertEquals("林", desktopPersonInitial(row))
        assertEquals("images/unreadable.png", person.appearanceImage)
        assertEquals(person.appearanceImage, row.avatarReference)
    }

    @Test fun `structured and freeform select exactly one person and preserve the avatar book`() {
        val people = listOf(CharacterInfo("a", "A", appearanceImage = "images/a.png"),
            CharacterInfo("b", "B", appearanceImage = "images/b.png"))
        val card = CharacterCard("card", "Card", characters = people, freeformCharacterText = "Unconverted text", createdAt = 1, updatedAt = 1)
        assertEquals("a", desktopPersonWorkspace(card, null).selected?.id)
        assertEquals("b", desktopPersonWorkspace(card, "b").selected?.id)
        assertFalse(desktopPersonWorkspace(card, "b").avatarBook)
        val freeform = desktopPersonWorkspace(card.copy(editMode = CharacterEditMode.FREEFORM), "b")
        assertTrue(freeform.avatarBook)
        assertEquals(people.map { it.appearanceImage }, freeform.rows.map { it.avatarReference })
        assertEquals("b", freeform.selected?.id)
        assertEquals(people, card.characters)
        assertNull(desktopPersonWorkspace(card.copy(characters = emptyList()), "b").selected)
        assertFalse(DesktopPersonLayout.sideBySide(600f))
        assertTrue(DesktopPersonLayout.sideBySide(900f))
    }

    private fun source(name: String) = Files.readString(Path.of("src/main/kotlin/com/example/chatbar/desktop", name))
}
