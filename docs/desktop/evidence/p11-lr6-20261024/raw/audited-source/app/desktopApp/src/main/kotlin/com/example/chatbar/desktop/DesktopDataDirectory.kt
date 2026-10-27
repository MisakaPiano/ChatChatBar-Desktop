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
        applicationHomeResolution: DesktopApplicationHomeResult? = null,
        portableRootResolver: DesktopPortableRootResolver = DesktopPortableRootResolver(),
        portableActivationValidator: DesktopPortableRootActivationValidator =
            DesktopPortableRootActivationValidator(),
    ): DesktopDataRootResolution {
        return when (val selected = selectRootAuthority(environment, userHome, explicitOverride,
            applicationHomeResolution, portableRootResolver, capturePortableIdentity = false)) {
            is DesktopStartupRootSelection.Selected -> selected.root
            is DesktopStartupRootSelection.Failed -> selected.failure
            is DesktopStartupRootSelection.PortablePendingActivation ->
                portableResolution(selected, portableActivationValidator.validate(selected.candidate))
        }
    }

    /** Production startup selection. Never runs the Portable write probe or creates a root. */
    internal suspend fun selectRoot(explicitOverride: Path? = null): DesktopStartupRootSelection {
        val userHome = System.getProperty("user.home")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(Path::of) ?: Path.of(".").toAbsolutePath()
        return selectRoot(System.getenv(), userHome, explicitOverride)
    }

    /** Injectable authority inputs for isolated tests; uses the same read-only selection. */
    internal suspend fun selectRoot(
        environment: Map<String, String>,
        userHome: Path,
        explicitOverride: Path? = null,
        applicationHomeResolution: DesktopApplicationHomeResult? = null,
        portableRootResolver: DesktopPortableRootResolver = DesktopPortableRootResolver(),
    ): DesktopStartupRootSelection = selectRootAuthority(environment, userHome, explicitOverride,
        applicationHomeResolution, portableRootResolver, capturePortableIdentity = true)

    /** Requires an existing owner; this does not grant business readiness or perform G0. */
    internal fun activatePortable(
        selected: DesktopStartupRootSelection.PortablePendingActivation,
        validator: DesktopPortableRootActivationValidator = DesktopPortableRootActivationValidator(),
    ): DesktopDataRootResolution {
        val activation = if (!DesktopDataRootOwnership.isHeld(selected.appDataRoot)) {
            DesktopPortableActivationResult.Failure(DesktopPortableActivationFailureKind.OWNERSHIP_REQUIRED,
                "Portable activation requires existing data-root ownership")
        } else validator.validateOwned(selected.candidate, selected.identity)
        return portableResolution(selected, activation)
    }

    private fun portableResolution(
        selected: DesktopStartupRootSelection.PortablePendingActivation,
        activation: DesktopPortableActivationResult,
    ): DesktopDataRootResolution = when (activation) {
        is DesktopPortableActivationResult.Validated -> DesktopDataRootResolution.Resolved(
            activation.appDataRoot, DesktopDataRootProvenance.PORTABLE, selected.bootstrapPath)
        is DesktopPortableActivationResult.Failure -> DesktopDataRootResolution.Failed(
            DesktopDataRootResolutionFailureKind.PORTABLE_ACTIVATION_FAILED, selected.bootstrapPath,
            activation.message, portableActivationFailure = activation)
    }

    private suspend fun selectRootAuthority(
        environment: Map<String, String>,
        userHome: Path,
        explicitOverride: Path?,
        applicationHomeResolution: DesktopApplicationHomeResult?,
        portableRootResolver: DesktopPortableRootResolver,
        capturePortableIdentity: Boolean,
    ): DesktopStartupRootSelection {
        fun failed(failure: DesktopDataRootResolution.Failed) = DesktopStartupRootSelection.Failed(failure)
        fun selected(root: DesktopDataRootResolution.Resolved) = DesktopStartupRootSelection.Selected(root)
        val platformRoot = platformRoot(environment, userHome)
        val defaultRoot = platformRoot.resolve(DIRECTORY_NAME).normalize()
        val bootstrapPath = platformRoot.resolve(BOOTSTRAP_FILE_NAME).normalize()

        if (explicitOverride != null) {
            if (!explicitOverride.isAbsolute) {
                return failed(DesktopDataRootResolution.Failed(
                    kind = DesktopDataRootResolutionFailureKind.INVALID_CLI_OVERRIDE,
                    bootstrapPath = bootstrapPath,
                    message = "Explicit Desktop data-root override must be absolute",
                ))
            }
            return selected(DesktopDataRootResolution.Resolved(
                appDataRoot = explicitOverride.normalize(),
                provenance = DesktopDataRootProvenance.CLI_OVERRIDE,
                bootstrapPath = bootstrapPath,
            ))
        }

        when (val applicationHome = applicationHomeResolution ?: DesktopApplicationHome.resolve()) {
            is DesktopApplicationHomeResult.Unavailable -> Unit
            is DesktopApplicationHomeResult.Failure -> return failed(DesktopDataRootResolution.Failed(
                kind = DesktopDataRootResolutionFailureKind.APPLICATION_HOME_FAILED,
                bootstrapPath = bootstrapPath,
                message = applicationHome.message,
                applicationHomeFailure = applicationHome,
            ))

            is DesktopApplicationHomeResult.Available -> {
                when (val portable = portableRootResolver.resolve(applicationHome.path)) {
                    DesktopPortableRootResolution.NoMarker -> Unit
                    is DesktopPortableRootResolution.Failure -> return failed(DesktopDataRootResolution.Failed(
                        kind = DesktopDataRootResolutionFailureKind.PORTABLE_RESOLUTION_FAILED,
                        bootstrapPath = bootstrapPath,
                        message = portable.message,
                        portableResolutionFailure = portable,
                    ))

                    is DesktopPortableRootResolution.PortableCandidate -> {
                        val identity = try {
                            if (capturePortableIdentity) captureDesktopPortableIdentity(portable) else null
                        } catch (error: Exception) {
                            return failed(DesktopDataRootResolution.Failed(
                                DesktopDataRootResolutionFailureKind.PORTABLE_RESOLUTION_FAILED,
                                bootstrapPath, "Portable authority identity could not be established",
                                portableResolutionFailure = DesktopPortableRootResolution.Failure(
                                    DesktopPortableRootFailureKind.APPLICATION_HOME_UNSAFE,
                                    "Portable authority identity could not be established", error)))
                        }
                        return DesktopStartupRootSelection.PortablePendingActivation(portable, bootstrapPath, identity)
                    }
                }
            }
        }

        return when (val loadResult = DesktopBootstrapSettingsStore(bootstrapPath).load()) {
            is DesktopBootstrapLoadResult.Missing -> selected(DesktopDataRootResolution.Resolved(
                appDataRoot = defaultRoot,
                provenance = DesktopDataRootProvenance.MISSING_BOOTSTRAP_DEFAULT,
                bootstrapPath = bootstrapPath,
            ))

            is DesktopBootstrapLoadResult.Loaded -> when (loadResult.document.selection.mode) {
                DesktopDataRootMode.DEFAULT -> selected(DesktopDataRootResolution.Resolved(
                    appDataRoot = defaultRoot,
                    provenance = DesktopDataRootProvenance.BOOTSTRAP_DEFAULT,
                    bootstrapPath = bootstrapPath,
                ))

                DesktopDataRootMode.CUSTOM -> selected(DesktopDataRootResolution.Resolved(
                    appDataRoot = loadResult.document.selection.customRoot!!,
                    provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
                    bootstrapPath = bootstrapPath,
                ))
            }

            is DesktopBootstrapLoadResult.Failure -> failed(DesktopDataRootResolution.Failed(
                kind = DesktopDataRootResolutionFailureKind.BOOTSTRAP_LOAD_FAILED,
                bootstrapPath = bootstrapPath,
                message = loadResult.message,
                bootstrapFailure = loadResult,
            ))
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

/** Selection evidence only. In particular a pending Portable root is NOT a Resolved/Ready root. */
internal sealed interface DesktopStartupRootSelection {
    data class Selected(val root: DesktopDataRootResolution.Resolved) : DesktopStartupRootSelection
    data class Failed(val failure: DesktopDataRootResolution.Failed) : DesktopStartupRootSelection
    class PortablePendingActivation internal constructor(
        internal val candidate: DesktopPortableRootResolution.PortableCandidate,
        val bootstrapPath: Path,
        internal val identity: List<DesktopPortablePathIdentity>?,
    ) : DesktopStartupRootSelection {
        val appDataRoot: Path get() = candidate.appDataRoot
        val provenance: DesktopDataRootProvenance get() = DesktopDataRootProvenance.PORTABLE
    }
}

enum class DesktopDataRootProvenance {
    CLI_OVERRIDE,
    PORTABLE,
    BOOTSTRAP_DEFAULT,
    BOOTSTRAP_CUSTOM,
    MISSING_BOOTSTRAP_DEFAULT,
}

enum class DesktopDataRootResolutionFailureKind {
    INVALID_CLI_OVERRIDE,
    APPLICATION_HOME_FAILED,
    PORTABLE_RESOLUTION_FAILED,
    PORTABLE_ACTIVATION_FAILED,
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
        val applicationHomeFailure: DesktopApplicationHomeResult.Failure? = null,
        val portableResolutionFailure: DesktopPortableRootResolution.Failure? = null,
        val portableActivationFailure: DesktopPortableActivationResult.Failure? = null,
    ) : DesktopDataRootResolution
}

class DesktopDataRootResolutionException(
    val failure: DesktopDataRootResolution.Failed,
) : IllegalStateException("Desktop data-root resolution failed: ${failure.message}")
