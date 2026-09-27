package com.example.chatbar.desktop.security

internal class InMemoryDesktopSecretStore : DesktopSecretStore {
    val values = mutableMapOf<DesktopCredentialKey, String>()
    var loadFailure: Throwable? = null
    var saveFailureOnCall: Int? = null
    var deleteFailureOnCall: Int? = null
    private var saveCalls = 0
    private var deleteCalls = 0

    override fun load(key: DesktopCredentialKey): String? {
        loadFailure?.let { throw it }
        return values[key]
    }

    override fun save(key: DesktopCredentialKey, secret: String) {
        saveCalls++
        if (saveCalls == saveFailureOnCall) throw storageFailure("Injected secret save failure")
        values[key] = secret
    }

    override fun delete(key: DesktopCredentialKey) {
        deleteCalls++
        if (deleteCalls == deleteFailureOnCall) throw storageFailure("Injected secret delete failure")
        values.remove(key)
    }

    fun failNextLoad() {
        loadFailure = storageFailure("Injected secret load failure")
    }

    fun failSave(call: Int) {
        saveFailureOnCall = call
    }

    private fun storageFailure(message: String) = DesktopSecretStoreException(
        DesktopSecretStoreFailureKind.STORAGE_FAILURE,
        message,
    )
}

internal class ReversibleTestSecretProtector : SecretProtector {
    override fun protect(plaintext: ByteArray): ByteArray =
        plaintext.reversedArray().map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray()

    override fun unprotect(ciphertext: ByteArray): ByteArray =
        ciphertext.map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray().reversedArray()

    private companion object {
        const val MASK = 0x37
    }
}
