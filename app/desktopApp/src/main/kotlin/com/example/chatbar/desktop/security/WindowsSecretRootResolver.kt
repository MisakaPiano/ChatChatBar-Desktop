package com.example.chatbar.desktop.security

import java.nio.file.InvalidPathException
import java.nio.file.Path

object WindowsSecretRootResolver {
    const val DIRECTORY_NAME = "ChatChatBarDesktopCredentials"

    fun resolve(selectedAppDataRoot: Path? = null): Path =
        resolve(System.getenv(), selectedAppDataRoot)

    internal fun resolve(
        environment: Map<String, String>,
        selectedAppDataRoot: Path? = null,
    ): Path {
        val localAppDataValue = environment["LOCALAPPDATA"]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw DesktopSecretStoreException(
                DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
                "Windows LocalAppData is unavailable for credential storage",
            )
        val localAppData = try {
            Path.of(localAppDataValue)
        } catch (error: InvalidPathException) {
            throw DesktopSecretStoreException(
                DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
                "Windows LocalAppData is not a valid credential-storage path",
                error,
            )
        }
        if (!localAppData.isAbsolute) {
            throw DesktopSecretStoreException(
                DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
                "Windows LocalAppData must be absolute for credential storage",
            )
        }

        val secretRoot = localAppData.resolve(DIRECTORY_NAME).normalize()
        if (selectedAppDataRoot != null) {
            if (!selectedAppDataRoot.isAbsolute) {
                throw DesktopSecretStoreException(
                    DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
                    "Selected app-data root must be absolute when validating credential isolation",
                )
            }
            val dataRoot = selectedAppDataRoot.normalize()
            if (secretRoot.startsWith(dataRoot) || dataRoot.startsWith(secretRoot)) {
                throw DesktopSecretStoreException(
                    DesktopSecretStoreFailureKind.ROOT_UNAVAILABLE,
                    "Credential storage must not overlap the selected app-data root",
                )
            }
        }
        return secretRoot
    }
}
