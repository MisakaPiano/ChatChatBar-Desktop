package com.example.chatbar.utils.diagnostics

import android.app.ApplicationExitInfo
import android.system.OsConstants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExpectedBackupExitPolicyTest {
    private val marker = ExpectedBackupExit(123, 100_000)
    private fun matches(pid: Int = 123, time: Long = 100_001,
        reason: Int = ApplicationExitInfo.REASON_SIGNALED, status: Int = OsConstants.SIGKILL,
        expected: ExpectedBackupExit? = marker) = ExpectedBackupExitPolicy.matches(expected, pid, time, reason, status)

    @Test fun onlyExplicitMatchingKillIsExpected() { assertTrue(matches()) }
    @Test fun absentMarkerKeepsNormalDiagnostics() { assertFalse(matches(expected = null)) }
    @Test fun differentProcessIsNotSuppressed() { assertFalse(matches(pid = 124)) }
    @Test fun earlierExitIsNotSuppressed() { assertFalse(matches(time = 99_999)) }
    @Test fun staleMarkerCannotSuppressLaterDeath() {
        assertTrue(matches(time = 110_000))
        assertFalse(matches(time = 110_001))
        assertFalse(matches(time = Long.MAX_VALUE))
    }
    @Test fun anotherSignalIsNotSuppressed() { assertFalse(matches(status = OsConstants.SIGKILL + 1)) }
    @Test fun realCrashAnrAndMemoryReasonsAreNeverSuppressed() {
        listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_DEPENDENCY_DIED)
            .forEach { assertFalse("reason=$it", matches(reason = it)) }
    }
    @Test fun markerSerializationPreservesExactProcessAndTime() {
        assertEquals(marker, Json.decodeFromString<ExpectedBackupExit>(Json.encodeToString(marker)))
    }
    @Test fun invalidMarkerCannotAuthorizeExit() {
        listOf("{}", "{\"processId\":0,\"recordedAt\":1}", "{\"processId\":123,\"recordedAt\":-1}")
            .forEach { assertTrue(runCatching { Json.decodeFromString<ExpectedBackupExit>(it) }.isFailure) }
    }
}
