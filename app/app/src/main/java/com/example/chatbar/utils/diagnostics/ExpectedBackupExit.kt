package com.example.chatbar.utils.diagnostics

import android.app.ApplicationExitInfo
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One local process exit explicitly requested by the backup close/reopen flow. */
@Serializable
internal data class ExpectedBackupExit(val processId: Int, val recordedAt: Long) {
    init {
        require(processId > 0)
        require(recordedAt >= 0)
    }
}

internal object ExpectedBackupExitPolicy {
    const val MAX_EXIT_DELAY_MS = 10_000L

    fun matches(expected: ExpectedBackupExit?, processId: Int, timestamp: Long, reason: Int, status: Int): Boolean =
        expected != null && processId == expected.processId &&
            timestamp >= expected.recordedAt && timestamp - expected.recordedAt <= MAX_EXIT_DELAY_MS &&
            reason == ApplicationExitInfo.REASON_SIGNALED && status == OsConstants.SIGKILL
}

/** Kept outside restored/exported data, so restoring another device cannot authorize an exit. */
internal class ExpectedBackupExitStore(noBackupRoot: File) {
    private val file = File(noBackupRoot, "diagnostics/expected-backup-exit.json")
    private val json = Json

    fun record(exit: ExpectedBackupExit) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(json.encodeToString(exit).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(atomic::failWrite)
            throw error
        }
    }

    fun read(): ExpectedBackupExit? {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return runCatching {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { json.decodeFromString<ExpectedBackupExit>(it.readText()) }
        }.onFailure { Log.w("ExpectedBackupExit", "Invalid expected-exit marker; keep normal crash diagnostics", it) }.getOrNull()
    }

    fun clear() = AtomicFile(file).delete()
}
