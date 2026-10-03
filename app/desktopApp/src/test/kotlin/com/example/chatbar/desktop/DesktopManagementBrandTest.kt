package com.example.chatbar.desktop

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*

class DesktopManagementBrandTest {
    @Test
    fun `character management uses existing avatar reference and full metadata`() {
        val card = card().copy(avatar = "owned-image", characters = listOf(CharacterInfo("person", "Name")),
            customDocuments = listOf(DocumentInfo("doc", "Notes", "owned-document", "txt", 1)))
        var requested: String? = null
        val presentation = desktopCharacterManagementPresentation(card) { requested = it; solidLogo() }
        assertEquals("owned-image", requested)
        assertNotNull(presentation.avatar)
        assertEquals(card.name, presentation.name)
        assertEquals(1, presentation.characterCount)
        assertEquals(1, presentation.documentCount)
        assertEquals("角", presentation.fallbackInitial)
    }

    @Test
    fun `missing invalid and unreadable avatars use stable fallback without entity mutation`() {
        val card = card()
        assertNull(desktopCharacterManagementPresentation(card) { null }.avatar)
        val invalid = desktopCharacterManagementPresentation(card) { "not an image".toByteArray() }
        val unreadable = desktopCharacterManagementPresentation(card) { error("unreadable") }
        assertNull(invalid.avatar)
        assertNull(unreadable.avatar)
        assertEquals("角", invalid.fallbackInitial)
        assertEquals(invalid.fallbackInitial, unreadable.fallbackInitial)
        assertNull(card.avatar)
    }

    @Test
    fun `official window logo resource and native ICO decode`() {
        val png = DesktopBrandResources.logoBytes()
        assertNotNull(javaClass.classLoader.getResource(DesktopBrandResources.LOGO_RESOURCE))
        val image = ImageIO.read(png.inputStream())
        assertTrue(image.width > 0 && image.height > 0)
        val ico = javaClass.classLoader.getResourceAsStream("brand/ccb.ico")!!.use { it.readBytes() }
        val header = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, header.short.toInt())
        assertEquals(1, header.short.toInt())
        assertEquals(1, header.short.toInt())
        header.position(14)
        val length = header.int
        val offset = header.int
        val nativeImage = ImageIO.read(ico.copyOfRange(offset, offset + length).inputStream())
        assertEquals(256, nativeImage.width)
        assertEquals(256, nativeImage.height)
    }

    @Test
    fun `PNG export draws injected image logo at existing placement and scale`() {
        var loads = 0
        val renderer = DesktopCharacterCardPngRenderer { loads++; solidLogo() }
        val options = CharacterCardPngExportOptions(sizePx = 1024).normalized()
        val bytes = renderer.render(card(), options)
        val image = ImageIO.read(bytes.inputStream())
        val margin = (options.sizePx * .055f).roundToInt()
        val mark = (options.sizePx * options.logoScale).roundToInt()
        assertEquals(Color.MAGENTA.rgb, image.getRGB(margin + mark / 2, options.sizePx - margin - mark / 2))
        assertEquals(1, loads)
        assertEquals(1024, image.width)
    }

    @Test
    fun `raw format flag explanation distinguishes global fallback in both languages`() {
        for (language in DesktopUiLanguage.entries) {
            val text = DesktopUiStrings(language)
            assertNotEquals(text(DesktopUiText.GLOBAL_DEFAULT), text(DesktopUiText.FORMAT_DEFAULT_FLAG))
            assertTrue(text(DesktopUiText.FORMAT_DEFAULT_EXPLANATION).isNotBlank())
        }
    }

    private fun card() = CharacterCard(id = "card", name = "角色完整名称", createdAt = 1, updatedAt = 1)
    private fun solidLogo(): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { color = Color.MAGENTA; fillRect(0, 0, 16, 16); dispose() }
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }
}
