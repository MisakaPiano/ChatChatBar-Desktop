package com.example.chatbar.desktop

import java.io.IOException
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopSettingsDocument internal constructor(
    val settings: DesktopSettings,
    internal val source: JsonObject,
)

sealed interface DesktopSettingsLoadResult {
    data class Missing(val document: DesktopSettingsDocument) : DesktopSettingsLoadResult

    data class Loaded(val document: DesktopSettingsDocument) : DesktopSettingsLoadResult

    sealed interface Failure : DesktopSettingsLoadResult {
        val message: String
    }

    data class Corrupt(
        override val message: String,
        val cause: Exception? = null,
    ) : Failure

    data class Invalid(
        override val message: String,
    ) : Failure

    data class UnsupportedFormatVersion(
        val formatVersion: Int,
        override val message: String = "Unsupported Desktop settings format version: $formatVersion",
    ) : Failure
}

class DesktopSettingsStore internal constructor(
    appDataRoot: Path,
    private val temporaryId: () -> String,
    private val replaceFile: (temporary: Path, target: Path) -> Unit,
) {
    constructor(appDataRoot: Path) : this(
        appDataRoot = appDataRoot,
        temporaryId = { UUID.randomUUID().toString() },
        replaceFile = ::replaceSettingsFile,
    )

    private val appDataRoot = appDataRoot.toAbsolutePath().normalize()
    val settingsPath: Path = this.appDataRoot.resolve(SETTINGS_FILE_NAME)
    private val json = Json { prettyPrint = true }

    suspend fun load(): DesktopSettingsLoadResult = withContext(Dispatchers.IO) {
        unsafeRootReason()?.let { reason ->
            return@withContext DesktopSettingsLoadResult.Invalid(reason)
        }
        if (!Files.exists(settingsPath, LinkOption.NOFOLLOW_LINKS)) {
            return@withContext DesktopSettingsLoadResult.Missing(
                DesktopSettingsDocument(DesktopSettings(), buildJsonObject {}),
            )
        }
        unsafeTargetReason()?.let { reason ->
            return@withContext DesktopSettingsLoadResult.Invalid(reason)
        }

        val bytes = try {
            Files.newByteChannel(
                settingsPath,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                Channels.newInputStream(channel).use { input -> input.readBytes() }
            }
        } catch (error: IOException) {
            return@withContext DesktopSettingsLoadResult.Corrupt(
                message = "Desktop settings could not be read",
                cause = error,
            )
        }
        val root = try {
            json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            return@withContext DesktopSettingsLoadResult.Corrupt(
                message = "Desktop settings JSON is malformed",
                cause = error,
            )
        }

        decode(root)
    }

    suspend fun save(
        document: DesktopSettingsDocument,
        settings: DesktopSettings,
    ): DesktopSettingsDocument = withContext(Dispatchers.IO) {
        require(settings.formatVersion == CURRENT_DESKTOP_SETTINGS_FORMAT_VERSION)
        prepareSafeTarget()
        val merged = mergeDocument(document.source, settings)
        val temporary = settingsPath.resolveSibling(
            ".${settingsPath.fileName}.${temporaryId()}.tmp",
        )
        check(temporary.parent == settingsPath.parent) { "Unsafe Desktop settings temporary path" }
        try {
            Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).buffered().writer(StandardCharsets.UTF_8).use { writer ->
                writer.write(json.encodeToString(merged))
                writer.flush()
            }
            replaceFile(temporary, settingsPath)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
        DesktopSettingsDocument(settings, merged)
    }

    private fun decode(root: JsonObject): DesktopSettingsLoadResult {
        val formatVersion = root[FORMAT_VERSION]
            ?.jsonPrimitive
            ?.intOrNull
            ?: return DesktopSettingsLoadResult.Corrupt("Desktop settings formatVersion is missing or invalid")
        if (formatVersion != CURRENT_DESKTOP_SETTINGS_FORMAT_VERSION) {
            return DesktopSettingsLoadResult.UnsupportedFormatVersion(formatVersion)
        }

        val automatic = try {
            root[AUTOMATIC_BACKUP]?.jsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return DesktopSettingsLoadResult.Corrupt("Desktop settings automaticBackup object is missing or invalid")

        val enabled = automatic[ENABLED]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: return DesktopSettingsLoadResult.Corrupt("Automatic backup enabled flag is missing or invalid")
        val maximumCount = automatic[MAXIMUM_SNAPSHOT_COUNT]
            ?.jsonPrimitive
            ?.intOrNull
            ?: return DesktopSettingsLoadResult.Corrupt("Automatic backup maximumSnapshotCount is missing or invalid")
        val minimumInterval = parseDuration(automatic, MINIMUM_BACKUP_INTERVAL)
            ?: return DesktopSettingsLoadResult.Invalid("Automatic backup minimumBackupInterval is invalid")
        val checkInterval = parseDuration(automatic, CHECK_INTERVAL)
            ?: return DesktopSettingsLoadResult.Invalid("Automatic backup checkInterval is invalid")

        val automaticSettings = try {
            DesktopAutomaticBackupSettings(
                enabled = enabled,
                minimumBackupInterval = minimumInterval,
                maximumSnapshotCount = maximumCount,
                checkInterval = checkInterval,
            )
        } catch (error: IllegalArgumentException) {
            return DesktopSettingsLoadResult.Invalid(
                error.message ?: "Automatic backup settings are invalid",
            )
        }
        val settings = DesktopSettings(
            formatVersion = formatVersion,
            automaticBackup = automaticSettings,
        )
        return DesktopSettingsLoadResult.Loaded(DesktopSettingsDocument(settings, root))
    }

    private fun parseDuration(root: JsonObject, name: String): Duration? {
        val value = try {
            root[name]?.jsonPrimitive?.content
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return try {
            Duration.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun mergeDocument(source: JsonObject, settings: DesktopSettings): JsonObject {
        val sourceAutomatic = runCatching { source[AUTOMATIC_BACKUP]?.jsonObject }
            .getOrNull()
            .orEmpty()
        val automatic = JsonObject(sourceAutomatic.toMutableMap().apply {
            put(ENABLED, kotlinx.serialization.json.JsonPrimitive(settings.automaticBackup.enabled))
            put(
                MINIMUM_BACKUP_INTERVAL,
                kotlinx.serialization.json.JsonPrimitive(settings.automaticBackup.minimumBackupInterval.toString()),
            )
            put(
                MAXIMUM_SNAPSHOT_COUNT,
                kotlinx.serialization.json.JsonPrimitive(settings.automaticBackup.maximumSnapshotCount),
            )
            put(
                CHECK_INTERVAL,
                kotlinx.serialization.json.JsonPrimitive(settings.automaticBackup.checkInterval.toString()),
            )
        })
        return JsonObject(source.toMutableMap().apply {
            put(FORMAT_VERSION, kotlinx.serialization.json.JsonPrimitive(settings.formatVersion))
            put(AUTOMATIC_BACKUP, automatic)
        })
    }

    private fun unsafeRootReason(): String? = when {
        Files.exists(appDataRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(appDataRoot) ->
            "App-data root cannot be a symbolic link"
        Files.exists(appDataRoot, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(appDataRoot, LinkOption.NOFOLLOW_LINKS) ->
            "App-data root must be a directory"
        else -> null
    }

    private fun unsafeTargetReason(): String? = when {
        Files.isSymbolicLink(settingsPath) -> "Desktop settings file cannot be a symbolic link"
        !Files.isRegularFile(settingsPath, LinkOption.NOFOLLOW_LINKS) ->
            "Desktop settings target must be a regular file"
        else -> null
    }

    private fun prepareSafeTarget() {
        if (Files.exists(appDataRoot, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(appDataRoot)) { "App-data root cannot be a symbolic link" }
            require(Files.isDirectory(appDataRoot, LinkOption.NOFOLLOW_LINKS)) {
                "App-data root must be a directory"
            }
        } else {
            Files.createDirectories(appDataRoot)
        }
        if (Files.exists(settingsPath, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(settingsPath)) {
                "Desktop settings file cannot be a symbolic link"
            }
            require(Files.isRegularFile(settingsPath, LinkOption.NOFOLLOW_LINKS)) {
                "Desktop settings target must be a regular file"
            }
        }
    }

    companion object {
        const val SETTINGS_FILE_NAME = "desktop-settings.json"

        private const val FORMAT_VERSION = "formatVersion"
        private const val AUTOMATIC_BACKUP = "automaticBackup"
        private const val ENABLED = "enabled"
        private const val MINIMUM_BACKUP_INTERVAL = "minimumBackupInterval"
        private const val MAXIMUM_SNAPSHOT_COUNT = "maximumSnapshotCount"
        private const val CHECK_INTERVAL = "checkInterval"
    }
}

private fun replaceSettingsFile(temporary: Path, target: Path) {
    try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
