package com.example.chatbar.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopBootstrapAuthorityTransactionTest {
    @Test
    fun `missing bootstrap commits custom destination from fresh missing document`() = runTest {
        withFixture { fixture ->
            val result = fixture.transaction().commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertCommitted(result, fixture.destination)
        }
    }

    @Test
    fun `DEFAULT bootstrap commits custom destination`() = runTest {
        withFixture { fixture ->
            fixture.save(DesktopDataRootSelection())

            val result = fixture.transaction().commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertCommitted(result, fixture.destination)
        }
    }

    @Test
    fun `CUSTOM expected source commits a new custom destination`() = runTest {
        withFixture { fixture ->
            fixture.save(DesktopDataRootSelection.custom(fixture.customSource))

            val result = fixture.transaction().commitCustomRoot(
                fixture.source(
                    DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                    fixture.customSource,
                ),
                fixture.destination,
            )

            assertCommitted(result, fixture.destination)
        }
    }

    @Test
    fun `fresh unknown fields survive and stale caller state cannot overwrite them`() = runTest {
        withFixture { fixture ->
            fixture.bootstrapPath.writeText(
                """
                {
                  "formatVersion": 1,
                  "mode": "DEFAULT",
                  "freshField": {"value": "fresh"}
                }
                """.trimIndent(),
            )

            val result = fixture.transaction().commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertCommitted(result, fixture.destination)
            val root = Json.parseToJsonElement(fixture.bootstrapPath.readText()).jsonObject
            assertEquals(
                "fresh",
                root.getValue("freshField").jsonObject.getValue("value").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `changed bootstrap authority is rejected without write`() = runTest {
        withFixture { fixture ->
            val current = fixture.parent.resolve("other-current-root")
            fixture.save(DesktopDataRootSelection.custom(current))
            val before = Files.readAllBytes(fixture.bootstrapPath)

            val result = fixture.transaction().commitCustomRoot(
                fixture.source(
                    DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                    fixture.customSource,
                ),
                fixture.destination,
            )

            assertIs<DesktopBootstrapAuthorityCommitResult.AuthorityChanged>(result)
            assertTrue(before.contentEquals(Files.readAllBytes(fixture.bootstrapPath)))
        }
    }

    @Test
    fun `Portable authority appearing before commit rejects bootstrap write`() = runTest {
        withFixture { fixture ->
            fixture.save(DesktopDataRootSelection())
            val applicationHome = Files.createDirectory(fixture.parent.resolve("application-home"))
            applicationHome.resolve(DesktopPortableRootResolver.PORTABLE_MARKER_FILE_NAME)
                .writeText(DesktopPortableRootResolver.PORTABLE_MARKER_TOKEN)
            Files.createDirectory(
                applicationHome.resolve(DesktopPortableRootResolver.PORTABLE_USER_DATA_DIRECTORY_NAME),
            )
            val before = Files.readAllBytes(fixture.bootstrapPath)
            val transaction = fixture.transaction(
                DesktopApplicationHomeResult.Available(
                    applicationHome,
                    DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST,
                ),
            )

            val result = transaction.commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertIs<DesktopBootstrapAuthorityCommitResult.AuthorityChanged>(result)
            assertTrue(before.contentEquals(Files.readAllBytes(fixture.bootstrapPath)))
        }
    }

    @Test
    fun `non-empty authority lock is unsafe and remains unchanged`() = runTest {
        withFixture { fixture ->
            val lockPath = fixture.authorityLockPath
            lockPath.writeText("not-owned-but-unsafe")

            val result = fixture.transaction().commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertIs<DesktopBootstrapAuthorityCommitResult.AuthorityLockUnsafe>(result)
            assertEquals("not-owned-but-unsafe", lockPath.readText())
            assertFalse(Files.exists(fixture.bootstrapPath))
        }
    }

    @Test
    fun `stale zero-length authority lock is reusable and persists`() = runTest {
        withFixture { fixture ->
            Files.createFile(fixture.authorityLockPath)

            val result = fixture.transaction().commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertCommitted(result, fixture.destination)
            assertTrue(Files.isRegularFile(fixture.authorityLockPath))
            assertEquals(0L, Files.size(fixture.authorityLockPath))
        }
    }

    @Test
    fun `same JVM overlapping authority transaction is structured Busy`() = runTest {
        withFixture { fixture ->
            val held = assertIs<AuthorityLockAcquisition.Acquired>(
                DesktopBootstrapAuthorityTransaction.acquireLock(fixture.bootstrapPath),
            ).lock
            try {
                val result = fixture.transaction().commitCustomRoot(
                    fixture.source(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT),
                    fixture.destination,
                )

                val busy = assertIs<DesktopBootstrapAuthorityCommitResult.Busy>(result)
                assertTrue(busy.sameJvm)
            } finally {
                held.close()
            }
        }
    }

    @Test
    fun `authority lock close is idempotent and permits reacquisition`() = runTest {
        withFixture { fixture ->
            val first = assertIs<AuthorityLockAcquisition.Acquired>(
                DesktopBootstrapAuthorityTransaction.acquireLock(fixture.bootstrapPath),
            ).lock

            first.close()
            first.close()

            assertIs<AuthorityLockAcquisition.Acquired>(
                DesktopBootstrapAuthorityTransaction.acquireLock(fixture.bootstrapPath),
            ).lock.close()
        }
    }

    @Test
    fun `directory authority lock target is unsafe without rewrite`() = runTest {
        withFixture { fixture ->
            Files.createDirectory(fixture.authorityLockPath)

            val result = fixture.transaction().commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertIs<DesktopBootstrapAuthorityCommitResult.AuthorityLockUnsafe>(result)
            assertTrue(Files.isDirectory(fixture.authorityLockPath))
        }
    }

    @Test
    fun `symbolic-link authority lock target is unsafe when fixture is available`() = runTest {
        withFixture { fixture ->
            val actual = fixture.parent.resolve("actual-authority-lock")
            Files.createFile(actual)
            if (runCatching { Files.createSymbolicLink(fixture.authorityLockPath, actual) }.isFailure) {
                println("AUTHORITY_LOCK_SYMLINK_FIXTURE_UNAVAILABLE")
                return@withFixture
            }
            println("AUTHORITY_LOCK_SYMLINK_FIXTURE_AVAILABLE")

            val result = fixture.transaction().commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertIs<DesktopBootstrapAuthorityCommitResult.AuthorityLockUnsafe>(result)
            assertTrue(Files.isSymbolicLink(fixture.authorityLockPath))
        }
    }

    @Test
    fun `save failure with expected source intact is pre-commit failure`() = runTest {
        withFixture { fixture ->
            fixture.save(DesktopDataRootSelection())
            val transaction = fixture.transaction(
                replaceFile = { _, _ -> throw IOException("replace failed before commit") },
            )

            val result = transaction.commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertIs<DesktopBootstrapAuthorityCommitResult.CommitFailedPreCommit>(result)
            val loaded = assertIs<DesktopBootstrapLoadResult.Loaded>(fixture.store.load())
            assertEquals(DesktopDataRootMode.DEFAULT, loaded.document.selection.mode)
        }
    }

    @Test
    fun `save exception after replace is classified as committed with warning`() = runTest {
        withFixture { fixture ->
            fixture.save(DesktopDataRootSelection())
            val transaction = fixture.transaction(
                replaceFile = { temporary, target ->
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                    throw IOException("fixture exception after replace")
                },
            )

            val result = assertIs<DesktopBootstrapAuthorityCommitResult.Committed>(
                transaction.commitCustomRoot(
                    fixture.source(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT),
                    fixture.destination,
                ),
            )

            assertNotNull(result.persistenceWarning)
            assertEquals(fixture.destination, result.destination)
        }
    }

    @Test
    fun `corrupt readback after save failure is commit indeterminate`() = runTest {
        withFixture { fixture ->
            fixture.save(DesktopDataRootSelection())
            val transaction = fixture.transaction(
                replaceFile = { temporary, target ->
                    Files.deleteIfExists(temporary)
                    target.writeText("{ malformed")
                    throw IOException("ambiguous replacement")
                },
            )

            val result = transaction.commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertIs<DesktopBootstrapAuthorityCommitResult.CommitIndeterminate>(result)
        }
    }

    @Test
    fun `unexpected successful readback is commit indeterminate`() = runTest {
        withFixture { fixture ->
            fixture.save(DesktopDataRootSelection())
            val transaction = fixture.transaction(
                replaceFile = { temporary, target ->
                    Files.deleteIfExists(temporary)
                    target.writeText("""{"formatVersion":1,"mode":"DEFAULT"}""")
                },
            )

            val result = transaction.commitCustomRoot(
                fixture.source(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT),
                fixture.destination,
            )

            assertIs<DesktopBootstrapAuthorityCommitResult.CommitIndeterminate>(result)
        }
    }

    @Test
    fun `Portable and CLI source provenance are unsupported without lock creation`() = runTest {
        withFixture { fixture ->
            listOf(
                DesktopDataRootProvenance.PORTABLE,
                DesktopDataRootProvenance.CLI_OVERRIDE,
            ).forEach { provenance ->
                val result = fixture.transaction().commitCustomRoot(
                    fixture.source(provenance, fixture.customSource),
                    fixture.destination,
                )

                assertIs<DesktopBootstrapAuthorityCommitResult.UnsupportedSourceProvenance>(result)
            }
            assertFalse(Files.exists(fixture.authorityLockPath))
        }
    }

    private fun assertCommitted(
        result: DesktopBootstrapAuthorityCommitResult,
        destination: Path,
    ) {
        val committed = assertIs<DesktopBootstrapAuthorityCommitResult.Committed>(result)
        assertEquals(destination.toAbsolutePath().normalize(), committed.destination)
        assertEquals(DesktopDataRootMode.CUSTOM, committed.document.selection.mode)
        assertTrue(committed.document.selection.customRoot!!.windowsEquals(destination))
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val parent = Files.createTempDirectory("desktop-bootstrap-authority-")
        try {
            block(Fixture(parent))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private data class Fixture(val parent: Path) {
        val bootstrapPath: Path = parent.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME)
        val authorityLockPath: Path = parent.resolve(
            DesktopBootstrapAuthorityTransaction.AUTHORITY_LOCK_FILE_NAME,
        )
        val defaultSource: Path = parent.resolve(DesktopDataDirectory.DIRECTORY_NAME)
        val customSource: Path = parent.resolve("current-custom")
        val destination: Path = parent.resolve("destination").toAbsolutePath().normalize()
        val store = DesktopBootstrapSettingsStore(bootstrapPath)

        fun source(
            provenance: DesktopDataRootProvenance,
            root: Path = defaultSource,
        ) = DesktopDataRootResolution.Resolved(
            appDataRoot = root.toAbsolutePath().normalize(),
            provenance = provenance,
            bootstrapPath = bootstrapPath,
        )

        fun transaction(
            applicationHome: DesktopApplicationHomeResult =
                DesktopApplicationHomeResult.Unavailable(
                    DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT,
                ),
            replaceFile: ((Path, Path) -> Unit)? = null,
        ) = DesktopBootstrapAuthorityTransaction(
            storeFactory = { path ->
                if (replaceFile == null) {
                    DesktopBootstrapSettingsStore(path)
                } else {
                    DesktopBootstrapSettingsStore(
                        bootstrapPath = path,
                        temporaryId = { "authority-fixture" },
                        replaceFile = replaceFile,
                    )
                }
            },
            applicationHomeResolver = { applicationHome },
            portableRootResolver = DesktopPortableRootResolver(),
        )

        suspend fun save(selection: DesktopDataRootSelection) {
            val current = store.load()
            val document = when (current) {
                is DesktopBootstrapLoadResult.Missing -> current.document
                is DesktopBootstrapLoadResult.Loaded -> current.document
                is DesktopBootstrapLoadResult.Failure -> error(current.message)
            }
            store.save(document, selection)
        }
    }
}
