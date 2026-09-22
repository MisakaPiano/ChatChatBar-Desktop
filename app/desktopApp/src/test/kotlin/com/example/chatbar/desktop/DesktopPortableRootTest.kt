package com.example.chatbar.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopPortableRootTest {
    private val resolver = DesktopPortableRootResolver()

    @Test
    fun `relative ApplicationHome is rejected without user dir fallback`() {
        val result = assertIs<DesktopPortableRootResolution.Failure>(
            resolver.resolve(Path.of("relative-application-home")),
        )

        assertEquals(DesktopPortableRootFailureKind.APPLICATION_HOME_UNSAFE, result.kind)
    }

    @Test
    fun `no marker returns NoMarker without creating anything`() = withApplicationHome { home ->
        val before = children(home)

        assertIs<DesktopPortableRootResolution.NoMarker>(resolver.resolve(home))

        assertEquals(before, children(home))
    }

    @Test
    fun `exact marker token with conventional line endings resolves existing UserData`() {
        listOf("", "\n", "\r\n").forEach { ending ->
            withApplicationHome { home ->
                writePortableMarker(home, DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN + ending)
                val userData = Files.createDirectory(
                    home.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME),
                )

                val result = assertIs<DesktopPortableRootResolution.PortableCandidate>(
                    resolver.resolve(home),
                )

                assertEquals(userData, result.appDataRoot)
            }
        }
    }

    @Test
    fun `wrong marker token fails without rewrite`() = withApplicationHome { home ->
        val marker = writePortableMarker(home, "future-or-invalid-token")

        val result = assertIs<DesktopPortableRootResolution.Failure>(resolver.resolve(home))

        assertEquals(DesktopPortableRootFailureKind.MARKER_CONTENT_INVALID, result.kind)
        assertEquals("future-or-invalid-token", marker.readText())
    }

    @Test
    fun `marker directory is rejected`() = withApplicationHome { home ->
        Files.createDirectory(home.resolve(DesktopPortableRootResolver.PORTABLE_MARKER_FILE_NAME))

        val result = assertIs<DesktopPortableRootResolution.Failure>(resolver.resolve(home))

        assertEquals(DesktopPortableRootFailureKind.MARKER_UNSAFE, result.kind)
    }

    @Test
    fun `missing UserData fails without creating it`() = withApplicationHome { home ->
        writePortableMarker(home)
        val userData = home.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME)

        val result = assertIs<DesktopPortableRootResolution.Failure>(resolver.resolve(home))

        assertEquals(DesktopPortableRootFailureKind.USER_DATA_MISSING, result.kind)
        assertFalse(Files.exists(userData))
    }

    @Test
    fun `UserData file is rejected`() = withApplicationHome { home ->
        writePortableMarker(home)
        Files.writeString(home.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME), "file")

        val result = assertIs<DesktopPortableRootResolution.Failure>(resolver.resolve(home))

        assertEquals(DesktopPortableRootFailureKind.USER_DATA_UNSAFE, result.kind)
    }

    @Test
    fun `symbolic link marker and UserData are rejected when fixtures are available`() {
        withApplicationHome { home ->
            val actualMarker = home.resolve("actual-marker")
            actualMarker.writeText(DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN)
            val marker = home.resolve(DesktopPortableRootResolver.PORTABLE_MARKER_FILE_NAME)
            if (runCatching { Files.createSymbolicLink(marker, actualMarker) }.isFailure) {
                println("SYMLINK_FIXTURE_UNAVAILABLE")
                return@withApplicationHome
            }
            println("SYMLINK_FIXTURE_AVAILABLE")
            val result = assertIs<DesktopPortableRootResolution.Failure>(resolver.resolve(home))
            assertEquals(DesktopPortableRootFailureKind.MARKER_UNSAFE, result.kind)
        }

        withApplicationHome { home ->
            writePortableMarker(home)
            val actualUserData = Files.createDirectory(home.resolve("actual-user-data"))
            val userData = home.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME)
            if (runCatching { Files.createSymbolicLink(userData, actualUserData) }.isFailure) {
                println("SYMLINK_FIXTURE_UNAVAILABLE")
                return@withApplicationHome
            }
            println("SYMLINK_FIXTURE_AVAILABLE")
            val result = assertIs<DesktopPortableRootResolution.Failure>(resolver.resolve(home))
            assertEquals(DesktopPortableRootFailureKind.USER_DATA_UNSAFE, result.kind)
        }
    }

    @Test
    fun `activation probe is flushed and removed`() = withPortableCandidate { candidate ->
        val before = children(candidate.appDataRoot)

        val result = DesktopPortableRootActivationValidator().validate(candidate)

        assertIs<DesktopPortableActivationResult.Validated>(result)
        assertEquals(before, children(candidate.appDataRoot))
    }

    @Test
    fun `activation write failure is structured and cleans possible residue`() =
        withPortableCandidate { candidate ->
            val validator = DesktopPortableRootActivationValidator(
                resolver = resolver,
                probeId = { "write-failure" },
                writeProbe = { path ->
                    Files.write(path, byteArrayOf(1), StandardOpenOption.CREATE_NEW)
                    throw IOException("write fixture failure")
                },
                deleteProbe = { path -> Files.deleteIfExists(path) },
            )

            val result = assertIs<DesktopPortableActivationResult.Failure>(
                validator.validate(candidate),
            )

            assertEquals(DesktopPortableActivationFailureKind.WRITE_PROBE_FAILED, result.kind)
            assertFalse(Files.exists(candidate.appDataRoot.resolve(".portable-write-probe-write-failure.tmp")))
        }

    @Test
    fun `activation cleanup failure is reported with retained probe`() =
        withPortableCandidate { candidate ->
            val validator = DesktopPortableRootActivationValidator(
                resolver = resolver,
                probeId = { "cleanup-failure" },
                writeProbe = { path ->
                    Files.write(path, byteArrayOf(1), StandardOpenOption.CREATE_NEW)
                },
                deleteProbe = { throw IOException("cleanup fixture failure") },
            )

            val result = assertIs<DesktopPortableActivationResult.Failure>(
                validator.validate(candidate),
            )

            assertEquals(DesktopPortableActivationFailureKind.PROBE_CLEANUP_FAILED, result.kind)
            assertEquals(
                candidate.appDataRoot.resolve(".portable-write-probe-cleanup-failure.tmp"),
                result.retainedProbe,
            )
            assertTrue(Files.exists(result.retainedProbe))
        }

    @Test
    fun `moving complete portable image resolves moved UserData`() {
        val parent = Files.createTempDirectory("portable-move-")
        try {
            val original = Files.createDirectory(parent.resolve("original-image"))
            writePortableMarker(original)
            Files.createDirectory(original.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME))
            val moved = parent.resolve("moved-image")

            Files.move(original, moved, StandardCopyOption.ATOMIC_MOVE)
            val result = assertIs<DesktopPortableRootResolution.PortableCandidate>(
                resolver.resolve(moved),
            )

            assertEquals(
                moved.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME),
                result.appDataRoot,
            )
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun withPortableCandidate(
        block: (DesktopPortableRootResolution.PortableCandidate) -> Unit,
    ) = withApplicationHome { home ->
        writePortableMarker(home)
        Files.createDirectory(home.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME))
        block(assertIs<DesktopPortableRootResolution.PortableCandidate>(resolver.resolve(home)))
    }

    private fun writePortableMarker(
        home: Path,
        content: String = DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN,
    ): Path = home.resolve(DesktopPortableRootResolver.PORTABLE_MARKER_FILE_NAME).also {
        it.writeText(content)
    }

    private fun withApplicationHome(block: (Path) -> Unit) {
        val parent = Files.createTempDirectory("desktop-application-home-")
        val home = Files.createDirectory(parent.resolve("application-image"))
        try {
            block(home)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun children(directory: Path): List<String> = Files.list(directory).use { stream ->
        stream.map { it.fileName.toString() }.sorted().toList()
    }
}
