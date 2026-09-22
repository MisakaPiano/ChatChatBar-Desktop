package com.example.chatbar.desktop

import java.nio.file.Path

object DesktopDataDirectory {
    const val DIRECTORY_NAME = "ChatChatBarDesktop"
    const val BOOTSTRAP_FILE_NAME = "ChatChatBarDesktop.bootstrap.json"

    suspend fun resolveRoot(explicitOverride: Path? = null): DesktopDataRootResolution {
        val userHome = System.getProperty("user.home")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)
            ?: Path.of(".").toAbsolutePath()

        return resolveRoot(System.getenv(), userHome, explicitOverride)
    }

    internal suspend fun resolveRoot(
        environment: Map<String, String>,
        userHome: Path,
        explicitOverride: Path? = null,
    ): DesktopDataRootResolution {
        val platformRoot = platformRoot(environment, userHome)
        val defaultRoot = platformRoot.resolve(DIRECTORY_NAME).normalize()
        val bootstrapPath = platformRoot.resolve(BOOTSTRAP_FILE_NAME).normalize()

        if (explicitOverride != null) {
            if (!explicitOverride.isAbsolute) {
                return DesktopDataRootResolution.Failed(
                    kind = DesktopDataRootResolutionFailureKind.INVALID_CLI_OVERRIDE,
                    bootstrapPath = bootstrapPath,
                    message = "Explicit Desktop data-root override must be absolute",
                )
            }
            return DesktopDataRootResolution.Resolved(
                appDataRoot = explicitOverride.normalize(),
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = bootstrapPath,
            )
        }

        return when (val loadResult = DesktopBootstrapSettingsStore(bootstrapPath).load()) {
            is DesktopBootstrapLoadResult.Missing -> DesktopDataRootResolution.Resolved(
                appDataRoot = defaultRoot,
                provenance = DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT,
                bootstrapPath = bootstrapPath,
            )

            is DesktopBootstrapLoadResult.Loaded -> when (loadResult.document.selection.mode) {
                DesktopDataRootMode.DEFAULT -> DesktopDataRootResolution.Resolved(
                    appDataRoot = defaultRoot,
                    provenance = DesktopDataRootProvenance.BOOTSTRAP_DEFAULT,
                    bootstrapPath = bootstrapPath,
                )

                DesktopDataRootMode.CUSTOM -> DesktopDataRootResolution.Resolved(
                    appDataRoot = loadResult.document.selection.customRoot!!,
                    provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                    bootstrapPath = bootstrapPath,
                )
            }

            is DesktopBootstrapLoadResult.Failure -> DesktopDataRootResolution.Failed(
                kind = DesktopDataRootResolutionFailureKind.BOOTSTRAP_LOAD_FAILED,
                bootstrapPath = bootstrapPath,
                message = loadResult.message,
                bootstrapFailure = loadResult,
            )
        }
    }

    private fun platformRoot(environment: Map<String, String>, userHome: Path): Path {
        val localAppData = environment["LOCALAPPDATA"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)
        return (localAppData ?: userHome.resolve("AppData").resolve("Local")).normalize()
    }
}

enum class DesktopDataRootProvenance {
    CLI_OVERRIDE,
    BOOTSTRAP_DEFAULT,
    BOOTSTRAP_CUSTOM,
    MISSING_BOOTSTRAP_DEFAULT,
}

enum class DesktopDataRootResolutionFailureKind {
    INVALID_CLI_OVERRIDE,
    BOOTSTRAP_LOAD_FAILED,
}

sealed interface DesktopDataRootResolution {
    data class Resolved(
        val appDataRoot: Path,
        val provenance: DesktopDataRootProvenance,
        val bootstrapPath: Path,
    ) : DesktopDataRootResolution

    /**
     * Invalid CUSTOM selection 绝不能静默 fallback 到 default root；否则应用可能打开空目录，
     * 在用户看来等同于数据丢失。
     */
    data class Failed(
        val kind: DesktopDataRootResolutionFailureKind,
        val bootstrapPath: Path,
        val message: String,
        val bootstrapFailure: DesktopBootstrapLoadResult.Failure? = null,
    ) : DesktopDataRootResolution
}

class DesktopDataRootBootstrapException(
    val failure: DesktopDataRootResolution.Failed,
) : IllegalStateException("Desktop data-root bootstrap failed: ${failure.message}")
