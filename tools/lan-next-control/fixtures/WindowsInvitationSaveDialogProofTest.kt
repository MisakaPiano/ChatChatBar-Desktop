package com.example.chatbar.desktop

import androidx.compose.ui.awt.ComposeWindow
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** One explicit hosted-Windows proof of the production picker and invitation file writer. */
class WindowsInvitationSaveDialogProofTest {
    @Test(timeout = 120_000)
    fun realSaveDialogPreservesTheExistingInvitationAndAcceptsANewName() {
        check(System.getenv("GITHUB_ACTIONS") == "true" &&
            System.getenv("CCB_WINDOWS_INVITE_DIALOG_PROOF") == "true" &&
            DesktopWindowChrome.isWindows && !GraphicsEnvironment.isHeadless()) { "INVITE_DIALOG_GRAPHICAL_CLOUD_REQUIRED" }
        val runner = Path.of(checkNotNull(System.getenv("RUNNER_TEMP"))).toRealPath()
        val script = Path.of(checkNotNull(System.getenv("CCB_INVITE_DIALOG_AUTOMATION_SCRIPT"))).toAbsolutePath().normalize()
        check(script.parent == runner && script.fileName.toString() == "invoke-owned-invitation-save.ps1" &&
            Files.isRegularFile(script, NOFOLLOW_LINKS) && script.toRealPath() == script) { "INVITE_DIALOG_SCRIPT_SCOPE" }
        val directory = Files.createTempDirectory(runner, "ccb-win-invite-dialog-").toRealPath()
        check(directory.parent == runner) { "INVITE_DIALOG_DIRECTORY_SCOPE" }
        val existing = directory.resolve("synthetic-existing.ccbinvite")
        val new = directory.resolve("synthetic-new.ccbinvite")
        val original = "old synthetic invitation bytes".encodeToByteArray()
        val proposed = "new synthetic invitation bytes".encodeToByteArray()
        val dialogOwner = DesktopDialogOwner()
        var window: ComposeWindow? = null
        try {
            Files.write(existing, original)
            EventQueue.invokeAndWait {
                val ownerWindow = ComposeWindow().also { window = it }
                ownerWindow.title = "CCB invitation dialog synthetic owner"
                ownerWindow.setSize(640, 420)
                ownerWindow.isVisible = true
                dialogOwner.attach(ownerWindow)
            }
            DesktopPlatformPickers.create(dialogOwner).use { pickers ->
                val owner = dialogOwner.requireHandle()
                val occupiedPath = choose(pickers.files, script, owner, existing)
                assertEquals("SYNC_GROUP_INVITE_FILE_DESTINATION_CHANGED",
                    assertFailsWith<IllegalStateException> { writeNewDesktopInvitationFile(occupiedPath, proposed) }.message)
                assertContentEquals(original, Files.readAllBytes(existing))
                assertFalse(Files.exists(new, NOFOLLOW_LINKS))

                val newPath = choose(pickers.files, script, owner, new)
                writeNewDesktopInvitationFile(newPath, proposed)
                assertContentEquals(proposed, Files.readAllBytes(new))
                assertContentEquals(original, Files.readAllBytes(existing))
            }
        } finally {
            EventQueue.invokeAndWait {
                window?.let { ownerWindow ->
                    dialogOwner.detach(ownerWindow)
                    ownerWindow.dispose()
                }
            }
            Files.deleteIfExists(new)
            Files.deleteIfExists(existing)
            Files.deleteIfExists(directory)
        }
    }

    private fun choose(picker: DesktopFilePicker, script: Path, owner: Long, target: Path): Path {
        val result = CompletableFuture<Path?>()
        EventQueue.invokeLater {
            runCatching {
                picker.pickNewSaveFile(DesktopFileType("设备组邀请文件", listOf("ccbinvite")),
                    "设备组邀请.ccbinvite", "保存邀请文件 · 请使用新文件名")
            }.fold(result::complete, result::completeExceptionally)
        }
        try {
            automate(script, owner, target, "save", 24)
            val selected = result.get(10, SECONDS)
            assertEquals(target, selected, "The product picker must return the exact chosen destination")
            return checkNotNull(selected)
        } finally {
            if (!result.isDone) {
                runCatching { automate(script, owner, target, "close", 8) }
                runCatching { result.get(3, SECONDS) }
            }
        }
    }

    private fun automate(script: Path, owner: Long, target: Path, operation: String, timeout: Long) {
        val executable = Path.of(checkNotNull(System.getenv("SystemRoot")),
            "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val process = ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-File", script.toString(),
            "-ExpectedProcessId", ProcessHandle.current().pid().toString(), "-OwnerHandle", owner.toString(),
            "-SelectedPath", target.toString(), "-Operation", operation)
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(timeout, SECONDS)) { "INVITE_DIALOG_AUTOMATION_TIMEOUT" }
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            val expected = if (operation == "save") "INVITE_DIALOG_SAVE_INVOKED" else "INVITE_DIALOG_CLEANUP_COMPLETE"
            check(process.exitValue() == 0 && expected in lines) {
                lines.firstOrNull { it.matches(Regex("INVITE_DIALOG_[A-Z_]+")) } ?: "INVITE_DIALOG_AUTOMATION_FAILED"
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
