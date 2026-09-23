package com.example.chatbar.desktop

import com.example.chatbar.data.root.AppDataRootInfrastructure
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopMigrationDestinationPreparerTest {
    @Test
    fun `missing destination creates exactly one directory and retains ownership`() = withFixture {
        val destination = parent.resolve("destination")

        val prepared = assertIs<DesktopMigrationDestinationResult.Prepared>(
            preparer().prepare(source(), destination),
        ).destination

        assertTrue(Files.isDirectory(destination))
        assertTrue(prepared.createdByPreparation)
        assertEquals(
            listOf(AppDataRootInfrastructure.OWNERSHIP_LOCK_FILE_NAME),
            children(destination),
        )
        assertIs<DesktopDataRootOwnershipResult.AlreadyInUse>(acquire(destination))
        prepared.close()
        assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
    }

    @Test
    fun `missing parent is rejected without recursive creation`() = withFixture {
        val missingParent = parent.resolve("missing-parent")
        val destination = missingParent.resolve("destination")

        val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
            preparer().prepare(source(), destination),
        )

        assertEquals(DesktopMigrationDestinationFailureKind.DESTINATION_PARENT_MISSING, failure.kind)
        assertFalse(Files.exists(missingParent))
    }

    @Test
    fun `non-directory destination parent is rejected without creation`() = withFixture {
        val parentFile = parent.resolve("parent-file")
        Files.writeString(parentFile, "not a directory")
        val destination = parentFile.resolve("destination")

        val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
            preparer().prepare(source(), destination),
        )

        assertEquals(DesktopMigrationDestinationFailureKind.DESTINATION_PARENT_UNSAFE, failure.kind)
        assertFalse(Files.exists(destination))
    }

    @Test
    fun `existing empty destination and stale ownership file are accepted`() = withFixture {
        listOf(false, true).forEach { withStaleLock ->
            val destination = Files.createDirectory(parent.resolve("destination-$withStaleLock"))
            if (withStaleLock) {
                Files.createFile(destination.resolve(AppDataRootInfrastructure.OWNERSHIP_LOCK_FILE_NAME))
            }

            val prepared = assertIs<DesktopMigrationDestinationResult.Prepared>(
                preparer().prepare(source(), destination),
            ).destination

            assertFalse(prepared.createdByPreparation)
            prepared.close()
        }
    }

    @Test
    fun `all payload-looking direct children make destination non-empty`() = withFixture {
        val fixtures: List<(Path) -> Unit> = listOf(
            { Files.writeString(it.resolve("unknown.txt"), "payload") },
            { Files.createDirectory(it.resolve("backups")) },
            { Files.writeString(it.resolve(".hidden"), "payload") },
            { Files.createDirectory(it.resolve(".migration-workspace")) },
            { Files.createDirectory(it.resolve(".restore-workspace")) },
            { Files.createDirectory(it.resolve(".snapshot-workspace")) },
            { Files.createDirectory(it.resolve(".prune-workspace")) },
            { Files.createDirectory(it.resolve("entities")) },
            { Files.writeString(it.resolve("desktop-settings.json"), "{}") },
        )
        fixtures.forEachIndexed { index, createPayload ->
            val destination = Files.createDirectory(parent.resolve("non-empty-$index"))
            createPayload(destination)

            val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
                preparer().prepare(source(), destination),
            )

            assertEquals(DesktopMigrationDestinationFailureKind.DESTINATION_NOT_EMPTY, failure.kind)
            assertTrue(Files.exists(destination))
            assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership.close()
        }
    }

    @Test
    fun `ownership failure retains a newly created empty destination for future policy`() = withFixture {
        val destination = parent.resolve("created-before-ownership-failure")
        val injected = DesktopMigrationDestinationPreparer(
            applicationHomeResolver = {
                DesktopApplicationHomeResult.Unavailable(
                    DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT,
                )
            },
            ownershipAcquire = { selected ->
                DesktopDataRootOwnershipResult.Failure(
                    kind = DesktopDataRootOwnershipFailureKind.LOCK_OPEN_FAILED,
                    appDataRoot = selected.appDataRoot,
                    lockPath = selected.appDataRoot.resolve(
                        AppDataRootInfrastructure.OWNERSHIP_LOCK_FILE_NAME,
                    ),
                    message = "fixture ownership failure",
                )
            },
        )

        val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
            injected.prepare(source(), destination),
        )

        assertEquals(
            DesktopMigrationDestinationFailureKind.DESTINATION_OWNERSHIP_FAILED,
            failure.kind,
        )
        assertTrue(failure.createdDestination)
        assertTrue(Files.isDirectory(destination))
        assertTrue(children(destination).isEmpty())
    }

    @Test
    fun `destination already owned is structured and not modified`() = withFixture {
        val destination = Files.createDirectory(parent.resolve("owned"))
        val ownership = assertIs<DesktopDataRootOwnershipResult.Acquired>(acquire(destination)).ownership
        try {
            val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
                preparer().prepare(source(), destination),
            )

            assertEquals(
                DesktopMigrationDestinationFailureKind.DESTINATION_ALREADY_IN_USE,
                failure.kind,
            )
            assertFalse(failure.createdDestination)
        } finally {
            ownership.close()
        }
    }

    @Test
    fun `same and Windows case-equivalent roots are rejected`() = withFixture {
        val same = assertIs<DesktopMigrationDestinationResult.Failure>(
            preparer().prepare(source(), sourceRoot),
        )
        assertEquals(DesktopMigrationDestinationFailureKind.DESTINATION_RELATION_UNSAFE, same.kind)

        if (isWindows()) {
            val caseVariant = Path.of(sourceRoot.toString().uppercase())
            val equivalent = assertIs<DesktopMigrationDestinationResult.Failure>(
                preparer().prepare(source(), caseVariant),
            )
            assertEquals(
                DesktopMigrationDestinationFailureKind.DESTINATION_RELATION_UNSAFE,
                equivalent.kind,
            )
        }
    }

    @Test
    fun `source and destination nesting is rejected in both directions`() = withFixture {
        val insideSource = sourceRoot.resolve("child")
        val insideFailure = assertIs<DesktopMigrationDestinationResult.Failure>(
            preparer().prepare(source(), insideSource),
        )
        assertEquals(
            DesktopMigrationDestinationFailureKind.DESTINATION_RELATION_UNSAFE,
            insideFailure.kind,
        )

        val outer = parent.parent.toAbsolutePath().normalize()
        val outerFailure = assertIs<DesktopMigrationDestinationResult.Failure>(
            preparer().prepare(source(), outer),
        )
        assertEquals(
            DesktopMigrationDestinationFailureKind.DESTINATION_RELATION_UNSAFE,
            outerFailure.kind,
        )
    }

    @Test
    fun `source backup subtree and source lock path are rejected`() = withFixture {
        val backups = Files.createDirectory(sourceRoot.resolve("backups"))
        val lockPath = sourceRoot.resolve(AppDataRootInfrastructure.OWNERSHIP_LOCK_FILE_NAME)

        listOf(backups, backups.resolve("nested"), lockPath).forEach { destination ->
            val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
                preparer().prepare(source(), destination),
            )
            assertEquals(
                DesktopMigrationDestinationFailureKind.DESTINATION_RELATION_UNSAFE,
                failure.kind,
            )
        }
    }

    @Test
    fun `ApplicationHome and descendants are rejected`() = withFixture {
        val applicationHome = Files.createDirectory(parent.resolve("application-home"))
        val configured = preparer(
            DesktopApplicationHomeResult.Available(
                applicationHome,
                DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST,
            ),
        )

        listOf(applicationHome, applicationHome.resolve("child")).forEach { destination ->
            val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
                configured.prepare(source(), destination),
            )
            assertEquals(
                DesktopMigrationDestinationFailureKind.APPLICATION_HOME_UNSAFE,
                failure.kind,
            )
        }
    }

    @Test
    fun `UNC source and destination are rejected on Windows before filesystem access`() = withFixture {
        if (!isWindows()) {
            println("WINDOWS_UNC_MIGRATION_TEST_SKIPPED")
            return@withFixture
        }
        val unc = Path.of("\\\\server\\share\\ccb-data")

        val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
            preparer().prepare(source(), unc),
        )

        assertEquals(DesktopMigrationDestinationFailureKind.NETWORK_PATH_UNSUPPORTED, failure.kind)

        val sourceFailure = assertIs<DesktopMigrationDestinationResult.Failure>(
            preparer().prepare(
                DesktopDataRootResolution.Resolved(
                    appDataRoot = unc,
                    provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                    bootstrapPath = parent.resolve("bootstrap.json"),
                ),
                parent.resolve("local-destination"),
            ),
        )
        assertEquals(
            DesktopMigrationDestinationFailureKind.NETWORK_PATH_UNSUPPORTED,
            sourceFailure.kind,
        )
    }

    @Test
    fun `detectable symbolic-link destination is rejected when fixture is available`() = withFixture {
        val actual = Files.createDirectory(parent.resolve("actual"))
        val alias = parent.resolve("alias")
        if (runCatching { Files.createSymbolicLink(alias, actual) }.isFailure) {
            println("SYMLINK_FIXTURE_UNAVAILABLE")
            return@withFixture
        }
        println("SYMLINK_FIXTURE_AVAILABLE")
        assertTrue(Files.isSameFile(actual, alias))

        val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
            preparer().prepare(source(), alias),
        )

        assertEquals(DesktopMigrationDestinationFailureKind.DESTINATION_UNSAFE, failure.kind)
    }

    @Test
    fun `Portable and CLI sources are unsupported`() = withFixture {
        listOf(
            DesktopDataRootProvenance.PORTABLE,
            DesktopDataRootProvenance.CLI_OVERRIDE,
        ).forEach { provenance ->
            val failure = assertIs<DesktopMigrationDestinationResult.Failure>(
                preparer().prepare(source(provenance), parent.resolve("destination-$provenance")),
            )
            assertEquals(
                DesktopMigrationDestinationFailureKind.UNSUPPORTED_SOURCE_PROVENANCE,
                failure.kind,
            )
        }
    }

    private fun preparer(
        applicationHome: DesktopApplicationHomeResult = DesktopApplicationHomeResult.Unavailable(
            DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT,
        ),
    ) = DesktopMigrationDestinationPreparer(
        applicationHomeResolver = { applicationHome },
        ownershipAcquire = DesktopDataRootOwnership::acquire,
    )

    private fun Fixture.source(
        provenance: DesktopDataRootProvenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
    ) = DesktopDataRootResolution.Resolved(
        appDataRoot = sourceRoot,
        provenance = provenance,
        bootstrapPath = parent.resolve("ChatChatBarDesktop.bootstrap.json"),
    )

    private fun acquire(root: Path): DesktopDataRootOwnershipResult =
        DesktopDataRootOwnership.acquire(
            DesktopDataRootResolution.Resolved(
                appDataRoot = root,
                provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                bootstrapPath = root.resolveSibling("bootstrap.json"),
            ),
        )

    private fun withFixture(block: Fixture.() -> Unit) {
        val parent = Files.createTempDirectory("desktop-migration-destination-")
        val source = Files.createDirectory(parent.resolve("source"))
        try {
            Fixture(parent, source).block()
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun children(root: Path): List<String> = Files.list(root).use { paths ->
        paths.map { it.fileName.toString() }.sorted().toList()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private data class Fixture(
        val parent: Path,
        val sourceRoot: Path,
    )
}
