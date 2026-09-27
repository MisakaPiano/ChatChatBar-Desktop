package com.example.chatbar.desktop.security

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

internal interface SecretProtector {
    fun protect(plaintext: ByteArray): ByteArray
    fun unprotect(ciphertext: ByteArray): ByteArray
}

internal class WindowsDpapiSecretProtector(
    private val osName: String = System.getProperty("os.name").orEmpty(),
) : SecretProtector {
    override fun protect(plaintext: ByteArray): ByteArray = invokeDpapi("protect") {
        Crypt32Util.cryptProtectData(
            plaintext,
            OPTIONAL_ENTROPY,
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
            "",
            null,
        )
    }

    override fun unprotect(ciphertext: ByteArray): ByteArray = invokeDpapi("unprotect") {
        Crypt32Util.cryptUnprotectData(
            ciphertext,
            OPTIONAL_ENTROPY,
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
            null,
        )
    }

    private fun invokeDpapi(operation: String, block: () -> ByteArray): ByteArray {
        if (!osName.startsWith("Windows", ignoreCase = true)) {
            throw DesktopSecretStoreException(
                DesktopSecretStoreFailureKind.UNSUPPORTED_PLATFORM,
                "Windows DPAPI is unavailable on this platform",
            )
        }
        return try {
            block()
        } catch (error: DesktopSecretStoreException) {
            throw error
        } catch (error: Exception) {
            throw DesktopSecretStoreException(
                DesktopSecretStoreFailureKind.PROTECTION_FAILURE,
                "Windows DPAPI $operation failed",
                error,
            )
        } catch (error: LinkageError) {
            throw DesktopSecretStoreException(
                DesktopSecretStoreFailureKind.PROTECTION_FAILURE,
                "Windows DPAPI $operation is unavailable",
                error,
            )
        }
    }

    private companion object {
        val OPTIONAL_ENTROPY = "ChatChatBarDesktop/SecretStore/v1".toByteArray(Charsets.UTF_8)
    }
}
