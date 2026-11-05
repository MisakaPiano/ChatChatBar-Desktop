package com.example.chatbar.desktop

import java.nio.file.Files
import kotlin.test.*

class DesktopProgramDataBoundaryTest {
    @Test fun `managed root siblings and ancestors refuse before creating data while moving out of nested source remains possible`() {
        val parent = Files.createTempDirectory("program-data-boundary-")
        try {
            val program = Files.createDirectory(parent.resolve("program"))
            val versions = Files.createDirectory(program.resolve("versions"))
            val home = Files.createDirectory(versions.resolve("synthetic-version"))
            Files.writeString(program.resolve("install-owner.txt"), "ccb-desktop-nsis-v1")
            val source = Files.createDirectory(program.resolve("existing-data"))
            val resolution = DesktopDataRootResolution.Resolved(source, DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, parent.resolve("bootstrap.json"))
            val preparer = DesktopMigrationDestinationPreparer({ DesktopApplicationHomeResult.Available(home,
                DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST) }, DesktopDataRootOwnership::acquire)
            val inside = program.resolve("new-UserData")
            assertTrue(DesktopProgramDataBoundary.conflicts(home, inside))
            assertTrue(DesktopProgramDataBoundary.conflicts(home, program))
            assertTrue(DesktopProgramDataBoundary.conflicts(home, parent))
            val refusal = assertIs<DesktopMigrationDestinationResult.Failure>(preparer.prepare(resolution, inside))
            assertEquals(DesktopMigrationDestinationFailureKind.APPLICATION_HOME_UNSAFE, refusal.kind)
            assertFalse(Files.exists(inside))
            val outside = parent.resolve("program-data")
            assertFalse(DesktopProgramDataBoundary.conflicts(home, outside))
            val prepared = assertIs<DesktopMigrationDestinationResult.Prepared>(preparer.prepare(resolution, outside)).destination
            prepared.close()
            assertTrue(Files.isDirectory(source)); assertTrue(Files.isDirectory(outside))
            assertFalse(Files.exists(parent.resolve("bootstrap.json")))
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test fun `independent Program and UserData siblings are distinct while version-shaped unowned image is not claimed as managed root`() {
        val parent = Files.createTempDirectory("independent-data-boundary-")
        try {
            val program = Files.createDirectory(parent.resolve("Program"))
            assertFalse(DesktopProgramDataBoundary.conflicts(program, parent.resolve("UserData")))
            assertTrue(DesktopProgramDataBoundary.conflicts(program, program.resolve("nested-data")))
            val versions = Files.createDirectory(parent.resolve("versions"))
            val home = Files.createDirectory(versions.resolve("unowned-image"))
            assertEquals(listOf(home.toRealPath()), DesktopProgramDataBoundary.protectedRoots(home))
            assertFalse(DesktopProgramDataBoundary.conflicts(home, parent.resolve("separate-data")))
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test fun `contradictory owned root marker refuses instead of treating installed image as unowned`() {
        val parent = Files.createTempDirectory("program-owner-refusal-")
        try {
            val versions = Files.createDirectory(parent.resolve("versions"))
            val home = Files.createDirectory(versions.resolve("fixture"))
            val owner = parent.resolve("install-owner.txt")
            Files.writeString(owner, "synthetic-foreign-owner")
            assertFailsWith<IllegalStateException> { DesktopProgramDataBoundary.conflicts(home, parent.resolve("UserData")) }
            assertEquals("synthetic-foreign-owner", Files.readString(owner))
            assertFalse(Files.exists(parent.resolve("UserData")))
        } finally { parent.toFile().deleteRecursively() }
    }
}
