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
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class DesktopDataDirectoryTest {
    @Test
    fun `read only selection preserves default bootstrap custom and invalid authority behavior`() = runTest {
        for (mode in listOf("missing", "default", "custom", "invalid")) withSelectionFixture { parent ->
            val local = Files.createDirectory(parent.resolve("local"))
            val home = Files.createDirectory(parent.resolve("image"))
            val store = DesktopBootstrapSettingsStore(local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME))
            if (mode == "invalid") Files.writeString(local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME), "{bad")
            else if (mode != "missing") {
                val document = assertIs<DesktopBootstrapLoadResult.Missing>(store.load()).document
                store.save(document, if (mode == "custom") DesktopDataRootSelection.custom(parent.resolve("custom")) else DesktopDataRootSelection())
            }
            val result = assertSelectionReadOnly("bootstrap-$mode", parent) {
                selectFixture(parent, home)
            }
            if (mode == "invalid") assertEquals(DesktopDataRootResolutionFailureKind.BOOTSTRAP_LOAD_FAILED,
                assertIs<DesktopStartupRootSelection.Failed>(result).failure.kind)
            else {
                val root = assertIs<DesktopStartupRootSelection.Selected>(result).root
                assertEquals(if (mode == "custom") parent.resolve("custom") else local.resolve(DesktopDataDirectory.DIRECTORY_NAME), root.appDataRoot)
                assertEquals(when (mode) {
                    "missing" -> DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT
                    "default" -> DesktopDataRootProvenance.BOOTSTRAP_DEFAULT
                    else -> DesktopDataRootProvenance.BOOTSTRAP_CUSTOM
                }, root.provenance)
                assertFalse(Files.exists(root.appDataRoot))
            }
        }
    }

    @Test
    fun `portable selection is pending without a probe and invalid portable never falls back`() = runTest {
        for (mode in listOf("valid", "invalid-marker", "missing-userdata", "file-userdata")) withSelectionFixture { parent ->
            val local = Files.createDirectory(parent.resolve("local"))
            Files.writeString(local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME), "{bad bootstrap must not win")
            val home = Files.createDirectory(parent.resolve("image"))
            Files.writeString(home.resolve("portable.flag"), if (mode == "invalid-marker") "invalid" else DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN)
            if (mode == "file-userdata") Files.writeString(home.resolve("UserData"), "synthetic")
            else if (mode != "missing-userdata") Files.createDirectory(home.resolve("UserData"))
            val result = assertSelectionReadOnly("portable-$mode", parent) { selectFixture(parent, home) }
            if (mode == "valid") {
                val pending = assertIs<DesktopStartupRootSelection.PortablePendingActivation>(result)
                assertEquals(home.resolve("UserData"), pending.appDataRoot)
                assertEquals(DesktopDataRootProvenance.PORTABLE, pending.provenance)
                assertTrue(Files.list(pending.appDataRoot).use { it.toList() }.isEmpty())
            } else assertEquals(DesktopDataRootResolutionFailureKind.PORTABLE_RESOLUTION_FAILED,
                assertIs<DesktopStartupRootSelection.Failed>(result).failure.kind)
        }
    }

    @Test
    fun `read only CLI precedence bypasses malformed portable and bootstrap`() = runTest { withSelectionFixture { parent ->
        val local = Files.createDirectory(parent.resolve("local"))
        Files.writeString(local.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME), "{bad")
        val override = parent.resolve("override")
        val result = assertSelectionReadOnly("cli-precedence", parent) {
            DesktopDataDirectory.selectRoot(mapOf("LOCALAPPDATA" to local.toString()), parent.resolve("unused"),
                explicitOverride = override, applicationHomeResolution = DesktopApplicationHomeResult.Failure("synthetic unavailable"))
        }
        assertEquals(override, assertIs<DesktopStartupRootSelection.Selected>(result).root.appDataRoot)
        val relative = assertSelectionReadOnly("relative-cli", parent) {
            DesktopDataDirectory.selectRoot(mapOf("LOCALAPPDATA" to local.toString()), parent,
                explicitOverride = Path.of("relative"))
        }
        assertEquals(DesktopDataRootResolutionFailureKind.INVALID_CLI_OVERRIDE,
            assertIs<DesktopStartupRootSelection.Failed>(relative).failure.kind)
    } }

    @Test
    fun `relocated application image gets a fresh pending authority without writes`() = runTest { withSelectionFixture { parent ->
        Files.createDirectory(parent.resolve("local"))
        val original = Files.createDirectory(parent.resolve("original"))
        Files.writeString(original.resolve("portable.flag"), DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN)
        Files.createDirectory(original.resolve("UserData"))
        val first = assertIs<DesktopStartupRootSelection.PortablePendingActivation>(assertSelectionReadOnly("original-home", parent) { selectFixture(parent, original) })
        val moved = Files.move(original, parent.resolve("moved"))
        val next = assertIs<DesktopStartupRootSelection.PortablePendingActivation>(assertSelectionReadOnly("moved-home", parent) { selectFixture(parent, moved) })
        assertEquals(moved.resolve("UserData"), next.appDataRoot)
        assertFalse(Files.exists(first.appDataRoot))
        val noHome = assertSelectionReadOnly("bad-application-home", parent) {
            DesktopDataDirectory.selectRoot(mapOf("LOCALAPPDATA" to parent.resolve("local").toString()), parent,
                applicationHomeResolution = DesktopApplicationHomeResult.Failure("synthetic malformed"))
        }
        assertEquals(DesktopDataRootResolutionFailureKind.APPLICATION_HOME_FAILED,
            assertIs<DesktopStartupRootSelection.Failed>(noHome).failure.kind)
    } }

    private suspend fun selectFixture(parent: Path, home: Path) = DesktopDataDirectory.selectRoot(
        mapOf("LOCALAPPDATA" to parent.resolve("local").toString()), parent.resolve("unused-home"),
        applicationHomeResolution = DesktopApplicationHomeResult.Available(home, DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST))

    private suspend fun withSelectionFixture(block: suspend (Path) -> Unit) {
        val parent = Files.createTempDirectory("g1a-root-selection-")
        try { block(parent) } finally { parent.toFile().deleteRecursively() }
    }

    private suspend fun <T> assertSelectionReadOnly(label: String, parent: Path, block: suspend () -> T): T {
        fun snapshot() = Files.walk(parent).use { paths -> paths.sorted().toList().associate { path ->
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val hash = if (a.isRegularFile) MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).contentToString() else "directory"
            parent.relativize(path).toString() to "$hash:${a.size()}:${a.creationTime()}:${a.lastModifiedTime()}"
        } }
        val before = snapshot()
        val result = block()
        val after = snapshot()
        assertEquals(before, after)
        println("G1A_READ_ONLY $label entries=${before.size} unchanged=true")
        return result
    }

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
