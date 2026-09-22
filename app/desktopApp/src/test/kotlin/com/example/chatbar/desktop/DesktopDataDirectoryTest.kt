package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DesktopDataDirectoryTest {
    @Test
    fun `missing bootstrap preserves exact LOCALAPPDATA default without writes`() = runTest {
        val localAppData = Path.of("local-app-data").toAbsolutePath()

        val result = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
            environment = mapOf("LOCALAPPDATA" to localAppData.toString()),
            userHome = Path.of("unused-home").toAbsolutePath(),
        ))

        assertEquals(localAppData.resolve("ChatChatBarDesktop"), result.appDataRoot)
        assertEquals(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT, result.provenance)
        assertEquals(localAppData.resolve("ChatChatBarDesktop.bootstrap.json"), result.bootstrapPath)
        assertFalse(Files.exists(result.appDataRoot))
        assertFalse(Files.exists(result.bootstrapPath))
    }

    @Test
    fun `missing bootstrap preserves user home AppData Local fallback`() = runTest {
        val userHome = Path.of("user-home").toAbsolutePath()

        val result = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
            environment = mapOf("LOCALAPPDATA" to "  "),
            userHome = userHome,
        ))

        assertEquals(
            userHome.resolve("AppData").resolve("Local").resolve("ChatChatBarDesktop"),
            result.appDataRoot,
        )
        assertEquals(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT, result.provenance)
    }

    @Test
    fun `CLI override has highest precedence and is not persisted`() = runTest {
        val parent = Files.createTempDirectory("desktop-root-cli-")
        try {
            val localAppData = parent.resolve("local")
            Files.createDirectories(localAppData)
            val bootstrapPath = localAppData.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME)
            val store = DesktopBootstrapSettingsStore(bootstrapPath)
            val missing = assertIs<DesktopBootstrapLoadResult.Missing>(store.load())
            store.save(missing.document, DesktopDataRootSelection.custom(parent.resolve("persisted")))
            val before = Files.readAllBytes(bootstrapPath)
            val override = parent.resolve("override")

            val result = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to localAppData.toString()),
                userHome = parent.resolve("home"),
                explicitOverride = override,
            ))

            assertEquals(override.normalize(), result.appDataRoot)
            assertEquals(DesktopDataRootProvenance.CLI_OVERRIDE, result.provenance)
            assertFalse(Files.exists(override))
            assertContentEquals(before, Files.readAllBytes(bootstrapPath))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `relative CLI override is rejected without reading or writing bootstrap`() = runTest {
        val parent = Files.createTempDirectory("desktop-root-relative-cli-")
        try {
            val localAppData = parent.resolve("local")
            val result = assertIs<DesktopDataRootResolution.Failed>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to localAppData.toString()),
                userHome = parent.resolve("home"),
                explicitOverride = Path.of("relative-root"),
            ))

            assertEquals(DesktopDataRootResolutionFailureKind.INVALID_CLI_OVERRIDE, result.kind)
            assertFalse(Files.exists(localAppData))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `CLI override wins without inspecting invalid Portable authority`() = runTest {
        val parent = Files.createTempDirectory("desktop-root-cli-portable-")
        try {
            val override = parent.resolve("override")
            val result = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to parent.resolve("local").toString()),
                userHome = parent.resolve("home"),
                explicitOverride = override,
                applicationHomeResolution = DesktopApplicationHomeResult.Failure("fixture failure"),
            ))

            assertEquals(override, result.appDataRoot)
            assertEquals(DesktopDataRootProvenance.CLI_OVERRIDE, result.provenance)
            assertFalse(Files.exists(override))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Portable wins over bootstrap CUSTOM and leaves bootstrap unchanged`() = runTest {
        val parent = Files.createTempDirectory("desktop-root-portable-")
        try {
            val local = Files.createDirectory(parent.resolve("local"))
            val bootstrapPath = local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME)
            val store = DesktopBootstrapSettingsStore(bootstrapPath)
            val missing = assertIs<DesktopBootstrapLoadResult.Missing>(store.load())
            store.save(missing.document, DesktopDataRootSelection.custom(parent.resolve("custom")))
            val bootstrapBytes = Files.readAllBytes(bootstrapPath)
            val home = Files.createDirectory(parent.resolve("application-home"))
            Files.writeString(
                home.resolve(DesktopPortableRootResolver.PORTABLE_MARKER_FILE_NAME),
                DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN,
            )
            val userData = Files.createDirectory(
                home.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME),
            )

            val result = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to local.toString()),
                userHome = parent.resolve("home"),
                applicationHomeResolution = DesktopApplicationHomeResult.Available(
                    home,
                    DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST,
                ),
            ))

            assertEquals(userData, result.appDataRoot)
            assertEquals(DesktopDataRootProvenance.PORTABLE, result.provenance)
            assertContentEquals(bootstrapBytes, Files.readAllBytes(bootstrapPath))
            assertTrue(Files.list(userData).use { it.toList() }.isEmpty())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid Portable authority never falls through to bootstrap`() = runTest {
        val parent = Files.createTempDirectory("desktop-root-invalid-portable-")
        try {
            val local = Files.createDirectory(parent.resolve("local"))
            val bootstrapPath = local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME)
            val store = DesktopBootstrapSettingsStore(bootstrapPath)
            val missing = assertIs<DesktopBootstrapLoadResult.Missing>(store.load())
            val custom = parent.resolve("custom")
            store.save(missing.document, DesktopDataRootSelection.custom(custom))
            val home = Files.createDirectory(parent.resolve("application-home"))
            Files.writeString(
                home.resolve(DesktopPortableRootResolver.PORTABLE_MARKER_FILE_NAME),
                DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN,
            )

            val result = assertIs<DesktopDataRootResolution.Failed>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to local.toString()),
                userHome = parent.resolve("home"),
                applicationHomeResolution = DesktopApplicationHomeResult.Available(
                    home,
                    DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST,
                ),
            ))

            assertEquals(DesktopDataRootResolutionFailureKind.PORTABLE_RESOLUTION_FAILED, result.kind)
            assertFalse(Files.exists(custom))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no marker and unpackaged development both allow bootstrap resolution`() = runTest {
        val parent = Files.createTempDirectory("desktop-root-no-portable-")
        try {
            val local = Files.createDirectory(parent.resolve("local"))
            val home = Files.createDirectory(parent.resolve("application-home"))

            val noMarker = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to local.toString()),
                userHome = parent.resolve("home"),
                applicationHomeResolution = DesktopApplicationHomeResult.Available(
                    home,
                    DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST,
                ),
            ))
            val unpackaged = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to local.toString()),
                userHome = parent.resolve("home"),
                applicationHomeResolution = DesktopApplicationHomeResult.Unavailable(
                    DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT,
                ),
            ))

            assertEquals(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT, noMarker.provenance)
            assertEquals(noMarker, unpackaged)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed packaged ApplicationHome fails before bootstrap`() = runTest {
        val parent = Files.createTempDirectory("desktop-root-home-failure-")
        try {
            val result = assertIs<DesktopDataRootResolution.Failed>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to parent.resolve("local").toString()),
                userHome = parent.resolve("home"),
                applicationHomeResolution = DesktopApplicationHomeResult.Failure("malformed property"),
            ))

            assertEquals(DesktopDataRootResolutionFailureKind.APPLICATION_HOME_FAILED, result.kind)
            assertFalse(Files.exists(parent.resolve("local")))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
