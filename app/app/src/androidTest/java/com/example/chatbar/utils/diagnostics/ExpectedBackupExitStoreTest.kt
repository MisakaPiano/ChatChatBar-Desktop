package com.example.chatbar.utils.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.app.ApplicationExitInfo
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExpectedBackupExitStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun markerSurvivesRecreationAndIsConsumable() {
        val root = temp.newFolder()
        val marker = ExpectedBackupExit(Process.myPid(), 100_000)
        ExpectedBackupExitStore(root).record(marker)
        val reopened = ExpectedBackupExitStore(root)
        assertEquals(marker, reopened.read())
        assertEquals(9, OsConstants.SIGKILL)
        assertTrue(ExpectedBackupExitPolicy.matches(reopened.read(), marker.processId, 100_001,
            ApplicationExitInfo.REASON_SIGNALED, 9))
        reopened.clear()
        assertNull(ExpectedBackupExitStore(root).read())
    }

    @Test fun corruptMarkerFailsClosedWithoutRemovingOtherDiagnosticFiles() {
        val root = temp.newFolder()
        val file = File(root, "diagnostics/expected-backup-exit.json").apply { parentFile!!.mkdirs(); writeText("{broken") }
        val diagnostic = File(file.parentFile, "existing-crash.txt").apply { writeText("synthetic crash remains") }
        assertNull(ExpectedBackupExitStore(root).read())
        assertEquals("synthetic crash remains", diagnostic.readText())
    }

    @Test fun plannedBackupCloseDoesNotEraseExistingCrashOrEnterRestorableFiles() {
        val files = temp.newFolder()
        val noBackup = temp.newFolder()
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = noBackup
        }
        val report = File(files, "diagnostics/pending-crash-report.txt").apply {
            parentFile!!.mkdirs(); writeText("synthetic real crash report")
        }
        assertTrue(CrashReportManager.recordExpectedBackupExit(context).isSuccess)
        val marker = requireNotNull(ExpectedBackupExitStore(noBackup).read())
        assertEquals(Process.myPid(), marker.processId)
        assertEquals("synthetic real crash report", report.readText())
        assertFalse(File(files, "diagnostics/expected-backup-exit.json").exists())
        assertFalse(ExpectedBackupExitPolicy.matches(marker, marker.processId + 1, marker.recordedAt,
            ApplicationExitInfo.REASON_SIGNALED, 9))
    }
}
