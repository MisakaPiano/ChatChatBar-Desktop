package com.example.chatbar.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopDataRootOwnershipTest {
    @Test
    fun `first acquisition succeeds and same JVM overlap is structured`() = withTempDirectory { root ->
        val first = acquire(root)
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(first).ownership
        try {
            val second = assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(root))

            assertTrue(second.sameJvm)
            assertEquals(root.toAbsolutePath().normalize(), second.appDataRoot)
            assertEquals(root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME), second.lockPath)
        } finally {
            ownership.close()
        }
    }

    @Test
    fun `different roots can be owned concurrently`() = withTempDirectory { parent ->
        val rootA = Files.createDirectory(parent.resolve("a"))
        val rootB = Files.createDirectory(parent.resolve("b"))
        val ownershipA = assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(rootA)).ownership
        val ownershipB = assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(rootB)).ownership

        ownershipB.close()
        ownershipA.close()
    }

    @Test
    fun `release allows reacquisition and leaves stale lock file`() = withTempDirectory { root ->
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(root)).ownership.close()

        val lockPath = root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME)
        assertTrue(Files.exists(lockPath))
        assertEquals(0L, Files.size(lockPath))

        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(root)).ownership.close()
    }

    @Test
    fun `pre-existing zero-length stale file does not block ownership`() = withTempDirectory { root ->
        val lockPath = Files.createFile(root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME))

        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(root)).ownership.close()

        assertTrue(Files.exists(lockPath))
        assertEquals(0L, Files.size(lockPath))
    }

    @Test
    fun `non-empty existing lock target is rejected without rewrite`() = withTempDirectory { root ->
        val lockPath = root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME)
        lockPath.writeText("user-owned")

        val result = assertIs<DesktopDataRootOwnershipResult.Failure>(acquire(root))

        assertEquals(DesktopDataRootOwnershipFailureKind.LOCK_TARGET_UNSAFE, result.kind)
        assertEquals("user-owned", Files.readString(lockPath))
    }

    @Test
    fun `directory lock target is rejected`() = withTempDirectory { root ->
        Files.createDirectory(root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME))

        val result = assertIs<DesktopDataRootOwnershipResult.Failure>(acquire(root))

        assertEquals(DesktopDataRootOwnershipFailureKind.LOCK_TARGET_UNSAFE, result.kind)
    }

    @Test
    fun `symbolic-link lock target is rejected when fixture is available`() = withTempDirectory { root ->
        val actual = Files.createFile(root.resolve("actual-lock"))
        val link = root.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME)
        if (runCatching { Files.createSymbolicLink(link, actual) }.isFailure) {
            println("SYMLINK_FIXTURE_UNAVAILABLE")
            return@withTempDirectory
        }
        println("SYMLINK_FIXTURE_AVAILABLE")

        val result = assertIs<DesktopDataRootOwnershipResult.Failure>(acquire(root))

        assertEquals(DesktopDataRootOwnershipFailureKind.LOCK_TARGET_UNSAFE, result.kind)
    }

    @Test
    fun `missing default roots are created and validated`() = withTempDirectory { parent ->
        listOf(
            DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT,
            DesktopDataRootProvenance.BOOTSTRAP_DEFAULT,
        ).forEach { provenance ->
            val root = parent.resolve(provenance.name.lowercase())

            val result = assertIs<DesktopDataRootOwnershipResult.Acquired>(
                acquire(root, provenance),
            )
            result.ownership.close()

            assertTrue(Files.isDirectory(root))
        }
    }

    @Test
    fun `missing custom CLI and Portable roots fail without creation`() = withTempDirectory { parent ->
        listOf(
            DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            DesktopDataRootProvenance.CLI_OVERRIDE,
            DesktopDataRootProvenance.PORTABLE,
        ).forEach { provenance ->
            val root = parent.resolve(provenance.name.lowercase())

            val result = assertIs<DesktopDataRootOwnershipResult.Failure>(
                acquire(root, provenance),
            )

            assertEquals(DesktopDataRootOwnershipFailureKind.ROOT_MISSING, result.kind)
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun `non-directory root is rejected`() = withTempDirectory { parent ->
        val root = Files.createFile(parent.resolve("not-a-directory"))

        val result = assertIs<DesktopDataRootOwnershipResult.Failure>(acquire(root))

        assertEquals(DesktopDataRootOwnershipFailureKind.ROOT_UNSAFE, result.kind)
    }

    @Test
    fun `symbolic-link root is rejected when fixture is available`() = withTempDirectory { parent ->
        val actual = Files.createDirectory(parent.resolve("actual-root"))
        val root = parent.resolve("linked-root")
        if (runCatching { Files.createSymbolicLink(root, actual) }.isFailure) {
            println("SYMLINK_FIXTURE_UNAVAILABLE")
            return@withTempDirectory
        }
        println("SYMLINK_FIXTURE_AVAILABLE")

        val result = assertIs<DesktopDataRootOwnershipResult.Failure>(acquire(root))

        assertEquals(DesktopDataRootOwnershipFailureKind.ROOT_UNSAFE, result.kind)
    }

    @Test
    fun `close is idempotent and never deletes lock path`() = withTempDirectory { root ->
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(root)).ownership

        ownership.close()
        ownership.close()

        assertTrue(Files.exists(ownership.lockPath))
    }

    @Test
    fun `failed overlapping acquisition leaks no ownership`() = withTempDirectory { root ->
        val first = assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(root)).ownership
        assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(root))

        first.close()

        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(root)).ownership.close()
    }

    @Test
    fun `diagnostic paths are absolute and normalized`() = withTempDirectory { parent ->
        val selected = parent.resolve("child").resolve("..").resolve("root")

        val acquired = assertIs<DesktopDataRootOwnershipResult.Acquired>(
            acquire(selected, DesktopDataRootProvenance.BOOTSTRAP_DEFAULT),
        ).ownership
        try {
            val expectedRoot = selected.toAbsolutePath().normalize()
            assertEquals(expectedRoot, acquired.appDataRoot)
            assertEquals(
                expectedRoot.resolve(DesktopDataRootOwnership.LOCK_FILE_NAME),
                acquired.lockPath,
            )
        } finally {
            acquired.close()
        }
    }

    private fun acquire(
        root: Path,
        provenance: DesktopDataRootProvenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
    ): DesktopDataRootOwnershipResult = DesktopDataRootOwnership.acquire(
        DesktopDataRootResolution.Resolved(
            appDataRoot = root,
            provenance = provenance,
            bootstrapPath = root.resolveSibling("bootstrap.json"),
        ),
    )

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("desktop-data-root-ownership-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
