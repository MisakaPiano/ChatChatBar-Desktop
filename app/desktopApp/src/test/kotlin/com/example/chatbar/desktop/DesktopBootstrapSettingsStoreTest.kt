package com.example.chatbar.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopBootstrapSettingsStoreTest {
    @Test
    fun `construction and missing load are zero write`() = runTest {
        withTemporaryBootstrap { bootstrapPath ->
            val store = DesktopBootstrapSettingsStore(bootstrapPath)

            assertFalse(Files.exists(bootstrapPath.parent))
            val result = assertIs<DesktopBootstrapLoadResult.Missing>(store.load())

            assertEquals(DesktopDataRootSelection(), result.document.selection)
            assertFalse(Files.exists(bootstrapPath.parent))
            assertFalse(Files.exists(bootstrapPath))
        }
    }

    @Test
    fun `DEFAULT bootstrap resolves exact default root`() = runTest {
        withTemporaryBootstrap { bootstrapPath ->
            saveSelection(bootstrapPath, DesktopDataRootSelection())
            val platformRoot = bootstrapPath.parent

            val result = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to platformRoot.toString()),
                userHome = platformRoot.resolve("unused-home"),
            ))

            assertEquals(platformRoot.resolve(DesktopDataDirectory.DIRECTORY_NAME), result.appDataRoot)
            assertEquals(DesktopDataRootProvenance.BOOTSTRAP_DEFAULT, result.provenance)
            assertFalse(Files.exists(result.appDataRoot))
        }
    }

    @Test
    fun `CUSTOM absolute root resolves normalized path without creating it`() = runTest {
        withTemporaryBootstrap { bootstrapPath ->
            val customRoot = bootstrapPath.parent.resolve("custom").resolve("nested").resolve("..")
            saveSelection(bootstrapPath, DesktopDataRootSelection.custom(customRoot))

            val result = assertIs<DesktopDataRootResolution.Resolved>(DesktopDataDirectory.resolveRoot(
                environment = mapOf("LOCALAPPDATA" to bootstrapPath.parent.toString()),
                userHome = bootstrapPath.parent.resolve("unused-home"),
            ))

            assertEquals(customRoot.normalize(), result.appDataRoot)
            assertEquals(DesktopDataRootProvenance.BOOTSTRAP_CUSTOM, result.provenance)
            assertFalse(Files.exists(result.appDataRoot))
        }
    }

    @Test
    fun `relative and missing CUSTOM roots fail without fallback`() = runTest {
        val documents = listOf(
            bootstrapJson(mode = "CUSTOM", customRoot = "\"relative-root\"") to
                DesktopBootstrapLoadResult.Invalid::class,
            bootstrapJson(mode = "CUSTOM", customRoot = null) to
                DesktopBootstrapLoadResult.Invalid::class,
        )
        documents.forEach { (text, expectedType) ->
            withTemporaryBootstrap { bootstrapPath ->
                Files.createDirectories(bootstrapPath.parent)
                val bytes = text.toByteArray()
                Files.write(bootstrapPath, bytes)

                val loadResult = DesktopBootstrapSettingsStore(bootstrapPath).load()
                assertTrue(expectedType.isInstance(loadResult))
                assertContentEquals(bytes, bootstrapPath.readBytes())

                val resolution = assertIs<DesktopDataRootResolution.Failed>(
                    DesktopDataDirectory.resolveRoot(
                        environment = mapOf("LOCALAPPDATA" to bootstrapPath.parent.toString()),
                        userHome = bootstrapPath.parent.resolve("unused-home"),
                    ),
                )
                assertEquals(DesktopDataRootResolutionFailureKind.BOOTSTRAP_LOAD_FAILED, resolution.kind)
                assertFalse(Files.exists(bootstrapPath.parent.resolve(DesktopDataDirectory.DIRECTORY_NAME)))
            }
        }
    }

    @Test
    fun `wrong JSON node types return structured failures and preserve bytes`() = runTest {
        val documents = listOf(
            """{"formatVersion":{},"mode":"DEFAULT"}""" to DesktopBootstrapLoadResult.Corrupt::class,
            """{"formatVersion":1,"mode":[]}""" to DesktopBootstrapLoadResult.Corrupt::class,
            """{"formatVersion":1,"mode":"CUSTOM","customRoot":{}}""" to
                DesktopBootstrapLoadResult.Corrupt::class,
        )
        documents.forEach { (text, expectedType) ->
            withTemporaryBootstrap { bootstrapPath ->
                Files.createDirectories(bootstrapPath.parent)
                val bytes = text.toByteArray()
                Files.write(bootstrapPath, bytes)

                val result = DesktopBootstrapSettingsStore(bootstrapPath).load()

                assertTrue(expectedType.isInstance(result), "Expected $expectedType, got ${result::class}")
                assertContentEquals(bytes, bootstrapPath.readBytes())
            }
        }
    }

    @Test
    fun `corrupt JSON and unsupported version remain byte for byte unchanged`() = runTest {
        val documents = listOf(
            "{ malformed" to DesktopBootstrapLoadResult.Corrupt::class,
            bootstrapJson(formatVersion = 2, mode = "DEFAULT") to
                DesktopBootstrapLoadResult.UnsupportedFormatVersion::class,
        )
        documents.forEach { (text, expectedType) ->
            withTemporaryBootstrap { bootstrapPath ->
                Files.createDirectories(bootstrapPath.parent)
                val bytes = text.toByteArray()
                Files.write(bootstrapPath, bytes)

                val result = DesktopBootstrapSettingsStore(bootstrapPath).load()

                assertTrue(expectedType.isInstance(result))
                assertContentEquals(bytes, bootstrapPath.readBytes())
            }
        }
    }

    @Test
    fun `unknown root fields survive explicit save`() = runTest {
        withTemporaryBootstrap { bootstrapPath ->
            Files.createDirectories(bootstrapPath.parent)
            bootstrapPath.writeText(
                """
                {
                  "formatVersion": 1,
                  "mode": "DEFAULT",
                  "futureField": {"keep": true}
                }
                """.trimIndent(),
            )
            val store = DesktopBootstrapSettingsStore(bootstrapPath)
            val loaded = assertIs<DesktopBootstrapLoadResult.Loaded>(store.load())

            store.save(loaded.document, DesktopDataRootSelection.custom(bootstrapPath.parent.resolve("custom")))

            val root = Json.parseToJsonElement(bootstrapPath.readText()).jsonObject
            assertEquals("true", root.getValue("futureField").jsonObject.getValue("keep").jsonPrimitive.content)
            assertEquals("CUSTOM", root.getValue("mode").jsonPrimitive.content)
        }
    }

    @Test
    fun `successful save leaves no temporary residue`() = runTest {
        withTemporaryBootstrap { bootstrapPath ->
            val store = DesktopBootstrapSettingsStore(bootstrapPath)
            val missing = assertIs<DesktopBootstrapLoadResult.Missing>(store.load())

            store.save(missing.document, DesktopDataRootSelection())

            assertTrue(Files.isRegularFile(bootstrapPath))
            assertTrue(temporaryFiles(bootstrapPath).isEmpty())
        }
    }

    @Test
    fun `failed replacement preserves previous bootstrap and cleans temporary file`() = runTest {
        withTemporaryBootstrap { bootstrapPath ->
            val workingStore = DesktopBootstrapSettingsStore(bootstrapPath)
            val missing = assertIs<DesktopBootstrapLoadResult.Missing>(workingStore.load())
            val originalDocument = workingStore.save(missing.document, DesktopDataRootSelection())
            val originalBytes = bootstrapPath.readBytes()
            val failingStore = DesktopBootstrapSettingsStore(
                bootstrapPath = bootstrapPath,
                temporaryId = { "fixture" },
                replaceFile = { _, _ -> throw IOException("replacement fixture failure") },
            )

            assertFailsWith<IOException> {
                failingStore.save(
                    originalDocument,
                    DesktopDataRootSelection.custom(bootstrapPath.parent.resolve("custom")),
                )
            }

            assertContentEquals(originalBytes, bootstrapPath.readBytes())
            assertTrue(temporaryFiles(bootstrapPath).isEmpty())
        }
    }

    @Test
    fun `non regular bootstrap target is rejected`() = runTest {
        withTemporaryBootstrap { bootstrapPath ->
            Files.createDirectories(bootstrapPath)
            val store = DesktopBootstrapSettingsStore(bootstrapPath)

            assertIs<DesktopBootstrapLoadResult.Invalid>(store.load())
            assertFailsWith<IllegalArgumentException> {
                store.save(missingDocument(), DesktopDataRootSelection())
            }
        }
    }

    @Test
    fun `symbolic link bootstrap target is rejected when fixture is available`() = runTest {
        withTemporaryBootstrap { bootstrapPath ->
            Files.createDirectories(bootstrapPath.parent)
            val target = bootstrapPath.resolveSibling("actual-bootstrap.json")
            target.writeText(bootstrapJson(mode = "DEFAULT"))
            if (runCatching { Files.createSymbolicLink(bootstrapPath, target) }.isFailure) {
                println("SYMLINK_FIXTURE_UNAVAILABLE")
                return@withTemporaryBootstrap
            }
            println("SYMLINK_FIXTURE_AVAILABLE")
            val store = DesktopBootstrapSettingsStore(bootstrapPath)

            assertIs<DesktopBootstrapLoadResult.Invalid>(store.load())
            assertFailsWith<IllegalArgumentException> {
                store.save(missingDocument(), DesktopDataRootSelection())
            }
        }
    }

    private suspend fun saveSelection(path: Path, selection: DesktopDataRootSelection) {
        val store = DesktopBootstrapSettingsStore(path)
        val missing = assertIs<DesktopBootstrapLoadResult.Missing>(store.load())
        store.save(missing.document, selection)
    }

    private suspend fun withTemporaryBootstrap(block: suspend (Path) -> Unit) {
        val parent = Files.createTempDirectory("desktop-bootstrap-")
        val bootstrapParent = parent.resolve("platform-root")
        try {
            block(bootstrapParent.resolve(DesktopDataDirectory.BOOTSTRAP_FILE_NAME))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun temporaryFiles(bootstrapPath: Path): List<Path> {
        val parent = bootstrapPath.parent
        if (!Files.isDirectory(parent)) return emptyList()
        return Files.list(parent).use { paths ->
            paths.filter { it.fileName.toString().startsWith(".${bootstrapPath.fileName}.") }.toList()
        }
    }

    private fun bootstrapJson(
        formatVersion: Int = 1,
        mode: String,
        customRoot: String? = null,
    ): String = buildString {
        append("{\"formatVersion\":")
        append(formatVersion)
        append(",\"mode\":\"")
        append(mode)
        append('"')
        if (customRoot != null) {
            append(",\"customRoot\":")
            append(customRoot)
        }
        append('}')
    }

    private fun missingDocument() = DesktopBootstrapDocument(
        DesktopDataRootSelection(),
        kotlinx.serialization.json.buildJsonObject {},
    )
}
