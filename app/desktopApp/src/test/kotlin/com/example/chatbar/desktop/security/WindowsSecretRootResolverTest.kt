package com.example.chatbar.desktop.security

import java.nio.file.Files
import kotlin.io.path.absolute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WindowsSecretRootResolverTest {
    @Test
    fun `fixed per-user secret root stays outside selected roots and survives root changes`() {
        val parent = Files.createTempDirectory("secret-root-resolver-").absolute()
        try {
            val localAppData = parent.resolve("LocalAppData")
            val selectedA = parent.resolve("SelectedA")
            val selectedB = parent.resolve("SelectedB")
            val environment = mapOf("LOCALAPPDATA" to localAppData.toString())

            val rootA = WindowsSecretRootResolver.resolve(environment, selectedA)
            val rootB = WindowsSecretRootResolver.resolve(environment, selectedB)

            assertEquals(localAppData.resolve(WindowsSecretRootResolver.DIRECTORY_NAME), rootA)
            assertEquals(rootA, rootB)
            assertFalse(rootA.startsWith(selectedA))
            assertFalse(rootA.startsWith(selectedB))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing relative and overlapping LocalAppData fail explicitly`() {
        assertEquals(
            DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
            assertFailsWith<DesktopSecretStoreException> {
                WindowsSecretRootResolver.resolve(emptyMap())
            }.kind,
        )
        assertEquals(
            DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
            assertFailsWith<DesktopSecretStoreException> {
                WindowsSecretRootResolver.resolve(mapOf("LOCALAPPDATA" to "relative"))
            }.kind,
        )

        val parent = Files.createTempDirectory("secret-root-overlap-").absolute()
        try {
            val environment = mapOf("LOCALAPPDATA" to parent.toString())
            val secretRoot = parent.resolve(WindowsSecretRootResolver.DIRECTORY_NAME)
            assertEquals(
                DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
                assertFailsWith<DesktopSecretStoreException> {
                    WindowsSecretRootResolver.resolve(environment, parent)
                }.kind,
            )
            assertEquals(
                DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
                assertFailsWith<DesktopSecretStoreException> {
                    WindowsSecretRootResolver.resolve(environment, secretRoot.resolve("data"))
                }.kind,
            )
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
