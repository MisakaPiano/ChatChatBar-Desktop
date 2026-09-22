package com.example.chatbar.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DesktopApplicationHomeTest {
    @Test
    fun `packaged launcher property resolves absolute normalized home`() {
        val home = Path.of("application-home").toAbsolutePath().resolve("nested").resolve("..")

        val result = assertIs<DesktopApplicationHomeResult.Available>(
            DesktopApplicationHome.resolve(home.toString()),
        )

        assertEquals(home.normalize(), result.path)
        assertEquals(
            DesktopApplicationHomeProvenance.PACKAGED_LAUNCHER_PROPERTY,
            result.provenance,
        )
    }

    @Test
    fun `absent property is unpackaged development and never uses user dir`() {
        val result = assertIs<DesktopApplicationHomeResult.Unavailable>(
            DesktopApplicationHome.resolve(packagedPropertyValue = null),
        )

        assertEquals(DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT, result.reason)
    }

    @Test
    fun `literal jpackage macro is unavailable instead of a filesystem path`() {
        val result = assertIs<DesktopApplicationHomeResult.Unavailable>(
            DesktopApplicationHome.resolve(DesktopApplicationHome.UNEXPANDED_JPACKAGE_ROOT),
        )

        assertEquals(
            DesktopApplicationHomeUnavailableReason.UNEXPANDED_JPACKAGE_MACRO,
            result.reason,
        )
    }

    @Test
    fun `relative and blank packaged properties fail structurally`() {
        assertIs<DesktopApplicationHomeResult.Failure>(
            DesktopApplicationHome.resolve("relative-home"),
        )
        assertIs<DesktopApplicationHomeResult.Failure>(
            DesktopApplicationHome.resolve("  "),
        )
    }

    @Test
    fun `injected development home has distinct provenance`() {
        val home = Path.of("fixture-home").toAbsolutePath()

        val result = assertIs<DesktopApplicationHomeResult.Available>(
            DesktopApplicationHome.resolve(
                packagedPropertyValue = null,
                injectedApplicationHome = home,
            ),
        )

        assertEquals(home, result.path)
        assertEquals(
            DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST,
            result.provenance,
        )
    }
}
