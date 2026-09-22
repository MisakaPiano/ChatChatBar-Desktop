package com.example.chatbar.desktop

import java.io.IOException
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

const val CURRENT_DESKTOP_BOOTSTRAP_FORMAT_VERSION = 1

enum class DesktopDataRootMode {
    DEFAULT,
    CUSTOM,
}

data class DesktopDataRootSelection(
    val formatVersion: Int = CURRENT_DESKTOP_BOOTSTRAP_FORMAT_VERSION,
    val mode: DesktopDataRootMode = DesktopDataRootMode.DEFAULT,
    val customRoot: Path? = null,
) {
    init {
        require(formatVersion == CURRENT_DESKTOP_BOOTSTRAP_FORMAT_VERSION) {
            "Unsupported Desktop bootstrap format version: $formatVersion"
        }
        when (mode) {
            DesktopDataRootMode.DEFAULT -> require(customRoot == null) {
                "DEFAULT data-root selection must not define customRoot"
            }

            DesktopDataRootMode.CUSTOM -> require(customRoot != null && customRoot.isAbsolute) {
                "CUSTOM data-root selection requires an absolute customRoot"
            }
        }
    }

    companion object {
        fun custom(root: Path): DesktopDataRootSelection = DesktopDataRootSelection(
            mode = DesktopDataRootMode.CUSTOM,
            customRoot = root.normalize(),
        )
    }
}

class DesktopBootstrapDocument internal constructor(
    val selection: DesktopDataRootSelection,
    internal val source: JsonObject,
)

sealed interface DesktopBootstrapLoadResult {
    data class Missing(val document: DesktopBootstrapDocument) : DesktopBootstrapLoadResult

    data class Loaded(val document: DesktopBootstrapDocument) : DesktopBootstrapLoadResult

    sealed interface Failure : DesktopBootstrapLoadResult {
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
        override val message: String = "Unsupported Desktop bootstrap format version: $formatVersion",
    ) : Failure
}

/**
 * Bootstrap/root-location authority 必须位于 selected appDataRoot 外部；否则读取 authority 前先要
 * 知道 root，形成 bootstrap paradox（自举问题），且 snapshot/restore 可能错误改变 root selection。
 * Construction 与 load 都是 zero-write，只有显式 [save] 会创建或替换 bootstrap 文件。
 */
class DesktopBootstrapSettingsStore internal constructor(
    bootstrapPath: Path,
    private val temporaryId: () -> String,
    private val replaceFile: (temporary: Path, target: Path) -> Unit,
) {
    constructor(bootstrapPath: Path) : this(
        bootstrapPath = bootstrapPath,
        temporaryId = { UUID.randomUUID().toString() },
        replaceFile = ::replaceBootstrapFile,
    )

    val bootstrapPath: Path = bootstrapPath.toAbsolutePath().normalize()
    private val json = Json { prettyPrint = true }

    suspend fun load(): DesktopBootstrapLoadResult = withContext(Dispatchers.IO) {
        if (!Files.exists(bootstrapPath, LinkOption.NOFOLLOW_LINKS)) {
            return@withContext DesktopBootstrapLoadResult.Missing(
                DesktopBootstrapDocument(DesktopDataRootSelection(), buildJsonObject {}),
            )
        }
        unsafeTargetReason()?.let { reason ->
            return@withContext DesktopBootstrapLoadResult.Invalid(reason)
        }

        val bytes = try {
            Files.newByteChannel(
                bootstrapPath,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                Channels.newInputStream(channel).use { input -> input.readBytes() }
            }
        } catch (error: IOException) {
            return@withContext DesktopBootstrapLoadResult.Corrupt(
                message = "Desktop bootstrap could not be read",
                cause = error,
            )
        }
        val root = try {
            json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
        } catch (error: Exception) {
            return@withContext DesktopBootstrapLoadResult.Corrupt(
                message = "Desktop bootstrap JSON is malformed",
                cause = error,
            )
        } ?: return@withContext DesktopBootstrapLoadResult.Corrupt(
            "Desktop bootstrap JSON root must be an object",
        )

        decode(root)
    }

    suspend fun save(
        document: DesktopBootstrapDocument,
        selection: DesktopDataRootSelection,
    ): DesktopBootstrapDocument = withContext(Dispatchers.IO) {
        require(selection.formatVersion == CURRENT_DESKTOP_BOOTSTRAP_FORMAT_VERSION)
        prepareSafeTarget()
        val merged = mergeDocument(document.source, selection)
        val temporary = bootstrapPath.resolveSibling(
            ".${bootstrapPath.fileName}.${temporaryId()}.tmp",
        )
        check(temporary.parent == bootstrapPath.parent) { "Unsafe Desktop bootstrap temporary path" }
        try {
            Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).buffered().writer(StandardCharsets.UTF_8).use { writer ->
                writer.write(json.encodeToString(merged))
                writer.flush()
            }
            replaceFile(temporary, bootstrapPath)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
        DesktopBootstrapDocument(selection, merged)
    }

    private fun decode(root: JsonObject): DesktopBootstrapLoadResult {
        val formatVersion = root.primitive(FORMAT_VERSION)?.intOrNull
            ?: return DesktopBootstrapLoadResult.Corrupt(
                "Desktop bootstrap formatVersion is missing or invalid",
            )
        if (formatVersion != CURRENT_DESKTOP_BOOTSTRAP_FORMAT_VERSION) {
            return DesktopBootstrapLoadResult.UnsupportedFormatVersion(formatVersion)
        }

        val modeValue = root.stringValue(MODE)
            ?: return DesktopBootstrapLoadResult.Corrupt(
                "Desktop bootstrap mode is missing or invalid",
            )
        val mode = try {
            DesktopDataRootMode.valueOf(modeValue)
        } catch (_: IllegalArgumentException) {
            return DesktopBootstrapLoadResult.Invalid("Desktop bootstrap mode is unsupported: $modeValue")
        }

        val selection = when (mode) {
            DesktopDataRootMode.DEFAULT -> {
                if (CUSTOM_ROOT in root) {
                    return DesktopBootstrapLoadResult.Invalid(
                        "DEFAULT Desktop bootstrap must not define customRoot",
                    )
                }
                DesktopDataRootSelection(formatVersion = formatVersion)
            }

            DesktopDataRootMode.CUSTOM -> {
                val value = root.stringValue(CUSTOM_ROOT)
                    ?: return if (CUSTOM_ROOT in root) {
                        DesktopBootstrapLoadResult.Corrupt(
                            "Desktop bootstrap customRoot has an invalid JSON type",
                        )
                    } else {
                        DesktopBootstrapLoadResult.Invalid(
                            "CUSTOM Desktop bootstrap is missing customRoot",
                        )
                    }
                val path = try {
                    Path.of(value)
                } catch (_: InvalidPathException) {
                    return DesktopBootstrapLoadResult.Invalid(
                        "Desktop bootstrap customRoot is not a valid path",
                    )
                }
                if (!path.isAbsolute) {
                    return DesktopBootstrapLoadResult.Invalid(
                        "Desktop bootstrap customRoot must be absolute",
                    )
                }
                DesktopDataRootSelection.custom(path)
            }
        }
        return DesktopBootstrapLoadResult.Loaded(DesktopBootstrapDocument(selection, root))
    }

    private fun mergeDocument(
        source: JsonObject,
        selection: DesktopDataRootSelection,
    ): JsonObject = JsonObject(source.toMutableMap().apply {
        put(FORMAT_VERSION, JsonPrimitive(selection.formatVersion))
        put(MODE, JsonPrimitive(selection.mode.name))
        when (selection.mode) {
            DesktopDataRootMode.DEFAULT -> remove(CUSTOM_ROOT)
            DesktopDataRootMode.CUSTOM -> put(
                CUSTOM_ROOT,
                JsonPrimitive(selection.customRoot!!.normalize().toString()),
            )
        }
    })

    private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive

    private fun JsonObject.stringValue(name: String): String? =
        primitive(name)?.takeIf(JsonPrimitive::isString)?.content

    private fun unsafeTargetReason(): String? = when {
        Files.isSymbolicLink(bootstrapPath) -> "Desktop bootstrap file cannot be a symbolic link"
        !Files.isRegularFile(bootstrapPath, LinkOption.NOFOLLOW_LINKS) ->
            "Desktop bootstrap target must be a regular file"
        else -> null
    }

    private fun prepareSafeTarget() {
        val parent = bootstrapPath.parent
            ?: throw IllegalArgumentException("Desktop bootstrap path must have a parent")
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(parent)) { "Desktop bootstrap parent must be a directory" }
        } else {
            Files.createDirectories(parent)
        }
        if (Files.exists(bootstrapPath, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(bootstrapPath)) {
                "Desktop bootstrap file cannot be a symbolic link"
            }
            require(Files.isRegularFile(bootstrapPath, LinkOption.NOFOLLOW_LINKS)) {
                "Desktop bootstrap target must be a regular file"
            }
        }
    }

    companion object {
        private const val FORMAT_VERSION = "formatVersion"
        private const val MODE = "mode"
        private const val CUSTOM_ROOT = "customRoot"
    }
}

private fun replaceBootstrapFile(temporary: Path, target: Path) {
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
