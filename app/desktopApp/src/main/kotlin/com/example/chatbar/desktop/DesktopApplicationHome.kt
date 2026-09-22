package com.example.chatbar.desktop

import java.nio.file.InvalidPathException
import java.nio.file.Path

enum class DesktopApplicationHomeProvenance {
    PACKAGED_LAUNCHER_PROPERTY,
    INJECTED_DEVELOPMENT_TEST,
}

enum class DesktopApplicationHomeUnavailableReason {
    PROPERTY_ABSENT,
    UNEXPANDED_JPACKAGE_MACRO,
}

sealed interface DesktopApplicationHomeResult {
    data class Available(
        val path: Path,
        val provenance: DesktopApplicationHomeProvenance,
    ) : DesktopApplicationHomeResult

    data class Unavailable(
        val reason: DesktopApplicationHomeUnavailableReason,
    ) : DesktopApplicationHomeResult

    data class Failure(
        val message: String,
        val cause: Exception? = null,
    ) : DesktopApplicationHomeResult
}

/**
 * ApplicationHome 是 packaged application image 的根目录，不是 current working directory。
 * `user.dir` 会随 IDE、terminal 与 shortcut 的启动方式变化，绝不能用于发现 Portable data root。
 */
object DesktopApplicationHome {
    const val PROPERTY_NAME = "chatbar.desktop.applicationHome"
    internal const val UNEXPANDED_JPACKAGE_ROOT = "\$ROOTDIR"

    fun resolve(): DesktopApplicationHomeResult = resolve(
        packagedPropertyValue = System.getProperty(PROPERTY_NAME),
    )

    internal fun resolve(
        packagedPropertyValue: String?,
        injectedApplicationHome: Path? = null,
    ): DesktopApplicationHomeResult {
        if (injectedApplicationHome != null) {
            if (!injectedApplicationHome.isAbsolute) {
                return DesktopApplicationHomeResult.Failure(
                    "Injected Desktop ApplicationHome must be absolute",
                )
            }
            return DesktopApplicationHomeResult.Available(
                path = injectedApplicationHome.normalize(),
                provenance = DesktopApplicationHomeProvenance.INJECTED_DEVELOPMENT_TEST,
            )
        }

        if (packagedPropertyValue == null) {
            return DesktopApplicationHomeResult.Unavailable(
                DesktopApplicationHomeUnavailableReason.PROPERTY_ABSENT,
            )
        }
        if (packagedPropertyValue == UNEXPANDED_JPACKAGE_ROOT) {
            return DesktopApplicationHomeResult.Unavailable(
                DesktopApplicationHomeUnavailableReason.UNEXPANDED_JPACKAGE_MACRO,
            )
        }
        if (packagedPropertyValue.isBlank()) {
            return DesktopApplicationHomeResult.Failure(
                "Packaged Desktop ApplicationHome property is blank",
            )
        }

        val path = try {
            Path.of(packagedPropertyValue)
        } catch (error: InvalidPathException) {
            return DesktopApplicationHomeResult.Failure(
                message = "Packaged Desktop ApplicationHome property is not a valid path",
                cause = error,
            )
        }
        if (!path.isAbsolute) {
            return DesktopApplicationHomeResult.Failure(
                "Packaged Desktop ApplicationHome property must be absolute",
            )
        }
        return DesktopApplicationHomeResult.Available(
            path = path.normalize(),
            provenance = DesktopApplicationHomeProvenance.PACKAGED_LAUNCHER_PROPERTY,
        )
    }
}
