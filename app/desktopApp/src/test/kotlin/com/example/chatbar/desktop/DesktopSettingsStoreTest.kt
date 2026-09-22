package com.example.chatbar.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
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

class DesktopSettingsStoreTest {
    @Test
    fun `Project defaults are exact`() {
        val defaults = DesktopSettings().automaticBackup

        assertFalse(defaults.enabled)
        assertEquals(Duration.ofHours(24), defaults.minimumBackupInterval)
        assertEquals(7, defaults.maximumSnapshotCount)
        assertEquals(Duration.ofHours(1), defaults.checkInterval)
        assertEquals(1, DesktopSettings().formatVersion)
    }

    @Test
    fun `construction and missing load do not create root or file`() = runTest {
        withTemporaryParent { root ->
            val store = DesktopSettingsStore(root)

            assertFalse(Files.exists(root))
            val result = assertIs<DesktopSettingsLoadResult.Missing>(store.load())

            assertEquals(DesktopSettings(), result.document.settings)
            assertFalse(Files.exists(root))
            assertFalse(Files.exists(store.settingsPath))
        }
    }

    @Test
    fun `valid settings round trip uses human readable durations`() = runTest {
        withTemporaryParent { root ->
            val store = DesktopSettingsStore(root)
            val missing = assertIs<DesktopSettingsLoadResult.Missing>(store.load())
            val settings = DesktopSettings(
                automaticBackup = DesktopAutomaticBackupSettings(
                    enabled = true,
                    minimumBackupInterval = Duration.ZERO,
                    maximumSnapshotCount = 3,
                    checkInterval = Duration.ofMinutes(15),
                ),
            )

            store.save(missing.document, settings)
            val loaded = assertIs<DesktopSettingsLoadResult.Loaded>(store.load())

            assertEquals(settings, loaded.document.settings)
            val text = store.settingsPath.readText()
            assertTrue("\"minimumBackupInterval\": \"PT0S\"" in text)
            assertTrue("\"checkInterval\": \"PT15M\"" in text)
        }
    }

    @Test
    fun `unknown root and nested fields survive explicit save`() = runTest {
        withTemporaryParent { root ->
            Files.createDirectories(root)
            val store = DesktopSettingsStore(root)
            store.settingsPath.writeText(
                """
                {
                  "formatVersion": 1,
                  "futureField": "keep-root",
                  "automaticBackup": {
                    "enabled": true,
                    "minimumBackupInterval": "PT24H",
                    "maximumSnapshotCount": 7,
                    "checkInterval": "PT1H",
                    "futureNestedField": "keep-nested"
                  }
                }
                """.trimIndent(),
            )
            val loaded = assertIs<DesktopSettingsLoadResult.Loaded>(store.load())

            store.save(
                loaded.document,
                loaded.document.settings.copy(
                    automaticBackup = loaded.document.settings.automaticBackup.copy(
                        maximumSnapshotCount = 9,
                    ),
                ),
            )

            val rootJson = Json.parseToJsonElement(store.settingsPath.readText()).jsonObject
            assertEquals("keep-root", rootJson.getValue("futureField").jsonPrimitive.content)
            val automatic = rootJson.getValue("automaticBackup").jsonObject
            assertEquals("keep-nested", automatic.getValue("futureNestedField").jsonPrimitive.content)
            assertEquals(9, automatic.getValue("maximumSnapshotCount").jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `corrupt JSON is distinct and remains byte for byte unchanged`() = runTest {
        withTemporaryParent { root ->
            Files.createDirectories(root)
            val store = DesktopSettingsStore(root)
            val bytes = "{ definitely not JSON".toByteArray()
            Files.write(store.settingsPath, bytes)

            assertIs<DesktopSettingsLoadResult.Corrupt>(store.load())

            assertContentEquals(bytes, store.settingsPath.readBytes())
        }
    }

    @Test
    fun `unsupported format version remains unchanged`() = runTest {
        withTemporaryParent { root ->
            Files.createDirectories(root)
            val store = DesktopSettingsStore(root)
            val bytes = validJson(formatVersion = 2).toByteArray()
            Files.write(store.settingsPath, bytes)

            val result = assertIs<DesktopSettingsLoadResult.UnsupportedFormatVersion>(store.load())

            assertEquals(2, result.formatVersion)
            assertContentEquals(bytes, store.settingsPath.readBytes())
        }
    }

    @Test
    fun `semantically invalid settings remain unchanged`() = runTest {
        val invalidDocuments = listOf(
            validJson(minimumInterval = "not-a-duration"),
            validJson(minimumInterval = "-PT1H"),
            validJson(maximumCount = 0),
            validJson(checkInterval = "PT0S"),
        )
        invalidDocuments.forEach { text ->
            withTemporaryParent { root ->
                Files.createDirectories(root)
                val store = DesktopSettingsStore(root)
                val bytes = text.toByteArray()
                Files.write(store.settingsPath, bytes)

                assertIs<DesktopSettingsLoadResult.Invalid>(store.load())
                assertContentEquals(bytes, store.settingsPath.readBytes())
            }
        }
    }

    @Test
    fun `non regular settings target is rejected`() = runTest {
        withTemporaryParent { root ->
            Files.createDirectories(root.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME))
            val store = DesktopSettingsStore(root)

            assertIs<DesktopSettingsLoadResult.Invalid>(store.load())
            assertFailsWith<IllegalArgumentException> {
                store.save(missingDocument(), DesktopSettings())
            }
        }
    }

    @Test
    fun `symbolic link settings target is rejected when fixture is available`() = runTest {
        withTemporaryParent { root ->
            Files.createDirectories(root)
            val target = root.resolve("actual-settings.json")
            target.writeText(validJson())
            val link = root.resolve(DesktopSettingsStore.SETTINGS_FILE_NAME)
            if (runCatching { Files.createSymbolicLink(link, target) }.isFailure) {
                println("SYMLINK_FIXTURE_UNAVAILABLE")
                return@withTemporaryParent
            }
            println("SYMLINK_FIXTURE_AVAILABLE")

            val store = DesktopSettingsStore(root)

            assertIs<DesktopSettingsLoadResult.Invalid>(store.load())
            assertFailsWith<IllegalArgumentException> {
                store.save(missingDocument(), DesktopSettings())
            }
        }
    }

    @Test
    fun `successful save leaves no temporary file`() = runTest {
        withTemporaryParent { root ->
            val store = DesktopSettingsStore(root)
            val missing = assertIs<DesktopSettingsLoadResult.Missing>(store.load())

            store.save(missing.document, DesktopSettings())

            assertTrue(Files.isRegularFile(store.settingsPath))
            assertTrue(temporaryFiles(root).isEmpty())
        }
    }

    @Test
    fun `failed replacement preserves previous settings and cleans temporary file`() = runTest {
        withTemporaryParent { root ->
            val workingStore = DesktopSettingsStore(root)
            val missing = assertIs<DesktopSettingsLoadResult.Missing>(workingStore.load())
            val originalDocument = workingStore.save(missing.document, DesktopSettings())
            val originalBytes = workingStore.settingsPath.readBytes()
            val failingStore = DesktopSettingsStore(
                appDataRoot = root,
                temporaryId = { "fixture" },
                replaceFile = { _, _ -> throw IOException("replacement fixture failure") },
            )

            assertFailsWith<IOException> {
                failingStore.save(
                    originalDocument,
                    DesktopSettings(
                        automaticBackup = DesktopAutomaticBackupSettings(enabled = true),
                    ),
                )
            }

            assertContentEquals(originalBytes, workingStore.settingsPath.readBytes())
            assertTrue(temporaryFiles(root).isEmpty())
        }
    }

    private suspend fun withTemporaryParent(block: suspend (Path) -> Unit) {
        val parent = Files.createTempDirectory("desktop-settings-")
        try {
            block(parent.resolve("app-data"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private fun temporaryFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().startsWith(".desktop-settings.json.") }.toList()
        }
    }

    private fun validJson(
        formatVersion: Int = 1,
        minimumInterval: String = "PT24H",
        maximumCount: Int = 7,
        checkInterval: String = "PT1H",
    ): String =
        """
        {
          "formatVersion": $formatVersion,
          "automaticBackup": {
            "enabled": false,
            "minimumBackupInterval": "$minimumInterval",
            "maximumSnapshotCount": $maximumCount,
            "checkInterval": "$checkInterval"
          }
        }
        """.trimIndent()

    private fun missingDocument() = DesktopSettingsDocument(
        DesktopSettings(),
        kotlinx.serialization.json.buildJsonObject {},
    )
}
